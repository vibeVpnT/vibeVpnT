package main

import (
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// Журнал приложения.
//
// Программа собрана оконной, поэтому вывод в консоль уходит в никуда:
// без файла любая ошибка оставалась бы невидимой, а человеку досталась
// бы подсказка из трёх слов. Пишем рядом с настройками, с обрезкой —
// журнал не должен расти бесконечно.
const logLimit = 512 * 1024

var (
	logMu   sync.Mutex
	logFile *os.File
	// Последние строки держим в памяти: их показывает окно «О программе»,
	// чтобы не отправлять человека искать файл.
	logTail []string
)

func logPath() string { return filepath.Join(dataDir(), "vv.log") }

func openLog() {
	logMu.Lock()
	defer logMu.Unlock()

	if info, err := os.Stat(logPath()); err == nil && info.Size() > logLimit {
		_ = os.Remove(logPath())
	}
	file, err := os.OpenFile(logPath(), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err == nil {
		logFile = file
	}
}

func logf(format string, args ...any) {
	line := time.Now().Format("2006-01-02 15:04:05") + " " + fmt.Sprintf(format, args...)

	logMu.Lock()
	defer logMu.Unlock()

	if logFile != nil {
		_, _ = logFile.WriteString(line + "\n")
	}
	logTail = append(logTail, line)
	if len(logTail) > 40 {
		logTail = logTail[len(logTail)-40:]
	}
}

func recentLog() []string {
	logMu.Lock()
	defer logMu.Unlock()
	out := make([]string, len(logTail))
	copy(out, logTail)
	return out
}
