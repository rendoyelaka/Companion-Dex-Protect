"""
patch_apk_manifest.py  —  Fixed version

Android binary XML string pool uses UTF-8 encoding with a 2-byte length prefix:
  byte 0: character count  (if < 0x80, stored as-is; else high-byte + low-byte)
  byte 1: byte count       (same encoding)
  bytes:  UTF-8 string data
  null:   \\x00 terminator

This version also patches android.intent.category.INFO -> LAUNCHER so the
APK shows a proper install prompt and launcher icon.
"""

import sys
import struct
import zipfile
import shutil
import os
import io

# ── Config ───────────────────────────────────────────────────────────────────
OLD_CLASS = "com.android.pictach.App"
NEW_CLASS = "com.android.pictach.CompanionApp"

# Extra string pool replacements applied after the main class rename
EXTRA_REPLACEMENTS = {
    "android.intent.category.INFO": "android.intent.category.LAUNCHER",
}

def encode_utf8_len(n):
    """Encode a string-pool length value the Android way."""
    if n < 0x80:
        return bytes([n])
    else:
        return bytes([0x80 | (n >> 8), n & 0xFF])

def encode_string_entry(s):
    """Encode a string as Android binary XML UTF-8 string pool entry."""
    encoded = s.encode('utf-8')
    char_len  = len(s)        # character count
    byte_len  = len(encoded)  # byte count
    return encode_utf8_len(char_len) + encode_utf8_len(byte_len) + encoded + b'\x00'

def patch_manifest(manifest_data):
    """
    Parse the binary AndroidManifest.xml string pool, find OLD_CLASS,
    replace with NEW_CLASS, rebuild pool with correct offsets.
    Also applies EXTRA_REPLACEMENTS.
    Returns patched manifest bytes.
    """
    # ── 1. Parse file header ──────────────────────────────────────────────────
    file_type    = struct.unpack_from('<H', manifest_data, 0)[0]
    file_hdr_sz  = struct.unpack_from('<H', manifest_data, 2)[0]
    assert file_type == 0x0003, f"Not a binary XML file: {hex(file_type)}"

    # ── 2. Parse string pool chunk ────────────────────────────────────────────
    sp_start      = file_hdr_sz
    sp_type       = struct.unpack_from('<H', manifest_data, sp_start)[0]
    sp_hdr_sz     = struct.unpack_from('<H', manifest_data, sp_start + 2)[0]
    sp_chunk_sz   = struct.unpack_from('<I', manifest_data, sp_start + 4)[0]
    string_count  = struct.unpack_from('<I', manifest_data, sp_start + 8)[0]
    style_count   = struct.unpack_from('<I', manifest_data, sp_start + 12)[0]
    flags         = struct.unpack_from('<I', manifest_data, sp_start + 16)[0]
    strings_start = struct.unpack_from('<I', manifest_data, sp_start + 20)[0]
    styles_start  = struct.unpack_from('<I', manifest_data, sp_start + 24)[0]

    assert sp_type == 0x0001, f"Expected string pool chunk: {hex(sp_type)}"
    is_utf8 = bool(flags & (1 << 8))
    assert is_utf8, "String pool is UTF-16 — unexpected. Report this."

    offsets_array_start = sp_start + 28
    strings_data_start = sp_start + strings_start

    # Read all string entries as raw bytes
    raw_entries = []
    for i in range(string_count):
        off = struct.unpack_from('<I', manifest_data, offsets_array_start + i * 4)[0]
        abs_off = strings_data_start + off

        b0 = manifest_data[abs_off]
        if b0 & 0x80:
            char_len = ((b0 & 0x7F) << 8) | manifest_data[abs_off + 1]
            byte_len_off = abs_off + 2
        else:
            char_len = b0
            byte_len_off = abs_off + 1

        b1 = manifest_data[byte_len_off]
        if b1 & 0x80:
            byte_len = ((b1 & 0x7F) << 8) | manifest_data[byte_len_off + 1]
            data_off = byte_len_off + 2
        else:
            byte_len = b1
            data_off = byte_len_off + 1

        raw_str = manifest_data[data_off:data_off + byte_len].decode('utf-8', errors='replace')
        raw_entries.append(raw_str)

    # ── 3. Find and replace ───────────────────────────────────────────────────
    if NEW_CLASS in raw_entries:
        print(f"[*] Manifest already patched — {NEW_CLASS} found.")
    elif OLD_CLASS not in raw_entries:
        print(f"[!] '{OLD_CLASS}' not found in string pool.")
        print("    Strings found:", [s for s in raw_entries if 'pictach' in s])
        sys.exit(1)
    else:
        idx = raw_entries.index(OLD_CLASS)
        raw_entries[idx] = NEW_CLASS
        print(f"[*] Replaced index {idx}: '{OLD_CLASS}' -> '{NEW_CLASS}'")

    # Apply extra replacements
    for old_str, new_str in EXTRA_REPLACEMENTS.items():
        if old_str in raw_entries:
            idx2 = raw_entries.index(old_str)
            raw_entries[idx2] = new_str
            print(f"[*] Replaced index {idx2}: '{old_str}' -> '{new_str}'")
        else:
            print(f"[*] '{old_str}' not found — skipping.")

    # ── 4. Rebuild string pool data ───────────────────────────────────────────
    new_string_data = bytearray()
    new_offsets     = []

    for s in raw_entries:
        new_offsets.append(len(new_string_data))
        new_string_data += encode_string_entry(s)

    style_data_start = sp_start + styles_start if styles_start > 0 else sp_start + sp_chunk_sz
    style_data_end   = sp_start + sp_chunk_sz
    style_bytes      = manifest_data[style_data_start:style_data_end] if styles_start > 0 else b''

    # ── 5. Build new string pool chunk ───────────────────────────────────────
    new_offsets_bytes = b''.join(struct.pack('<I', o) for o in new_offsets)

    new_strings_start = 28 + len(new_offsets_bytes)
    new_styles_start  = (new_strings_start + len(new_string_data)) if style_bytes else 0

    new_sp_chunk_sz = (28 + len(new_offsets_bytes) + len(new_string_data) + len(style_bytes))

    new_sp_header = struct.pack('<HHIIIIII',
        0x0001,
        28,
        new_sp_chunk_sz,
        string_count,
        style_count,
        flags,
        new_strings_start,
        new_styles_start,
    )

    new_sp_chunk = (
        new_sp_header +
        new_offsets_bytes +
        bytes(new_string_data) +
        style_bytes
    )

    # ── 6. Assemble patched manifest ──────────────────────────────────────────
    before_sp   = manifest_data[:sp_start]
    after_sp    = manifest_data[sp_start + sp_chunk_sz:]

    new_file_size = len(before_sp) + len(new_sp_chunk) + len(after_sp)
    patched = bytearray(before_sp + new_sp_chunk + after_sp)
    struct.pack_into('<I', patched, 4, new_file_size)

    print(f"[*] String pool rebuilt. Old size: {sp_chunk_sz}, New size: {new_sp_chunk_sz}")
    return bytes(patched)


