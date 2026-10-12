package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"math/rand"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/net/websocket"
)

// Замена Android WebView для капчи VK: Chromium под управлением DevTools Protocol.
//   auto            — невидимый (headless) браузер сам ставит галочку «я не робот» (как WBV Auto);
//   manual/selected — открывается окно, капчу решает человек.
// success_token перехватывается из ответа captchaNotRobot.check тем же JS, что и в приложении.

var captchaBrowserPath string // флаг -browser: "" = искать автоматически, "none" = не использовать

var captchaBrowserMu sync.Mutex

const captchaBindingName = "__wdttCaptcha"

const captchaInterceptorJS = `(function() {
    if (window.__wdtt_interceptor_installed) return;
    window.__wdtt_interceptor_installed = true;
    function report(data) {
        try {
            if (data.response && data.response.success_token) {
                window.__wdttCaptcha(JSON.stringify({t: 'ok', v: data.response.success_token}));
            } else if (data.response && data.response.show_captcha_type === 'slider') {
                window.__wdttCaptcha(JSON.stringify({t: 'slider'}));
            } else if (data.error) {
                window.__wdttCaptcha(JSON.stringify({t: 'err', v: JSON.stringify(data.error)}));
            }
        } catch (e) {}
    }
    const origFetch = window.fetch;
    window.fetch = async function() {
        const arg = arguments[0] || '';
        const url = typeof arg === 'string' ? arg : (arg && arg.url) || '';
        const response = await origFetch.apply(this, arguments);
        if (url.includes('captchaNotRobot.check')) {
            try { report(await response.clone().json()); } catch (e) {}
        }
        return response;
    };
    const origOpen = XMLHttpRequest.prototype.open;
    const origSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(method, url) {
        this.__wdtt_url = String(url);
        return origOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function() {
        const xhr = this;
        if (xhr.__wdtt_url && xhr.__wdtt_url.includes('captchaNotRobot.check')) {
            xhr.addEventListener('load', function() {
                try { report(JSON.parse(xhr.responseText)); } catch (e) {}
            });
        }
        return origSend.apply(this, arguments);
    };
})();`

// Тот же поиск чекбокса, что в приложении: координаты label, чтобы кликнуть в случайную точку.
const captchaLocateCheckboxJS = `(function() {
    var slider = document.querySelector(
        '[class*="SliderCaptcha"], [class*="Kaleidoscope"], ' +
        '.vkc__SliderCaptcha-module__description, ' +
        '.vkc__KaleidoscopeScreen-module__captchaId'
    );
    if (slider) return 'slider_detected';
    var el = document.querySelector('label.vkc__Checkbox-module__Checkbox');
    if (!el) el = document.querySelector('label[for="not-robot-captcha-checkbox"]');
    if (!el) el = document.getElementById('not-robot-captcha-checkbox');
    if (!el) return 'not_found';
    var rect = el.getBoundingClientRect();
    var style = window.getComputedStyle(el);
    if (rect.width < 5 || rect.height < 5 || style.display === 'none' || style.visibility === 'hidden') {
        return 'not_found';
    }
    return rect.left + ',' + rect.top + ',' + rect.width + ',' + rect.height;
})()`

const captchaJSClickFallback = `(function() {
    var el = document.querySelector('label.vkc__Checkbox-module__Checkbox');
    if (!el) el = document.getElementById('not-robot-captcha-checkbox');
    if (el) { el.click(); return 'clicked'; }
    return 'nothing';
})()`

var errCaptchaSlider = errors.New("slider_detected")

func findCaptchaBrowser() string {
	switch captchaBrowserPath {
	case "none":
		return ""
	case "":
	default:
		return captchaBrowserPath
	}
	return findInstalledBrowser()
}

