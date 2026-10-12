package main

import (
	"net"
	"time"
)

// Обход туннеля для собственных сокетов клиента (DNS, HTTPS к VK, UDP к TURN):
// socketControl для каждой платформы своя (netbind_unix.go, netbind_windows.go).
var (
	bypassMark  int    // 0 = выкл (только Linux)
	bypassIface string // "" = выкл, "auto" = интерфейс маршрута по умолчанию
)

func newDialer(timeout, keepAlive time.Duration) *net.Dialer {
	return &net.Dialer{Timeout: timeout, KeepAlive: keepAlive, Control: socketControl}
}
