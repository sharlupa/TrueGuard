package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log"
	"net"
	"syscall"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
)

// Режим tunfd — для Android: системный интерфейс создаёт VpnService приложения.
// Ядро сообщает событием TUN_REQUEST адреса, DNS и MTU из конфига WireGuard,
// приложение поднимает VPN и передаёт дескриптор TUN через абстрактный unix-сокет
// (SCM_RIGHTS), а ядро запускает на нём WireGuard. Свои сокеты ядро не помечает:
// приложение исключает само себя из VPN (addDisallowedApplication).
func startFDTunnel(ctx context.Context, cfg *wgConfig) (func(), error) {
	var rnd [8]byte
	_, _ = rand.Read(rnd[:])
	name := "wdtt-tun-" + hex.EncodeToString(rnd[:])
	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: "@" + name, Net: "unix"})
	if err != nil {
		return nil, fmt.Errorf("сокет для TUN: %w", err)
	}
	defer ln.Close()

	var addrs, dns []string
	for _, a := range cfg.Addresses {
		addrs = append(addrs, a.String())
	}
	for _, d := range cfg.DNS {
		dns = append(dns, d.String())
	}
	emitEvent(eventTunRequest, map[string]any{"socket": name, "address": addrs, "dns": dns, "mtu": cfg.MTU})

	_ = ln.SetDeadline(time.Now().Add(60 * time.Second))
	go func() {
		<-ctx.Done()
		_ = ln.Close()
	}()
	c, err := ln.AcceptUnix()
	if err != nil {
		return nil, fmt.Errorf("приложение не передало TUN: %w", err)
	}
	fd, err := receiveFD(c)
	_ = c.Close()
	if err != nil {
		return nil, err
	}

	tunDev, ifname, err := tun.CreateUnmonitoredTUNFromFD(fd)
	if err != nil {
		_ = syscall.Close(fd)
		return nil, fmt.Errorf("TUN из дескриптора: %w", err)
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
	log.Printf("[TUN] VPN Android поднят (%s, %v, DNS %v)", ifname, addrs, dns)
	return dev.Close, nil
}

func receiveFD(c *net.UnixConn) (int, error) {
	buf := make([]byte, 1)
	oob := make([]byte, syscall.CmsgSpace(4))
	_ = c.SetReadDeadline(time.Now().Add(10 * time.Second))
	_, oobn, _, _, err := c.ReadMsgUnix(buf, oob)
	if err != nil {
		return -1, fmt.Errorf("приём дескриптора TUN: %w", err)
	}
	msgs, err := syscall.ParseSocketControlMessage(oob[:oobn])
	if err != nil || len(msgs) == 0 {
		return -1, fmt.Errorf("в сообщении нет дескриптора TUN")
	}
	fds, err := syscall.ParseUnixRights(&msgs[0])
	if err != nil || len(fds) == 0 {
		return -1, fmt.Errorf("в сообщении нет дескриптора TUN")
	}
	for _, extra := range fds[1:] {
		_ = syscall.Close(extra)
	}
	return fds[0], nil
}
