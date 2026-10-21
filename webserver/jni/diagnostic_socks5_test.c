#include "diagnostic_socks5.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
static size_t decode(const char *text,uint8_t *bytes,size_t cap) {
    size_t n=strlen(text)/2;assert(strlen(text)%2==0 && n<=cap);
    for(size_t i=0;i<n;i++){char word[3]={text[i*2],text[i*2+1],0};bytes[i]=(uint8_t)strtoul(word,NULL,16);}
    return n;
}
int main(int argc,char **argv) {
    assert(argc==2);FILE *input=NULL;
#ifdef _MSC_VER
    assert(fopen_s(&input,argv[1],"r")==0);
#else
    input=fopen(argv[1],"r");
#endif
    assert(input);
    char line[4096];unsigned count=0;uint8_t token[16];
    for(unsigned i=0;i<16;i++)token[i]=(uint8_t)i;
    while(fgets(line,sizeof(line),input)) {
        if(line[0]=='#')continue;
        char *hex=strchr(line,'\t');assert(hex);*hex++=0;
        char *answer=strchr(hex,'\t');assert(answer);*answer++=0;
        uint8_t packet[512];size_t size=decode(hex,packet,sizeof(packet));unsigned actual;
        if(strcmp(line,"greeting")==0)actual=ds_greeting(packet,size);
        else if(strcmp(line,"auth")==0)actual=ds_auth(packet,size,token);
        else if(strncmp(line,"reply",5)==0)actual=ds_reply_code(packet,size,strcmp(line,"reply6")==0?6u:4u);
        else actual=ds_connect(packet,size,strcmp(line,"connect6")==0?6u:4u);
        assert(actual==(unsigned)strtoul(answer,NULL,10));count++;
    }
    assert(fclose(input)==0);assert(count>=24);printf("%u fixed authenticated SOCKS5 contract vectors passed\n",count);return 0;
}
