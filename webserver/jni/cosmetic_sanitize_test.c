/*
 * cosmetic 规则净化的宿主机测试（D 项）。
 * cc -std=c11 -Wall -Wextra -o cosmetic_sanitize_test webserver/jni/cosmetic_sanitize_test.c && ./cosmetic_sanitize_test
 */
#include <stdio.h>
#include <string.h>
#include "cosmetic_sanitize.h"

static int failures = 0, checks = 0;
static void ok_str(const char *what, const char *got, const char *want) {
    checks++;
    if (strcmp(got, want) != 0) { failures++; printf("FAIL %s = [%s], want [%s]\n", what, got, want); }
}
static void ok_num(const char *what, unsigned long got, unsigned long want) {
    checks++;
    if (got != want) { failures++; printf("FAIL %s = %lu, want %lu\n", what, got, want); }
}

int main(void) {
    struct cosmetic_stats st;

    /* 真实规则（含 { }）：必须原样保留 —— 这正是第一版判据会全丢的情形 */
    char b1[] = ".ad{display:none !important;}\n#banner{display:none;}\n";
    size_t n1 = cosmetic_sanitize(b1, strlen(b1), &st);
    ok_str("含 {}/{} 的真实规则原样保留", b1, ".ad{display:none !important;}\n#banner{display:none;}\n");
    ok_num("长度不变", (unsigned long) n1, (unsigned long) strlen(".ad{display:none !important;}\n#banner{display:none;}\n"));
    ok_num("保留 2 条", (unsigned long) st.kept, 2);
    ok_num("丢弃 0 条", (unsigned long) st.dropped, 0);

    /* 含 < 的行：整条丢弃；其它行保留 */
    char b2[] = ".ok{color:red}\n</style><script>alert(1)</script>\n.also-ok{color:blue}\n";
    cosmetic_sanitize(b2, strlen(b2), &st);
    ok_str("含 < 的整条被丢弃", b2, ".ok{color:red}\n.also-ok{color:blue}\n");
    ok_num("丢弃 1 条", (unsigned long) st.dropped, 1);
    ok_num("保留 2 条", (unsigned long) st.kept, 2);

    /* 单独的 '<' 即便不是 </style> 也拒（防御一致） */
    char b3[] = "div[title=a<b]{display:none}\n.x{display:none}\n";
    cosmetic_sanitize(b3, strlen(b3), &st);
    ok_str("任何 '<' 都拒", b3, ".x{display:none}\n");
    ok_num("b3 丢弃 1", (unsigned long) st.dropped, 1);

    /* 空行与纯空白行保留结构、不计为规则 */
    char b4[] = "\n   \n.a{}\n";
    cosmetic_sanitize(b4, strlen(b4), &st);
    ok_str("空行保留、规则保留", b4, "\n   \n.a{}\n");
    ok_num("只计 1 条规则", (unsigned long) st.kept, 1);
    ok_num("无丢弃", (unsigned long) st.dropped, 0);

    /* 空输入 */
    char b5[] = "";
    size_t n5 = cosmetic_sanitize(b5, 0, &st);
    ok_num("空输入长度 0", (unsigned long) n5, 0);
    ok_num("空输入 kept=0", (unsigned long) st.kept, 0);
    ok_num("bytes_in=0", (unsigned long) st.bytes_in, 0);

    /* 单条判定函数 */
    checks++; if (cosmetic_rule_is_safe(".a{display:none}", 17) != true) { failures++; printf("FAIL 含 {}/{} 应判安全\n"); }
    checks++; if (cosmetic_rule_is_safe("a<b", 3) != false) { failures++; printf("FAIL 含 < 应判不安全\n"); }
    checks++; if (cosmetic_rule_is_safe("", 0) != true) { failures++; printf("FAIL 空串应判安全\n"); }

    printf("%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
