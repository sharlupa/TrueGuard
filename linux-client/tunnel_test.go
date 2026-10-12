package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.org/x/net/proxy"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

func testKeyPair(t *testing.T) (privB64, pubB64 string) {
	var priv [32]byte
	if _, err := rand.Read(priv[:]); err != nil {
		t.Fatal(err)
	}
	priv[0] &= 248
	priv[31] = (priv[31] & 127) | 64
	pub, err := curve25519.X25519(priv[:], curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	return base64.StdEncoding.EncodeToString(priv[:]), base64.StdEncoding.EncodeToString(pub)
}

func freeAddr(t *testing.T, network string) string {
	switch network {
	case "udp":
		c, err := net.ListenPacket("udp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		defer c.Close()
		return c.LocalAddr().String()
	default:
		l, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		defer l.Close()
		return l.Addr().String()
	}
}

// Сквозная проверка режима proxy: WG-конфиг в формате сервера → userspace WireGuard
// → SOCKS5/HTTP-прокси → HTTP-сервер внутри «серверного» WireGuard.
func TestProxyTunnelEndToEnd(t *testing.T) {
	srvPriv, srvPub := testKeyPair(t)
	cliPriv, cliPub := testKeyPair(t)
	srvEndpoint := freeAddr(t, "udp")
	_, srvPort, _ := net.SplitHostPort(srvEndpoint)

	srvTun, srvNet, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.66.66.1")}, nil, 1280)
	if err != nil {
		t.Fatal(err)
	}
	srvDev := device.NewDevice(srvTun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "[srv] "))
	defer srvDev.Close()
	srvPrivHex, _ := keyB64ToHex(srvPriv)
	cliPubHex, _ := keyB64ToHex(cliPub)
	if err := srvDev.IpcSet(fmt.Sprintf("private_key=%s\nlisten_port=%s\npublic_key=%s\nallowed_ip=10.66.66.2/32\n", srvPrivHex, srvPort, cliPubHex)); err != nil {
		t.Fatal(err)
	}
	if err := srvDev.Up(); err != nil {
		t.Fatal(err)
	}
	httpLn, err := srvNet.ListenTCP(&net.TCPAddr{Port: 80})
	if err != nil {
		t.Fatal(err)
	}
	go http.Serve(httpLn, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, "hello-wdtt "+r.URL.Path)
	}))

	// Тот же формат, что у buildClientConfig на сервере.
	raw := fmt.Sprintf("[Interface]\nPrivateKey = %s\nAddress = 10.66.66.2/32\nDNS = 1.1.1.1\nMTU = 1280\n\n[Peer]\nPublicKey = %s\nAllowedIPs = 0.0.0.0/0\nEndpoint = 127.0.0.1:%s\nPersistentKeepalive = 25",
		cliPriv, srvPub, srvPort)
	cfg, err := parseWGConfig(raw)
	if err != nil {
		t.Fatal(err)
	}
	proxyAddr := freeAddr(t, "tcp")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	stop, err := startProxyTunnel(ctx, cfg, proxyAddr)
	if err != nil {
		t.Fatal(err)
	}
	defer stop()

	expect := func(name, got string) {
		t.Helper()
		if !strings.HasPrefix(got, "hello-wdtt /") {
			t.Fatalf("%s: неожиданный ответ %q", name, got)
		}
	}

	// SOCKS5
	socks, err := proxy.SOCKS5("tcp", proxyAddr, nil, proxy.Direct)
	if err != nil {
		t.Fatal(err)
	}
	socksClient := &http.Client{Timeout: 10 * time.Second, Transport: &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return socks.(proxy.ContextDialer).DialContext(ctx, network, addr)
		},
	}}
	expect("socks5", get(t, socksClient, "http://10.66.66.1/socks"))

	// HTTP-прокси, обычный запрос
	pu, _ := url.Parse("http://" + proxyAddr)
	httpClient := &http.Client{Timeout: 10 * time.Second, Transport: &http.Transport{Proxy: http.ProxyURL(pu)}}
	expect("http", get(t, httpClient, "http://10.66.66.1/plain"))

	// HTTP CONNECT
	c, err := net.DialTimeout("tcp", proxyAddr, 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(10 * time.Second))
	fmt.Fprintf(c, "CONNECT 10.66.66.1:80 HTTP/1.1\r\nHost: 10.66.66.1:80\r\n\r\n")
	br := bufio.NewReader(c)
	resp, err := http.ReadResponse(br, nil)
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("CONNECT: %v %v", resp, err)
	}
	fmt.Fprintf(c, "GET /connect HTTP/1.1\r\nHost: 10.66.66.1\r\nConnection: close\r\n\r\n")
	resp, err = http.ReadResponse(br, nil)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	expect("connect", string(body))
}

func get(t *testing.T, c *http.Client, u string) string {
	t.Helper()
	resp, err := c.Get(u)
	if err != nil {
		t.Fatalf("GET %s: %v", u, err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return string(b)
}
