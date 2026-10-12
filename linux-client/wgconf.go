package main

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"net/netip"
	"os"
	"strconv"
	"strings"
)

// wgConfig — WireGuard-конфиг, который сервер отдаёт на GETCONF.
type wgConfig struct {
	PrivateKey    string
	Addresses     []netip.Prefix
	DNS           []netip.Addr
	MTU           int
	PeerPublicKey string
	AllowedIPs    []string
	Endpoint      string
	Keepalive     int
}

func parseWGConfig(raw string) (*wgConfig, error) {
	cfg := &wgConfig{MTU: 1280}
	section := ""
	for _, line := range strings.Split(raw, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if strings.HasPrefix(line, "[") {
			section = strings.ToLower(strings.Trim(line, "[]"))
			continue
		}
		key, val, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		key = strings.ToLower(strings.TrimSpace(key))
		val = strings.TrimSpace(val)

		switch section + "." + key {
		case "interface.privatekey":
			cfg.PrivateKey = val
		case "interface.address":
			for _, a := range splitList(val) {
				p, err := netip.ParsePrefix(a)
				if err != nil {
					addr, err2 := netip.ParseAddr(a)
					if err2 != nil {
						return nil, fmt.Errorf("Address %q: %w", a, err)
					}
					p = netip.PrefixFrom(addr, addr.BitLen())
				}
				cfg.Addresses = append(cfg.Addresses, p)
			}
		case "interface.dns":
			for _, a := range splitList(val) {
				if addr, err := netip.ParseAddr(a); err == nil {
					cfg.DNS = append(cfg.DNS, addr)
				}
			}
		case "interface.mtu":
			if n, err := strconv.Atoi(val); err == nil && n > 0 {
				cfg.MTU = n
			}
		case "peer.publickey":
			cfg.PeerPublicKey = val
		case "peer.allowedips":
			cfg.AllowedIPs = splitList(val)
		case "peer.endpoint":
			cfg.Endpoint = val
		case "peer.persistentkeepalive":
			cfg.Keepalive, _ = strconv.Atoi(val)
		}
	}
	if cfg.PrivateKey == "" || cfg.PeerPublicKey == "" || len(cfg.Addresses) == 0 || cfg.Endpoint == "" {
		return nil, fmt.Errorf("неполный WireGuard-конфиг")
	}
	if len(cfg.DNS) == 0 {
		cfg.DNS = []netip.Addr{netip.MustParseAddr("1.1.1.1")}
	}
	return cfg, nil
}

func splitList(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

// uapi — конфиг в формате IPC wireguard-go (ключи в hex).
func (c *wgConfig) uapi() (string, error) {
	priv, err := keyB64ToHex(c.PrivateKey)
	if err != nil {
		return "", fmt.Errorf("PrivateKey: %w", err)
	}
	pub, err := keyB64ToHex(c.PeerPublicKey)
	if err != nil {
		return "", fmt.Errorf("PublicKey: %w", err)
	}
	var b strings.Builder
	fmt.Fprintf(&b, "private_key=%s\n", priv)
	fmt.Fprintf(&b, "public_key=%s\n", pub)
	fmt.Fprintf(&b, "endpoint=%s\n", c.Endpoint)
	for _, a := range c.AllowedIPs {
		fmt.Fprintf(&b, "allowed_ip=%s\n", a)
	}
	if c.Keepalive > 0 {
		fmt.Fprintf(&b, "persistent_keepalive_interval=%d\n", c.Keepalive)
	}
	return b.String(), nil
}

func keyB64ToHex(s string) (string, error) {
	b, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		return "", err
	}
	if len(b) != 32 {
		return "", fmt.Errorf("длина ключа %d != 32", len(b))
	}
	return hex.EncodeToString(b), nil
}

// defaultDeviceID — стабильный ID машины (аналог android_id, 16 hex-символов).
// Сервер привязывает сгенерированный пароль к первому device-id, а каждому
// device-id выдаёт свои WG-ключи и IP, поэтому ID не должен зависеть от
// пользователя (root в режиме tun и обычный пользователь в режиме proxy).
func defaultDeviceID() string {
	for _, p := range []string{"/etc/machine-id", "/var/lib/dbus/machine-id"} {
		if data, err := os.ReadFile(p); err == nil {
			if id := strings.TrimSpace(string(data)); id != "" {
				sum := sha256.Sum256([]byte("wdtt-linux-device:" + id))
				return hex.EncodeToString(sum[:8])
			}
		}
	}
	host, _ := os.Hostname()
	sum := sha256.Sum256([]byte("wdtt-linux-host:" + host))
	return hex.EncodeToString(sum[:8])
}
