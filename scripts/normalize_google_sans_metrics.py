"""Keep bundled Google Sans line boxes at their declared typographic height."""

from pathlib import Path
from struct import pack_into, unpack_from


FONT_DIR = Path(__file__).resolve().parents[1] / "TMessagesProj/src/main/assets/fonts"


def checksum(data):
    data += bytes(-len(data) % 4)
    return sum(unpack_from(">I", data, offset)[0] for offset in range(0, len(data), 4)) & 0xFFFFFFFF


for filename in ("GoogleSans.ttf", "GoogleSansFlex.ttf"):
    path = FONT_DIR / filename
    font = bytearray(path.read_bytes())
    count = unpack_from(">H", font, 4)[0]
    records = {}
    for index in range(count):
        record = 12 + index * 16
        tag = bytes(font[record:record + 4])
        offset, length = unpack_from(">II", font, record + 8)
        records[tag] = (record, offset, length)

    _, os2_offset, os2_length = records[b"OS/2"]
    assert os2_length >= 78
    ascent, descent, _ = unpack_from(">hhh", font, os2_offset + 68)
    assert ascent > 0 and descent < 0
    pack_into(">HH", font, os2_offset + 74, ascent, -descent)

    record, offset, length = records[b"OS/2"]
    pack_into(">I", font, record + 4, checksum(font[offset:offset + length]))
    _, head_offset, _ = records[b"head"]
    pack_into(">I", font, head_offset + 8, 0)
    pack_into(">I", font, head_offset + 8, (0xB1B0AFBA - checksum(font)) & 0xFFFFFFFF)
    path.write_bytes(font)
    print(f"{filename}: line box {ascent} / {-descent}")
