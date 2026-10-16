//go:build !windows

package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Ядро и справочники ставятся пакетом и лежат рядом с программой — в
// /opt/vv. Внутрь исполняемого файла их не прячем: в deb для них есть
// законное место, а «достать из себя и запустить» — ровно та повадка,
// за которую в Windows приходится объясняться с антивирусом.
//
// Ищем по порядку: рядом с программой (обычная установка), в /opt/vv
// (если программу запустили не из своей папки — скажем, по ссылке из
// /usr/bin, хотя её мы и разыменовываем), и наконец в папке
// пользователя — туда их может положить тот, кто ставил без пакета.
func ensureAssets() error {
	need := []string{coreBinaryName, "geosite.dat", "geoip.dat"}

	tried := []string{}
	for _, dir := range []string{exeDir(), "/opt/" + brandName, filepath.Join(dataDir(), "core")} {
		missing := ""
		for _, name := range need {
			if _, err := os.Stat(filepath.Join(dir, name)); err != nil {
				missing = name
				break
			}
		}
		if missing == "" {
			assetsAt = dir
			return nil
		}
		tried = append(tried, fmt.Sprintf("%s — нет %s", dir, missing))
	}

	return fmt.Errorf("Рядом с программой нет ядра и справочников.\n\nИскал тут:\n  %s\n\n"+
		"Похоже, пакет установлен не целиком — переустановите %s.",
		strings.Join(tried, "\n  "), brandTitle)
}
