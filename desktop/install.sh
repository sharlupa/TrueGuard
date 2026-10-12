#!/usr/bin/env bash
# Сборка и установка TrueGuard (Compose Multiplatform, MD3 Expressive) для Linux.
#
#   ./install.sh                    собрать и установить в /opt/trueguard (спросит sudo)
#   ./install.sh --no-password-tun  + правило polkit: группа wheel подключает TUN без пароля
#   ./install.sh --no-build         установить уже собранное
#   ./install.sh --uninstall        удалить
set -euo pipefail

PREFIX=/opt/trueguard
APP_ID=com.trueguard.TrueGuard
HERE="$(cd "$(dirname "$0")" && pwd)"
CORE_SRC="$HERE/../linux-client"
TRAY_SRC="$HERE/tray"
BUNDLE="$HERE/build/compose/binaries/main-release/app/trueguard"
POLICY=/usr/share/polkit-1/actions/com.trueguard.wdtt-client.policy
RULE=/etc/polkit-1/rules.d/49-trueguard.rules

build=1 nopass=0 uninstall=0
for a in "$@"; do
  case "$a" in
    --no-build) build=0 ;;
    --no-password-tun) nopass=1 ;;
    --uninstall) uninstall=1 ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) echo "неизвестный параметр: $a" >&2; exit 2 ;;
  esac
done

SUDO=""
[ "$(id -u)" -eq 0 ] || SUDO=sudo

if [ "$uninstall" = 1 ]; then
  $SUDO rm -rf "$PREFIX" "/usr/local/bin/trueguard" "/usr/share/applications/$APP_ID.desktop" \
    /usr/share/icons/hicolor/256x256/apps/trueguard.png /usr/share/icons/hicolor/192x192/apps/trueguard.png \
    "$POLICY" "$RULE"
  $SUDO gtk-update-icon-cache -q /usr/share/icons/hicolor 2>/dev/null || true
  echo "TrueGuard удалён. Настройки пользователя остались в ~/.config/trueguard"
  exit 0
fi

if [ "$build" = 1 ]; then
  echo "==> Сборка ядра wdtt-client"
  [ -f "$CORE_SRC/deploy_assets/wdtt-server" ] || "$CORE_SRC/build-server.sh"
  (cd "$CORE_SRC" && go build -trimpath -ldflags="-s -w" -o wdtt-client .)
  echo "==> Сборка помощника трея"
  (cd "$TRAY_SRC" && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o trueguard-tray .)
  echo "==> Сборка интерфейса (Compose Multiplatform, со встроенной Java)"
  (cd "$HERE" && gradle --console=plain -q createReleaseDistributable)
fi
[ -x "$BUNDLE/bin/trueguard" ] || { echo "нет $BUNDLE/bin/trueguard — соберите без --no-build" >&2; exit 1; }
[ -x "$CORE_SRC/wdtt-client" ] || { echo "нет $CORE_SRC/wdtt-client" >&2; exit 1; }
[ -x "$TRAY_SRC/trueguard-tray" ] || { echo "нет $TRAY_SRC/trueguard-tray" >&2; exit 1; }

for tool in wg-quick nft; do
  command -v "$tool" >/dev/null || echo "!  не найден $tool — режим TUN/исключения приложений будут недоступны (пакеты wireguard-tools, nftables)"
done
command -v pkexec >/dev/null || command -v sudo >/dev/null || echo "!  нет ни pkexec, ни sudo — режим TUN будет недоступен"
command -v chromium >/dev/null || command -v google-chrome-stable >/dev/null || \
  echo "!  не найден Chromium/Chrome — ручная капча будет недоступна"

# запущенная версия держит файлы — закрываем её (туннель остановится штатно)
if pgrep -x trueguard >/dev/null 2>&1; then
  echo "==> Закрываю запущенный TrueGuard"
  pkill -TERM -x trueguard || true
  sleep 2
fi

