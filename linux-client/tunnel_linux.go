//go:build !windows

package main

import (
	"fmt"
	"log"
	"net"
	"net/netip"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// prepareTunMode: в Linux режим tun требует root, а сокеты клиента помечаются tunFwMark.
func prepareTunMode() {
	if os.Geteuid() != 0 {
		log.Fatal("[КЛИЕНТ] Режим tun требует root (sudo)")
	}
	if bypassMark == 0 {
		bypassMark = tunFwMark
	}
}

const (
	tunConfDir = "/run/wdtt"
	// приоритет правил исключений: раньше правил wg-quick (32764/32765)
	tunExcludeRulePref = 31000
)


// Режим tun: системный туннель через wg-quick (ядро WireGuard).
// Сокеты самого клиента помечены tunFwMark, а wg-quick с FwMark = tunFwMark
// заворачивает в туннель только непомеченный трафик — так нет петли.
func startSystemTunnel(cfg *wgConfig, localEndpoint string, opts tunOptions) (func(), error) {
	conf, err := buildWGQuickConfig(cfg, localEndpoint, opts)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(tunConfDir, 0700); err != nil {
		return nil, err
	}
	path := filepath.Join(tunConfDir, tunIfaceName+".conf")
	if err := os.WriteFile(path, []byte(conf), 0600); err != nil {
		return nil, err
	}

	if _, err := net.InterfaceByName(tunIfaceName); err == nil {
		log.Printf("[TUN] %s уже существует, опускаю перед запуском", tunIfaceName)
		_ = runWGQuick("down", path)
	}
	if err := runWGQuick("up", path); err != nil {
		_ = os.Remove(path)
		return nil, err
	}
	switch {
	case len(opts.Include) > 0:
		log.Printf("[TUN] Туннель %s поднят: через WDTT идут только %d адрес(ов) из белого списка", tunIfaceName, len(opts.Include))
	default:
		log.Printf("[TUN] Туннель %s поднят: весь IPv4 идёт через WDTT (исключений: %d)", tunIfaceName, len(opts.Exclude))
	}
	return func() {
		if err := runWGQuick("down", path); err != nil {
			log.Printf("[TUN] wg-quick down: %v", err)
		}
		_ = os.Remove(path)
	}, nil
}

func runWGQuick(action, path string) error {
	out, err := exec.Command("wg-quick", action, path).CombinedOutput()
	for _, line := range strings.Split(strings.TrimSpace(string(out)), "\n") {
		if line != "" {
			log.Printf("[TUN] %s", line)
		}
	}
	if err != nil {
		return fmt.Errorf("wg-quick %s: %w", action, err)
	}
	return nil
}

// buildWGQuickConfig собирает конфиг для wg-quick только из разобранных и проверенных
// полей. Строки от сервера дословно не копируются: wg-quick выполняет PostUp/PreUp
// от root, и сервер не должен иметь возможности подсунуть туда команду.
func buildWGQuickConfig(cfg *wgConfig, localEndpoint string, opts tunOptions) (string, error) {
	if _, err := keyB64ToHex(cfg.PrivateKey); err != nil {
		return "", fmt.Errorf("PrivateKey: %w", err)
	}
	if _, err := keyB64ToHex(cfg.PeerPublicKey); err != nil {
		return "", fmt.Errorf("PublicKey: %w", err)
	}
	if _, err := netip.ParseAddrPort(localEndpoint); err != nil {
		return "", fmt.Errorf("локальный endpoint %q: %w", localEndpoint, err)
	}
	if len(cfg.Addresses) == 0 {
		return "", fmt.Errorf("нет Address")
	}

	join := func(items []string) string { return strings.Join(items, ", ") }
	var addrs, dns, allowed []string
	for _, a := range cfg.Addresses {
		addrs = append(addrs, a.String())
	}
	for _, d := range cfg.DNS {
		dns = append(dns, d.String())
	}

	var b strings.Builder
	b.WriteString("[Interface]\n")
	fmt.Fprintf(&b, "PrivateKey = %s\n", cfg.PrivateKey)
	fmt.Fprintf(&b, "Address = %s\n", join(addrs))
	fmt.Fprintf(&b, "MTU = %d\n", cfg.MTU)
	fmt.Fprintf(&b, "FwMark = %d\n", tunFwMark)
	// В режиме белого списка DNS туннеля недоступен напрямую — системный DNS не трогаем.
	if opts.DNS && len(dns) > 0 && len(opts.Include) == 0 && !opts.AppsOnly {
		fmt.Fprintf(&b, "DNS = %s\n", join(dns))
	}
	if len(opts.Include) == 0 {
		for _, p := range opts.Exclude {
			family := "-4"
			if p.Addr().Is6() {
				family = "-6"
			}
			fmt.Fprintf(&b, "PostUp = ip %s rule add to %s lookup main pref %d\n", family, p, tunExcludeRulePref)
			fmt.Fprintf(&b, "PostDown = ip %s rule del to %s lookup main pref %d\n", family, p, tunExcludeRulePref)
		}
	}

	if len(opts.Include) > 0 {
		for _, p := range opts.Include {
			allowed = append(allowed, p.String())
		}
	} else {
		allowed = []string{"0.0.0.0/0"}
		if opts.BlockIPv6 {
			allowed = append(allowed, "::/0")
		}
	}

	b.WriteString("\n[Peer]\n")
	fmt.Fprintf(&b, "PublicKey = %s\n", cfg.PeerPublicKey)
	fmt.Fprintf(&b, "AllowedIPs = %s\n", join(allowed))
	fmt.Fprintf(&b, "Endpoint = %s\n", localEndpoint)
	if cfg.Keepalive > 0 {
		fmt.Fprintf(&b, "PersistentKeepalive = %d\n", cfg.Keepalive)
	}
	return b.String(), nil
}

