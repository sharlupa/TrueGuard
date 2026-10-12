package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/binary"
	"io"
	"net"
	"net/http"
	"strconv"
	"time"
)

// Локальный прокси: SOCKS5 и HTTP (CONNECT и обычные запросы) на одном порту.
// Используется в режиме proxy (dial = сеть внутри WireGuard) и для браузера капчи
// (dial = сокеты в обход туннеля).

type dialFunc func(ctx context.Context, network, address string) (net.Conn, error)

const proxyDialTimeout = 20 * time.Second

func serveProxy(ctx context.Context, ln net.Listener, dial dialFunc) {
	stop := context.AfterFunc(ctx, func() { _ = ln.Close() })
	defer stop()
	for {
		c, err := ln.Accept()
		if err != nil {
			if ne, ok := err.(net.Error); ok && ne.Timeout() {
				continue
			}
			return
		}
		go handleProxyConn(ctx, c, dial)
	}
}

func handleProxyConn(ctx context.Context, c net.Conn, dial dialFunc) {
	defer c.Close()
	_ = c.SetReadDeadline(time.Now().Add(30 * time.Second))
	br := bufio.NewReader(c)
	first, err := br.Peek(1)
	if err != nil {
		return
	}
	if first[0] == 0x05 {
		handleSocks5(ctx, c, br, dial)
		return
	}
	handleHTTPProxy(ctx, c, br, dial)
}

func handleSocks5(ctx context.Context, c net.Conn, br *bufio.Reader, dial dialFunc) {
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(br, hdr); err != nil {
		return
	}
	methods := make([]byte, hdr[1])
	if _, err := io.ReadFull(br, methods); err != nil {
		return
	}
	if !bytes.Contains(methods, []byte{0x00}) {
		_, _ = c.Write([]byte{0x05, 0xFF})
		return
	}
	if _, err := c.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	req := make([]byte, 4)
	if _, err := io.ReadFull(br, req); err != nil {
		return
	}
	var host string
	switch req[3] {
	case 0x01, 0x04:
		ip := make([]byte, 4)
		if req[3] == 0x04 {
			ip = make([]byte, 16)
		}
		if _, err := io.ReadFull(br, ip); err != nil {
			return
		}
		host = net.IP(ip).String()
	case 0x03:
		l, err := br.ReadByte()
		if err != nil {
			return
		}
		name := make([]byte, l)
		if _, err := io.ReadFull(br, name); err != nil {
			return
		}
		host = string(name)
	default:
		socksReply(c, 0x08)
		return
	}
	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(br, portBuf); err != nil {
		return
	}
	if req[1] != 0x01 { // поддерживается только CONNECT
		socksReply(c, 0x07)
		return
	}

	dctx, cancel := context.WithTimeout(ctx, proxyDialTimeout)
	up, err := dial(dctx, "tcp", net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(portBuf)))))
	cancel()
	if err != nil {
		socksReply(c, 0x05)
		return
	}
	defer up.Close()
	socksReply(c, 0x00)
	_ = c.SetReadDeadline(time.Time{})
	pipeConns(c, br, up)
}

func socksReply(c net.Conn, code byte) {
	_, _ = c.Write([]byte{0x05, code, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
}

func handleHTTPProxy(ctx context.Context, c net.Conn, br *bufio.Reader, dial dialFunc) {
	req, err := http.ReadRequest(br)
	if err != nil {
		return
	}

	if req.Method == http.MethodConnect {
		dctx, cancel := context.WithTimeout(ctx, proxyDialTimeout)
		up, err := dial(dctx, "tcp", hostWithDefaultPort(req.Host, "443"))
		cancel()
		if err != nil {
			_, _ = io.WriteString(c, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
			return
		}
		defer up.Close()
		if _, err := io.WriteString(c, "HTTP/1.1 200 Connection established\r\n\r\n"); err != nil {
			return
		}
		_ = c.SetReadDeadline(time.Time{})
		pipeConns(c, br, up)
		return
	}

	if req.URL.Host == "" {
		_, _ = io.WriteString(c, "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n")
		return
	}
	dctx, cancel := context.WithTimeout(ctx, proxyDialTimeout)
	up, err := dial(dctx, "tcp", hostWithDefaultPort(req.URL.Host, "80"))
	cancel()
	if err != nil {
		_, _ = io.WriteString(c, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
		return
	}
	defer up.Close()

	// Одно соединение на запрос: так не нужно разбирать ответы и keep-alive.
	req.Header.Del("Proxy-Connection")
	req.Header.Del("Proxy-Authorization")
	req.Header.Set("Connection", "close")
	req.Close = true
	_ = c.SetReadDeadline(time.Time{})
	if err := req.Write(up); err != nil {
		return
	}
	_, _ = io.Copy(c, up)
}

func hostWithDefaultPort(host, port string) string {
	if _, _, err := net.SplitHostPort(host); err == nil {
		return host
	}
	return net.JoinHostPort(host, port)
}

type closeWriter interface{ CloseWrite() error }

func pipeConns(client net.Conn, clientReader io.Reader, upstream net.Conn) {
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(upstream, clientReader)
		if cw, ok := upstream.(closeWriter); ok {
			_ = cw.CloseWrite()
		}
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(client, upstream)
		if cw, ok := client.(closeWriter); ok {
			_ = cw.CloseWrite()
		}
		done <- struct{}{}
	}()
	<-done
	<-done
}
