"""테스트용 합성 견적 엑셀 생성기. 실제 업체명·금액·파일 없이 양식 위치만 흉내 낸다.

- xlsx는 openpyxl로 만든다.
- xls(BIFF8)는 xlwt 없이 최소 구조(CFB 컨테이너 + 전역/시트 레코드)를 직접 쓴다.
"""

from __future__ import annotations

import datetime as dt
import io
import struct
from typing import Any

HEADER = {1: "NO.", 2: "품명", 5: "사이즈", 7: "규격", 8: "수량", 9: "총수량", 10: "단가", 12: "금액", 15: "비고"}


def sample_cells(items: list[dict[str, Any]], total: Any, *, client="합성거래처 귀하", site="합성현장A",
                 year="2025년", date: Any = None, header: dict[int, str] | None = None,
                 header_row: int = 11) -> dict[tuple[int, int], Any]:
    """0-based (행, 열) → 값. items: {no,name,size,size2,spec,qty,total_qty,price,amount,note}."""
    cells: dict[tuple[int, int], Any] = {(0, 2): "견 적 서", (3, 2): year, (4, 2): client, (5, 5): site,
                                         (10, 6): total}
    if date is not None:
        cells[(2, 10)] = date
    for col, label in (header or HEADER).items():
        cells[(header_row, col)] = label
    columns = {"no": 1, "name": 2, "size": 5, "size2": 6, "spec": 7, "qty": 8, "total_qty": 9,
               "price": 10, "amount": 12, "note": 15}
    for offset, item in enumerate(items):
        for key, col in columns.items():
            if item.get(key) is not None:
                cells[(header_row + 1 + offset, col)] = item[key]
    return cells


def xlsx_bytes(cells: dict[tuple[int, int], Any], sheet_title: str = "견적서") -> bytes:
    import openpyxl

    workbook = openpyxl.Workbook()
    sheet = workbook.active
    sheet.title = sheet_title
    for (r, c), value in cells.items():
        sheet.cell(row=r + 1, column=c + 1, value=value)
    buffer = io.BytesIO()
    workbook.save(buffer)
    return buffer.getvalue()


# ─────────────────────────────────────────
# 최소 BIFF8 .xls
# ─────────────────────────────────────────

def _record(rtype: int, payload: bytes) -> bytes:
    return struct.pack("<HH", rtype, len(payload)) + payload


def _unicode(text: str, lenlen: int) -> bytes:
    raw = text.encode("utf-16-le")
    prefix = struct.pack("<H" if lenlen == 2 else "<B", len(text))
    return prefix + b"\x01" + raw


def _xf(format_index: int) -> bytes:
    return _record(0x00E0, struct.pack("<HHHBBBBIiH", 0, format_index, 0x0001, 0x20, 0, 0, 0, 0, 0, 0x20C0))


def _workbook_stream(cells: dict[tuple[int, int], Any], sheet_name: str) -> bytes:
    bof_globals = _record(0x0809, struct.pack("<HHHHII", 0x0600, 0x0005, 0x0DBB, 0x07CC, 0, 0x06))
    font = _record(0x0031, struct.pack("<HHHHHBBBB", 200, 0, 0x7FFF, 400, 0, 0, 0, 0, 0) + _unicode("Arial", 1))
    head = bof_globals + _record(0x0042, struct.pack("<H", 1200)) + _record(0x0022, struct.pack("<H", 0))
    head += font * 5 + _xf(0) + _xf(14)

    def boundsheet(offset: int) -> bytes:
        return _record(0x0085, struct.pack("<IBB", offset, 0, 0) + _unicode(sheet_name, 1))

    eof = _record(0x000A, b"")
    globals_len = len(head) + len(boundsheet(0)) + len(eof)
    globals_part = head + boundsheet(globals_len) + eof

    rows = [r for r, _ in cells] or [0]
    cols = [c for _, c in cells] or [0]
    sheet = _record(0x0809, struct.pack("<HHHHII", 0x0600, 0x0010, 0x0DBB, 0x07CC, 0, 0x06))
    sheet += _record(0x0200, struct.pack("<IIHHH", 0, max(rows) + 1, 0, max(cols) + 1, 0))
    epoch = dt.datetime(1899, 12, 30)
    for (r, c), value in sorted(cells.items()):
        if isinstance(value, (dt.datetime, dt.date)):
            moment = value if isinstance(value, dt.datetime) else dt.datetime(value.year, value.month, value.day)
            serial = (moment - epoch).total_seconds() / 86400
            sheet += _record(0x0203, struct.pack("<HHHd", r, c, 1, serial))
        elif isinstance(value, (int, float)) and not isinstance(value, bool):
            sheet += _record(0x0203, struct.pack("<HHHd", r, c, 0, float(value)))
        else:
            sheet += _record(0x0204, struct.pack("<HHH", r, c, 0) + _unicode(str(value), 2))
    sheet += eof
    return globals_part + sheet


def xls_bytes(cells: dict[tuple[int, int], Any], sheet_name: str = "견적서") -> bytes:
    stream = _workbook_stream(cells, sheet_name)
    # 4096바이트 미만 스트림은 미니 스트림에 들어가야 하므로, 0으로 채워 일반 섹터 스트림으로 둔다.
    stream = stream.ljust(max(4096, (len(stream) + 511) // 512 * 512), b"\x00")
    size = len(stream)
    sectors = len(stream) // 512
    if sectors > 126:
        raise ValueError("합성 xls가 너무 큽니다")
    end, free, fat_sect, nostream = 0xFFFFFFFE, 0xFFFFFFFF, 0xFFFFFFFD, 0xFFFFFFFF

    fat = [fat_sect, end] + [3 + i for i in range(sectors - 1)] + [end]
    fat += [free] * (128 - len(fat))
    fat_bytes = struct.pack("<128I", *fat)

    def entry(name: str, kind: int, child: int, start: int, length: int) -> bytes:
        raw = (name.encode("utf-16-le") + b"\x00\x00") if name else b""
        return (raw.ljust(64, b"\x00") + struct.pack("<HBBIII", len(raw), kind, 1, nostream, nostream, child)
                + b"\x00" * 16 + struct.pack("<I", 0) + b"\x00" * 16 + struct.pack("<III", start, length, 0))

    directory = (entry("Root Entry", 5, 1, end, 0) + entry("Workbook", 2, nostream, 2, size)
                 + entry("", 0, nostream, end, 0) * 2)

    header = bytes.fromhex("D0CF11E0A1B11AE1") + b"\x00" * 16
    header += struct.pack("<HHHHH", 0x003E, 0x0003, 0xFFFE, 9, 6) + b"\x00" * 6
    header += struct.pack("<IIIIIIIII", 0, 1, 1, 0, 4096, end, 0, end, 0)
    header += struct.pack("<109I", 0, *([free] * 108))
    return header + fat_bytes + directory + stream
