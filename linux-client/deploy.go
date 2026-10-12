package main

import (
	"bufio"
	"bytes"
	_ "embed"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/ssh"
)

// Установка и удаление сервера WDTT на VPS по SSH — то, что в Android делала вкладка «Деплой».
// Секреты передаются через переменные окружения, чтобы не светиться в списке процессов:
//   WDTT_SSH_PASSWORD, WDTT_TUNNEL_PASSWORD, WDTT_ADMIN_ID, WDTT_BOT_TOKEN

//go:embed deploy_assets/deploy.sh
var deployScript []byte

//go:embed deploy_assets/wdtt-server
var deployServerBinary []byte

var (
	safePasswordRe = regexp.MustCompile(`^[A-Za-z0-9._@:+=,!#%^*~-]{4,128}$`)
	adminIDRe      = regexp.MustCompile(`^[0-9]{1,20}$`)
	botTokenRe     = regexp.MustCompile(`^[0-9]{3,20}:[A-Za-z0-9_-]{20,100}$`)
	progressLineRe = regexp.MustCompile(`^WDTT_PROGRESS\|([0-9.]+)\|(.*)$`)
)

func runDeploy(args []string) int {
	log.SetFlags(log.Ltime)
	fs := flag.NewFlagSet("deploy", flag.ExitOnError)
	host := fs.String("host", "", "IP или домен VPS")
	sshPort := fs.Int("ssh-port", 22, "порт SSH")
	user := fs.String("user", "root", "логин SSH")
	dtlsPort := fs.Int("dtls-port", 56000, "порт DTLS сервера")
	wgPort := fs.Int("wg-port", 56001, "порт WireGuard сервера")
	action := "install"
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		action, args = args[0], args[1:]
	}
	_ = fs.Parse(args)
	eventOutputEnabled = true

	fail := func(msg string) int {
		log.Printf("[ДЕПЛОЙ] Ошибка: %s", msg)
		emitEvent(eventDeployDone, map[string]any{"ok": false, "action": action, "message": msg})
		return 1
	}
	progress := func(p float64, text string) {
		log.Printf("[ДЕПЛОЙ] %s", text)
		emitEvent(eventDeployProgress, map[string]any{"progress": p, "text": text})
	}

	if *host == "" {
		return fail("не указан IP сервера")
	}
	if *dtlsPort < 1 || *dtlsPort > 65535 || *wgPort < 1 || *wgPort > 65535 || *sshPort < 1 || *sshPort > 65535 {
		return fail("неверный порт")
	}
	sshPassword := os.Getenv("WDTT_SSH_PASSWORD")
	tunnelPassword := os.Getenv("WDTT_TUNNEL_PASSWORD")
	adminID := os.Getenv("WDTT_ADMIN_ID")
	botToken := os.Getenv("WDTT_BOT_TOKEN")

	var serverArgs []string
	if action == "install" {
		if tunnelPassword == "" {
			return fail("задайте пароль туннеля")
		}
		// Значения попадают в systemd ExecStart через WDTT_ARGS — пускаем только безопасные символы.
		if !safePasswordRe.MatchString(tunnelPassword) {
			return fail("пароль туннеля: 4–128 символов, латиница, цифры и ._@:+=,!#%^*~-")
		}
		serverArgs = append(serverArgs, `-password "`+tunnelPassword+`"`)
		if adminID != "" {
			if !adminIDRe.MatchString(adminID) {
				return fail("ID админа — только цифры")
			}
			serverArgs = append(serverArgs, `-admin "`+adminID+`"`)
		}
		if botToken != "" {
			if !botTokenRe.MatchString(botToken) {
				return fail("неверный формат токена бота")
			}
			serverArgs = append(serverArgs, `-bot-token "`+botToken+`"`)
		}
	} else if action != "uninstall" {
		return fail("неизвестное действие " + action)
	}

	progress(0.02, "Подключение по SSH...")
	client, err := sshConnect(*host, *sshPort, *user, sshPassword)
	if err != nil {
		return fail("СБОЙ SSH: " + err.Error())
	}
	defer client.Close()
	r := &remote{client: client, password: sshPassword, isRoot: *user == "root"}

	progress(0.05, "Подготовка файлов...")
	progress(0.06, "Загрузка на сервер...")
	if err := r.upload("/tmp/deploy.sh", deployScript); err != nil {
		return fail("загрузка deploy.sh: " + err.Error())
	}

	if action == "uninstall" {
		steps := []struct {
			p    float64
			text string
			cmd  string
		}{
			{0.15, "Остановка сервиса...", "systemctl unmask wdtt 2>/dev/null || true; systemctl stop wdtt 2>/dev/null || true; systemctl disable wdtt 2>/dev/null || true; rm -f /etc/systemd/system/wdtt.service; systemctl daemon-reload 2>/dev/null || true"},
			{0.3, "Удаление через deploy.sh...", fmt.Sprintf("[ -f /tmp/deploy.sh ] && env WDTT_DTLS_PORT=%d WDTT_WG_PORT=%d bash /tmp/deploy.sh uninstall 2>/dev/null || true", *dtlsPort, *wgPort)},
			{0.45, "Удаление бинарника...", "pkill -x wdtt-server 2>/dev/null || true; rm -f /usr/local/bin/wdtt-server"},
			{0.6, "Очистка firewall...", fmt.Sprintf(`if command -v iptables >/dev/null 2>&1; then for i in 1 2 3 4 5; do for iface in $(ls /sys/class/net 2>/dev/null || true); do iptables -t nat -D POSTROUTING -s 10.66.66.0/24 -o "$iface" -m comment --comment WDTT_MANAGED -j MASQUERADE 2>/dev/null || true; done; iptables -D INPUT -p udp --dport %d -m comment --comment WDTT_MANAGED -j ACCEPT 2>/dev/null || true; iptables -D INPUT -p udp --dport %d -m comment --comment WDTT_MANAGED -j ACCEPT 2>/dev/null || true; iptables -D INPUT -p udp --dport 56000 -m comment --comment WDTT_MANAGED -j ACCEPT 2>/dev/null || true; iptables -D INPUT -p udp --dport 56001 -m comment --comment WDTT_MANAGED -j ACCEPT 2>/dev/null || true; iptables -D FORWARD -i wdtt0 -m comment --comment WDTT_MANAGED -j ACCEPT 2>/dev/null || true; iptables -D FORWARD -o wdtt0 -m comment --comment WDTT_MANAGED -j ACCEPT 2>/dev/null || true; done; fi; if command -v nft >/dev/null 2>&1; then nft delete table ip wdtt 2>/dev/null || true; nft delete table inet wdtt 2>/dev/null || true; nft delete table inet wdtt_mangle 2>/dev/null || true; fi`, *dtlsPort, *wgPort)},
			{0.75, "Удаление WDTT-интерфейса...", "ip link show wdtt0 >/dev/null 2>&1 && ip link del wdtt0 2>/dev/null || true; rm -rf /etc/wdtt"},
			{0.9, "Очистка sysctl...", "rm -f /etc/sysctl.d/99-wdtt.conf; sysctl --system >/dev/null 2>&1 || true"},
		}
		for _, s := range steps {
			progress(s.p, s.text)
			if _, err := r.runRoot(s.cmd, nil); err != nil {
				return fail(s.text + " " + err.Error())
			}
		}
		progress(1.0, "Готово!")
		emitEvent(eventDeployDone, map[string]any{"ok": true, "action": action, "message": "Сервер удалён"})
		return 0
	}

	if err := r.upload("/tmp/wdtt-server", deployServerBinary); err != nil {
		return fail("загрузка wdtt-server: " + err.Error())
	}
	progress(0.08, "Установка...")

	cmd := fmt.Sprintf("env WDTT_ARGS=%s WDTT_DTLS_PORT=%d WDTT_WG_PORT=%d WDTT_SSH_PORT=%d bash /tmp/deploy.sh",
		shellQuote(strings.Join(serverArgs, " ")), *dtlsPort, *wgPort, *sshPort)
	out, err := r.runRoot(cmd, func(line string) {
		if m := progressLineRe.FindStringSubmatch(line); m != nil {
			p, _ := strconv.ParseFloat(m[1], 64)
			if p < 0.08 {
				p = 0.08
			}
			progress(p, m[2])
			return
		}
		if strings.TrimSpace(line) != "" {
			log.Printf("[СЕРВЕР] %s", line)
		}
	})
	ok := err == nil && !strings.Contains(out, "error:")
	if strings.Contains(out, "✅") || strings.Contains(out, "Деплой успешно") {
		ok = err == nil
	}
	if !ok {
		msg := "Ошибка выполнения скрипта"
		if err != nil {
			msg += ": " + err.Error()
		}
		return fail(msg)
	}
	progress(1.0, "Деплой успешно")
	emitEvent(eventDeployDone, map[string]any{"ok": true, "action": action, "message": "Сервер установлен"})
	return 0
}

