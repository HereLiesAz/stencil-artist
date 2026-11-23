#!/usr/bin/env python3
"""Builds models/paints.tsv (and models/paints.NOTICE.txt): the bundled paint catalogue.

Merges the two research files in tools/catalogue/source/ (art/graffiti brands and hardware brands)
into one compact tab-separated text file that `PaintCatalogue.parse` reads. Python 3 standard
library only. Deterministic: the same inputs always give the same bytes. Re-run it from anywhere:

    python3 tools/catalogue/build.py

Format (UTF-8, LF line endings; fields are separated by one TAB, which never occurs inside a field):

    #stencil-artist-paints<TAB>1
    #brand<TAB>id<TAB>name<TAB>source hosts, comma separated
    #line<TAB>id<TAB>brand id<TAB>name
    line id<TAB>name<TAB>finish<TAB>code<TAB>hex<TAB>hex source<TAB>gtin

Brand and line names are written once, in the header, and every row refers to its line by number.
An empty field is a missing value. `hex` is six lowercase digits without `#`; `hex source` is `o`
(the maker's own value) or `s` (sampled by us from the maker's swatch image) and is empty when
there is no hex. Rows are sorted by brand, line, name, finish and code, so the order is stable.

The build fails on a malformed hex, a gtin whose GS1 check digit is wrong, or two rows with the same
catalogue key (brand|line|finish|code-or-name), and prints the row counts per brand.
"""
import argparse
import collections
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlparse

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent

HEX = re.compile(r"[0-9a-f]{6}")
SOURCE_LETTER = {"official": "o", "sampled": "s"}

#: What each brand's data came from, beyond the hosts the rows cite. Written to the notice.
BRAND_NOTES = {
    "Montana Cans": "Maker shop swatches (hex, code, name). Barcodes read from the shop's labels were "
                    "de-duplicated and kept only when consistent, unique within the line and valid; 151 were dropped.",
    "MTN (Montana Colors)": "Names, SKUs and barcodes from a retailer's Shopify product listing; the maker's site "
                            "refused automated access. There is no maker colour code and no colour value.",
    "Loop Colors": "Maker product pages (code, name, hex). No barcodes.",
    "Kobra": "Maker product pages (code, name, hex). No barcodes.",
    "Ironlak": "Maker 400ml page swatches (hex, name). No codes, no barcodes.",
    "Clash": "Maker shop's store API (code, name). No colour value, no barcodes.",
    "Flame": "Names, codes and barcodes from a retailer's Shopify product listing. No colour value.",
    "Molotow": "Names, codes and barcodes from a retailer's Shopify product listing. No colour value.",
    "Rust-Oleum": "Maker catalogue pages (SKU as code, sheen as finish, hex, UPC where listed). "
                  "Only aerosol sizes were kept, by a heuristic that may misclassify a few rows.",
    "Krylon": "Maker colour cards (internal colour id as code, hex). No finish, no barcodes; effect finishes have no hex.",
    "Liquitex": "Maker shop (colour code, barcode). The hex is OUR sampling of the maker's swatch image.",
    "Dupli-Color": "Maker pages (SKU, finish). The hex is OUR sampling of the maker's swatch image. No barcodes.",
}


def gtin_ok(digits):
    """True if `digits` is a GS1 number (8, 12, 13 or 14 digits) with a correct check digit."""
    if not digits.isdigit() or len(digits) not in (8, 12, 13, 14):
        return False
    body, check = digits[:-1], int(digits[-1])
    # Weights 3,1,3,1... from the digit next to the check digit, going left.
    total = sum(int(d) * (3 if i % 2 == 0 else 1) for i, d in enumerate(reversed(body)))
    return (10 - total % 10) % 10 == check


