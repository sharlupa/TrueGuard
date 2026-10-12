package main

import (
	"encoding/base64"
	"encoding/binary"
	"fmt"
	"log"
	"net"
	"net/netip"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
	"unicode/utf16"

	"golang.org/x/sys/windows"
)

// Режим tun в Windows: туннель поднимает официальный WireGuard для Windows
// (аналог wg-quick в Linux). Установка службы туннеля требует прав администратора —
// Windows спросит разрешение (UAC); если клиент уже запущен от администратора, окна не будет.
//
// Чтобы WireGuard не включал «аварийный выключатель» (он заблокировал бы соединения клиента
// с TURN-серверами VK), весь интернет задаётся маршрутами /1, а сокеты клиента
// привязываются к физическому интерфейсу (netbind_windows.go).

func prepareTunMode() {
	if bypassIface == "" {
		bypassIface = "auto"
	}
}

func findWireGuard() string {
	if p, err := exec.LookPath("wireguard.exe"); err == nil {
		return p
	}
	for _, env := range []string{"ProgramFiles", "ProgramW6432", "ProgramFiles(x86)"} {
		if root := os.Getenv(env); root != "" {
			p := filepath.Join(root, "WireGuard", "wireguard.exe")
			if _, err := os.Stat(p); err == nil {
				return p
			}
		}
	}
	return ""
}

func tunConfPath() (string, error) {
	base := os.Getenv("LOCALAPPDATA")
	if base == "" {
		base = os.TempDir()
	}
	dir := filepath.Join(base, "TrueGuard", "tunnel")
	if err := os.MkdirAll(dir, 0700); err != nil {
		return "", err
	}
	return filepath.Join(dir, tunIfaceName+".conf"), nil
}

func startSystemTunnel(cfg *wgConfig, localEndpoint string, opts tunOptions) (func(), error) {
	wg := findWireGuard()
	if wg == "" {
		return nil, fmt.Errorf("для режима TUN установите WireGuard для Windows (https://www.wireguard.com/install/) или выберите режим «Прокси»")
	}
	conf, err := buildWindowsConfig(cfg, localEndpoint, opts)
	if err != nil {
		return nil, err
	}
	path, err := tunConfPath()
	if err != nil {
		return nil, err
	}
	if err := os.WriteFile(path, []byte(conf), 0600); err != nil {
		return nil, err
	}
	log.Printf("[TUN] Установка службы туннеля WireGuard (%s)...", tunIfaceName)
	// старая служба (после сбоя) удаляется в том же запросе прав, чтобы не спрашивать дважды
	if err := runWireGuardElevated(wg, [][]string{
		{"/uninstalltunnelservice", tunIfaceName},
		{"/installtunnelservice", path},
	}); err != nil {
		_ = os.Remove(path)
		return nil, err
	}
	deadline := time.Now().Add(20 * time.Second)
	for {
		if _, err := net.InterfaceByName(tunIfaceName); err == nil {
			break
		}
		if time.Now().After(deadline) {
			_ = runWireGuardElevated(wg, [][]string{{"/uninstalltunnelservice", tunIfaceName}})
			_ = os.Remove(path)
			return nil, fmt.Errorf("WireGuard не поднял интерфейс %s за 20 с", tunIfaceName)
		}
		time.Sleep(300 * time.Millisecond)
	}
	if len(opts.Include) > 0 {
		log.Printf("[TUN] Туннель %s поднят: через WDTT идут только %d адрес(ов) из белого списка", tunIfaceName, len(opts.Include))
	} else {
		log.Printf("[TUN] Туннель %s поднят: весь IPv4 идёт через WDTT (исключений: %d)", tunIfaceName, len(opts.Exclude))
	}
	return func() {
		if err := runWireGuardElevated(wg, [][]string{{"/uninstalltunnelservice", tunIfaceName}}); err != nil {
			log.Printf("[TUN] удаление службы туннеля: %v", err)
		}
		_ = os.Remove(path)
	}, nil
}

func isElevated() bool {
	return windows.GetCurrentProcessToken().IsElevated()
}

