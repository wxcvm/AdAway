#include "request_host.h"
#include <stdio.h>
#include <stdlib.h>
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x); exit(1); } } while (0)
static void good(const char *header, const char *expected) {
    char out[192];
    CHECK(request_host_parse(header,strlen(header),out,sizeof(out)));
    CHECK(!strcmp(out,expected));
}
static void bad(const char *header, size_t length) {
    char out[192];
    CHECK(!request_host_parse(header,length,out,sizeof(out)));
    CHECK(!strcmp(out,"(unknown host)"));
}
int main(void) {
    /* RFC 5737/3849 documentation addresses: parsing only, never contacted. */
    good("example.test","example.test");
    good("Example.TEST.:8443","Example.TEST.");
    good("192.0.2.1:80","192.0.2.1");
    good("[2001:db8::1]","2001:db8::1");
    good("[2001:db8::1]:443","2001:db8::1");
    good("[2001:db8:1::2]:8443","2001:db8:1::2");
    good(" [::ffff:192.0.2.1]:80\t","::ffff:192.0.2.1");
    good("xn--fsqu00a.test:65535","xn--fsqu00a.test");
    good("例子.test:8080","例子.test");
    good("é例😀.test:443","é例😀.test");
    const char *invalid[]={"","2001:db8::1","[2001:db8::1","[2001:db8::1]suffix","[2001:db8::1]:","[2001:db8::1]:bad","example.test:",
        "example.test:0","example.test:65536","example.test:80:90","[example.test]",
        "user:password@example.test","https://example.test/private?token=secret","example.test/path",
        "example.test?token=secret","example.test#private","example.test\\secret","exa mple.test",
        "example.test\r\nsecret","example.test\xff","\xc0\xaf.test","\xed\xa0\x80.test","bad\xc2\x85.test",
        "\xe4\xbe","\xe4\x41\x80.test","\xf0\x80\x80\x80.test","\xf4\x90\x80\x80.test","bad\x7f.test"};
    for(size_t i=0;i<sizeof(invalid)/sizeof(invalid[0]);i++) bad(invalid[i],strlen(invalid[i]));
    bad(NULL,0);
    const char embedded[]="example.test\0hidden"; bad(embedded,sizeof(embedded)-1);
    char long_host[192]; memset(long_host,'a',sizeof(long_host)-1); long_host[191]=0;
    good(long_host,long_host);
    char too_long[193]; memset(too_long,'a',sizeof(too_long)-1); too_long[192]=0;
    bad(too_long,strlen(too_long));
    char bounded[8]; memset(bounded,'X',sizeof(bounded));
    CHECK(request_host_parse("test",4,bounded+1,5)); CHECK(!strcmp(bounded+1,"test"));
    CHECK(bounded[0]=='X' && bounded[6]=='X');
    memset(bounded,'X',sizeof(bounded));
    CHECK(!request_host_parse("test",4,bounded+1,4)); CHECK(bounded[4]==0 && bounded[5]=='X');
    CHECK(!request_host_parse("test",4,NULL,0));
    CHECK(!request_host_parse(NULL,0,bounded,1) && bounded[0]==0);
    puts("Request host: IPv4/IPv6 authority, numeric port, missing Host privacy, UTF-8 and output bounds passed");
    return 0;
}
