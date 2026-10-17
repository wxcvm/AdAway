/*
 * cosmetic 域名作用域匹配的宿主机测试（D 项）。
 * cc -std=c11 -Wall -Wextra -o cosmetic_scope_test webserver/jni/cosmetic_scope_test.c && ./cosmetic_scope_test
 */
#include <stdio.h>
#include "cosmetic_scope.h"

static int failures = 0, checks = 0;
static void ok(const char *what, bool got, bool want) {
    checks++;
    if (got != want) { failures++; printf("FAIL %s = %d, want %d\n", what, (int)got, (int)want); }
}
#define M(rule, host) cosmetic_domain_match(rule, sizeof(rule) - 1, host)

int main(void) {
    ok("全局 * 命中任意主机", M("*", "example.com"), true);
    ok("全局 * 命中子域", M("*", "a.b.example.com"), true);
    ok("空域名即全局", cosmetic_domain_match("", 0, "example.com"), true);

    ok("精确命中", M("example.com", "example.com"), true);
    ok("子域命中", M("example.com", "a.example.com"), true);
    ok("多级子域命中", M("example.com", "x.y.example.com"), true);

    ok("不同域不命中", M("example.com", "example.org"), false);
    ok("前缀相似不命中", M("example.com", "notexample.com"), false);
    ok("后缀相似不命中", M("example.com", "example.com.evil.net"), false);
    ok("父域不被子域规则命中", M("a.example.com", "example.com"), false);

    ok("大小写不敏感（主机大写）", M("example.com", "EXAMPLE.COM"), true);
    ok("大小写不敏感（规则大写）", M("Example.COM", "example.com"), true);
    ok("大小写不敏感（子域混合）", M("ExAmPlE.com", "Sub.Example.COM"), true);

    ok("主机末尾点被忽略", M("example.com", "example.com."), true);
    ok("子域 + 末尾点", M("example.com", "a.example.com."), true);

    ok("空主机不命中", M("example.com", ""), false);
    ok("主机只有点不命中", M("example.com", "."), false);
    ok("全局也不命中空主机", M("*", ""), false);

    ok("单标签主机精确命中", M("localhost", "localhost"), true);
    ok("单标签主机子域", M("localhost", "a.localhost"), true);

    printf("%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
