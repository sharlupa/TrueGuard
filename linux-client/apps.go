//go:build !windows

package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Раздельное туннелирование по приложениям (на Android — «Исключения» через VpnService).
// На Linux процессы выбранных приложений переносятся в отдельную cgroup v2, а nftables
// помечает их пакеты fwmark tunFwMark. wg-quick заворачивает в туннель только непомеченный
// трафик, поэтому:
//   bypass — помеченные приложения идут мимо VPN (чёрный список);
//   only   — помечено всё, кроме приложений из cgroup, и через VPN идут только они (белый список).

const (
	cgroupRoot     = "/sys/fs/cgroup"
	appsNftTable   = "trueguard"
	appsScanPeriod = 300 * time.Millisecond
)

type appKey struct {
	kind  string // name, path, flatpak, snap
	value string
}

func parseAppKeys(raw string) []appKey {
	var keys []appKey
	for _, item := range splitList(raw) {
		switch {
		case strings.HasPrefix(item, "flatpak:"):
			keys = append(keys, appKey{"flatpak", strings.TrimPrefix(item, "flatpak:")})
		case strings.HasPrefix(item, "snap:"):
			keys = append(keys, appKey{"snap", strings.TrimPrefix(item, "snap:")})
		case strings.HasPrefix(item, "/"):
			keys = append(keys, appKey{"path", filepath.Clean(item)})
		default:
			keys = append(keys, appKey{"name", item})
		}
	}
	return keys
}

// procInfo — то, по чему процесс сопоставляется с приложением.
type procInfo struct {
	exe    string
	comm   string
	argv   []string
	cgroup string
}

var interpreters = map[string]bool{
	"sh": true, "bash": true, "dash": true, "zsh": true, "env": true,
	"python": true, "python3": true, "perl": true, "node": true, "java": true,
}

func (p *procInfo) matches(k appKey) bool {
	switch k.kind {
	case "flatpak":
		return strings.Contains(p.cgroup, "/app-flatpak-"+k.value+"-")
	case "snap":
		return strings.Contains(p.cgroup, "/snap."+k.value+".")
	case "path":
		if p.exe == k.value {
			return true
		}
		for i, a := range p.argv {
			if i > 1 {
				break
			}
			if a == k.value {
				return true
			}
		}
		return false
	}
	name := k.value
	if filepath.Base(p.exe) == name {
		return true
	}
	// comm обрезается ядром до 15 символов
	if len(name) > 15 && p.comm == name[:15] || p.comm == name {
		return true
	}
	if len(p.argv) > 0 {
		arg0 := filepath.Base(p.argv[0])
		if arg0 == name {
			return true
		}
		// скрипты: /bin/sh /opt/app/run.sh
		if interpreters[strings.TrimRight(arg0, "0123456789.")] && len(p.argv) > 1 && filepath.Base(p.argv[1]) == name {
			return true
		}
	}
	return false
}

func readProcInfo(pid int) (*procInfo, error) {
	dir := "/proc/" + strconv.Itoa(pid)
	cmdline, err := os.ReadFile(dir + "/cmdline")
	if err != nil {
		return nil, err
	}
	if len(cmdline) == 0 {
		return nil, errors.New("kernel thread")
	}
	p := &procInfo{}
	p.exe, _ = os.Readlink(dir + "/exe")
	p.exe = strings.TrimSuffix(p.exe, " (deleted)")
	if comm, err := os.ReadFile(dir + "/comm"); err == nil {
		p.comm = strings.TrimSpace(string(comm))
	}
	for _, a := range bytes.Split(bytes.TrimRight(cmdline, "\x00"), []byte{0}) {
		p.argv = append(p.argv, string(a))
	}
	p.cgroup = procCgroup(pid)
	return p, nil
}

// procCgroup возвращает путь cgroup v2 процесса (строка «0::/путь»).
func procCgroup(pid int) string {
	data, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/cgroup")
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(data), "\n") {
		if rest, ok := strings.CutPrefix(line, "0::"); ok {
			return rest
		}
	}
	return ""
}

// procStartTime — поле starttime из /proc/PID/stat, чтобы не путать переиспользованные PID.
func procStartTime(pid int) uint64 {
	data, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/stat")
	if err != nil {
		return 0
	}
	// comm может содержать пробелы и скобки — поля считаем после последней «)»
	i := bytes.LastIndexByte(data, ')')
	if i < 0 {
		return 0
	}
	fields := strings.Fields(string(data[i+1:]))
	if len(fields) < 20 {
		return 0
	}
	v, _ := strconv.ParseUint(fields[19], 10, 64)
	return v
}

type appRouting struct {
	mode   string
	keys   []appKey
	cgName string
	cgPath string

	mu    sync.Mutex
	seen  map[int]uint64 // pid → starttime: уже проверенные процессы
	moved map[int]string // pid → исходная cgroup
	self  map[int]bool   // наш процесс и его предки (pkexec, GUI) не трогаем
}

func cgroup2Available() bool {
	_, err := os.Stat(filepath.Join(cgroupRoot, "cgroup.controllers"))
	return err == nil
}

