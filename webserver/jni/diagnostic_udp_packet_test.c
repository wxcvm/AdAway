#include "diagnostic_udp_packet.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
static size_t unhex(const char *text,uint8_t *out,size_t cap) {
    size_t size=strlen(text)/2; assert(size<=cap);
    for (size_t i=0;i<size;i++) { char hex[3]={text[2*i],text[2*i+1],0}; out[i]=(uint8_t)strtoul(hex,NULL,16); }
    return size;
}
int main(int argc,char **argv) {
    assert(argc==2); FILE *file=fopen(argv[1],"r"); assert(file);
    char line[7000]; unsigned count=0;
    while (fgets(line,sizeof(line),file)) {
        if (line[0]=='#') continue;
        char *name=strtok(line,"\t"),*hex=strtok(NULL,"\t"),*family=strtok(NULL,"\t"),*answer=strtok(NULL,"\r\n");
        assert(name && hex && family && answer);
        uint8_t packet[DUDP_MAX_PACKET],out[DUDP_MAX_PACKET],expected[DUDP_MAX_PACKET],request[DUDP_MAX_PACKET];
        size_t n=unhex(hex,packet,sizeof(packet)); struct dudp_packet parsed;
        unsigned accepted=(unsigned)strtoul(family,NULL,10);
        assert((unsigned)dudp_parse(packet,n,&parsed)==(accepted?1u:0u));
        if (accepted) {
            assert(parsed.family==accepted); uint8_t payload[DUDP_PAYLOAD]; memcpy(payload,packet+parsed.header+8,parsed.payload); payload[0]^=0x5a;
            size_t bytes=dudp_reply(packet,n,payload,parsed.payload,out,sizeof(out)); size_t wanted=unhex(answer,expected,sizeof(expected));
            assert(bytes==wanted && !memcmp(out,expected,bytes));
            assert(!dudp_reply(packet,n,payload,parsed.payload,out,bytes-1));
            size_t built=dudp_request(accepted,parsed.source_port,packet+parsed.header+8,parsed.payload,request,sizeof(request));
            assert(built==n && !memcmp(packet,request,n));
        }
        count++;
    }
    assert(count>=30); fclose(file); printf("%u strict IPv4/IPv6 packet vectors passed\n",count); return 0;
}
