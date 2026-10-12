package main

import (
	"context"
	"fmt"
	"strings"
)

type appKey struct {
	kind  string
	value string
}

// В Windows раздельная маршрутизация по программам требует драйвера WFP — пока не поддерживается.
func parseAppKeys(raw string) []appKey {
	var keys []appKey
	for _, item := range splitList(raw) {
		keys = append(keys, appKey{"name", strings.TrimSpace(item)})
	}
	return keys
}

func startAppRouting(_ context.Context, _ string, keys []appKey) (func(), error) {
	if len(keys) == 0 {
		return func() {}, nil
	}
	return nil, fmt.Errorf("[ПРИЛОЖЕНИЯ] исключения по программам в Windows пока не поддерживаются")
}
