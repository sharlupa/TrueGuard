# wdtt-client для Linux

Порт клиента TrueGuard (Android, протокол WDTT) на Linux. Это WireGuard внутри DTLS,
пакеты замаскированы под RTP и идут через TURN-серверы VK-звонков на ваш VPS.
Транспорт (получение TURN-кредов VK, TURN, RTP-обёртка, DTLS, воркеры) — тот же
Go-код, что в APK. Заменено то, что на Android делало приложение:

| Android | Linux |
|---|---|
| VpnService + wireguard-go | `-mode proxy`: userspace WireGuard + SOCKS5/HTTP-прокси, root не нужен; `-mode tun`: системный туннель через `wg-quick` |
| исключение приложения из VPN | `SO_MARK 51820` (tun) или `SO_BINDTODEVICE` (`-bind-iface`) на всех сокетах клиента |
| WebView-капча (авто и ручная) | Chromium через DevTools Protocol: headless сам ставит галочку, при слайдере открывается окно |
| `android_id` | стабильный ID из `/etc/machine-id` |

## Сборка

```sh
go build -trimpath -ldflags="-s -w" -o wdtt-client .
go test ./...        # тесты прокси/WireGuard и капчи (нужен chromium)
```

## Запуск

Нужны три вещи из приложения или от владельца сервера: адрес VPS (`IP:56000`),
пароль подключения и ссылка на VK-звонок (`https://vk.com/call/join/...`).

```sh
# Прокси без root: SOCKS5 и HTTP на 127.0.0.1:1080
./wdtt-client -peer 203.0.113.10:56000 -vk 'https://vk.com/call/join/...' -password '...'
curl -x socks5h://127.0.0.1:1080 https://ifconfig.me
curl -x http://127.0.0.1:1080 https://ifconfig.me

# Весь IPv4 системы через туннель (как VPN на телефоне)
sudo ./wdtt-client -mode tun -c wdtt.conf
```

Параметры удобно держать в файле (`wdtt.conf.example`), пароль можно передать через `WDTT_PASSWORD`.

### Режим tun

- Интерфейс `wdtt0` поднимается после того, как сервер отдал WG-конфиг, и опускается при выходе (Ctrl+C, SIGTERM).
- `wg-quick` заворачивает в туннель всё, что не помечено fwmark 51820; сокеты клиента помечены, поэтому не зацикливаются.
- IPv6 по умолчанию тоже направляется в туннель (`-block-ipv6=false`, чтобы не трогать), сервер IPv6 не раздаёт, так что он просто не работает вместо утечки.
- DNS из конфига сервера ставится через `resolvconf` (`-tun-dns=false`, чтобы не трогать).
- Не запускайте одновременно с другим VPN, который забирает весь трафик (например, hysteria в режиме TUN): их правила маршрутизации стоят раньше.

### Исключения приложений (режим tun)

Аналог «Исключений» Android: `-apps` — список программ (имя исполняемого файла, путь,
`flatpak:ID`, `snap:имя`), `-apps-mode bypass` — они идут мимо VPN, `only` — через VPN идут только они.

```sh
sudo ./wdtt-client -mode tun -c wdtt.conf -apps 'firefox,flatpak:org.telegram.desktop' -apps-mode bypass
```

Процессы этих приложений переносятся в cgroup `/sys/fs/cgroup/trueguard-<режим>`, nftables
(таблица `inet trueguard`) помечает их трафик fwmark 51820. Уже открытые соединения сохраняют
прежний маршрут — для полного эффекта приложение лучше перезапустить. При выходе правила
снимаются, процессы возвращаются в исходные cgroup, если те ещё существуют.

Графический клиент — в `../gui` (вкладки Главная, Деплой, Логи, Настройки, как в Android).

### Рядом с другим VPN

В режиме proxy клиент ходит в сеть как обычная программа. Если на машине уже поднят
полный туннель (hysteria и т.п.) и запросы к VK должны идти напрямую, добавьте
`-bind-iface auto`: сокеты привяжутся к интерфейсу маршрута по умолчанию из таблицы main.

## Пароль и device-id

Сервер привязывает сгенерированный пароль к первому устройству. Если этот пароль уже
используется на телефоне, Linux получит `DENIED:device_mismatch`: возьмите для
компьютера отдельный пароль (бот сервера) или основной пароль владельца.

## Капча

`-captcha-mode auto` (по умолчанию): Go-решатель ×2 → headless Chromium ×2 → Go ×1 → окно Chromium для ручного решения.
Браузер ищется автоматически (`chromium`, `google-chrome`, …) или задаётся `-browser`.
Под `sudo` окно открывается от имени исходного пользователя.

## Сервер

Исходники и `deploy.sh` лежат в `../server` (из того же APK). Установка без приложения:

```sh
scp ../server/wdtt-server root@VPS:/tmp/wdtt-server
scp ../server/deploy.sh root@VPS:/root/
ssh root@VPS "WDTT_ARGS='-password ПАРОЛЬ' bash /root/deploy.sh"
```

`deploy.sh` переключает firewall на iptables и открывает 56000/udp и 56001/udp — на
сервере с другими сервисами лучше поставить бинарник и юнит вручную.
