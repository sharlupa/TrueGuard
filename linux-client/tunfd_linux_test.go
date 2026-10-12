package main

import (
	"net"
	"os"
	"syscall"
	"testing"
)

// Приложение передаёт дескриптор TUN через абстрактный unix-сокет (как LocalSocket в Android).
func TestReceiveFD(t *testing.T) {
	name := "@wdtt-test-" + randomHexForTest()
	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: name, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	defer w.Close()
	go func() {
		c, err := net.DialUnix("unix", nil, &net.UnixAddr{Name: name, Net: "unix"})
		if err != nil {
			return
		}
		defer c.Close()
		_, _, _ = c.WriteMsgUnix([]byte{1}, syscall.UnixRights(int(w.Fd())), nil)
	}()
	c, err := ln.AcceptUnix()
	if err != nil {
		t.Fatal(err)
	}
	fd, err := receiveFD(c)
	if err != nil {
		t.Fatal(err)
	}
	f := os.NewFile(uintptr(fd), "tun")
	defer f.Close()
	if _, err := f.Write([]byte("ok")); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 2)
	if _, err := r.Read(buf); err != nil || string(buf) != "ok" {
		t.Fatalf("получен не тот дескриптор: %q %v", buf, err)
	}
}

func randomHexForTest() string {
	b := make([]byte, 6)
	f, _ := os.Open("/dev/urandom")
	defer f.Close()
	_, _ = f.Read(b)
	const hexd = "0123456789abcdef"
	out := make([]byte, 0, 12)
	for _, v := range b {
		out = append(out, hexd[v>>4], hexd[v&15])
	}
	return string(out)
}
