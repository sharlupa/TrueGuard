package main

import "github.com/godbus/dbus/v5"

// watcherPresent — есть ли в сессии панель, принимающая StatusNotifierItem.
func watcherPresent() bool {
	conn, err := dbus.SessionBus()
	if err != nil {
		return false
	}
	var owner string
	err = conn.BusObject().Call("org.freedesktop.DBus.GetNameOwner", 0, "org.kde.StatusNotifierWatcher").Store(&owner)
	return err == nil && owner != ""
}
