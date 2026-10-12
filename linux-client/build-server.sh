#!/bin/sh
# Сервер WDTT для вкладки «Деплой»: ядро встраивает его в себя (go:embed deploy_assets/wdtt-server),
# поэтому собрать его нужно до ядра. Исходники — ../server (linux-server из APK).
set -e
cd "$(dirname "$0")/../server"
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o ../linux-client/deploy_assets/wdtt-server .
