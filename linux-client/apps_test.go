//go:build !windows

package main

import (
	"os"
	"testing"
)

func TestAppKeyMatching(t *testing.T) {
	keys := parseAppKeys("firefox, /opt/discord/Discord, flatpak:org.telegram.desktop, snap:spotify, telegram-desktop, run.sh")
	if len(keys) != 6 {
		t.Fatalf("ключей %d, ожидалось 6", len(keys))
	}
	cases := []struct {
		name string
		p    procInfo
		want int // индекс ключа или -1
	}{
		{"exe basename", procInfo{exe: "/usr/lib/firefox/firefox", comm: "firefox", argv: []string{"/usr/lib/firefox/firefox"}}, 0},
		{"exe path", procInfo{exe: "/opt/discord/Discord", comm: "Discord"}, 1},
		{"flatpak scope", procInfo{exe: "/app/bin/telegram", cgroup: "/user.slice/user-1000.slice/user@1000.service/app.slice/app-flatpak-org.telegram.desktop-1234.scope"}, 2},
		{"snap scope", procInfo{exe: "/snap/spotify/1/spotify", cgroup: "/user.slice/user-1000.slice/user@1000.service/app.slice/snap.spotify.spotify-abc.scope"}, 3},
		{"comm truncated", procInfo{exe: "/bedrock/strata/arch/usr/bin/telegram-desktop-x", comm: "telegram-deskto"}, 4},
		{"script", procInfo{exe: "/usr/bin/bash", comm: "run.sh", argv: []string{"/bin/bash", "/opt/app/run.sh"}}, 5},
		{"script via python", procInfo{exe: "/usr/bin/python3.13", comm: "python3", argv: []string{"python3.13", "/opt/x/run.sh"}}, 5},
		{"other", procInfo{exe: "/usr/bin/chromium", comm: "chromium", argv: []string{"/usr/bin/chromium", "firefox"}}, -1},
	}
	for _, c := range cases {
		got := -1
		for i, k := range keys {
			if c.p.matches(k) {
				got = i
				break
			}
		}
		if got != c.want {
			t.Errorf("%s: совпал ключ %d, ожидался %d", c.name, got, c.want)
		}
	}
}

func TestReadProcInfoSelf(t *testing.T) {
	p, err := readProcInfo(os.Getpid())
	if err != nil {
		t.Fatal(err)
	}
	if p.exe == "" || len(p.argv) == 0 || p.cgroup == "" {
		t.Fatalf("неполная информация о процессе: %+v", p)
	}
	if procStartTime(os.Getpid()) == 0 {
		t.Fatal("starttime = 0")
	}
	if parentPID(os.Getpid()) != os.Getppid() {
		t.Fatal("parentPID не совпадает с getppid")
	}
}
