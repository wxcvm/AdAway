/*
 * 规则归一化的宿主机测试（G1）。
 *
 * 编译运行：cc -std=c11 -Wall -Wextra -o rule_norm_test webserver/jni/rule_norm_test.c && ./rule_norm_test
 * 不依赖 mongoose、不依赖服务端状态，因此可以在 CI 的普通 runner 上跑。
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

#include "rule_norm.h"

static int failures = 0;
static int checks = 0;

static void expect_norm(const char *in, const char *want) {
    char got[256];
    domain_rule_normalize(in, got, sizeof(got));
    checks++;
    if (strcmp(got, want) != 0) {
        failures++;
        printf("FAIL normalize(%s) = %s, want %s\n", in, got, want);
    }
}

static void expect_modifier(const char *in, int want) {
    int got = rule_has_unsupported_modifier(in) ? 1 : 0;
    checks++;
    if (got != want) {
        failures++;
        printf("FAIL has_unsupported_modifier(%s) = %d, want %d\n", in, got, want);
    }
}


/*
 * 共享向量的驱动（G1/D）：从 rule_vectors.txt 读取「输入 / 期望归一化 / 期望修饰符」，
 * 让两端用同一份数据断言。默认路径按仓库根目录解析（CI 里就是仓库根）。
 */
static void run_vectors(const char *path) {
    FILE *f = fopen(path, "r");
    if (f == NULL) {
        failures++;
        printf("FAIL cannot open vector file: %s\n", path);
        return;
    }
    char line[512];
    int n = 0;
    while (fgets(line, sizeof(line), f) != NULL) {
        if (line[0] == '#' || line[0] == '\n') continue;
        /* 去掉行尾换行 */
        size_t len = strlen(line);
        while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) line[--len] = 0;
        if (len == 0) continue;
        char *tab1 = strchr(line, '\t');
        if (tab1 == NULL) continue;
        *tab1 = 0;
        char *tab2 = strchr(tab1 + 1, '\t');
        if (tab2 == NULL) continue;
        *tab2 = 0;
        const char *in = line;
        const char *want_norm = tab1 + 1;
        int want_mod = atoi(tab2 + 1);
        n++;
        expect_norm(in, want_norm);
        expect_modifier(in, want_mod);
    }
    fclose(f);
    if (n < 10) {
        failures++;
        printf("FAIL vector file has only %d vectors\n", n);
    } else {
        printf("vectors: %d loaded from %s\n", n, path);
    }
}

int main(void) {
    run_vectors("webserver/jni/rule_vectors.txt");

    /* hosts 格式：取空白之后的那个字段 */
    expect_norm("0.0.0.0 ads.example.com", "ads.example.com");
    expect_norm(":: ads.example.com", "ads.example.com");
    /* 裸域名与大小写 */
    expect_norm("Ads.Example.COM", "ads.example.com");
    /* ABP 风格：前导 || 与结尾 ^ 都被剥掉 */
    expect_norm("||ads.example.com^", "ads.example.com");
    expect_norm("|ads.example.com|", "ads.example.com");
    /* 例外前缀只被当装饰（当前语义：不实现例外） */
    expect_norm("@@||ads.example.com^", "ads.example.com");
    /* scheme 与路径被剥掉 */
    expect_norm("https://ads.example.com/path?q=1", "ads.example.com");
    /* 前导 * 与 . */
    expect_norm("*.ads.example.com", "ads.example.com");
    /* 空与超短输入 */
    expect_norm("", "");
    expect_norm("#comment", "");
    /* 修饰符判据：$ 在 ^ 之后（或没有 ^）才算 */
    expect_modifier("||ads.example.com^", 0);
    expect_modifier("||cdn.example.com^$script", 1);
    expect_modifier("||cdn.example.com$script", 1);
    expect_modifier("||cdn.example.com^$image,third-party", 1);
    expect_modifier("cdn.example.com", 0);
    /* $ 出现在主机名之前的情形不应被当成修饰符 */
    expect_modifier("$$||ads.example.com^", 0);

    printf("%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
