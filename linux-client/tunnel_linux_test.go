//go:build !windows

package main

import (
	"net/netip"
	"strings"
	"testing"
)

func TestBuildWGQuickConfig(t *testing.T) {
	_, pub := testKeyPair(t)
	priv, _ := testKeyPair(t)
	// Сервер пытается подсунуть команду, которую wg-quick выполнил бы от root.
	raw := "[Interface]\nPrivateKey = " + priv + "\nAddress = 10.66.66.2/32\nDNS = 1.1.1.1\nMTU = 1280\nPostUp = touch /tmp/pwned\n\n[Peer]\nPublicKey = " + pub +
		"\nAllowedIPs = 0.0.0.0/0\nEndpoint = 6.6.6.6:9000\nPersistentKeepalive = 25\nPreDown = rm -rf /"
	cfg, err := parseWGConfig(raw)
	if err != nil {
		t.Fatal(err)
	}

	got, err := buildWGQuickConfig(cfg, "127.0.0.1:9000", tunOptions{DNS: true, BlockIPv6: true,
		Exclude: []netip.Prefix{netip.MustParsePrefix("192.0.2.0/24")}})
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"FwMark = 51820", "AllowedIPs = 0.0.0.0/0, ::/0", "Endpoint = 127.0.0.1:9000", "DNS = 1.1.1.1",
		"PostUp = ip -4 rule add to 192.0.2.0/24 lookup main pref 31000"} {
		if !strings.Contains(got, want) {
			t.Errorf("нет %q в\n%s", want, got)
		}
	}
	for _, bad := range []string{"pwned", "rm -rf", "6.6.6.6"} {
		if strings.Contains(got, bad) {
			t.Errorf("в конфиг попало %q от сервера:\n%s", bad, got)
		}
	}

	got, err = buildWGQuickConfig(cfg, "127.0.0.1:9000", tunOptions{DNS: true,
		Include: []netip.Prefix{netip.MustParsePrefix("203.0.113.5/32")}})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(got, "AllowedIPs = 203.0.113.5/32") || strings.Contains(got, "DNS =") {
		t.Errorf("белый список:\n%s", got)
	}
}