// startAppRouting поднимает cgroup, правила nftables и наблюдатель за процессами.
func startAppRouting(ctx context.Context, mode string, keys []appKey) (func(), error) {
	if len(keys) == 0 {
		return func() {}, nil
	}
	if mode != "bypass" && mode != "only" {
		return nil, fmt.Errorf("неизвестный -apps-mode %q (bypass/only)", mode)
	}
	if !cgroup2Available() {
		return nil, fmt.Errorf("приложения: нужна cgroup v2 в %s", cgroupRoot)
	}
	if _, err := exec.LookPath("nft"); err != nil {
		return nil, fmt.Errorf("приложения: не найден nft (пакет nftables)")
	}

	r := &appRouting{
		mode:   mode,
		keys:   keys,
		cgName: "trueguard-" + mode,
		seen:   map[int]uint64{},
		moved:  map[int]string{},
		self:   map[int]bool{},
	}
	r.cgPath = filepath.Join(cgroupRoot, r.cgName)
	for pid := os.Getpid(); pid > 1; pid = parentPID(pid) {
		r.self[pid] = true
	}
	if err := os.Mkdir(r.cgPath, 0755); err != nil && !os.IsExist(err) {
		return nil, fmt.Errorf("cgroup %s: %w", r.cgPath, err)
	}
	if err := r.loadRules(); err != nil {
		_ = os.Remove(r.cgPath)
		return nil, err
	}

	wctx, cancel := context.WithCancel(ctx)
	done := make(chan struct{})
	r.scan()
	go func() {
		defer close(done)
		t := time.NewTicker(appsScanPeriod)
		defer t.Stop()
		for {
			select {
			case <-wctx.Done():
				return
			case <-t.C:
				r.scan()
			}
		}
	}()

	what := "мимо VPN"
	if mode == "only" {
		what = "только они через VPN"
	}
	log.Printf("[ПРИЛОЖЕНИЯ] Раздельное туннелирование: %d прилож. (%s), cgroup %s", len(keys), what, r.cgName)

	return func() {
		cancel()
		<-done
		r.stop()
	}, nil
}

func (r *appRouting) loadRules() error {
	var rule string
	if r.mode == "bypass" {
		rule = fmt.Sprintf(`socket cgroupv2 level 1 "%s" meta mark set %d ct mark set %d`, r.cgName, tunFwMark, tunFwMark)
	} else {
		rule = fmt.Sprintf(`socket cgroupv2 level 1 "%s" return
		meta mark 0 meta mark set %d ct mark set %d`, r.cgName, tunFwMark, tunFwMark)
	}
	// Ответы на помеченные соединения тоже помечаем, иначе rp_filter сверит их с таблицей туннеля.
	script := fmt.Sprintf(`table inet %[1]s {}
delete table inet %[1]s
table inet %[1]s {
	chain output {
		type route hook output priority mangle; policy accept;
		%[2]s
	}
	chain prerouting {
		type filter hook prerouting priority mangle; policy accept;
		ct mark %[3]d meta mark set %[3]d
	}
}
`, appsNftTable, rule, tunFwMark)
	cmd := exec.Command("nft", "-f", "-")
	cmd.Stdin = strings.NewReader(script)
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("nftables: %v: %s", err, strings.TrimSpace(string(out)))
	}
	return nil
}

func (r *appRouting) scan() {
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	alive := make(map[int]bool, len(entries))
	for _, e := range entries {
		pid, err := strconv.Atoi(e.Name())
		if err != nil || pid <= 1 || r.self[pid] {
			continue
		}
		alive[pid] = true
		st := procStartTime(pid)
		if prev, ok := r.seen[pid]; ok && prev == st {
			continue
		}
		r.seen[pid] = st
		delete(r.moved, pid)
		p, err := readProcInfo(pid)
		if err != nil || p.cgroup == "/"+r.cgName {
			continue
		}
		for _, k := range r.keys {
			if !p.matches(k) {
				continue
			}
			if err := os.WriteFile(filepath.Join(r.cgPath, "cgroup.procs"), []byte(strconv.Itoa(pid)), 0644); err != nil {
				log.Printf("[ПРИЛОЖЕНИЯ] Не удалось перенести %s (pid %d): %v", p.comm, pid, err)
			} else {
				r.moved[pid] = p.cgroup
				log.Printf("[ПРИЛОЖЕНИЯ] %s (pid %d) → %s", p.comm, pid, r.cgName)
			}
			break
		}
	}
	for pid := range r.seen {
		if !alive[pid] {
			delete(r.seen, pid)
			delete(r.moved, pid)
		}
	}
}

func (r *appRouting) stop() {
	if out, err := exec.Command("nft", "delete", "table", "inet", appsNftTable).CombinedOutput(); err != nil {
		log.Printf("[ПРИЛОЖЕНИЯ] nft delete table: %v: %s", err, strings.TrimSpace(string(out)))
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	returned := 0
	for pid, orig := range r.moved {
		if procCgroup(pid) != "/"+r.cgName {
			continue
		}
		if err := os.WriteFile(filepath.Join(cgroupRoot, orig, "cgroup.procs"), []byte(strconv.Itoa(pid)), 0644); err == nil {
			returned++
		}
	}
	// Если исходная cgroup уже удалена systemd, процесс остаётся в нашей — без правил nft она ни на что не влияет.
	if err := os.Remove(r.cgPath); err != nil && !os.IsNotExist(err) {
		log.Printf("[ПРИЛОЖЕНИЯ] cgroup %s оставлена: в ней остались процессы", r.cgName)
	}
	log.Printf("[ПРИЛОЖЕНИЯ] Правила сняты, возвращено процессов: %d", returned)
}

func parentPID(pid int) int {
	data, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/stat")
	if err != nil {
		return 0
	}
	i := bytes.LastIndexByte(data, ')')
	if i < 0 {
		return 0
	}
	fields := strings.Fields(string(data[i+1:]))
	if len(fields) < 2 {
		return 0
	}
	ppid, _ := strconv.Atoi(fields[1])
	return ppid
}
