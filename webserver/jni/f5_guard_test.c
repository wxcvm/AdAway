/*
 * F5 重试护栏纯逻辑的宿主机测试（G1）。
 * 编译运行：cc -std=c11 -Wall -Wextra -o f5_guard_test webserver/jni/f5_guard_test.c && ./f5_guard_test
 */
#include <stdio.h>
#include <string.h>
#include "f5_guard.h"

static int failures = 0, checks = 0;
static void ok_bool(const char *what, bool got, bool want) {
    checks++;
    if (got != want) { failures++; printf("FAIL %s = %d, want %d\n", what, (int)got, (int)want); }
}
static void ok_u32(const char *what, unsigned int got, unsigned int want) {
    checks++;
    if (got != want) { failures++; printf("FAIL %s = %u, want %u\n", what, got, want); }
}
static void ok_u64(const char *what, uint64_t got, uint64_t want) {
    checks++;
    if (got != want) { failures++; printf("FAIL %s = %llu, want %llu\n", what,
        (unsigned long long)got, (unsigned long long)want); }
}

int main(void) {
    const struct f5_guard_cfg cfg = { 10000u, 3u, 30000u };
    struct f5_guard_entry g[2];
    memset(g, 0, sizeof(g));

    /* 新条目：count=1、until=0、win_start=now，且本请求不快速失败 */
    ok_bool("首次请求不快速失败", f5_guard_step(g, 2, 1001u, 77u, 1000u, &cfg, NULL), false);
    ok_u32("新条目 count", g[0].count, 1u);
    ok_u32("新条目 uid", g[0].uid, 1001u);
    ok_u32("新条目 host_hash", g[0].host_hash, 77u);
    ok_u64("新条目 win_start", g[0].win_start, 1000u);
    ok_u64("新条目 until_ms", g[0].until_ms, 0u);

    /* 同键第 2、3 次：未到阈值不设冷却 */
    ok_bool("第 2 次不快速失败", f5_guard_step(g, 2, 1001u, 77u, 1100u, &cfg, NULL), false);
    ok_u32("第 2 次 count", g[0].count, 2u);
    bool tripped = false;
    ok_bool("第 3 次不快速失败（本次刚好触发）", f5_guard_step(g, 2, 1001u, 77u, 1200u, &cfg, &tripped), false);
    ok_u32("第 3 次 count", g[0].count, 3u);
    ok_bool("第 3 次标记为刚好触发", tripped, true);
    ok_u64("冷却设为 now+cooldown", g[0].until_ms, 1200u + 30000u);

    /* 冷却中：直接快速失败，且不再改动条目 */
    ok_bool("冷却中快速失败", f5_guard_step(g, 2, 1001u, 77u, 1300u, &cfg, NULL), false == false);
    ok_bool("冷却中（第 2 次核对）", f5_guard_step(g, 2, 1001u, 77u, 31000u, &cfg, NULL), false == false);
    ok_u32("冷却中不改计数", g[0].count, 3u);
    ok_bool("冷却到期后不再快速失败", f5_guard_step(g, 2, 1001u, 77u, 31301u, &cfg, NULL), false);

    /* 窗口重置：超过 window 才重置 */
    struct f5_guard_entry h[1];
    memset(h, 0, sizeof(h));
    f5_guard_step(h, 1, 5u, 9u, 1000u, &cfg, NULL);
    f5_guard_step(h, 1, 5u, 9u, 2000u, &cfg, NULL);
    ok_u32("窗口内累加到 2", h[0].count, 2u);
    f5_guard_step(h, 1, 5u, 9u, 11001u, &cfg, NULL);
    ok_u32("超窗后重置为 1（11001-1000 > 10000）", h[0].count, 1u);
    ok_u64("窗口起点更新", h[0].win_start, 11001u);
    /* 边界：正好等于窗口不重置 */
    f5_guard_step(h, 1, 5u, 9u, 21001u, &cfg, NULL);
    ok_u32("正好等于窗口：不重置，继续累加", h[0].count, 2u);

    /* 不同键互不影响 */
    struct f5_guard_entry k[2];
    memset(k, 0, sizeof(k));
    f5_guard_step(k, 2, 1u, 10u, 100u, &cfg, NULL);
    f5_guard_step(k, 2, 2u, 20u, 100u, &cfg, NULL);
    ok_u32("两个键各占一个槽位", (unsigned)(k[0].win_start != 0) + (unsigned)(k[1].win_start != 0), 2u);

    /* 同一 uid、不同 host：算不同键 */
    struct f5_guard_entry m[2];
    memset(m, 0, sizeof(m));
    f5_guard_step(m, 2, 7u, 1u, 100u, &cfg, NULL);
    ok_bool("同 uid 不同 host 是新键", f5_guard_step(m, 2, 7u, 2u, 100u, &cfg, NULL), false);
    ok_u32("占用了第二个槽", (unsigned)(m[1].win_start != 0), 1u);

    /* 有界：满表时覆盖 win_start 最小的（最久未更新） */
    struct f5_guard_entry b[2];
    memset(b, 0, sizeof(b));
    f5_guard_step(b, 2, 1u, 1u, 500u, &cfg, NULL);   /* 槽0 win_start=500 */
    f5_guard_step(b, 2, 2u, 2u, 900u, &cfg, NULL);   /* 槽1 win_start=900 */
    f5_guard_step(b, 2, 3u, 3u, 1000u, &cfg, NULL);  /* 满表 → 覆盖最旧的槽0 */
    ok_u32("覆盖最旧：槽0 换成新键", b[0].uid, 3u);
    ok_u32("覆盖最旧：槽1 未动", b[1].uid, 2u);
    ok_u64("被覆盖的条目 win_start 更新", b[0].win_start, 1000u);

    printf("%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
