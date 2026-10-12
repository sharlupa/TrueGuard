//go:build !linux

package main

import (
	"context"
	"fmt"
)

func startFDTunnel(_ context.Context, _ *wgConfig) (func(), error) {
	return nil, fmt.Errorf("режим tunfd есть только в Linux/Android")
}
