"""Read source XLSX files without executing formulas or modifying originals."""
import json
import sys
import zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}


def read_book(path):
    with zipfile.ZipFile(path) as z:
        strings = []
        if "xl/sharedStrings.xml" in z.namelist():
            strings = ["".join(n.itertext()) for n in ET.fromstring(z.read("xl/sharedStrings.xml"))]
        rels = {n.attrib["Id"]: n.attrib["Target"] for n in ET.fromstring(z.read("xl/_rels/workbook.xml.rels"))}
        result = {"file": Path(path).name, "sheets": []}
        for sheet in ET.fromstring(z.read("xl/workbook.xml")).find("m:sheets", NS):
            target = rels[sheet.attrib["{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id"]]
            target = target.lstrip("/") if target.startswith("/") else "xl/" + target
            root = ET.fromstring(z.read(target))
            rows = []
            for row in root.findall("m:sheetData/m:row", NS):
                cells = {}
                for c in row:
                    value = c.find("m:v", NS)
                    text = value.text if value is not None else ""
                    if c.attrib.get("t") == "s":
                        text = strings[int(text)] if text else ""
                    elif c.attrib.get("t") == "inlineStr":
                        text = "".join(c.find("m:is", NS).itertext())
                    if text:
                        formula = c.find("m:f", NS)
                        cells[c.attrib["r"]] = {"value": text, **({"formula": formula.text} if formula is not None else {})}
                if cells:
                    rows.append(cells)
            result["sheets"].append({"name": sheet.attrib["name"], "rows": rows})
        return result


if __name__ == "__main__":
    books = [read_book(p) for p in sys.argv[1:]]
    Path("docs/source-workbooks.json").write_text(json.dumps(books, ensure_ascii=False, indent=2), encoding="utf-8")
    for book in books:
        print(book["file"])
        for s in book["sheets"]:
            print(s["name"], "rows:", len(s["rows"]))
            for row in s["rows"][:12]:
                print(json.dumps(row, ensure_ascii=False))
