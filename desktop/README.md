# TrueGuard для Linux — Compose Multiplatform, MD3 Expressive

Интерфейс воссоздан по декомпилированному APK TrueGuard 1.2.4: те же экраны, тексты, палитры,
размеры, скругления и анимации. Используются только официальные библиотеки:
Compose Multiplatform 1.12 от JetBrains и `material3 1.12.0-alpha03`
(внутри androidx material3 1.5.0-alpha22 — Android-версия собрана на 1.5.0-alpha04).

Ядро не изменилось: это Go-клиент `../linux-client/wdtt-client`. Интерфейс запускает его
так же, как раньше Flutter-версия, и понимает её `~/.config/trueguard/settings.json`.

## Что взято из APK

| Android (b5/c.java и др.) | Здесь |
|---|---|
| нижняя панель с «червяком»-индикатором (a5/c1, a5/l0) | `ui/Shell.kt` — `BottomBar` |
| 5 вкладок в HorizontalPager с затуханием соседних | `AppShell` |
| дашборд: карточка сервера, UDP/TCP, кнопка 150dp с волнами и «живой каплей», виджеты с перетаскиванием (c.H) | `ui/dashboard/*` |
| палитры Tyrn/Лес/Фиолет/Закат/Океан/Роза, «Свой» цвет по HSL, Amoled (a5/s3) | `ui/theme/Theme.kt` — цвета один в один |
| типографика (жирнее стандартной M3) | `AppTypography` + Roboto (OFL) |
| настройки: Сеть, Производительность, Интерфейс, Синхронизация, О приложении | `ui/settings/*` |
| Деплой, Исключения, Логи | `ui/deploy`, `ui/exceptions`, `ui/logs` |

## Что сделано в духе MD3 Expressive

- `MaterialExpressiveTheme` + `MotionScheme.expressive()`: все пружины из официальной схемы.
- Морфы при нажатии: все кнопки через `ButtonDefaults.shapes()` / `IconButtonDefaults.shapes()`,
  карточки настроек и образцы палитр меняют скругление.
- Главная кнопка — морфинг `MaterialShapes`. В покое круг, при нажатии сминается в Cookie9.
  При подключении — вращающееся Cookie9 с `LoadingIndicator`. Подключено — 12-лепестковое
  «печенье» (Cookie12) с каплей из APK.
- Сегментированные кнопки заменены связанными группами `ToggleButton` (connected button group).
- Деплой показывает прогресс через `LinearWavyProgressIndicator`, диагностика — через `LoadingIndicator`.

## Окно на компьютере

- Раскладка подстраивается под ширину окна. Уже 600dp — как на телефоне, с нижней панелью.
  Шире — боковая панель `WideNavigationRail` (сворачивается кнопкой-меню). Главная, Деплой,
  Исключения и Настройки раскладываются в две колонки (настройки: список слева, раздел справа).
- Масштаб интерфейса: Настройки → Интерфейс → «Масштаб окна».
- Тайлинговые композиторы (niri, sway, Hyprland, i3…): Java-окну нужна `_JAVA_AWT_WM_NONREPARENTING=1`,
  иначе окно не подстраивается под плитку и по краям видны белые полосы. Её ставит обёртка
  `/opt/trueguard/trueguard`, а при прямом запуске лаунчера приложение перезапускает себя с ней само.

## Права root для режима TUN

«Авто» выбирает так:
1. polkit уже разрешает запуск ядра (правило `--no-password-tun` или недавно введённый пароль) — pkexec, пароль не нужен;
2. в сессии работает агент polkit (процесс или имя на сессионной шине D-Bus) — pkexec, пароль спросит агент;
3. иначе — sudo, пароль спросит окно TrueGuard (хранится только в памяти).

Если агент установлен, но не запущен (например, `hyprpolkitagent`), в Настройки → Система есть кнопка «Запустить агент polkit».

## Отличия от Android, нужные Linux

- «Система» в настройках: права root (pkexec или sudo, пароль sudo только в памяти),
  автозапуск, трей, уведомления, путь к Chromium для капчи.
- «Режим туннеля» в «Сети»: TUN на всю систему или локальный SOCKS5/HTTP-прокси без root.
- «Исключения» работают по программам из `.desktop` (cgroup + nftables в ядре). Можно добавить
  программу вручную.
- Трей — помощник `tray/trueguard-tray` на Go (StatusNotifierItem): AWT-трей не работает в
  Wayland-панелях.
- «Динамические цвета» берутся из акцентного цвета рабочего стола (xdg-desktop-portal, KDE).

## Сборка и установка

Нужны JDK 21+, Gradle 9 и Go. Готовая программа несёт свою Java: в системе её ставить не нужно.

```sh
./install.sh                    # ядро + трей + интерфейс → /opt/trueguard
./install.sh --no-password-tun  # то же + polkit-правило: TUN без пароля для wheel
./install.sh --uninstall
```

Запуск без установки: `gradle run` (ядро ищется в `../linux-client/wdtt-client`).

Windows (переносная папка с Java и WireGuard для Windows в роли TUN): `./package-windows.sh`
→ `build-windows/TrueGuard-windows-x64.zip`.

## Android

Модуль `android/` собирает приложение `com.trueguard.client` из того же общего кода
(`src/shared/kotlin`); своё у Android — VpnService, WebView для капчи, плитка в шторке, уведомления.

```sh
gradle :android:assembleRelease   # → android/build/outputs/apk/release/android-release.apk
```

Нужны Android SDK (platform 37, путь в `local.properties`), NDK 28.2.13676358 и Go. Ядро собирается
под `GOOS=android` через NDK (как в оригинальном APK): статический Linux-бинарник на телефоне не видит
корневые сертификаты Android и не может перечислить сетевые интерфейсы (netlink закрыт с Android 11).

- Ядро запускается с `-mode tunfd`: событие `TUN_REQUEST` → приложение поднимает VpnService и передаёт
  дескриптор TUN через абстрактный unix-сокет; сокеты ядра идут мимо VPN (приложение исключает себя).
- Капча: `-captcha-app` → событие `CAPTCHA_REQUEST`, страница открывается в WebView, ответ
  `CAPTCHA_RESULT <id> <токен>` уходит в stdin ядра.
- ID устройства для сервера — `android_id`.
- APK подписан отладочным ключом: поверх оригинала не встанет, сначала удалите его
  (настройки можно перенести экспортом).

## Тесты и скриншоты

```sh
gradle test        # логика (ядро-заглушка, sudo-заглушка, импорт/экспорт) + рендер экранов
ls build/screens   # PNG всех экранов для сверки с телефоном
```
