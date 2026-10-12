// trueguard-tray — значок TrueGuard в системном трее (StatusNotifierItem по D-Bus).
//
// AWT SystemTray в Wayland-панелях (waybar, KDE, GNOME с AppIndicator) не работает,
// поэтому трей вынесен в этот маленький помощник. Протокол — строки:
//
//	stdin:  icon on|off|wait · status <текст> · server <текст> · toggle <надпись> · quit
//	stdout: ready | unavailable · activate · click show|toggle|quit
//
// Когда GUI закрывает stdin, помощник выходит. В Windows значок рисуется в области уведомлений.
package main

import (
	"bufio"
	"fmt"
	"os"
	"strings"
	"sync"

	"fyne.io/systray"
)


var outMu sync.Mutex

func emit(s string) {
	outMu.Lock()
	defer outMu.Unlock()
	fmt.Println(s)
}

func main() {
	if !watcherPresent() {
		emit("unavailable")
		// ждём закрытия stdin, чтобы GUI не перезапускал нас в цикле
		_, _ = bufio.NewReader(os.Stdin).ReadString(0)
		return
	}
	systray.Run(onReady, func() {})
}

func onReady() {
	systray.SetIcon(iconOff)
	systray.SetTitle("TrueGuard")
	systray.SetTooltip("TrueGuard: Отключено")

	status := systray.AddMenuItem("TrueGuard: Отключено", "")
	status.Disable()
	server := systray.AddMenuItem("сервер не выбран", "")
	server.Disable()
	systray.AddSeparator()
	toggle := systray.AddMenuItem("Подключить", "")
	show := systray.AddMenuItem("Открыть TrueGuard", "")
	systray.AddSeparator()
	quit := systray.AddMenuItem("Выход", "")

	systray.SetOnTapped(func() { emit("activate") })

	go func() {
		for {
			select {
			case <-toggle.ClickedCh:
				emit("click toggle")
			case <-show.ClickedCh:
				emit("click show")
			case <-quit.ClickedCh:
				emit("click quit")
			}
		}
	}()

	go func() {
		sc := bufio.NewScanner(os.Stdin)
		for sc.Scan() {
			cmd, arg, _ := strings.Cut(strings.TrimSpace(sc.Text()), " ")
			switch cmd {
			case "icon":
				switch arg {
				case "on":
					systray.SetIcon(iconOn)
				case "wait":
					systray.SetIcon(iconWait)
				default:
					systray.SetIcon(iconOff)
				}
			case "status":
				status.SetTitle(arg)
				systray.SetTooltip(arg)
			case "server":
				server.SetTitle(arg)
			case "toggle":
				toggle.SetTitle(arg)
			case "quit":
				systray.Quit()
				return
			}
		}
		// GUI закрылся
		systray.Quit()
	}()

	emit("ready")
}
