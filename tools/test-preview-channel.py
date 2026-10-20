"""Exercise the actual publication script without GitHub writes."""
import json
import os
from pathlib import Path
import runpy
import subprocess
import tempfile
from unittest.mock import patch

script=Path(__file__).with_name('publish-preview-channel.py')
for android in (True,False):
    repo='wxcvm/AdAway' if android else 'wxcvm/AdAway-Windows'
    name='manifest.json' if android else 'windows-manifest.json'
    pointer='android-preview' if android else 'win11-preview'
    new=dict(tag='progress-android-20261020.5' if android else 'progress-win-20261020.4',versionCode=200,version='1.79',build='39410500291')
    for old_rank,expected in ((None,'create'),(100,'upload'),(300,None)):
        commands=[]
        with tempfile.TemporaryDirectory() as temp:
            original=Path.cwd()
            os.chdir(temp)
            try:
                Path('release-assets').mkdir()
                (Path('release-assets')/name).write_text(json.dumps(new),encoding='utf-8')
                def fake_run(args,**kwargs):
                    commands.append(args)
                    output=''
                    code=0
                    if args[1:3]==['release','view']:
                        if args[3]==new['tag']: output=json.dumps(dict(isPrerelease=True,isDraft=False))
                        elif old_rank is None: code=1
                    if args[1:3]==['release','download']:
                        old=dict(new)
                        old['versionCode']=old_rank
                        old['build']='39410500290' if old_rank==100 else '39410500292'
                        (Path(args[args.index('--dir')+1])/name).write_text(json.dumps(old),encoding='utf-8')
                    return subprocess.CompletedProcess(args,code,output,'')
                with patch.dict(os.environ,dict(GITHUB_REPOSITORY=repo,GITHUB_SHA='a'*40)),patch('subprocess.run',side_effect=fake_run):
                    try: runpy.run_path(str(script),run_name='__main__')
                    except SystemExit as exit_code: assert exit_code.code==0
                writes=[cmd for cmd in commands if cmd[2] in ('create','upload')]
                assert len(writes)==(expected is not None),(repo,commands)
                if expected: assert writes[0][2:4]==[expected,pointer],commands
                assert all('android-latest' not in cmd and 'win11-latest' not in cmd for cmd in commands)
            finally: os.chdir(original)
print('Preview publication passed: create, advance, stale rejection and stable-pointer isolation for both platforms')
