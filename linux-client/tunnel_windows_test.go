package main

import (
	"net/netip"
	"strings"
	"testing"
)

func TestSubtractPrefixes(t *testing.T) {
	got := subtractPrefixes(netip.MustParsePrefix("0.0.0.0/0"), []netip.Prefix{netip.MustParsePrefix("192.168.0.0/16")})
	// остаток должен покрывать всё, кроме 192.168.0.0/16
	for _, ip := range []string{"8.8.8.8", "192.167.255.255", "192.169.0.0", "10.0.0.1"} {
		if !coveredBy(got, ip) {
			t.Fatalf("%s должен идти в туннель: %v", ip, got)
		}
	}
	for _, ip := range []string{"192.168.0.1", "192.168.255.254"} {
		if coveredBy(got, ip) {
			t.Fatalf("%s должен идти мимо туннеля: %v", ip, got)
		}
	}
	if len(got) != 16 {
		t.Fatalf("ожидалось 16 подсетей, получено %d: %v", len(got), got)
	}
}

func TestSplitDefaultAvoidsKillSwitch(t *testing.T) {
	got := splitDefault([]netip.Prefix{netip.MustParsePrefix("0.0.0.0/0"), netip.MustParsePrefix("::/0")})
	var s []string
	for _, p := range got {
		s = append(s, p.String())
	}
	if strings.Join(s, ",") != "0.0.0.0/1,128.0.0.0/1,::/1,8000::/1" {
		t.Fatalf("got %v", s)
	}
}

func coveredBy(ps []netip.Prefix, ip string) bool {
	a := netip.MustParseAddr(ip)
	for _, p := range ps {
		if p.Contains(a) {
			return true
		}
	}
	return false
}
