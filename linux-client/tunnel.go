package main

import (
	"context"
	"fmt"
	"log"
	"net"
	"net/netip"
	"strings"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// Режим proxy: userspace WireGuard + сетевой стек gVisor внутри процесса,
// наружу — SOCKS5/HTTP-прокси. Root не нужен, системная маршрутизация не меняется.
func startProxyTunnel(ctx context.Context, cfg *wgConfig, listenAddr string) (func(), error) {
	var addrs []netip.Addr
	for _, p := range cfg.Addresses {
		addrs = append(addrs, p.Addr())
	}
	tunDev, tnet, err := netstack.CreateNetTUN(addrs, cfg.DNS, cfg.MTU)
	if err != nil {
		return nil, fmt.Errorf("netstack: %w", err)
	}
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "[WG] "))
	uapi, err := cfg.uapi()
	if err != nil {
		dev.Close()
		return nil, err
	}
	if err := dev.IpcSet(uapi); err != nil {
		dev.Close()
		return nil, fmt.Errorf("WG IpcSet: %w", err)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return nil, fmt.Errorf("WG Up: %w", err)
	}

	ln, err := net.Listen("tcp", listenAddr)
	if err != nil {
		dev.Close()
		return nil, fmt.Errorf("прокси %s: %w", listenAddr, err)
	}
	pctx, pcancel := context.WithCancel(ctx)
	go serveProxy(pctx, ln, tnet.DialContext)

	log.Printf("[ПРОКСИ] SOCKS5 и HTTP на %s (WG %s, DNS %v)", ln.Addr(), cfg.Addresses[0], cfg.DNS)
	return func() {
		pcancel()
		_ = ln.Close()
		dev.Close()
	}, nil
}

const (
	tunIfaceName = "wdtt0"
	tunFwMark    = 51820
)

type tunOptions struct {
	DNS       bool           // прописать DNS туннеля в систему
	BlockIPv6 bool           // ::/0 в туннель, чтобы IPv6 не шёл мимо VPN
	Exclude   []netip.Prefix // чёрный список: эти адреса идут мимо туннеля
	Include   []netip.Prefix // белый список: в туннель идут только эти адреса
	AppsOnly  bool           // белый список приложений: системный DNS не трогаем
}

// parseAddressList разбирает IP, подсети и домены (домены резолвятся сейчас, через обход туннеля).
func parseAddressList(ctx context.Context, raw string) ([]netip.Prefix, error) {
	var out []netip.Prefix
	for _, item := range strings.FieldsFunc(raw, func(r rune) bool {
		return r == ',' || r == ';' || r == '\n' || r == ' ' || r == '\t'
	}) {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		if p, err := netip.ParsePrefix(item); err == nil {
			out = append(out, p.Masked())
			continue
		}
		if a, err := netip.ParseAddr(item); err == nil {
			out = append(out, netip.PrefixFrom(a, a.BitLen()))
			continue
		}
		rctx, cancel := context.WithTimeout(ctx, 5*time.Second)
		ips, err := net.DefaultResolver.LookupNetIP(rctx, "ip", item)
		cancel()
		if err != nil {
			return nil, fmt.Errorf("исключения: %s: %w", item, err)
		}
		for _, ip := range ips {
			ip = ip.Unmap()
			out = append(out, netip.PrefixFrom(ip, ip.BitLen()))
		}
	}
	return out, nil
}
