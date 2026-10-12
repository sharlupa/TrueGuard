//go:build !windows

package main

import (
	"bufio"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
	"syscall"

	"golang.org/x/sys/unix"
)

// На Android обход туннеля делает VpnService, на Linux — SO_MARK (нужен root) и/или
// SO_BINDTODEVICE (с ядра 5.7 работает и без root).

func socketControl(_, address string, c syscall.RawConn) error {
	if bypassMark == 0 && bypassIface == "" {
		return nil
	}
	// Локальные адреса (например, systemd-resolved на 127.0.0.53) не трогаем.
	if host, _, err := net.SplitHostPort(address); err == nil {
		if ip := net.ParseIP(host); ip != nil && ip.IsLoopback() {
			return nil
		}
	}

	iface := bypassIface
	if iface == "auto" {
		iface = defaultRouteIface()
		if iface == "" {
			return fmt.Errorf("bypass: не найден маршрут по умолчанию в таблице main")
		}
	}

	var serr error
	err := c.Control(func(fd uintptr) {
		if bypassMark != 0 {
			if serr = unix.SetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_MARK, bypassMark); serr != nil {
				serr = fmt.Errorf("SO_MARK: %w", serr)
				return
			}
		}
		if iface != "" {
			if serr = unix.BindToDevice(int(fd), iface); serr != nil {
				serr = fmt.Errorf("SO_BINDTODEVICE %s: %w", iface, serr)
			}
		}
	})
	if err != nil {
		return err
	}
	return serr
}

// defaultRouteIface возвращает интерфейс IPv4-маршрута по умолчанию из таблицы main.
// /proc/net/route показывает только main, поэтому wg-quick и прочие VPN,
// которые кладут свой default в отдельные таблицы, здесь не видны.
func defaultRouteIface() string {
	f, err := os.Open("/proc/net/route")
	if err != nil {
		return ""
	}
	defer f.Close()

	best, bestMetric := "", -1
	sc := bufio.NewScanner(f)
	sc.Scan() // заголовок
	for sc.Scan() {
		fields := strings.Fields(sc.Text())
		if len(fields) < 8 || fields[1] != "00000000" || fields[7] != "00000000" {
			continue
		}
		flags, _ := strconv.ParseUint(fields[3], 16, 32)
		if flags&unix.RTF_UP == 0 {
			continue
		}
		metric, _ := strconv.Atoi(fields[6])
		if bestMetric < 0 || metric < bestMetric {
			best, bestMetric = fields[0], metric
		}
	}
	return best
}