func solveCaptchaInBrowser(ctx context.Context, streamID int, mode, redirectURI string) (string, error) {
	browser := findCaptchaBrowser()
	if browser == "" {
		return "", fmt.Errorf("не найден Chromium/Chrome (флаг -browser)")
	}

	captchaBrowserMu.Lock()
	defer captchaBrowserMu.Unlock()

	headless := mode == "auto"
	solved := false
	b, err := startCaptchaBrowser(ctx, browser, headless)
	if err != nil {
		return "", err
	}
	defer b.close()

	if headless {
		// HeadlessChrome в User-Agent — прямой признак бота.
		var ver struct {
			UserAgent string `json:"userAgent"`
		}
		if err := b.cdp.call(ctx, "Browser.getVersion", nil, &ver); err == nil && ver.UserAgent != "" {
			ua := strings.ReplaceAll(ver.UserAgent, "HeadlessChrome", "Chrome")
			_ = b.cdp.call(ctx, "Emulation.setUserAgentOverride", map[string]any{"userAgent": ua, "acceptLanguage": "ru-RU,ru;q=0.9,en;q=0.8"}, nil)
		}
	}

	for _, step := range []struct {
		method string
		params any
	}{
		{"Runtime.enable", nil},
		{"Page.enable", nil},
		{"Runtime.addBinding", map[string]any{"name": captchaBindingName}},
		{"Page.addScriptToEvaluateOnNewDocument", map[string]any{"source": captchaInterceptorJS}},
		{"Page.navigate", map[string]any{"url": redirectURI}},
	} {
		if err := b.cdp.call(ctx, step.method, step.params, nil); err != nil {
			return "", err
		}
	}

	if headless {
		log.Printf("[STREAM %d] [КАПЧА] Браузер (headless): ставлю галочку автоматически", streamID)
	} else {
		log.Printf("[STREAM %d] [КАПЧА] Открыто окно браузера — решите капчу вручную", streamID)
		emitCaptchaRequest(mode)
		defer func() { emitCaptchaDone(solved, "") }()
	}

	tick := time.NewTicker(450 * time.Millisecond)
	defer tick.Stop()
	start := time.Now()
	var lastClick time.Time
	clicks := 0
	jsFallbackDone := false

	for {
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		case <-b.cdp.done:
			return "", fmt.Errorf("браузер закрыт")
		case payload := <-b.cdp.bindings:
			var res struct {
				T string `json:"t"`
				V string `json:"v"`
			}
			if err := json.Unmarshal([]byte(payload), &res); err != nil {
				continue
			}
			switch res.T {
			case "ok":
				if res.V != "" {
					solved = true
					return res.V, nil
				}
			case "slider":
				if headless {
					return "", errCaptchaSlider
				}
			case "err":
				if headless {
					return "", fmt.Errorf("captchaNotRobot.check: %s", res.V)
				}
				log.Printf("[STREAM %d] [КАПЧА] Ошибка проверки, попробуйте ещё раз: %s", streamID, res.V)
			}
		case <-tick.C:
			if !headless {
				continue
			}
			if clicks > 0 && time.Since(lastClick) < 4*time.Second {
				continue
			}
			if clicks >= 3 {
				continue
			}
			state, err := b.evalString(ctx, captchaLocateCheckboxJS)
			if err != nil {
				continue
			}
			switch {
			case state == "slider_detected":
				return "", errCaptchaSlider
			case state == "not_found":
				if !jsFallbackDone && time.Since(start) > 6*time.Second {
					jsFallbackDone = true
					log.Printf("[STREAM %d] [КАПЧА] Чекбокс не найден — JS-клик (fallback)", streamID)
					_, _ = b.evalString(ctx, captchaJSClickFallback)
				}
			default:
				if err := b.humanClick(ctx, state); err != nil {
					log.Printf("[STREAM %d] [КАПЧА] Клик: %v", streamID, err)
					continue
				}
				clicks++
				lastClick = time.Now()
			}
		}
	}
}

type captchaBrowserProc struct {
	cmd     *exec.Cmd
	dir     string
	cdp     *cdpConn
	proxyLn net.Listener
}

