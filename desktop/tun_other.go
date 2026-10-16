//go:build !linux && !windows

package main

import "errors"

// Режим VPN пока только в линуксе.
//
// В Windows для этого нужен свой драйвер сетевого адаптера (wintun) и
// установка его в систему — это отдельная работа и отдельные права.
// Пока там остаётся системный прокси, который Windows применяет ко
// всему, что ходит через WinINet, то есть к браузерам и большинству
// программ.

func tunSupported() bool { return false }

// outboundMark — метить пакеты ядра незачем: туннеля нет, обходить
// нечего. Ноль означает «без метки».
func outboundMark() int { return 0 }

func tunActive() bool { return false }

func startTun() error { return errors.New("режим VPN пока только в Ubuntu") }

func stopTun() error { return nil }

func runTunHelper() error { return errors.New("режим VPN пока только в Ubuntu") }
