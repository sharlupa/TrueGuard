package main

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"sync"
)

// Структурированные события для GUI: строки «__WDTT_EVENT__|ТИП|json» в stdout.
// Обычный лог идёт в stderr.
var eventOutputEnabled = os.Getenv("WDTT_EVENTS") == "1"

var eventMu sync.Mutex

type eventType string

const (
	eventStarted        eventType = "STARTED"
	eventStopped        eventType = "STOPPED"
	eventReady          eventType = "READY"
	eventTunnelUp       eventType = "TUNNEL_UP"
	eventStats          eventType = "STATS"
	eventError          eventType = "ERROR"
	eventCaptchaRequest eventType = "CAPTCHA_REQUEST"
	eventCaptchaDone    eventType = "CAPTCHA_DONE"
	eventTunRequest     eventType = "TUN_REQUEST" // Android: поднять VpnService и передать дескриптор
	eventDeployProgress eventType = "DEPLOY_PROGRESS"
	eventDeployDone     eventType = "DEPLOY_DONE"
)

func emitEvent(t eventType, payload map[string]any) {
	if !eventOutputEnabled {
		return
	}
	var p []byte
	if len(payload) > 0 {
		var err error
		p, err = json.Marshal(payload)
		if err != nil {
			log.Printf("[EVENT] failed to marshal %s event: %v", t, err)
			return
		}
	}
	eventMu.Lock()
	fmt.Fprintf(os.Stdout, "__WDTT_EVENT__|%s|%s\n", t, string(p))
	eventMu.Unlock()
}

func emitError(code, message string, fatal bool) {
	emitEvent(eventError, map[string]any{
		"code":    code,
		"message": message,
		"fatal":   fatal,
	})
}

func emitStats(s *Stats) {
	emitEvent(eventStats, map[string]any{
		"active":     s.ActiveConnections.Load(),
		"workers":    s.TotalWorkers.Load(),
		"bytes_up":   s.TotalBytesUp.Load(),
		"bytes_down": s.TotalBytesDown.Load(),
	})
}

func emitReady() {
	emitEvent(eventReady, nil)
}

func emitCaptchaRequest(mode string) {
	emitEvent(eventCaptchaRequest, map[string]any{"mode": mode})
}

func emitCaptchaDone(success bool, err string) {
	payload := map[string]any{"success": success}
	if err != "" {
		payload["error"] = err
	}
	emitEvent(eventCaptchaDone, payload)
}
