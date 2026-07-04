import sys
import re

manifest_path = sys.argv[1]

with open(manifest_path, "r", encoding="utf-8") as f:
    content = f.read()

# Inject android:name into <application> tag if not already set
if 'android:name="com.android.pictach.CompanionApp"' not in content:
    content = re.sub(
        r'(<application\b)',
        r'\1 android:name="com.android.pictach.CompanionApp"',
        content,
        count=1
    )

with open(manifest_path, "w", encoding="utf-8") as f:
    f.write(content)

print("[*] AndroidManifest patched.")
