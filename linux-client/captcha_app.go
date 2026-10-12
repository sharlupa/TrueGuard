package main

import (
	"context"
	"fmt"
	"strconv"
	"strings"
	"sync"
)

// Капча через приложение (Android): ядро присылает событием CAPTCHA_REQUEST адрес капчи
// и скрипт-перехватчик, приложение показывает её в WebView и возвращает токен строкой
// «CAPTCHA_RESULT <id> <токен>» (или «CAPTCHA_CANCEL <id>») в stdin ядра.
var captchaViaApp bool

type captchaAnswer struct {
	token string
	err   error
}

var (
	captchaWaitMu sync.Mutex
	captchaWaits  = map[int]chan captchaAnswer{}
)

func solveCaptchaViaApp(ctx context.Context, streamID int, mode, redirectURI string) (string, error) {
	if mode == "auto" {
		// невидимого браузера в приложении нет — автоматическую попытку пропускаем
		return "", fmt.Errorf("автоматическое решение в приложении недоступно")
	}
	ch := make(chan captchaAnswer, 1)
	captchaWaitMu.Lock()
	captchaWaits[streamID] = ch
	captchaWaitMu.Unlock()
	defer func() {
		captchaWaitMu.Lock()
		delete(captchaWaits, streamID)
		captchaWaitMu.Unlock()
	}()

	emitEvent(eventCaptchaRequest, map[string]any{
		"mode":    mode,
		"id":      streamID,
		"url":     redirectURI,
		"binding": captchaBindingName,
		"script":  captchaInterceptorJS,
	})
	solved := false
	defer func() { emitCaptchaDone(solved, "") }()

	select {
	case <-ctx.Done():
		return "", ctx.Err()
	case a := <-ch:
		if a.err != nil {
			return "", a.err
		}
		solved = true
		return a.token, nil
	}
}

// handleCaptchaControl разбирает строку управления из stdin; true — строка была про капчу.
func handleCaptchaControl(line string) bool {
	f := strings.Fields(line)
	if len(f) < 2 || (f[0] != "CAPTCHA_RESULT" && f[0] != "CAPTCHA_CANCEL") {
		return false
	}
	id, err := strconv.Atoi(f[1])
	if err != nil {
		return true
	}
	a := captchaAnswer{err: fmt.Errorf("капча отменена в приложении")}
	if f[0] == "CAPTCHA_RESULT" && len(f) >= 3 {
		a = captchaAnswer{token: f[2]}
	}
	captchaWaitMu.Lock()
	ch := captchaWaits[id]
	captchaWaitMu.Unlock()
	if ch != nil {
		select {
		case ch <- a:
		default:
		}
	}
	return true
}