func sshConnect(host string, port int, user, password string) (*ssh.Client, error) {
	cfg := &ssh.ClientConfig{
		User: user,
		Auth: []ssh.AuthMethod{
			ssh.Password(password),
			ssh.KeyboardInteractive(func(_, _ string, questions []string, _ []bool) ([]string, error) {
				answers := make([]string, len(questions))
				for i := range answers {
					answers[i] = password
				}
				return answers, nil
			}),
		},
		// Как в приложении (StrictHostKeyChecking=no), но отпечаток пишем в лог.
		HostKeyCallback: func(hostname string, _ net.Addr, key ssh.PublicKey) error {
			log.Printf("[ДЕПЛОЙ] Ключ сервера %s: %s", hostname, ssh.FingerprintSHA256(key))
			return nil
		},
		Timeout: 20 * time.Second,
	}
	conn, err := newDialer(20*time.Second, 30*time.Second).Dial("tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return nil, err
	}
	c, chans, reqs, err := ssh.NewClientConn(conn, net.JoinHostPort(host, strconv.Itoa(port)), cfg)
	if err != nil {
		conn.Close()
		return nil, err
	}
	return ssh.NewClient(c, chans, reqs), nil
}

type remote struct {
	client   *ssh.Client
	password string
	isRoot   bool
}

