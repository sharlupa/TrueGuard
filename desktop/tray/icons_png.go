//go:build !windows

package main

import _ "embed"

// В StatusNotifierItem значок передаётся как PNG.
var (
	//go:embed icons/tray_on.png
	iconOn []byte
	//go:embed icons/tray_off.png
	iconOff []byte
	//go:embed icons/tray_wait.png
	iconWait []byte
)
