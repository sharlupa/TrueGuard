//go:build !linux

package main

// В Windows область уведомлений есть всегда.
func watcherPresent() bool { return true }
