#!/usr/bin/env python3
"""Оставляет в справочниках маршрутизации только то, чем мы пользуемся.

Полные geosite.dat и geoip.dat весят 27 мегабайт, а правил у нас
полтора десятка — всё остальное ядро читает и держит в памяти зря.
После обрезки остаётся меньше половины мегабайта: ради этого настольная
версия и умещается в один файл.

    ./trim_geo.py assets [путь/к/routing/default.json]

Список категорий берётся из правил маршрутизации, если их файл передан:
тогда он не разъедется с тем, что присылает бэкенд. Без него берётся
запасной список ниже — его придётся дополнять руками, и это важно:
ссылка на отсутствующую в справочнике категорию роняет ядро целиком,
то есть у человека просто перестаёт включаться подключение.

Формат справочников — protobuf, но разбирать его целиком незачем. Оба
файла устроены одинаково: подряд идут записи поля 1, внутри каждой
первым лежит поле 1 со строкой — названием категории. Этого хватает,
чтобы решить судьбу записи и переложить её байт в байт.
"""
import re
import sys
from pathlib import Path

# Запасной список — слепок с apps/routing/default.json бэкенда.
FALLBACK = """
geoip:private geoip:ru
geosite:private geosite:tld-ru geosite:category-ru
geosite:category-bank-ru geosite:category-ecommerce-ru
geosite:category-education-ru geosite:category-entertainment-ru
geosite:category-forums-ru geosite:category-gov-ru
geosite:category-media-ru geosite:category-medicine-ru
geosite:category-retail-ru geosite:category-tech-media-ru
geosite:category-travel-ru
""".split()


def wanted(routing: Path | None) -> tuple[set[str], set[str]]:
    """Какие категории оставить: отдельно для сайтов, отдельно для адресов."""
    refs = FALLBACK
    if routing and routing.exists():
        found = re.findall(r'"(geosite:[^"]+|geoip:[^"]+)"', routing.read_text())
        if found:
            refs = found
            print(f"  категории беру из {routing}")

    sites, ips = set(), set()
    for ref in refs:
        kind, _, name = ref.partition(":")
        # Уточнение через @ (geosite:google@ads) относится к домену внутри
        # категории — для отбора самой категории оно ни при чём.
        name = name.split("@")[0].upper()
        (sites if kind == "geosite" else ips).add(name)
    return sites, ips


def read_varint(data: bytes, pos: int) -> tuple[int, int]:
    value = shift = 0
    while True:
        byte = data[pos]
        pos += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, pos
        shift += 7


def trim(src: Path, dst: Path, keep: set[str]) -> None:
    data = src.read_bytes()
    out = bytearray()
    pos = 0
    seen = set()

    while pos < len(data):
        tag, pos = read_varint(data, pos)
        if tag != 0x0A:  # поле 1, длина с префиксом — других здесь не бывает
            raise SystemExit(f"{src}: неожиданное поле {tag:#x} на позиции {pos}")
        size, body = read_varint(data, pos)
        entry = data[body:body + size]
        pos = body + size

        # Имя категории — первое поле внутри записи.
        if not entry or entry[0] != 0x0A:
            continue
        name_len, name_at = read_varint(entry, 1)
        name = entry[name_at:name_at + name_len].decode()
        seen.add(name)

        if name in keep:
            out += encode_entry(entry)

    missing = keep - seen
    if missing:
        raise SystemExit(f"{src}: в справочнике нет категорий: {', '.join(sorted(missing))}")

    dst.write_bytes(bytes(out))
    print(f"  {dst.name}: {len(data) / 1e6:.1f} МБ → {len(out) / 1e6:.3f} МБ")


def encode_entry(entry: bytes) -> bytes:
    """Запись обратно в поток: тег, длина варинтом, тело как было."""
    size = len(entry)
    length = bytearray()
    while True:
        byte = size & 0x7F
        size >>= 7
        length.append(byte | (0x80 if size else 0))
        if not size:
            break
    return b"\x0a" + bytes(length) + entry


def main() -> None:
    assets = Path(sys.argv[1] if len(sys.argv) > 1 else "assets")
    routing = Path(sys.argv[2]) if len(sys.argv) > 2 else None

    sites, ips = wanted(routing)
    print(f"Обрезаю справочники: {len(sites)} категорий сайтов, {len(ips)} — адресов")

    trim(assets / "geosite.dat", assets / "geosite-vv.dat", sites)
    trim(assets / "geoip.dat", assets / "geoip-vv.dat", ips)


if __name__ == "__main__":
    main()
