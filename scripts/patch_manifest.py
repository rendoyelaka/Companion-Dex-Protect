import sys
import re

manifest_path = sys.argv[1]

with open(manifest_path, "r", encoding="utf-8") as f:
    content = f.read()

# Only inject if android:name is NOT already present in <application tag
app_tag_match = re.search(r'<application\b[^>]*>', content, re.DOTALL)
if app_tag_match:
    app_tag = app_tag_match.group(0)
    if 'android:name=' not in app_tag:
        new_tag = app_tag.replace('<application', '<application android:name="com.android.pictach.CompanionApp"', 1)
        content = content.replace(app_tag, new_tag, 1)
        print("[*] AndroidManifest patched — android:name injected.")
    else:
        # Replace existing android:name value with CompanionApp
        content = re.sub(
            r'(<application\b[^>]*android:name=")[^"]*(")',
            r'\1com.android.pictach.CompanionApp\2',
            content,
            count=1
        )
        print("[*] AndroidManifest patched — existing android:name replaced.")
else:
    print("[!] <application tag not found.")

with open(manifest_path, "w", encoding="utf-8") as f:
    f.write(content)
