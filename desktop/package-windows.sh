#!/usr/bin/env bash
# Переносная сборка TrueGuard для Windows x64 (собирается из Linux).
#
#   ./package-windows.sh        → build-windows/TrueGuard-windows-x64.zip
#
# Внутри: TrueGuard.exe (лаунчер), app\*.jar (интерфейс), runtime\ (Java 21),
# wdtt-client.exe (ядро), trueguard-tray.exe (значок в трее).
# Режим TUN требует WireGuard для Windows (https://www.wireguard.com/install/).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CORE_SRC="$HERE/../linux-client"
OUT="$HERE/build-windows"
DIST="$OUT/TrueGuard"
CACHE="$OUT/cache"
JRE_URL='https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse?project=jdk'

echo "==> Ядро wdtt-client.exe"
[ -f "$CORE_SRC/deploy_assets/wdtt-server" ] || "$CORE_SRC/build-server.sh"
(cd "$CORE_SRC" && CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o "$OUT/wdtt-client.exe" .)
echo "==> Трей trueguard-tray.exe"
(cd "$HERE/tray" && CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags="-s -w -H windowsgui" -o "$OUT/trueguard-tray.exe" .)
echo "==> Лаунчер TrueGuard.exe"
(cd "$HERE/winlauncher" && CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags="-s -w -H windowsgui" -o "$OUT/TrueGuard.exe" .)
echo "==> Интерфейс (Compose, ProGuard)"
(cd "$HERE" && gradle --console=plain -q -PtargetOs=windows proguardReleaseJars)

mkdir -p "$CACHE"
if [ ! -f "$CACHE/jre21-win.zip" ]; then
  echo "==> Java 21 для Windows (Temurin JRE)"
  curl -fsSL --retry 3 -o "$CACHE/jre21-win.zip.part" "$JRE_URL"
  mv "$CACHE/jre21-win.zip.part" "$CACHE/jre21-win.zip"
fi

echo "==> Сборка папки"
rm -rf "$DIST"
mkdir -p "$DIST/app"
cp "$OUT"/compose/tmp/main-release/proguard/*.jar "$DIST/app/"
tmp="$(mktemp -d)"
unzip -q "$CACHE/jre21-win.zip" -d "$tmp"
mv "$tmp"/*/ "$DIST/runtime"
rmdir "$tmp"
cp "$OUT/TrueGuard.exe" "$OUT/wdtt-client.exe" "$OUT/trueguard-tray.exe" "$DIST/"
cat > "$DIST/README.txt" <<'EOF'
TrueGuard для Windows (переносная версия)

Запуск: TrueGuard.exe. Настройки хранятся в %APPDATA%\TrueGuard.

Режимы туннеля (Настройки -> Сеть -> Режим туннеля):
  * Прокси - SOCKS5/HTTP на 127.0.0.1:1080, права администратора не нужны.
  * TUN - весь трафик Windows через WDTT. Нужен WireGuard для Windows:
    https://www.wireguard.com/install/
    При подключении и отключении Windows спросит разрешение администратора.
    Чтобы не спрашивала, запускайте TrueGuard.exe от имени администратора.

Капча решается в Chrome или Edge.
Исключения по программам в Windows пока не поддерживаются.
EOF
sed -i 's/$/\r/' "$DIST/README.txt"

rm -f "$OUT/TrueGuard-windows-x64.zip"
(cd "$OUT" && zip -qr9 TrueGuard-windows-x64.zip TrueGuard)
echo "Готово: $OUT/TrueGuard-windows-x64.zip ($(du -h "$OUT/TrueGuard-windows-x64.zip" | cut -f1))"
