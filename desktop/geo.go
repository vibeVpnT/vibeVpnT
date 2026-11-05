package main

import (
	"bufio"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Какие категории есть в справочниках рядом с ядром.
//
// Ссылка в правиле на категорию, которой нет в geosite.dat или geoip.dat,
// роняет ядро целиком: подключение просто не включается. А справочники у
// настольной версии обрезаны при сборке (trim_geo.py) — в них только то,
// что было в правилах на тот момент. Поэтому всё, что приходит с бэкенда,
// сверяем с самими файлами: незнакомая категория отбрасывается, а не
// роняет ядро, и новые правила можно добавлять без новой сборки.
//
// Формат — protobuf, но разбирать его целиком незачем: подряд идут записи
// поля 1, внутри каждой первым лежит поле 1 со строкой — названием
// категории. Его и читаем, остальное пропускаем.

type geoNames struct {
	modified time.Time
	size     int64
	names    map[string]bool
}

var (
	geoMu    sync.Mutex
	geoCache = map[string]geoNames{}
)

// geoKnown — есть ли категория вида geosite:xxx или geoip:xxx. Справочник,
// который не прочитался, не решает ничего: ссылку оставляем как есть.
func geoKnown(ref string) bool {
	kind, name, _ := strings.Cut(ref, ":")
	var file string
	switch kind {
	case "geosite":
		file = "geosite.dat"
	case "geoip":
		file = "geoip.dat"
	default:
		return true
	}
	// Уточнение через @ (geosite:google@ads) относится к доменам внутри
	// категории. В файле имена записаны заглавными.
	name, _, _ = strings.Cut(name, "@")
	if name == "" {
		return false
	}
	names, err := geoCategories(filepath.Join(assetDir(), file))
	if err != nil {
		return true
	}
	return names[strings.ToUpper(name)]
}

func geoCategories(path string) (map[string]bool, error) {
	info, err := os.Stat(path)
	if err != nil {
		return nil, err
	}

	geoMu.Lock()
	defer geoMu.Unlock()
	if c, ok := geoCache[path]; ok && c.modified.Equal(info.ModTime()) && c.size == info.Size() {
		return c.names, nil
	}

	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	names, err := readGeoNames(bufio.NewReaderSize(f, 64*1024))
	if err != nil {
		return nil, err
	}
	geoCache[path] = geoNames{modified: info.ModTime(), size: info.Size(), names: names}
	return names, nil
}

func readGeoNames(r *bufio.Reader) (map[string]bool, error) {
	names := map[string]bool{}
	for {
		tag, err := readUvarint(r)
		if err == io.EOF {
			return names, nil
		}
		if err != nil {
			return nil, err
		}
		if tag != 0x0A {
			return nil, errors.New("неожиданное поле в справочнике")
		}
		size, err := readUvarint(r)
		if err != nil {
			return nil, noEOF(err)
		}
		left := int64(size)

		inner, n, err := readUvarintN(r)
		if err != nil {
			return nil, noEOF(err)
		}
		left -= int64(n)
		if inner == 0x0A {
			length, n, err := readUvarintN(r)
			if err != nil {
				return nil, noEOF(err)
			}
			left -= int64(n)
			buf := make([]byte, length)
			if _, err := io.ReadFull(r, buf); err != nil {
				return nil, noEOF(err)
			}
			left -= int64(length)
			names[strings.ToUpper(string(buf))] = true
		}
		if left < 0 {
			return nil, errors.New("битая запись в справочнике")
		}
		if _, err := r.Discard(int(left)); err != nil {
			return nil, noEOF(err)
		}
	}
}

func readUvarint(r *bufio.Reader) (uint64, error) {
	v, _, err := readUvarintN(r)
	return v, err
}

// readUvarintN — число и сколько байт оно заняло. EOF до первого байта —
// честный конец файла, после — обрыв.
func readUvarintN(r *bufio.Reader) (uint64, int, error) {
	var value uint64
	for i := 0; i < 10; i++ {
		b, err := r.ReadByte()
		if err != nil {
			if i > 0 && err == io.EOF {
				err = io.ErrUnexpectedEOF
			}
			return 0, i, err
		}
		value |= uint64(b&0x7F) << (7 * i)
		if b&0x80 == 0 {
			return value, i + 1, nil
		}
	}
	return 0, 10, errors.New("слишком длинное число в справочнике")
}

func noEOF(err error) error {
	if err == io.EOF {
		return io.ErrUnexpectedEOF
	}
	return err
}