func (r *remote) upload(path string, data []byte) error {
	s, err := r.client.NewSession()
	if err != nil {
		return err
	}
	defer s.Close()
	s.Stdin = bytes.NewReader(data)
	var stderr bytes.Buffer
	s.Stderr = &stderr
	if err := s.Run("cat > " + shellQuote(path)); err != nil {
		return fmt.Errorf("%v %s", err, strings.TrimSpace(stderr.String()))
	}
	return nil
}

// runRoot выполняет команду от root: напрямую, если вошли root, иначе через sudo -S с паролем SSH.
func (r *remote) runRoot(cmd string, onLine func(string)) (string, error) {
	s, err := r.client.NewSession()
	if err != nil {
		return "", err
	}
	defer s.Close()

	q := shellQuote(cmd)
	wrapped := `if [ "$(id -u)" = "0" ]; then bash -c ` + q +
		`; elif command -v sudo >/dev/null 2>&1; then sudo -S -p '' bash -c ` + q +
		`; else echo 'error: root privileges required and sudo not found'; exit 1; fi`
	if !r.isRoot {
		s.Stdin = strings.NewReader(r.password + "\n")
	}

	var mu sync.Mutex
	var all strings.Builder
	pr, pw := io.Pipe()
	s.Stdout = pw
	s.Stderr = pw
	done := make(chan struct{})
	go func() {
		defer close(done)
		sc := bufio.NewScanner(pr)
		sc.Buffer(make([]byte, 64*1024), 1024*1024)
		for sc.Scan() {
			line := sc.Text()
			mu.Lock()
			all.WriteString(line + "\n")
			mu.Unlock()
			if onLine != nil {
				onLine(line)
			}
		}
	}()
	runErr := s.Run(wrapped)
	pw.Close()
	<-done
	mu.Lock()
	defer mu.Unlock()
	return all.String(), runErr
}

func shellQuote(s string) string {
	return "'" + strings.ReplaceAll(s, "'", `'"'"'`) + "'"
}
