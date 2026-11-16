"""Emits the manifest.json the Karoo polls for extension updates."""
import argparse
import json

p = argparse.ArgumentParser()
p.add_argument("--repo", required=True)  # owner/name
p.add_argument("--version", required=True)
p.add_argument("--code", type=int, required=True)
p.add_argument("--notes", required=True)
a = p.parse_args()

owner = a.repo.split("/")[0]
print(json.dumps({
    "label": "Flashback",
    "packageName": "dev.gtaylor.flashback",
    "iconUrl": f"https://raw.githubusercontent.com/{a.repo}/main/docs/icon.svg",
    "latestApkUrl": f"https://github.com/{a.repo}/releases/latest/download/flashback.apk",
    "latestVersion": a.version,
    "latestVersionCode": a.code,
    "developer": owner,
    "description": "Flashes your rear light only while the radar detects a vehicle. Karoo controls the light the rest of the time.",
    "releaseNotes": open(a.notes).read().strip(),
}, indent=4))
