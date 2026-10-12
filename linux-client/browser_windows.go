package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"syscall"
)

// В Windows нет групп процессов Unix: браузер получает свою группу,
// а при закрытии гасится всё дерево процессов через taskkill.
func setBrowserProcAttrs(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{CreationFlags: syscall.CREATE_NEW_PROCESS_GROUP}
}

func killBrowserTree(p *os.Process) {
	if err := exec.Command("taskkill", "/T", "/F", "/PID", strconv.Itoa(p.Pid)).Run(); err != nil {
		_ = p.Kill()
	}
}

// Chrome, Edge, Brave и Chromium в стандартных местах установки.
func findInstalledBrowser() string {
	for _, name := range []string{"chrome.exe", "msedge.exe", "brave.exe", "chromium.exe"} {
		if p, err := exec.LookPath(name); err == nil {
			return p
		}
	}
	var roots []string
	for _, env := range []string{"ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"} {
		if v := os.Getenv(env); v != "" {
			roots = append(roots, v)
		}
	}
	rel := []string{
		`Google\Chrome\Application\chrome.exe`,
		`Microsoft\Edge\Application\msedge.exe`,
		`BraveSoftware\Brave-Browser\Application\brave.exe`,
		`Chromium\Application\chrome.exe`,
	}
	for _, r := range rel {
		for _, root := range roots {
			p := filepath.Join(root, r)
			if _, err := os.Stat(p); err == nil {
				return p
			}
		}
	}
	return ""
}

// Клиент в Windows работает от имени пользователя: права понижать не нужно.
func dropBrowserPrivileges(cmd *exec.Cmd, dir string, headless bool) (noSandbox bool, err error) {
	return false, nil
}