def clean(text):
    """A field value with every run of whitespace (tabs and newlines included) as one space."""
    return " ".join((text or "").split())


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def convert(row, where):
    brand, line = clean(row["brand"]), clean(row["line"])
    code = clean(row.get("code"))
    name = clean(row.get("name")) or code
    if not brand or not line or not name:
        sys.exit(f"{where}: a row has no brand, line, or name and no code to stand for it: {row}")
    hex_value = (row.get("hex") or "").lower().lstrip("#")
    if hex_value and not HEX.fullmatch(hex_value):
        sys.exit(f"{where}: malformed hex {row.get('hex')!r} in {brand} / {line} / {name}")
    if bool(hex_value) != bool(row.get("hex_source")):
        sys.exit(f"{where}: hex and hex_source disagree in {brand} / {line} / {name}")
    source = SOURCE_LETTER[row["hex_source"]] if hex_value else ""
    gtin = row.get("gtin") or ""
    if gtin and not gtin_ok(gtin):
        sys.exit(f"{where}: gtin {gtin!r} fails the GS1 check digit in {brand} / {line} / {name}")
    return {
        "brand": brand, "line": line, "name": name, "finish": clean(row.get("finish")),
        "code": code, "hex": hex_value, "src": source, "gtin": gtin,
        "host": urlparse(row.get("source") or "").hostname or "",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    parser.add_argument("--art", default=HERE / "source" / "art.json")
    parser.add_argument("--hardware", default=HERE / "source" / "hardware.json")
    parser.add_argument("--out", default=ROOT / "models" / "paints.tsv")
    parser.add_argument("--notice", default=ROOT / "models" / "paints.NOTICE.txt")
    args = parser.parse_args()

    rows = [convert(r, f"{p.name}[{i}]")
            for p in (Path(args.art), Path(args.hardware))
            for i, r in enumerate(load(p))]
    rows.sort(key=lambda r: (r["brand"].lower(), r["brand"], r["line"].lower(), r["line"],
                             r["name"].lower(), r["name"], r["finish"], r["code"]))

    seen = {}
    for r in rows:
        key = (r["brand"], r["line"], r["finish"], r["code"] or r["name"])
        if key in seen:
            sys.exit(f"duplicate catalogue key {key}")
        seen[key] = True

    brands, lines = {}, {}
    hosts = collections.defaultdict(set)
    for r in rows:
        brands.setdefault(r["brand"], len(brands))
        lines.setdefault((r["brand"], r["line"]), len(lines))
        if r["host"]:
            hosts[r["brand"]].add(r["host"])

    out = ["#stencil-artist-paints\t1"]
    for name, bid in brands.items():
        out.append(f"#brand\t{bid}\t{name}\t{','.join(sorted(hosts[name]))}")
    for (brand, line), lid in lines.items():
        out.append(f"#line\t{lid}\t{brands[brand]}\t{line}")
    for r in rows:
        out.append("\t".join((str(lines[(r["brand"], r["line"])]), r["name"], r["finish"],
                              r["code"], r["hex"], r["src"], r["gtin"])))
    Path(args.out).write_text("\n".join(out) + "\n", encoding="utf-8", newline="\n")

    counts = collections.OrderedDict()
    for r in rows:
        c = counts.setdefault(r["brand"], collections.Counter())
        c["rows"] += 1
        c["hex"] += bool(r["hex"])
        c["sampled"] += r["src"] == "s"
        c["gtin"] += bool(r["gtin"])
    print(f"{'brand':24} {'rows':>5} {'hex':>5} {'sampled':>7} {'gtin':>5}")
    for name, c in counts.items():
        print(f"{name:24} {c['rows']:5} {c['hex']:5} {c['sampled']:7} {c['gtin']:5}")
    total = sum(counts.values(), collections.Counter())
    print(f"{'total':24} {total['rows']:5} {total['hex']:5} {total['sampled']:7} {total['gtin']:5}")
    distinct = len({r["hex"] for r in rows if r["hex"]})
    print(f"{distinct} distinct colour values; {len(lines)} lines; {Path(args.out).stat().st_size} bytes")

    notice = [
        "Paint catalogue (paints.tsv): provenance and caveats",
        "",
        "Generated by tools/catalogue/build.py from the research files in tools/catalogue/source/.",
        "",
        "DO NOT SHIP THIS DATA PUBLICLY YET. No licence to redistribute any of it was found: the makers'",
        "and retailers' pages carry no stated terms for reusing colour data, their content is presumably",
        "copyrighted, and colour names are trademarks. Check each maker's terms, or ask for permission,",
        "before a public release of the app, the web build or this repository.",
        "",
        "About the values:",
        "- Colour values are approximations as a screen shows them. They are not the colour of the paint",
        "  in the can, and a printed or sprayed colour will differ. The makers say so themselves.",
        "- A row whose hex source is 'o' (official) carries the maker's published value. A row whose hex",
        "  source is 's' (sampled) carries a value WE sampled from the maker's swatch image; it is our",
        "  own measurement, not a published figure.",
        "- A row with no colour value has none because the maker publishes none (effect finishes) or",
        "  its site could not be read. Such a can can still be listed, searched and scanned, but not",
        "  matched to an image colour.",
        "- Barcodes (GTIN) are kept only when digits-only, 8/12/13/14 long, with a valid GS1 check digit,",
        "  and not shared by several different products. Where a source disagreed it was dropped, not guessed.",
        "",
        "Where each brand's data came from (hosts cited by its rows):",
    ]
    for name in brands:
        notice.append(f"- {name}: {', '.join(sorted(hosts[name])) or 'n/a'}")
        notice.append(f"    {BRAND_NOTES.get(name, '')}".rstrip())
    notice += ["", f"Rows: {total['rows']}. With a colour value: {total['hex']} "
                   f"({total['sampled']} sampled). With a barcode: {total['gtin']}.", ""]
    Path(args.notice).write_text("\n".join(notice), encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
