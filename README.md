# TrueGuard — исходники для Android, Linux и Windows

Восстановленные исходники клиента TrueGuard 1.2.4 (форк WDTT). Интерфейс воссоздан по APK
на Compose Multiplatform с официальной Material 3 (MD3 Expressive): палитры, шрифты, размеры
и анимации перенесены из приложения. Один и тот же код интерфейса и логики работает на трёх платформах.

## Что где

| Папка | Что внутри |
|---|---|
| `desktop/src/shared` | общий код: экраны, тема, настройки, управление ядром, импорт/экспорт |
| `desktop/src/main` | Linux и Windows: окно, трей, права root, автозапуск |
| `desktop/android` | Android: VpnService, WebView для капчи, плитка в шторке, уведомления |
| `desktop/tray`, `desktop/winlauncher` | помощник трея и запускатель для Windows (Go) |
| `linux-client` | ядро `wdtt-client` (Go): транспорт из APK и режимы для каждой платформы |
| `server` | сервер WDTT (`linux-server` из APK); встраивается в ядро для вкладки «Деплой» |

Как ядро работает на каждой платформе:

- **Android:** `-mode tunfd`. Ядро просит VPN-интерфейс событием `TUN_REQUEST`, приложение поднимает VpnService и передаёт дескриптор.
- **Linux:** `-mode tun` через `wg-quick` с правами root или `-mode proxy` (SOCKS5/HTTP) без root.
- **Windows:** туннель через WireGuard для Windows.
- **Капча:**
  - на Android — WebView приложения (`-captcha-app`);
  - на ПК — окно Chromium/Chrome/Edge.

## Сборка

Нужны Go и JDK 21+ с Gradle 9. Для Android дополнительно нужны Android SDK (platform 37) и NDK 28.2.13676358.

```sh
cd desktop
./install.sh                        # Linux: ядро, трей и интерфейс → /opt/trueguard
./package-windows.sh                # Windows: переносной zip (собирается на Linux)
gradle :android:assembleRelease     # Android: APK (модуль подключается, если есть SDK)
gradle test                         # тесты интерфейса и логики; в linux-client — go test ./...
```

Подробности — в [desktop/README.md](desktop/README.md) и [linux-client/README.md](linux-client/README.md).

## Что проверено

**Linux:**
- все экраны;
- тесты с заглушками ядра и sudo;
- установка в /opt/trueguard.

**Windows:** пакет запускается под Wine. На настоящей Windows не проверялся.

**Android (эмулятор Android 16, вместо ядра — тестовая программа):**
- запрос VPN и передача дескриптора TUN;
- капча в WebView;
- уведомление и плитка в шторке.

**Не проверено:** подключение к настоящему серверу через VK, настоящая Windows и настоящий телефон.

Из оригинального Android-клиента пока нет виджета на рабочем столе и автозапуска после перезагрузки.

## Лицензия

Ядро и сервер основаны на [WDTT](https://github.com/amurcanov/proxy-turn-vk-android), который
распространяется под GPL-3.0, поэтому этот код распространяется на тех же условиях — см. [LICENSE](LICENSE).
