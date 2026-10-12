package main

import _ "embed"

// Область уведомлений Windows принимает только ICO.
var (
	//go:embed icons/tray_on.ico
	iconOn []byte
	//go:embed icons/tray_off.ico
	iconOff []byte
	//go:embed icons/tray_wait.ico
	iconWait []byte
)
