"""Fetch immutable PUBLIC inputs only. Existing caches are verified, never reset/deleted."""
import argparse,hashlib,json,pathlib,subprocess,urllib.request
HERE=pathlib.Path(__file__).resolve().parent
LOCK=json.loads((HERE/'dependencies.lock.json').read_text())
def run(*args,cwd=None):
    return subprocess.check_output(args,cwd=cwd,text=True,encoding='utf8').strip()
def fetch(directory):
    directory=pathlib.Path(directory).resolve();directory.mkdir(parents=True,exist_ok=True)
    source=directory/'lws-source';pin=LOCK['lws']['commit']
    if not source.exists():
        source.mkdir();run('git','init','-q',str(source))
        run('git','-C',str(source),'config','core.autocrlf','false')
        run('git','-C',str(source),'fetch','--depth','1',LOCK['lws']['repository'],pin)
        run('git','-C',str(source),'checkout','--detach','FETCH_HEAD')
    if run('git','-C',str(source),'rev-parse','HEAD')!=pin or run('git','-C',str(source),'status','--porcelain'):
        raise RuntimeError('LWS source cache changed: preserve edits, do not silently reset it')
    run('git','-C',str(source),'fsck','--no-reflogs')
    fixture=directory/'fixture'
    for name,digest in LOCK['fixture']['files'].items():
        path=fixture/name;path.parent.mkdir(parents=True,exist_ok=True)
        if not path.exists():
            url=f"https://raw.githubusercontent.com/{LOCK['fixture']['repository']}/{LOCK['fixture']['commit']}/{name}"
            data=urllib.request.urlopen(url,timeout=60).read()
            if hashlib.sha256(data).hexdigest()!=digest:raise RuntimeError('Downloaded fixture digest mismatch: '+name)
            path.write_bytes(data)
        if hashlib.sha256(path.read_bytes()).hexdigest()!=digest:raise RuntimeError('Fixture cache changed: '+name)
    return source,fixture
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('directory');a=p.parse_args()
    source,fixture=fetch(a.directory);print(json.dumps({'lws':str(source),'fixture':str(fixture),'lock':LOCK},indent=2))
