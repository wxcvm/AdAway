#!/usr/bin/env python3
"""文档交叉引用守卫（CI 用）。

为什么需要它：本项目里"某个东西在某处"的**手写引用**曾多次指错
（把 CRLF 小节写成 §4 末、把一个不存在的 §4.1 写进表格、把确实存在的 §5.2 误判为悬空）。
规则很简单：**文档里每个 §N / §N.M 引用，都必须能在它所指的文件里找到同编号的标题**。

解析约定（与文档现有写法一致）：
- 同一文件内的引用：在该文件里找 `## N.` 或 `### N.M`；
- 跨文件引用：若引用所在行的上下文出现「契约」或「windows-core-contract」，
  则在 `docs/windows-core-contract.md` 里解析；
- 一行里出现 `§6.1/§6.2` 这种写法时，两段分别解析。
"""
import os
import re
import sys

DOCS = [
    'docs/final-status.md',
    'docs/windows-core-contract.md',
    'docs/response-policies.md',
    'docs/settings-groups.md',
    'docs/device-acceptance.md',
]
CONTRACT = 'docs/windows-core-contract.md'
HEAD = re.compile(r'^#{2,4}\s+(\d+(?:\.\d+)?)\.?\s', re.M)
REF = re.compile(r'§\s*(\d+(?:\.\d+)?)')


def headings(text):
    return {m.group(1) for m in HEAD.finditer(text)}


def main():
    texts = {}
    for f in DOCS:
        if os.path.isfile(f):
            with open(f, encoding='utf-8') as fh:
                texts[f] = fh.read()
    heads = {f: headings(t) for f, t in texts.items()}
    bad = []
    total = 0
    for f, t in texts.items():
        for i, line in enumerate(t.split('\n'), 1):
            for m in REF.finditer(line):
                ref = m.group(1)
                total += 1
                ctx = line[max(0, m.start() - 40):m.end() + 10]
                cross = ('契约' in ctx) or ('windows-core-contract' in line)
                targets = [CONTRACT] if cross else [f]
                if not any(ref in heads.get(x, set()) for x in targets):
                    bad.append((f, i, ref, cross, line.strip()[:100]))
    print('文档引用总数：%d' % total)
    if bad:
        for f, i, ref, cross, line in bad:
            print('::error file=%s,line=%d::§%s 在%s里找不到同编号标题 —— %s' % (
                f, i, ref, '契约' if cross else '本文档', line))
        # 同时给出人可读的清单
        print('悬空引用 %d 处：' % len(bad))
        for f, i, ref, cross, line in bad:
            print('  %s:%d §%s%s' % (f, i, ref, '（跨文件→契约）' if cross else ''))
        return 1
    print('所有 §N/§N.M 引用都能解析到同编号标题 ✓')
    return 0


if __name__ == '__main__':
    sys.exit(main())
