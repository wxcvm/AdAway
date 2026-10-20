"""Publish a separate opt-in pointer after the immutable preview exists."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

repo = os.environ['GITHUB_REPOSITORY']
android = repo == 'wxcvm/AdAway'
if repo not in ('wxcvm/AdAway', 'wxcvm/AdAway-Windows'):
    raise ValueError('Unexpected preview repository')
pointer = 'android-preview' if android else 'win11-preview'
name = 'manifest.json' if android else 'windows-manifest.json'
manifest = Path('release-assets')/name
new = json.loads(manifest.read_text(encoding='utf-8'))
def rank(doc):
    return (int(doc['versionCode']),) if android else (tuple(map(int,doc['version'].split('.'))),int(doc['build']))
def gh(*args, check=True):
    return subprocess.run(['gh', *args, '--repo', repo], check=check, capture_output=True, text=True)
# The immutable release must already be published and correctly marked.
release = json.loads(gh('release','view',new['tag'],'--json','isPrerelease,isDraft').stdout)
if release['isDraft'] or not release['isPrerelease']: raise ValueError('Not a public preview')
exists = gh('release','view',pointer,check=False).returncode == 0
if exists:
    with tempfile.TemporaryDirectory() as temp:
        gh('release','download',pointer,'--pattern',name,'--dir',temp)
        old=json.loads((Path(temp)/name).read_text(encoding='utf-8'))
        if rank(old) >= rank(new):
            print('Newer or equal preview pointer already published; preserving it')
            raise SystemExit(0)
    gh('release','upload',pointer,str(manifest),'--clobber')
else:
    gh('release','create',pointer,str(manifest),'--target',os.environ['GITHUB_SHA'],
       '--prerelease','--latest=false','--title','Opt-in preview update manifest',
       '--notes','Discovery pointer for users who enable preview updates. Downloads use immutable release URLs. Stable feeds unchanged.')
print('Published independent opt-in preview pointer:', pointer)