func startCaptchaBrowser(ctx context.Context, browser string, headless bool) (*captchaBrowserProc, error) {
	dir, err := os.MkdirTemp("", "wdtt-captcha-")
	if err != nil {
		return nil, err
	}
	b := &captchaBrowserProc{dir: dir}
	ok := false
	defer func() {
		if !ok {
			b.close()
		}
	}()

	args := []string{
		"--user-data-dir=" + dir,
		"--remote-debugging-port=0",
		"--remote-allow-origins=*",
		"--no-first-run",
		"--no-default-browser-check",
		"--disable-sync",
		"--disable-extensions",
		"--disable-features=Translate,MediaRouter",
		"--password-store=basic",
		"--lang=ru-RU",
	}
	if headless {
		w := []int{356, 358, 360, 362, 364, 366, 368}[rand.Intn(7)]
		h := []int{376, 378, 380, 382, 384, 386, 388}[rand.Intn(7)]
		args = append(args, "--headless=new", "--hide-scrollbars", "--mute-audio", fmt.Sprintf("--window-size=%d,%d", w, h))
	} else {
		args = append(args, "--ozone-platform-hint=auto", "--window-size=480,720", "--app=about:blank")
	}

	// Трафик браузера идёт тем же путём, что и запросы клиента к VK (мимо туннеля).
	if bypassMark != 0 || bypassIface != "" {
		ln, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			return nil, err
		}
		b.proxyLn = ln
		go serveProxy(context.Background(), ln, newDialer(20*time.Second, 30*time.Second).DialContext)
		args = append(args, "--proxy-server=http://"+ln.Addr().String())
	}

	cmd := exec.Command(browser, args...)
	cmd.Stdout = io.Discard
	cmd.Stderr = io.Discard
	setBrowserProcAttrs(cmd)
	noSandbox, err := dropBrowserPrivileges(cmd, dir, headless)
	if err != nil {
		return nil, err
	}
	if noSandbox {
		cmd.Args = append(cmd.Args, "--no-sandbox")
	}
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("запуск %s: %w", browser, err)
	}
	b.cmd = cmd

	port, err := waitDevToolsPort(ctx, dir)
	if err != nil {
		return nil, err
	}
	wsURL, err := findPageTarget(ctx, port)
	if err != nil {
		return nil, err
	}
	conn, err := dialCDP(wsURL)
	if err != nil {
		return nil, err
	}
	b.cdp = conn
	ok = true
	return b, nil
}

func (b *captchaBrowserProc) close() {
	if b.cdp != nil {
		b.cdp.close()
	}
	if b.cmd != nil && b.cmd.Process != nil {
		killBrowserTree(b.cmd.Process)
		_ = b.cmd.Wait()
	}
	if b.proxyLn != nil {
		_ = b.proxyLn.Close()
	}
	_ = os.RemoveAll(b.dir)
}

func (b *captchaBrowserProc) evalString(ctx context.Context, expr string) (string, error) {
	var res struct {
		Result struct {
			Value any `json:"value"`
		} `json:"result"`
	}
	if err := b.cdp.call(ctx, "Runtime.evaluate", map[string]any{"expression": expr, "returnByValue": true}, &res); err != nil {
		return "", err
	}
	s, _ := res.Result.Value.(string)
	return s, nil
}

// humanClick кликает в случайную точку внутри label настоящими (isTrusted) событиями мыши.
func (b *captchaBrowserProc) humanClick(ctx context.Context, rect string) error {
	parts := strings.Split(rect, ",")
	if len(parts) < 4 {
		return fmt.Errorf("плохие координаты %q", rect)
	}
	var v [4]float64
	for i := range v {
		f, err := strconv.ParseFloat(strings.TrimSpace(parts[i]), 64)
		if err != nil {
			return err
		}
		v[i] = f
	}
	x := v[0] + v[2]*(0.15+rand.Float64()*0.7)
	y := v[1] + v[3]*(0.25+rand.Float64()*0.5)

	sleepCtx(ctx, time.Duration(420+rand.Intn(260))*time.Millisecond)
	mouse := func(typ string, x, y float64, button string) error {
		p := map[string]any{"type": typ, "x": x, "y": y, "button": button, "pointerType": "mouse"}
		if typ != "mouseMoved" {
			p["clickCount"] = 1
		}
		return b.cdp.call(ctx, "Input.dispatchMouseEvent", p, nil)
	}
	if err := mouse("mouseMoved", x-8-rand.Float64()*20, y+rand.Float64()*10, "none"); err != nil {
		return err
	}
	sleepCtx(ctx, time.Duration(60+rand.Intn(80))*time.Millisecond)
	if err := mouse("mouseMoved", x, y, "none"); err != nil {
		return err
	}
	if err := mouse("mousePressed", x, y, "left"); err != nil {
		return err
	}
	sleepCtx(ctx, time.Duration(80+rand.Intn(100))*time.Millisecond)
	return mouse("mouseReleased", x+rand.Float64()*2-1, y+rand.Float64()-0.5, "left")
}

func sleepCtx(ctx context.Context, d time.Duration) {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
	case <-t.C:
	}
}