def rebuild_apk(apk_path, patched_manifest):
    """Rebuild APK with the patched manifest, preserving all other entries."""
    tmp_path = apk_path + ".patching"

    with zipfile.ZipFile(apk_path, 'r') as src_zip:
        with zipfile.ZipFile(tmp_path, 'w', zipfile.ZIP_DEFLATED) as dst_zip:
            for item in src_zip.infolist():
                if item.filename == 'AndroidManifest.xml':
                    dst_zip.writestr(item, patched_manifest)
                else:
                    data = src_zip.read(item.filename)
                    dst_zip.writestr(item, data)

    shutil.move(tmp_path, apk_path)
    print(f"[*] APK rebuilt: {apk_path}")


def verify_patch(apk_path):
    """Confirm the new class name is present in the rebuilt APK manifest."""
    with zipfile.ZipFile(apk_path, 'r') as z:
        data = z.read('AndroidManifest.xml')
    if NEW_CLASS.encode('utf-8') in data:
        print(f"[✓] Verification passed — '{NEW_CLASS}' found in manifest.")
    else:
        print(f"[✗] Verification FAILED — '{NEW_CLASS}' not found after patch!")
        sys.exit(1)
    if b'category.LAUNCHER' in data:
        print(f"[✓] LAUNCHER category confirmed in manifest.")
    else:
        print(f"[!] WARNING: LAUNCHER category not found — install prompt may not appear.")


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("Usage: python patch_apk_manifest.py <path/to/app.apk>")
        sys.exit(1)

    apk_path = sys.argv[1]
    if not os.path.exists(apk_path):
        print(f"[!] File not found: {apk_path}")
        sys.exit(1)

    with zipfile.ZipFile(apk_path, 'r') as z:
        manifest_data = z.read('AndroidManifest.xml')

    patched = patch_manifest(manifest_data)
    rebuild_apk(apk_path, patched)
    verify_patch(apk_path)
