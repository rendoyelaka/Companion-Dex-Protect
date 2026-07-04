import os
import zipfile
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad
import hashlib

raw_key = os.environ.get("DEX_KEY", "")
if not raw_key:
    raise ValueError("DEX_KEY environment variable is not set.")

key = hashlib.sha256(raw_key.encode()).digest()

apk_path = "companion.apk"
dex_path = "classes.dex"
output_path = "encrypted_classes.dex"

print(f"[*] Extracting {dex_path} from {apk_path}...")
with zipfile.ZipFile(apk_path, 'r') as apk:
    if dex_path not in apk.namelist():
        raise FileNotFoundError(f"{dex_path} not found in APK.")
    apk.extract(dex_path, ".")

with open(dex_path, "rb") as f:
    dex_data = f.read()

print(f"[*] DEX size: {len(dex_data)} bytes")

iv = bytes(16)
cipher = AES.new(key, AES.MODE_CBC, iv)
encrypted = cipher.encrypt(pad(dex_data, AES.block_size))

with open(output_path, "wb") as f:
    f.write(encrypted)

print(f"[*] Encrypted DEX written to {output_path} ({len(encrypted)} bytes)")
os.remove(dex_path)
print("[*] Done.")
