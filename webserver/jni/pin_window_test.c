/*
 * pin 窗口纯逻辑的宿主机测试（G1）。
 * 编译运行：cc -std=c11 -Wall -Wextra -o pin_window_test webserver/jni/pin_window_test.c && ./pin_window_test
 */
#include <stdio.h>
#include <string.h>
#include "pin_window.h"

static int failures = 0, checks = 0;

static void expect_bool(const char *what, bool got, bool want) {
    checks++;
    if (got != want) { failures++; printf("FAIL %s = %d, want %d\n", what, (int)got, (int)want); }
}

static void expect_u32(const char *what, unsigned int got, unsigned int want) {
    checks++;
    if (got != want) { failures++; printf("FAIL %s = %u, want %u\n", what, got, want); }
}

static void expect_u64(const char *what, uint64_t got, uint64_t want) {
    checks++;
    if (got != want) { failures++; printf("FAIL %s = %llu, want %llu\n", what,
        (unsigned long long)got, (unsigned long long)want); }
}

int main(void) {
    /* bypass 命中：uid 相同且 until_ms 严格大于 now */
    struct pin_bypass_entry e[3] = { {1001u, 5000u}, {1002u, 100u}, {1003u, 0u} };
    expect_bool("hit uid=1001 now=1000", pin_bypass_hit(e, 3, 1001u, 1000u), true);
    expect_bool("hit uid=1001 now=4999", pin_bypass_hit(e, 3, 1001u, 4999u), true);
    expect_bool("miss uid=1001 now=5000 (边界：等于不算)", pin_bypass_hit(e, 3, 1001u, 5000u), false);
    expect_bool("miss uid=1001 now=5001", pin_bypass_hit(e, 3, 1001u, 5001u), false);
    expect_bool("miss uid=1002 (已过期)", pin_bypass_hit(e, 3, 1002u, 1000u), false);
    expect_bool("miss uid=9999 (不在表里)", pin_bypass_hit(e, 3, 9999u, 1000u), false);
    expect_bool("miss uid=-1", pin_bypass_hit(e, 3, (unsigned int)-1, 1000u), false);
    expect_bool("空表必然不命中", pin_bypass_hit(e, 0, 1001u, 1000u), false);
    /* 窗口推进：超过窗口才重置（严格大于） */
    uint64_t win = 1000u; unsigned int c = 0;
    c = pin_fail_window_step(&win, c, 1500u, 10000u);
    expect_u32("首次计数", c, 1u);
    expect_u64("窗口起点不变（1500-1000 未超窗）", win, 1000u);
    c = pin_fail_window_step(&win, c, 11001u, 10000u);
    expect_u32("超窗后重置为 1（11001-1000 > 10000）", c, 1u);
    expect_u64("窗口起点已更新", win, 11001u);
    /* 边界：正好等于窗口不算超窗 */
    c = pin_fail_window_step(&win, 5u, 21001u, 10000u);
    expect_u32("正好等于窗口：不重置，计数继续（5+1）", c, 6u);
    expect_u64("窗口起点未变（边界）", win, 11001u);
    c = pin_fail_window_step(&win, 5u, 21002u, 10000u);
    expect_u32("超过窗口一格：重置为 1", c, 1u);
    /* 阈值 */
    expect_bool("未达阈值", pin_fail_window_reached(39u, 40u), false);
    expect_bool("达到阈值", pin_fail_window_reached(40u, 40u), true);
    expect_bool("超过阈值", pin_fail_window_reached(41u, 40u), true);
    expect_bool("阈值 0 时任何计数都算达到", pin_fail_window_reached(1u, 0u), true);
    /* bypass 到期时间 */
    expect_u64("until = now + ms", pin_bypass_until(1000u, 600000u), 601000u);
    expect_u64("ms=0 时 until = now", pin_bypass_until(1234u, 0u), 1234u);

    printf("%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
