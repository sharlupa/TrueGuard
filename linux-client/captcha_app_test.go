package main

import (
	"context"
	"testing"
	"time"
)

func TestCaptchaViaAppRoundTrip(t *testing.T) {
	go func() {
		// ответ приложения приходит строкой в stdin
		for i := 0; i < 50; i++ {
			captchaWaitMu.Lock()
			_, ok := captchaWaits[7]
			captchaWaitMu.Unlock()
			if ok {
				handleCaptchaControl("CAPTCHA_RESULT 7 tok-123")
				return
			}
			time.Sleep(10 * time.Millisecond)
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	got, err := solveCaptchaViaApp(ctx, 7, "manual", "https://example/captcha")
	if err != nil || got != "tok-123" {
		t.Fatalf("got %q, %v", got, err)
	}
	if !handleCaptchaControl("CAPTCHA_CANCEL 99") || handleCaptchaControl("STOP") {
		t.Fatal("разбор строк управления")
	}
	if _, err := solveCaptchaViaApp(ctx, 8, "auto", "x"); err == nil {
		t.Fatal("автоматический режим в приложении должен сразу отказывать")
	}
}
