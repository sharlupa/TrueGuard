package main

import (
	"bufio"
	"context"
	"flag"
	"fmt"
	"log"
	"net"
	"net/netip"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

var captchaModeValue atomic.Value
var vkAuthModeValue atomic.Value

func init() {
	captchaModeValue.Store("auto")
	vkAuthModeValue.Store("vkcalls")
}

func normalizeCaptchaMode(mode string) string {
	switch strings.ToLower(strings.TrimSpace(mode)) {
	case "auto", "rjs", "wv":
		return strings.ToLower(strings.TrimSpace(mode))
	default:
		return "auto"
	}
}

func setCaptchaMode(mode string) string {
	normalized := normalizeCaptchaMode(mode)
	captchaModeValue.Store(normalized)
	return normalized
}

func getCaptchaMode() string {
	mode, _ := captchaModeValue.Load().(string)
	if mode == "" {
		return "auto"
	}
	return mode
}

func normalizeVKAuthMode(mode string) string {
	switch strings.ToLower(strings.TrimSpace(mode)) {
	case "legacy":
		return "legacy"
	default:
		return "vkcalls"
	}
}

func setVKAuthMode(mode string) string {
	normalized := normalizeVKAuthMode(mode)
	vkAuthModeValue.Store(normalized)
	return normalized
}

func getVKAuthMode() string {
	mode, _ := vkAuthModeValue.Load().(string)
	if mode == "" {
		return "vkcalls"
	}
	return mode
}

// loadConfigFile применяет файл вида «ключ = значение» (ключи = имена флагов).
// Флаги из командной строки разбираются после и имеют приоритет.
func loadConfigFile(path string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for n := 1; sc.Scan(); n++ {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		key, val, ok := strings.Cut(line, "=")
		if !ok {
			return fmt.Errorf("%s:%d: ожидается «ключ = значение»", path, n)
		}
		key = strings.TrimLeft(strings.TrimSpace(key), "-")
		val = strings.Trim(strings.TrimSpace(val), `"'`)
		if key == "c" || key == "config" {
			continue
		}
		if err := flag.Set(key, val); err != nil {
			return fmt.Errorf("%s:%d: %s: %w", path, n, key, err)
		}
	}
	return sc.Err()
}

func configPathFromArgs(args []string) string {
	for i, a := range args {
		name, val, hasVal := strings.Cut(strings.TrimLeft(a, "-"), "=")
		if !strings.HasPrefix(a, "-") || (name != "c" && name != "config") {
			continue
		}
		if hasVal {
			return val
		}
		if i+1 < len(args) {
			return args[i+1]
		}
	}
	return ""
}

func main() {
	log.SetFlags(log.Ldate | log.Ltime | log.Lmicroseconds)

	if len(os.Args) > 1 && os.Args[1] == "deploy" {
		os.Exit(runDeploy(os.Args[2:]))
	}

	flag.String("c", "", "файл конфига «ключ = значение» (ключи — имена флагов)")
	mode := flag.String("mode", "proxy", "proxy — SOCKS5/HTTP-прокси без root; tun — системный туннель (root, wg-quick); tunfd — VpnService Android; none — только транспорт и вывод WG-конфига")
	proxyListen := flag.String("proxy", "127.0.0.1:1080", "адрес SOCKS5/HTTP-прокси (режим proxy)")
	tunDNS := flag.Bool("tun-dns", true, "режим tun: прописать DNS из конфига сервера через resolvconf")
	blockIPv6 := flag.Bool("block-ipv6", true, "режим tun: завернуть IPv6 в туннель, чтобы он не шёл мимо VPN")
	saveConf := flag.String("save-conf", "", "режим none: сохранить WG-конфиг в файл")
	flag.StringVar(&bypassIface, "bind-iface", "", "привязать сокеты клиента к интерфейсу (auto = интерфейс маршрута по умолчанию), чтобы они шли мимо других VPN")
	flag.IntVar(&bypassMark, "fwmark", 0, "SO_MARK для сокетов клиента (нужен root; в режиме tun по умолчанию 51820)")
	flag.StringVar(&captchaBrowserPath, "browser", "", "путь к Chromium/Chrome для капчи (none — не использовать)")
	flag.BoolVar(&eventOutputEnabled, "events", eventOutputEnabled, "события для GUI в stdout (__WDTT_EVENT__|ТИП|json)")
	stdinControl := flag.Bool("stdin-control", false, "управление через stdin: STOP — остановка; закрытие stdin — тоже остановка")
	flag.BoolVar(&captchaViaApp, "captcha-app", false, "капчу показывает приложение (Android): событие CAPTCHA_REQUEST, ответ CAPTCHA_RESULT в stdin")
	excludeList := flag.String("exclude", "", "режим tun: адреса/подсети/домены мимо туннеля (чёрный список)")
	includeList := flag.String("include", "", "режим tun: через туннель только эти адреса/подсети/домены (белый список)")
	appsList := flag.String("apps", "", "режим tun: приложения (имя программы, путь, flatpak:ID, snap:имя) через запятую")
	appsMode := flag.String("apps-mode", "bypass", "режим tun: bypass — приложения из -apps мимо VPN; only — через VPN только они")
	wgDNS := flag.String("wg-dns", "", "заменить DNS из конфига сервера (IP через запятую)")
	wgMTU := flag.Int("wg-mtu", 0, "заменить MTU туннеля (0 — из конфига сервера; меньше 1280 — авто)")

	host := flag.String("turn", "", "переопределить IP TURN")
	port := flag.String("port", "", "переопределить порт TURN")
	listen := flag.String("listen", "127.0.0.1:9000", "локальный UDP-адрес для WireGuard")
	vkHash := flag.String("vk", "", "хеши или ссылки VK-звонков (через запятую)")
	peerAddr := flag.String("peer", "", "адрес:порт VPS сервера (обычно :56000)")
	numW := flag.Int("n", 24, "количество воркеров (округляется вниз до кратного 9, максимум 108)")

	deviceID := flag.String("device-id", "", "ID устройства (по умолчанию выводится из /etc/machine-id)")
	connPassword := flag.String("password", "", "пароль подключения (или переменная WDTT_PASSWORD)")
	vkAuthMode := flag.String("vk-auth-mode", "vkcalls", "режим получения VK TURN-кредов (vkcalls/legacy)")
	captchaMode := flag.String("captcha-mode", "auto", "режим обхода капчи (auto/wv/rjs)")
	fingerprint := flag.String("fingerprint", "chrome", "браузерный фингерпринт (chrome, safari, ios, android, firefox)")
	clientIdsFlag := flag.String("client-ids", "", "ID клиентов VK через запятую")
	obfsMode := flag.String("obfs", "audio", "режим обфускации (audio/video)")

	if path := configPathFromArgs(os.Args[1:]); path != "" {
		if err := loadConfigFile(path); err != nil {
			log.Fatalf("[КЛИЕНТ] Конфиг: %v", err)
		}
	}
	flag.Parse()

	// Под pkexec (в том числе с правилом polkit «без пароля») root-процессом управляет
	// обычный пользователь: запись произвольных файлов от root ему не даём.
	if os.Geteuid() == 0 && os.Getenv("PKEXEC_UID") != "" && *saveConf != "" {
		log.Fatal("[КЛИЕНТ] -save-conf недоступен при запуске через pkexec")
	}

	switch *mode {
	case "proxy", "none":
		if strings.TrimSpace(*appsList) != "" {
			log.Printf("[КЛИЕНТ] -apps работает только в режиме tun, игнорирую")
			*appsList = ""
		}
	case "tun":
		prepareTunMode()
	case "tunfd":
		// Android: обход VPN делает само приложение (addDisallowedApplication)
	default:
		log.Fatalf("[КЛИЕНТ] Неизвестный -mode %q (proxy/tun/tunfd/none)", *mode)
	}
	if bypassMark != 0 && bypassMark != tunFwMark && *mode == "tun" {
		log.Fatalf("[КЛИЕНТ] В режиме tun -fwmark должен быть %d", tunFwMark)
	}

	setupGlobalResolver()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	var cleanupMu sync.Mutex
	var cleanups []func()
	addCleanup := func(f func()) {
		cleanupMu.Lock()
		cleanups = append(cleanups, f)
		cleanupMu.Unlock()
	}
	runCleanups := func() {
		cleanupMu.Lock()
		defer cleanupMu.Unlock()
		for i := len(cleanups) - 1; i >= 0; i-- {
			cleanups[i]()
		}
		cleanups = nil
	}
	defer runCleanups()

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT, syscall.SIGHUP)
	go func() {
		select {
		case s := <-sig:
			log.Printf("[КЛИЕНТ] Сигнал %v, завершаю...", s)
			cancel()
		case <-ctx.Done():
			return
		}
		s := <-sig
		log.Printf("[КЛИЕНТ] Повторный %v, принудительный выход", s)
		runCleanups()
		os.Exit(1)
	}()

	if *stdinControl {
		go func() {
			sc := bufio.NewScanner(os.Stdin)
			for sc.Scan() {
				if handleCaptchaControl(strings.TrimSpace(sc.Text())) {
					continue
				}
				if strings.TrimSpace(sc.Text()) == "STOP" {
					log.Printf("[КЛИЕНТ] Команда STOP")
					break
				}
			}
			cancel()
		}()
	}

	activeVKAuthMode := setVKAuthMode(*vkAuthMode)
	activeCaptchaMode := setCaptchaMode(*captchaMode)

	if *connPassword == "" {
		*connPassword = os.Getenv("WDTT_PASSWORD")
	}
	if *peerAddr == "" || *vkHash == "" {
		log.Fatal("[КЛИЕНТ] Нужны -peer и -vk")
	}
	if *connPassword == "" {
		log.Fatal("[КЛИЕНТ] Нужен -password (или WDTT_PASSWORD): WRAP ключ выводится из пароля подключения")
	}
	if *deviceID == "" {
		*deviceID = defaultDeviceID()
	}

	cleanPeerAddr := strings.TrimSpace(*peerAddr)
	var err error
	var peer *net.UDPAddr
	for i := 0; i < 15; i++ {
		peer, err = net.ResolveUDPAddr("udp", cleanPeerAddr)
		if err == nil {
			break
		}
		time.Sleep(1 * time.Second)
	}
	if err != nil {
		log.Fatalf("[КЛИЕНТ] Ошибка разбора пира: %v", err)
	}

	if *fingerprint != "" {
		SetActiveFingerprint(*fingerprint)
	}
	if *clientIdsFlag != "" {
		SetActiveClientIds(*clientIdsFlag)
	}

	hashes := ParseHashes(*vkHash)
	if len(hashes) == 0 {
		log.Fatal("[КЛИЕНТ] Нет хешей VK")
	}

	wrapKey, err := deriveWrapKey(*connPassword)
	if err != nil {
		log.Fatalf("[КЛИЕНТ] WRAP key derive: %v", err)
	}

	maxWorkers := 108
	if *numW > maxWorkers {
		*numW = maxWorkers
	}
	if *numW < workersPerGroup {
		*numW = workersPerGroup
	}
	*numW = (*numW / workersPerGroup) * workersPerGroup

	tp := &TurnParams{
		Host:     *host,
		Port:     *port,
		Hashes:   hashes,
		WrapKey:  wrapKey,
		ObfsMode: *obfsMode,
	}

	var localConn net.PacketConn
	actualListenAddr := *listen
	for i := 0; i < 5; i++ {
		localConn, err = net.ListenPacket("udp", actualListenAddr)
		if err == nil {
			break
		}
		log.Printf("[ОЖИДАНИЕ] Порт %s занят. Жду... (%d/5)", actualListenAddr, i+1)
		time.Sleep(1 * time.Second)
	}
	if err != nil {
		log.Printf("[АВТО-ПОРТ] Порт %s всё ещё занят. Пробую случайный динамический порт...", actualListenAddr)
		actualListenAddr = "127.0.0.1:0"
		localConn, err = net.ListenPacket("udp", actualListenAddr)
		if err != nil {
			log.Fatalf("[ФАТАЛ] Ошибка бинда динамического порта: %v", err)
		}
	}
	if uc, ok := localConn.(*net.UDPConn); ok {
		_ = uc.SetReadBuffer(socketBufSize)
		_ = uc.SetWriteBuffer(socketBufSize)
	}
	stopLocalConn := context.AfterFunc(ctx, func() { _ = localConn.Close() })
	defer stopLocalConn()

	_, localPort, _ := net.SplitHostPort(localConn.LocalAddr().String())
	if localPort == "" {
		localPort = "9000"
	}

	numGroups := *numW / workersPerGroup

	captchaStatus := "AUTO: Go v2 x2 -> Browser Auto x2 -> Go v2 x1 -> Manual Browser"
	switch activeCaptchaMode {
	case "wv":
		captchaStatus = "Browser (ручное решение)"
	case "rjs":
		captchaStatus = "RJS Go v2 with Browser Auto fallback"
	}
	browserStatus := findCaptchaBrowser()
	if browserStatus == "" {
		browserStatus = "нет (только Go-решатель)"
	}
	bypassStatus := "выкл"
	if bypassMark != 0 {
		bypassStatus = fmt.Sprintf("fwmark %d", bypassMark)
	}
	if bypassIface != "" {
		bypassStatus = strings.TrimPrefix(bypassStatus+", ", "выкл, ") + "iface " + bypassIface
	}

	log.Println("[КЛИЕНТ] ═══════════════════════════════════════")
	log.Printf("[КЛИЕНТ] Режим: %s", *mode)
	log.Printf("[КЛИЕНТ] VK Creds: Client IDs: %s", GetActiveClientIdsString())
	log.Printf("[КЛИЕНТ] VK Auth: %s", activeVKAuthMode)
	log.Printf("[КЛИЕНТ] TLS: %s fingerprint", GetActiveFingerprint())
	log.Printf("[КЛИЕНТ] Воркеров: %d (групп: %d, по %d)", *numW, numGroups, workersPerGroup)
	log.Printf("[КЛИЕНТ] Хешей: %d", len(hashes))
	log.Printf("[КЛИЕНТ] Слушаю: %s | Пир: %s", localConn.LocalAddr(), cleanPeerAddr)
	log.Printf("[КЛИЕНТ] WRAP: ON (password HKDF + RTP AEAD), obfs=%s", *obfsMode)
	log.Printf("[КЛИЕНТ] Device ID: %s", *deviceID)
	log.Printf("[КЛИЕНТ] Обход туннеля: %s", bypassStatus)
	log.Printf("[КЛИЕНТ] Captcha: %s | браузер: %s", captchaStatus, browserStatus)
	log.Println("[КЛИЕНТ] ═══════════════════════════════════════")

	stats := NewStats()
	stats.TotalWorkers.Store(int32(*numW))
	go stats.RunLoop(ctx.Done())
	emitEvent(eventStarted, map[string]any{"mode": *mode, "workers": *numW, "device_id": *deviceID})
	defer emitEvent(eventStopped, nil)

	applyOverrides := func(cfg *wgConfig) error {
		if *wgMTU >= 1280 && *wgMTU <= 1500 {
			cfg.MTU = *wgMTU
		}
		if strings.TrimSpace(*wgDNS) != "" {
			var dns []netip.Addr
			for _, d := range splitList(*wgDNS) {
				a, err := netip.ParseAddr(d)
				if err != nil {
					return fmt.Errorf("-wg-dns %q: %w", d, err)
				}
				dns = append(dns, a)
			}
			cfg.DNS = dns
		}
		return nil
	}

	disp := NewDispatcher(ctx, localConn, stats)
	defer disp.Shutdown()

	configCh := make(chan string, 1)
	configDone := make(chan struct{})
	go func() {
		defer close(configDone)
		var rawConf string
		select {
		case c, ok := <-configCh:
			if !ok || c == "" {
				return
			}
			rawConf = c
		case <-ctx.Done():
			return
		}
		if !strings.Contains(rawConf, "MTU =") {
			var lines []string
			for _, line := range strings.Split(rawConf, "\n") {
				lines = append(lines, line)
				if strings.TrimSpace(line) == "[Interface]" {
					lines = append(lines, "MTU = 1280")
				}
			}
			rawConf = strings.Join(lines, "\n")
		}

		switch *mode {
		case "none":
			fmt.Println()
			fmt.Println("╔══════════════ WireGuard Конфиг ══════════════╗")
			for _, line := range strings.Split(rawConf, "\n") {
				fmt.Printf("║ %-44s ║\n", line)
			}
			fmt.Println("╚══════════════════════════════════════════════╝")
			if *saveConf != "" {
				if err := os.WriteFile(*saveConf, []byte(rawConf+"\n"), 0600); err != nil {
					log.Printf("[КОНФИГ] Ошибка сохранения: %v", err)
				} else {
					log.Printf("[КОНФИГ] Сохранён в %s", filepath.Clean(*saveConf))
				}
			}
		case "proxy", "tun", "tunfd":
			cfg, err := parseWGConfig(rawConf)
			if err == nil {
				err = applyOverrides(cfg)
			}
			if err != nil {
				log.Printf("[КОНФИГ] %v", err)
				emitError("CONFIG", err.Error(), true)
				cancel()
				return
			}
			var stop func()
			if *mode == "proxy" {
				stop, err = startProxyTunnel(ctx, cfg, *proxyListen)
			} else if *mode == "tunfd" {
				stop, err = startFDTunnel(ctx, cfg)
			} else {
				appKeys := parseAppKeys(*appsList)
				opts := tunOptions{DNS: *tunDNS, BlockIPv6: *blockIPv6, AppsOnly: len(appKeys) > 0 && *appsMode == "only"}
				if opts.Exclude, err = parseAddressList(ctx, *excludeList); err == nil {
					opts.Include, err = parseAddressList(ctx, *includeList)
				}
				if err == nil {
					stop, err = startSystemTunnel(cfg, net.JoinHostPort("127.0.0.1", localPort), opts)
				}
				if err == nil {
					var stopApps func()
					if stopApps, err = startAppRouting(ctx, *appsMode, appKeys); err != nil {
						stop()
					} else {
						tunnelStop := stop
						stop = func() { stopApps(); tunnelStop() }
					}
				}
			}
			if err != nil {
				log.Printf("[ТУННЕЛЬ] %v", err)
				emitError("TUNNEL", err.Error(), true)
				cancel()
				return
			}
			addCleanup(stop)
			emitEvent(eventTunnelUp, map[string]any{"mode": *mode, "proxy": *proxyListen, "address": cfg.Addresses[0].String()})
		}
	}()

	var pauseFlag int32
	var wg sync.WaitGroup
	workerIDCounter := 1

	var prevWaitCreds <-chan struct{}
	var prevWaitSpawn <-chan struct{}

	for g := 0; g < numGroups; g++ {
		isFirst := (g == 0)

		var myWaitCreds <-chan struct{}
		var mySignalCreds chan<- struct{}
		var myWaitSpawn <-chan struct{}
		var mySignalSpawn chan<- struct{}

		if g > 0 {
			myWaitCreds = prevWaitCreds
			myWaitSpawn = prevWaitSpawn
		}
		if g < numGroups-1 {
			chC := make(chan struct{})
			mySignalCreds = chC
			prevWaitCreds = chC

			chS := make(chan struct{})
			mySignalSpawn = chS
			prevWaitSpawn = chS
		}

		ids := make([]int, workersPerGroup)
		for i := range ids {
			ids[i] = workerIDCounter
			workerIDCounter++
		}

		gID := g + 1
		var cc chan<- string
		if isFirst {
			cc = configCh
		}

		wg.Add(1)
		go func(groupID int, isFirstGroup bool, configChan chan<- string, workerIds []int, startHashIndex int, waitC, waitS <-chan struct{}, sigC, sigS chan<- struct{}) {
			defer wg.Done()
			WorkerGroup(ctx, groupID, startHashIndex, tp, peer, disp, localPort,
				isFirstGroup, configChan, workerIds, &pauseFlag, *deviceID, *connPassword, stats, waitC, sigC, waitS, sigS)
		}(gID, isFirst, cc, ids, g, myWaitCreds, myWaitSpawn, mySignalCreds, mySignalSpawn)
	}

	wg.Wait()
	cancel()
	close(configCh)
	<-configDone
	log.Println("[КЛИЕНТ] Все воркеры завершены")
}
