//go:build windows

package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Ядро, справочники и туннель лежат рядом с программой, в той же папке,
// куда распакован архив. Внутрь exe они больше не прячутся.
//
// Раньше прятались: раздавали один файл, человек скачивал и запускал.
// Но «достать из себя второй исполняемый файл, положить на диск и
// запустить» — это ровно повадка дроппера, по которой браузер удаляет
// скачанное, а антивирус уносит ядро в карантин. Архив с файлами рядом
// этого не вызывает, да и сам exe худеет с двадцати семи мегабайт до
// восьми.
//
// Ищем по порядку: рядом с программой (обычная распаковка) и в папке
// пользователя — туда их может положить тот, кто собирал сам.
func ensureAssets() error {
	need := []string{coreBinaryName, "geosite.dat", "geoip.dat"}

	tried := []string{}
	for _, dir := range []string{exeDir(), filepath.Join(dataDir(), "core")} {
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
		"Похоже, запущен один %s.exe без остальных файлов — "+
		"распакуйте архив целиком и запустите из распакованной папки.",
		strings.Join(tried, "\n  "), brandName)
}
