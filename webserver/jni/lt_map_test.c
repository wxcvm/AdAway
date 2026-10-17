/*
 * 请求类型映射的宿主机测试（G1 / D 项）。
 * cc -std=c11 -Wall -Wextra -o lt_map_test webserver/jni/lt_map_test.c && ./lt_map_test
 */
#include <stdio.h>
#include <string.h>
#include "lt_map.h"

static int failures = 0, checks = 0;
static void ok(const char *what, bool got, bool want) {
    checks++;
    if (got != want) { failures++; printf("FAIL %s = %d, want %d\n", what, (int)got, (int)want); }
}
#define IMG(u) lt_is_image(u, strlen(u))
#define SCR(u) lt_is_script(u, strlen(u))
#define STY(u) lt_is_style(u, strlen(u))
#define FNT(u) lt_is_font(u, strlen(u))
#define MED(u) lt_is_media(u, strlen(u))

/* 每条链都必须覆盖清单里的全部后缀 —— 抽出来就是为了钉住这个 */
static const char *kImages[] = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif", ".svg", ".ico", ".bmp"};
static const char *kScripts[] = {".js", ".mjs"};
static const char *kStyles[] = {".css"};
static const char *kFonts[] = {".woff", ".woff2", ".ttf", ".otf", ".eot"};
static const char *kMedia[] = {".mp4", ".webm", ".m4a", ".m4v", ".mp3", ".aac", ".ogg", ".oga",
                               ".opus", ".flac", ".ts", ".m3u8", ".mpd", ".flv", ".mov", ".wav"};

int main(void) {
    char url[256];
    for (size_t i = 0; i < sizeof(kImages) / sizeof(kImages[0]); i++) {
        snprintf(url, sizeof(url), "/assets/pic%s", kImages[i]);
        ok(kImages[i], IMG(url), true);
        ok(kImages[i], SCR(url), false);
        ok(kImages[i], STY(url), false);
    }
    for (size_t i = 0; i < sizeof(kScripts) / sizeof(kScripts[0]); i++) {
        snprintf(url, sizeof(url), "/static/app%s", kScripts[i]);
        ok(kScripts[i], SCR(url), true);
        ok(kScripts[i], IMG(url), false);
    }
    for (size_t i = 0; i < sizeof(kStyles) / sizeof(kStyles[0]); i++) {
        snprintf(url, sizeof(url), "/static/site%s", kStyles[i]);
        ok(kStyles[i], STY(url), true);
    }
    for (size_t i = 0; i < sizeof(kFonts) / sizeof(kFonts[0]); i++) {
        snprintf(url, sizeof(url), "/f/x%s", kFonts[i]);
        ok(kFonts[i], FNT(url), true);
        ok(kFonts[i], MED(url), false);
    }
    for (size_t i = 0; i < sizeof(kMedia) / sizeof(kMedia[0]); i++) {
        snprintf(url, sizeof(url), "/v/clip%s", kMedia[i]);
        ok(kMedia[i], MED(url), true);
        ok(kMedia[i], IMG(url), false);
        ok(kMedia[i], SCR(url), false);
        ok(kMedia[i], STY(url), false);
    }

    ok("大写扩展名", IMG("/A/PIC.PNG"), true);
    ok("混合大小写", MED("/v/CLIP.Mp4"), true);
    ok("后缀本身大写", STY("/s/SITE.CSS"), true);

    /* 已知语义：比较对象是完整 URI（含查询串），带参数的资源不匹配 —— 与抽出前一致 */
    ok("带查询串不匹配（保持原语义）", IMG("/a/pic.png?v=1"), false);
    ok("带片段不匹配（保持原语义）", IMG("/a/pic.png#f"), false);

    ok("不匹配合法后缀的延长", IMG("/a/pic.jpgx"), false);
    ok("不匹配 .tsx", MED("/a/x.tsx"), false);
    ok("不匹配前缀相似", IMG("/a/picjpg"), false);

    ok("空串", IMG(""), false);
    ok("只有扩展名", IMG(".png"), true);
    ok("短于后缀", IMG("png"), false);
    ok("空后缀永假", lt_ends_with_ci("abc", 3, ""), false);
    ok("目录名含扩展名但不结尾", IMG("/a.png/b"), false);

    printf("%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
