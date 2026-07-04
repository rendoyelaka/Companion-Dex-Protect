import sys
import zipfile
import shutil
import os

apk_path = sys.argv[1]

# Read binary AndroidManifest.xml from APK
with zipfile.ZipFile(apk_path, 'r') as z:
    manifest_data = z.read("AndroidManifest.xml")

OLD_CLASS = b'com.android.pictach.App'
NEW_CLASS = b'com.android.pictach.CompanionApp'

if NEW_CLASS in manifest_data:
    print("[*] Manifest already patched.")
    sys.exit(0)

if OLD_CLASS not in manifest_data:
    print("[!] Original App class not found in manifest.")
    sys.exit(1)

# Android binary XML stores string lengths before strings
# OLD: \x17 (23 chars) + com.android.pictach.App
# NEW: \x1f (31 chars) + com.android.pictach.CompanionApp
# Length difference = 8 bytes

old_len = len(OLD_CLASS)  # 23
new_len = len(NEW_CLASS)  # 31

# Find the length byte before the string
idx = manifest_data.find(OLD_CLASS)
# The byte before is the string length in Android binary XML (little-endian 16-bit)
# Replace old string with new — pad with spaces if needed to keep same size
# Actually we need exact replacement with updated length

# Simple approach: replace the string and update the preceding length byte
# Find the pattern: \x17\x17 + string (UTF-8 strings have doubled length byte)
import re

# Pattern: two bytes of length + old class name
pattern = bytes([old_len, old_len]) + OLD_CLASS
replacement = bytes([new_len, new_len]) + NEW_CLASS

if pattern in manifest_data:
    patched = manifest_data.replace(pattern, replacement, 1)
    print(f"[*] Patched manifest: {OLD_CLASS.decode()} -> {NEW_CLASS.decode()}")
else:
    # Try single length byte
    pattern = bytes([old_len]) + OLD_CLASS
    replacement = bytes([new_len]) + NEW_CLASS
    if pattern in manifest_data:
        patched = manifest_data.replace(pattern, replacement, 1)
        print(f"[*] Patched manifest (single len): {OLD_CLASS.decode()} -> {NEW_CLASS.decode()}")
    else:
        print("[!] Could not find length+string pattern. Trying raw replace.")
        patched = manifest_data.replace(OLD_CLASS, NEW_CLASS, 1)
        print(f"[*] Raw patched: {OLD_CLASS.decode()} -> {NEW_CLASS.decode()}")

# Write patched manifest back into APK
tmp_apk = apk_path + ".tmp"
shutil.copy(apk_path, tmp_apk)

with zipfile.ZipFile(tmp_apk, 'a') as z:
    # Remove old entry by rebuilding
    pass

# Rebuild APK with patched manifest
import io
orig_entries = {}
with zipfile.ZipFile(apk_path, 'r') as z:
    for name in z.namelist():
        if name == "AndroidManifest.xml":
            orig_entries[name] = patched
        else:
            orig_entries[name] = z.read(name)

with zipfile.ZipFile(apk_path, 'w', zipfile.ZIP_DEFLATED) as z:
    for name, data in orig_entries.items():
        if name in ['classes.dex', 'classes2.dex', 'resources.arsc']:
            z.write(name, name) if os.path.exists(name) else z.writestr(name, data)
        else:
            z.writestr(name, data)

print("[*] APK rebuilt with patched manifest.")
