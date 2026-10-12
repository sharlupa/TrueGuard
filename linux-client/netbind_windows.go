package main

import (
	"encoding/binary"
	"fmt"
	"net"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

// В Windows сокеты клиента привязываются к физическому интерфейсу через IP_UNICAST_IF —
// так же WireGuard для Windows не пускает свои пакеты обратно в туннель.
// Туннель WDTT прокладывает маршруты 0.0.0.0/1 и 128.0.0.0/1, а маршрут /0
// остаётся у настоящей сетевой карты: его интерфейс и берём.

const (
	ipUnicastIf   = 31 // IP_UNICAST_IF
	ipv6UnicastIf = 31 // IPV6_UNICAST_IF
)

func socketControl(network, address string, c syscall.RawConn) error {
	if bypassIface == "" {
		return nil
	}
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return nil
	}
	ip := net.ParseIP(host)
	if ip != nil && ip.IsLoopback() {
		return nil
	}
	v6 := ip != nil && ip.To4() == nil
	idx, err := bypassIfaceIndex(v6)
	if err != nil {
		return err
	}
	var serr error
	err = c.Control(func(fd uintptr) {
		h := windows.Handle(fd)
		if v6 {
			serr = windows.SetsockoptInt(h, windows.IPPROTO_IPV6, ipv6UnicastIf, int(idx))
		} else {
			// для IPv4 индекс передаётся в сетевом порядке байт
			var be [4]byte
			binary.BigEndian.PutUint32(be[:], idx)
			serr = windows.SetsockoptInt(h, windows.IPPROTO_IP, ipUnicastIf, int(binary.LittleEndian.Uint32(be[:])))
		}
		if serr != nil {
			serr = fmt.Errorf("IP_UNICAST_IF %d: %w", idx, serr)
		}
	})
	if err != nil {
		return err
	}
	return serr
}

func bypassIfaceIndex(v6 bool) (uint32, error) {
	if bypassIface != "auto" {
		ifc, err := net.InterfaceByName(bypassIface)
		if err != nil {
			return 0, fmt.Errorf("bypass: интерфейс %q: %w", bypassIface, err)
		}
		return uint32(ifc.Index), nil
	}
	idx := defaultRouteIndex(v6)
	if idx == 0 {
		return 0, fmt.Errorf("bypass: не найден маршрут по умолчанию")
	}
	return idx, nil
}

// defaultRouteIndex — интерфейс маршрута /0 с наименьшей метрикой, кроме самого туннеля.
func defaultRouteIndex(v6 bool) uint32 {
	family := uint16(windows.AF_INET)
	if v6 {
		family = windows.AF_INET6
	}
	var tunIdx uint32
	if ifc, err := net.InterfaceByName(tunIfaceName); err == nil {
		tunIdx = uint32(ifc.Index)
	}
	var table *windows.MibIpForwardTable2
	if err := windows.GetIpForwardTable2(family, &table); err != nil {
		return 0
	}
	defer windows.FreeMibTable(unsafe.Pointer(table))
	var best uint32
	bestMetric := ^uint32(0)
	for _, r := range table.Rows() {
		if r.DestinationPrefix.PrefixLength != 0 || r.InterfaceIndex == tunIdx || r.Loopback != 0 {
			continue
		}
		if r.Metric < bestMetric {
			best, bestMetric = r.InterfaceIndex, r.Metric
		}
	}
	return best
}

// defaultRouteIface — имя интерфейса маршрута по умолчанию (для журнала).
func defaultRouteIface() string {
	idx := defaultRouteIndex(false)
	if idx == 0 {
		return ""
	}
	if ifc, err := net.InterfaceByIndex(int(idx)); err == nil {
		return ifc.Name
	}
	return fmt.Sprint(idx)
}
