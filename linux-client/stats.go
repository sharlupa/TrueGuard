package main

import (
	"log"
	"sync/atomic"
	"time"
)

type Stats struct {
	TotalBytesUp      atomic.Int64
	TotalBytesDown    atomic.Int64
	ActiveConnections atomic.Int32
	TotalWorkers      atomic.Int32
}

func NewStats() *Stats {
	return &Stats{}
}

// RunLoop: событие STATS для GUI раз в секунду, строка в лог — раз в 30 секунд.
func (s *Stats) RunLoop(shutdown <-chan struct{}) {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	for n := 1; ; n++ {
		select {
		case <-shutdown:
			return
		case <-ticker.C:
			emitStats(s)
			if n%30 != 0 {
				continue
			}
			active := s.ActiveConnections.Load()
			up := s.TotalBytesUp.Load()
			down := s.TotalBytesDown.Load()
			totalMB := float64(up+down) / (1024.0 * 1024.0)
			log.Printf("[СТАТИСТИКА] Активных: %d | Трафик: %.2f МБ", active, totalMB)
		}
	}
}