// runWireGuardElevated выполняет команды wireguard.exe с правами администратора одним запросом UAC.
func runWireGuardElevated(wg string, cmds [][]string) error {
	if isElevated() {
		var last error
		for i, args := range cmds {
			out, err := exec.Command(wg, args...).CombinedOutput()
			if err != nil && i == len(cmds)-1 {
				last = fmt.Errorf("wireguard %s: %v %s", args[0], err, strings.TrimSpace(string(out)))
			}
		}
		return last
	}
	q := func(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }
	var inner strings.Builder
	inner.WriteString("$wg=" + q(wg) + "\n$code=0\n")
	for i, args := range cmds {
		var quoted []string
		for _, a := range args {
			// путь к конфигу может содержать пробелы — внутри ArgumentList нужны двойные кавычки
			quoted = append(quoted, q(`"`+a+`"`))
		}
		fmt.Fprintf(&inner, "$p=Start-Process -FilePath $wg -ArgumentList %s -Wait -PassThru -WindowStyle Hidden\n", strings.Join(quoted, ","))
		if i == len(cmds)-1 {
			inner.WriteString("$code=$p.ExitCode\n")
		}
	}
	inner.WriteString("exit $code\n")
	outer := "try { $p=Start-Process -FilePath 'powershell.exe' -Verb RunAs -WindowStyle Hidden -Wait -PassThru " +
		"-ArgumentList '-NoProfile','-NonInteractive','-EncodedCommand','" + encodePS(inner.String()) + "'; exit $p.ExitCode } " +
		"catch { exit 1223 }"
	cmd := exec.Command("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encodePS(outer))
	out, err := cmd.CombinedOutput()
	if err != nil {
		if ee, ok := err.(*exec.ExitError); ok && ee.ExitCode() == 1223 {
			return fmt.Errorf("запрос прав администратора отменён")
		}
		return fmt.Errorf("wireguard %s: %v %s", cmds[len(cmds)-1][0], err, strings.TrimSpace(string(out)))
	}
	return nil
}

// encodePS — строка для powershell -EncodedCommand (UTF-16LE в base64).
func encodePS(script string) string {
	u := utf16.Encode([]rune(script))
	b := make([]byte, len(u)*2)
	for i, v := range u {
		binary.LittleEndian.PutUint16(b[i*2:], v)
	}
	return base64.StdEncoding.EncodeToString(b)
}

// buildWindowsConfig собирает конфиг WireGuard только из проверенных полей (как для wg-quick).
func buildWindowsConfig(cfg *wgConfig, localEndpoint string, opts tunOptions) (string, error) {
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
	if len(opts.Include) > 0 {
		for _, p := range opts.Include {
			allowed = append(allowed, p.String())
		}
	} else {
		for _, p := range splitDefault(subtractPrefixes(netip.MustParsePrefix("0.0.0.0/0"), opts.Exclude)) {
			allowed = append(allowed, p.String())
		}
		if opts.BlockIPv6 {
			for _, p := range splitDefault(subtractPrefixes(netip.MustParsePrefix("::/0"), opts.Exclude)) {
				allowed = append(allowed, p.String())
			}
		}
	}

	var b strings.Builder
	b.WriteString("[Interface]\n")
	fmt.Fprintf(&b, "PrivateKey = %s\n", cfg.PrivateKey)
	fmt.Fprintf(&b, "Address = %s\n", join(addrs))
	fmt.Fprintf(&b, "MTU = %d\n", cfg.MTU)
	if opts.DNS && len(dns) > 0 && len(opts.Include) == 0 && !opts.AppsOnly {
		fmt.Fprintf(&b, "DNS = %s\n", join(dns))
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

// splitDefault заменяет маршрут /0 двумя /1: тогда WireGuard не включает блокировку всего прочего трафика.
func splitDefault(ps []netip.Prefix) []netip.Prefix {
	var out []netip.Prefix
	for _, p := range ps {
		if p.Bits() == 0 {
			lo, hi := prefixHalves(p)
			out = append(out, lo, hi)
		} else {
			out = append(out, p)
		}
	}
	return out
}

// subtractPrefixes вычитает исключённые подсети из base, возвращая покрытие остатка.
func subtractPrefixes(base netip.Prefix, excl []netip.Prefix) []netip.Prefix {
	result := []netip.Prefix{base.Masked()}
	for _, e := range excl {
		e = e.Masked()
		if e.Addr().Is4() != base.Addr().Is4() {
			continue
		}
		var next []netip.Prefix
		for _, r := range result {
			next = append(next, subtractOne(r, e)...)
		}
		result = next
	}
	return result
}

func subtractOne(r, e netip.Prefix) []netip.Prefix {
	if !r.Overlaps(e) {
		return []netip.Prefix{r}
	}
	if e.Bits() <= r.Bits() {
		return nil // исключение целиком покрывает r
	}
	var out []netip.Prefix
	cur := r
	for cur.Bits() < e.Bits() {
		lo, hi := prefixHalves(cur)
		if lo.Contains(e.Addr()) {
			out = append(out, hi)
			cur = lo
		} else {
			out = append(out, lo)
			cur = hi
		}
	}
	return out
}

func prefixHalves(p netip.Prefix) (netip.Prefix, netip.Prefix) {
	bits := p.Bits() + 1
	lo := netip.PrefixFrom(p.Addr(), bits)
	a := p.Addr().As16()
	off := 0
	if p.Addr().Is4() {
		off = 96
	}
	pos := off + p.Bits()
	a[pos/8] |= 0x80 >> (pos % 8)
	hiAddr := netip.AddrFrom16(a)
	if p.Addr().Is4() {
		hiAddr = hiAddr.Unmap()
	}
	return lo, netip.PrefixFrom(hiAddr, bits)
}
