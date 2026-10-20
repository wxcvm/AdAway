"""Emit an installable version identity only after production signing checks."""
import hashlib
import json
import os
from pathlib import Path
import re

assets = Path('release-assets')
identity = (assets / 'APK-IDENTITY.txt').read_text(encoding='utf-8')
package = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", identity)
tag = os.environ['PREVIEW_TAG']
if not package or package[1] != 'com.wxcvm.Adaway' or not re.fullmatch(r'progress-android-[0-9]{8}\.[0-9]+', tag):
    raise ValueError('Invalid production preview identity')
doc = dict(channel='preview', tag=tag, packageName=package[1], versionCode=int(package[2]), version=package[3],
           downloadUrl=f'https://github.com/wxcvm/AdAway/releases/download/{tag}/AdAway-preview-arm64.apk',
           sha256=hashlib.sha256((assets/'AdAway-preview-arm64.apk').read_bytes()).hexdigest(),
           sourceCommit=os.environ['GITHUB_SHA'], changelog='Preview update channel and listener diagnostics. See release notes for platform acceptance.')
(assets/'manifest.json').write_text(json.dumps(doc, indent=2)+'\n', encoding='utf-8')
