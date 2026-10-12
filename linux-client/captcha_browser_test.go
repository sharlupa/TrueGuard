package main

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// Страница, повторяющая устройство капчи VK: label-чекбокс, по настоящему
// (isTrusted) клику — fetch к captchaNotRobot.check, в ответе success_token.
const fakeCaptchaPage = `<!doctype html><html><body style="margin:0">
<div style="padding:40px">
<label class="vkc__Checkbox-module__Checkbox" style="display:block;width:220px;height:44px;border:1px solid #888">Я не робот</label>
</div>
<script>
document.querySelector('label').addEventListener('click', async function (e) {
  if (!e.isTrusted) return;
  const r = await fetch('/method/captchaNotRobot.check?v=5.131', {method: 'POST'});
  await r.json();
});
</script></body></html>`

func TestBrowserCaptchaAuto(t *testing.T) {
	if findCaptchaBrowser() == "" {
		t.Skip("Chromium не найден")
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/method/captchaNotRobot.check" {
			w.Header().Set("Content-Type", "application/json")
			io.WriteString(w, `{"response":{"success_token":"tok-123","status":"OK"}}`)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		io.WriteString(w, fakeCaptchaPage)
	}))
	defer srv.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	token, err := solveCaptchaInBrowser(ctx, 1, "auto", srv.URL+"/not_robot_captcha")
	if err != nil {
		t.Fatal(err)
	}
	if token != "tok-123" {
		t.Fatalf("token = %q", token)
	}
}

func TestBrowserCaptchaAutoViaBypassProxy(t *testing.T) {
	old := bypassIface
	bypassIface = "auto"
	defer func() { bypassIface = old }()
	TestBrowserCaptchaAuto(t)
}
