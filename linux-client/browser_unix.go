//go:build !windows

package main

import (
	"fmt"
	"os"
	"os/exec"
	"os/user"
	"path/filepath"
	"strconv"
	"syscall"
)

// Браузер для капчи живёт в своей группе процессов и умирает вместе с клиентом.
func setBrowserProcAttrs(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true, Pdeathsig: syscall.SIGKILL}
}

func killBrowserTree(p *os.Process) {
	_ = syscall.Kill(-p.Pid, syscall.SIGKILL)
}

func findInstalledBrowser() string {
	for _, name := range []string{"chromium", "chromium-browser", "google-chrome-stable", "google-chrome", "brave-browser", "brave", "microsoft-edge-stable"} {
		if p, err := exec.LookPath(name); err == nil {
			return p
		}
	}
	return ""
}

// dropBrowserPrivileges: под sudo браузер запускается от имени исходного пользователя
// в его графической сессии; под «чистым» root — только с --no-sandbox.
func dropBrowserPrivileges(cmd *exec.Cmd, dir string, headless bool) (noSandbox bool, err error) {
	if os.Geteuid() != 0 {
		if !headless && os.Getenv("WAYLAND_DISPLAY") == "" && os.Getenv("DISPLAY") == "" {
			return false, fmt.Errorf("нет графической сессии для ручной капчи")
		}
		return false, nil
	}

	uidStr, gidStr := os.Getenv("SUDO_UID"), os.Getenv("SUDO_GID")
	if uidStr == "" {
		uidStr = os.Getenv("PKEXEC_UID")
		gidStr = ""
	}
	if uidStr == "" || uidStr == "0" {
		if !headless && os.Getenv("WAYLAND_DISPLAY") == "" && os.Getenv("DISPLAY") == "" {
			return false, fmt.Errorf("нет графической сессии для ручной капчи")
		}
		return true, nil
	}
	uid, err := strconv.Atoi(uidStr)
	if err != nil {
		return false, err
	}
	home, userName := "/tmp", os.Getenv("SUDO_USER")
	if u, err := user.LookupId(uidStr); err == nil {
		home, userName = u.HomeDir, u.Username
		if gidStr == "" {
			gidStr = u.Gid
		}
	}
	gid, _ := strconv.Atoi(gidStr)
	cmd.SysProcAttr.Credential = &syscall.Credential{Uid: uint32(uid), Gid: uint32(gid)}
	if err := os.Chown(dir, uid, gid); err != nil {
		return false, err
	}

	runtimeDir := "/run/user/" + uidStr
	env := []string{
		"HOME=" + home,
		"USER=" + userName,
		"LOGNAME=" + userName,
		"PATH=" + os.Getenv("PATH"),
		"XDG_RUNTIME_DIR=" + runtimeDir,
	}
	wayland := os.Getenv("WAYLAND_DISPLAY")
	if wayland == "" {
		if m, _ := filepath.Glob(runtimeDir + "/wayland-[0-9]"); len(m) > 0 {
			wayland = filepath.Base(m[0])
		}
	}
	if wayland != "" {
		env = append(env, "WAYLAND_DISPLAY="+wayland)
	}
	display := os.Getenv("DISPLAY")
	if display == "" {
		if _, err := os.Stat("/tmp/.X11-unix/X0"); err == nil {
			display = ":0"
		}
	}
	if display != "" {
		env = append(env, "DISPLAY="+display)
	}
	if xa := os.Getenv("XAUTHORITY"); xa != "" {
		env = append(env, "XAUTHORITY="+xa)
	}
	if !headless && wayland == "" && display == "" {
		return false, fmt.Errorf("нет графической сессии для ручной капчи")
	}
	cmd.Env = env
	return false, nil
}