func waitDevToolsPort(ctx context.Context, dir string) (int, error) {
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		if data, err := os.ReadFile(filepath.Join(dir, "DevToolsActivePort")); err == nil {
			lines := strings.SplitN(string(data), "\n", 2)
			if port, err := strconv.Atoi(strings.TrimSpace(lines[0])); err == nil && port > 0 {
				return port, nil
			}
		}
		select {
		case <-ctx.Done():
			return 0, ctx.Err()
		case <-time.After(100 * time.Millisecond):
		}
	}
	return 0, fmt.Errorf("браузер не открыл DevTools за 20 с")
}

func findPageTarget(ctx context.Context, port int) (string, error) {
	url := fmt.Sprintf("http://127.0.0.1:%d/json/list", port)
	for i := 0; i < 50; i++ {
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
		if resp, err := http.DefaultClient.Do(req); err == nil {
			var targets []struct {
				Type  string `json:"type"`
				WSURL string `json:"webSocketDebuggerUrl"`
			}
			err = json.NewDecoder(resp.Body).Decode(&targets)
			resp.Body.Close()
			if err == nil {
				for _, t := range targets {
					if t.Type == "page" && t.WSURL != "" {
						return t.WSURL, nil
					}
				}
			}
		}
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		case <-time.After(100 * time.Millisecond):
		}
	}
	return "", fmt.Errorf("у браузера нет вкладки для капчи")
}

// ─── Минимальный клиент Chrome DevTools Protocol ───

type cdpMessage struct {
	ID     int64           `json:"id,omitempty"`
	Method string          `json:"method,omitempty"`
	Params json.RawMessage `json:"params,omitempty"`
	Result json.RawMessage `json:"result,omitempty"`
	Error  *struct {
		Message string `json:"message"`
	} `json:"error,omitempty"`
}

type cdpConn struct {
	ws       *websocket.Conn
	nextID   atomic.Int64
	wmu      sync.Mutex
	mu       sync.Mutex
	pending  map[int64]chan cdpMessage
	bindings chan string
	done     chan struct{}
}

func dialCDP(wsURL string) (*cdpConn, error) {
	ws, err := websocket.Dial(wsURL, "", "http://127.0.0.1/")
	if err != nil {
		return nil, fmt.Errorf("CDP: %w", err)
	}
	ws.MaxPayloadBytes = 64 << 20
	c := &cdpConn{
		ws:       ws,
		pending:  make(map[int64]chan cdpMessage),
		bindings: make(chan string, 16),
		done:     make(chan struct{}),
	}
	go c.readLoop()
	return c, nil
}

func (c *cdpConn) readLoop() {
	defer close(c.done)
	for {
		var msg cdpMessage
		if err := websocket.JSON.Receive(c.ws, &msg); err != nil {
			return
		}
		if msg.ID != 0 {
			c.mu.Lock()
			ch := c.pending[msg.ID]
			delete(c.pending, msg.ID)
			c.mu.Unlock()
			if ch != nil {
				ch <- msg
			}
			continue
		}
		if msg.Method == "Runtime.bindingCalled" {
			var p struct {
				Name    string `json:"name"`
				Payload string `json:"payload"`
			}
			if json.Unmarshal(msg.Params, &p) == nil && p.Name == captchaBindingName {
				select {
				case c.bindings <- p.Payload:
				default:
				}
			}
		}
	}
}

func (c *cdpConn) call(ctx context.Context, method string, params, result any) error {
	id := c.nextID.Add(1)
	ch := make(chan cdpMessage, 1)
	c.mu.Lock()
	c.pending[id] = ch
	c.mu.Unlock()
	defer func() {
		c.mu.Lock()
		delete(c.pending, id)
		c.mu.Unlock()
	}()

	req := map[string]any{"id": id, "method": method}
	if params != nil {
		req["params"] = params
	}
	c.wmu.Lock()
	err := websocket.JSON.Send(c.ws, req)
	c.wmu.Unlock()
	if err != nil {
		return fmt.Errorf("CDP %s: %w", method, err)
	}

	callCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	select {
	case msg := <-ch:
		if msg.Error != nil {
			return fmt.Errorf("CDP %s: %s", method, msg.Error.Message)
		}
		if result != nil && len(msg.Result) > 0 {
			return json.Unmarshal(msg.Result, result)
		}
		return nil
	case <-c.done:
		return fmt.Errorf("CDP %s: соединение с браузером закрыто", method)
	case <-callCtx.Done():
		return fmt.Errorf("CDP %s: %w", method, callCtx.Err())
	}
}

func (c *cdpConn) close() {
	_ = c.ws.Close()
}
