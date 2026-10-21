"""Independent fixed-packet reference generator, stdlib only."""
import ipaddress
from pathlib import Path
import struct
def checksum(data):
    data+=b'\0'*(len(data)%2)
    value=sum(struct.unpack('!%dH'%(len(data)//2),data))
    while value>>16: value=(value&65535)+(value>>16)
    return (~value)&65535
def packet(family,payload,reply=False):
    ips=['198.18.0.2','198.18.0.1'] if family==4 else ['2001:2::2','2001:2::1']
    if reply: ips.reverse()
    src,dst=map(lambda x:ipaddress.ip_address(x).packed,ips)
    ports=(53536,12345) if reply else (12345,53536)
    udp=struct.pack('!HHHH',*ports,8+len(payload),0)+payload
    pseudo=src+dst+(struct.pack('!BBH',0,17,len(udp)) if family==4 else struct.pack('!I3xB',len(udp),17))
    udp=udp[:6]+struct.pack('!H',checksum(pseudo+udp) or 65535)+udp[8:]
    if family==4:
        ip=struct.pack('!BBHHHBBH',0x45,0,20+len(udp),0,0,64,17,0)+src+dst
        return ip[:10]+struct.pack('!H',checksum(ip))+ip[12:]+udp
    return struct.pack('!IHBB',6<<28,len(udp),17,64)+src+dst+udp
rows=[]
def add(name,data,family=0,payload=None):
    answer='-' if not family else packet(family,bytes([payload[0]^0x5a])+payload[1:],True).hex()
    rows.append('\t'.join((name,data.hex(),str(family),answer)))
for family in [4,6]:
    for payload in [b'nonce-0123456789',bytes(range(256)),b'\0']:
        add(f'v{family}-valid-{len(payload)}',packet(family,payload),family,payload)
    base=packet(family,b'nonce-0123456789'); header=20 if family==4 else 40
    for name,offset,value in [('wrong-source',15 if family==4 else 23,3),('wrong-destination',19 if family==4 else 39,3),('zero-source-port',header,0),('wrong-port',header+3,0),('wrong-checksum',header+6,0x12)]:
        data=bytearray(base); data[offset]=value
        if name=='zero-source-port': data[header:header+2]=b'\0\0'
        add(f'v{family}-{name}',data)
    add(f'v{family}-truncated',base[:-1]); add(f'v{family}-trailing',base+b'\0')
    add(f'v{family}-empty',packet(family,b'')); add(f'v{family}-oversized',packet(family,bytes(257)))
    if family==6:
        for name,offset,value in [('zero-checksum',46,0),('fragment',6,44),('extension',6,0),('hop-zero',7,0)]:
            data=bytearray(base); data[offset]=value
            if name=='zero-checksum': data[46:48]=b'\0\0'
            add('v6-'+name,data)
    else:
        data=bytearray(base); data[6]=0x20; data[10:12]=b'\0\0'; data[10:12]=struct.pack('!H',checksum(data[:20])); add('v4-fragment',data)
path=Path(__file__).resolve().parent.parent/'app/src/test/resources/transport/packet_vectors.tsv'
add('unknown-ip-version',bytes([0x70])+packet(6,b'nonce')[1:])
add('too-short',bytes(12))
path.parent.mkdir(parents=True,exist_ok=True)
path.write_text('# name\trequest hex\taccepted family (0 rejects before I/O)\treply hex\n'+'\n'.join(rows)+'\n',encoding='ascii')
print(len(rows),'shared dual-stack transport vectors')