echo "==> Установка в $PREFIX"
$SUDO rm -rf "$PREFIX"
$SUDO install -d -m 755 "$PREFIX"
$SUDO cp -a "$BUNDLE/." "$PREFIX/"
# ядро принадлежит root: его запускает pkexec, и polkit-действие привязано к этому пути
$SUDO install -m 755 -o root -g root "$CORE_SRC/wdtt-client" "$PREFIX/wdtt-client"
$SUDO install -m 755 "$TRAY_SRC/trueguard-tray" "$PREFIX/trueguard-tray"
# обёртка запуска: в тайлинговых композиторах Java-окну нужен режим «без рамки WM»,
# иначе по краям остаются белые полосы и окно не подстраивается под плитку
$SUDO install -Dm755 /dev/stdin "$PREFIX/trueguard" <<'WRAPPER'
#!/bin/sh
case "$(printf '%s:%s:%s' "$XDG_CURRENT_DESKTOP" "$XDG_SESSION_DESKTOP" "$DESKTOP_SESSION" | tr '[:upper:]' '[:lower:]')" in
  *niri*|*sway*|*hyprland*|*river*|*wayfire*|*labwc*|*dwl*|*i3*|*bspwm*|*dwm*|*qtile*|*xmonad*|*awesome*|*herbstluftwm*|*leftwm*|*spectrwm*|*cosmic*)
    export _JAVA_AWT_WM_NONREPARENTING=1 ;;
esac
exec /opt/trueguard/bin/trueguard "$@"
WRAPPER
$SUDO chown -R root:root "$PREFIX"
$SUDO ln -sf "$PREFIX/trueguard" /usr/local/bin/trueguard

$SUDO install -Dm644 "$HERE/src/main/resources/icons/app256.png" /usr/share/icons/hicolor/256x256/apps/trueguard.png
$SUDO install -Dm644 "$HERE/src/main/resources/icons/app.png" /usr/share/icons/hicolor/192x192/apps/trueguard.png
$SUDO gtk-update-icon-cache -q /usr/share/icons/hicolor 2>/dev/null || true

$SUDO install -Dm644 /dev/stdin "/usr/share/applications/$APP_ID.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=TrueGuard
GenericName=VPN
Comment=WDTT VPN: WireGuard через TURN-серверы VK-звонков
Exec=$PREFIX/trueguard %U
Icon=trueguard
Terminal=false
Categories=Network;Security;
Keywords=vpn;wireguard;turn;vk;
StartupWMClass=com-trueguard-MainKt
Actions=connect;

[Desktop Action connect]
Name=Подключить
Exec=$PREFIX/trueguard --connect
EOF

$SUDO install -Dm644 /dev/stdin "$POLICY" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE policyconfig PUBLIC "-//freedesktop//DTD PolicyKit Policy Configuration 1.0//EN"
 "http://www.freedesktop.org/standards/PolicyKit/1/policyconfig.dtd">
<policyconfig>
  <vendor>TrueGuard</vendor>
  <action id="com.trueguard.wdtt-client">
    <description>Run the TrueGuard tunnel</description>
    <description xml:lang="ru">Запуск туннеля TrueGuard</description>
    <message>Authentication is required to bring up the TrueGuard VPN tunnel</message>
    <message xml:lang="ru">Для включения VPN-туннеля TrueGuard нужны права администратора</message>
    <icon_name>trueguard</icon_name>
    <defaults>
      <allow_any>auth_admin</allow_any>
      <allow_inactive>auth_admin</allow_inactive>
      <allow_active>auth_admin_keep</allow_active>
    </defaults>
    <annotate key="org.freedesktop.policykit.exec.path">$PREFIX/wdtt-client</annotate>
  </action>
</policyconfig>
EOF

if [ "$nopass" = 1 ]; then
  $SUDO install -Dm644 /dev/stdin "$RULE" <<'EOF'
// TrueGuard: администраторы (группа wheel) включают туннель без пароля.
polkit.addRule(function(action, subject) {
    if (action.id == "com.trueguard.wdtt-client" && subject.local && subject.active && subject.isInGroup("wheel")) {
        return polkit.Result.YES;
    }
});
EOF
  # метка для GUI: pkexec сработает без агента polkit
  $SUDO touch "$PREFIX/.polkit-nopass"
  echo "==> Правило polkit: TUN без пароля для группы wheel"
else
  $SUDO rm -f "$RULE"
fi

echo "Готово: запустите «TrueGuard» из меню приложений или командой trueguard"
