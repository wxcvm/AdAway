"""Deterministic RFC1928/1929 diagnostic subset; shared by actual Java/C paths."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
rows=[]
def add(kind,packet,answer): rows.append((kind,bytes(packet).hex(),str(answer)))
add('greeting',b'\x05\x01\x02',0)
for p in (b'',b'\x05',b'\x04\x01\x02',b'\x05\x01\x00',b'\x05\x02\x02',b'\x05\x01\x02\x00'):
    add('greeting',p,255)
auth=b'\x01\x06adaway\x10'+bytes(range(16))
add('auth',auth,0)
for at in (0,1,2,8,9,24):
    p=bytearray(auth);p[at]^=1;add('auth',p,1)
add('auth',auth[:-1],1);add('auth',auth+b'\0',1)
for family in (4,6):
    address=bytes([198,18,0,1]) if family==4 else bytes.fromhex('20010002000000000000000000000001')
    p=bytes([5,1,0,1 if family==4 else 4])+address+(53539).to_bytes(2,'big')
    kind=f'connect{family}';add(kind,p,0)
    for at,value,answer in ((0,4,1),(1,2,7),(1,3,7),(2,1,1),(3,3,8),(4,8,2),(len(p)-1,0,2)):
        q=bytearray(p);q[at]=value;add(kind,q,answer)
    add(kind,p[:-1],2);add(kind,p+b'\0',2)
out=root/'app/src/test/resources/tcp/socks5_vectors.tsv';out.parent.mkdir(parents=True,exist_ok=True)
out.write_text('# kind\tframe hex\tRFC result code\n'+'\n'.join('\t'.join(r) for r in rows)+'\n',encoding='ascii')
print(f'{len(rows)} SOCKS5 vectors generated')
