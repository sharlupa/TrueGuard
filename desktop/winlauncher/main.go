// TrueGuard.exe — лаунчер переносной сборки для Windows.
//
// Запускает встроенную Java (runtime\bin\javaw.exe) с интерфейсом из app\*.jar без окна консоли
// и передаёт приложению свою папку, чтобы оно нашло ядро wdtt-client.exe и трей.
package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"unsafe"
)

func main() {
	exe, err := os.Executable()
	if err != nil {
		fail("Не удалось определить путь к TrueGuard.exe: " + err.Error())
	}
	dir := filepath.Dir(exe)
	java := filepath.Join(dir, "runtime", "bin", "javaw.exe")
	if _, err := os.Stat(java); err != nil {
		fail("Не найдена встроенная Java: " + java + "\nРаспакуйте архив TrueGuard целиком.")
	}
	args := []string{
		"-Dtrueguard.appdir=" + dir,
		"-Dtrueguard.launcher=" + exe,
		"-Dfile.encoding=UTF-8",
		"-Dsun.java2d.uiScale.enabled=true",
		"-Xss2m",
		"--enable-native-access=ALL-UNNAMED",
		"-cp", filepath.Join(dir, "app", "*"),
		"com.trueguard.MainKt",
	}
	cmd := exec.Command(java, append(args, os.Args[1:]...)...)
	cmd.Dir = dir
	if err := cmd.Start(); err != nil {
		fail("Не удалось запустить TrueGuard: " + err.Error())
	}
}

func fail(msg string) {
	user32 := syscall.NewLazyDLL("user32.dll")
	box := user32.NewProc("MessageBoxW")
	text, _ := syscall.UTF16PtrFromString(msg)
	title, _ := syscall.UTF16PtrFromString("TrueGuard")
	box.Call(0, uintptr(unsafe.Pointer(text)), uintptr(unsafe.Pointer(title)), 0x10)
	os.Exit(1)
}
