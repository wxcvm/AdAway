# 真机验收单（A4 实测 + G2 回归，一次跑完）

> 用途：把需要**你的手机**才能完成的两项合并成一次运行。**判定规则在运行之前就已写死**（见下表），
> 我不在拿到结果之后再解释。全部命令都是 **Windows cmd 可直接粘贴**的形式（我此前给过 bash 写法，是我的错）。
> 把下面每一步的输出**原样贴回**给我即可；**成功或失败都贴**，我按表判定并如实入账。

## 一、A4-①：证书到底装在哪（四条路径）

```
adb shell su -c "ls -lt /data/misc/user/0/cacerts-added; echo ---APEX---; ls -lt /apex/com.android.conscrypt/cacerts; echo ---APEXDATA---; ls -lt /data/misc/apexdata/com.android.conscrypt/cacerts; echo ---SYSTEM(recent)---; ls -lt /system/etc/security/cacerts | head -5"
```
（若 `su -c` 报 permission denied：去掉 `su -c ` 再跑一次，并把两次输出都贴回。）

**判定表（与 `WebServerUtils.certificateTrustLevel()` 的实现一致，事先定死）**

| 看到什么 | 判定 | 含义 |
|---|---|---|
| `---APEX---` 或 `---APEXDATA---` 段里**有证书文件**（`<hash>.0`） | **系统级**（TRUST_SYSTEM） | 多数 App 会信任；这也是 Conscrypt 真正优先读取的位置 |
| 只有第一段（`cacerts-added`）有证书 | **用户级**（TRUST_USER） | 多数 App **默认不信任** —— 与设置页文案一致 |
| 三段都没有证书 | **未安装**（TRUST_NONE） | 设置页应显示「未安装」 |
| 输出含 `permission denied` / `not found` | **判定降级** | 只能按用户库推断；**不能**据此断言「没有系统级」 |
| `---SYSTEM(recent)---` 里最新的几条日期很近 | 有**挂载覆盖**在生效 | 与内核级证书注入一致（非 App 行为） |

**交叉核对（必须互相印证）**：设置页显示的证书状态应与上表判定**一致**；若不一致，那是**我这边的 bug**，
请把两者都贴回，我来修。

## 二、A4-②：issuer 是不是本机 CA（两条等价命令，二选一）

有 openssl 时：
```
openssl version
openssl s_client -connect 被拦域名:443 -servername 被拦域名 < NUL | openssl x509 -noout -issuer -subject
```
没有 openssl 时（我在你的机器上实测过这条脚本，4 个用例都过）：
```
powershell -NoProfile -ExecutionPolicy Bypass -File "%TEMP%\tls_issuer.ps1" -Domain 被拦域名
```
（`被拦域名` 换成你规则里真实被拦的一个，可从应用内「查询日志」里挑。）

| 结果 | 判定 |
|---|---|
| issuer 是本机 CA（如 `CN=ADBlock Local CA`） | 流量**确实经过本服务** ✓ |
| issuer 仍是公网 CA（Cloudflare / Google Trust / DigiCert 等） | 该应用流量**没走到本服务** —— 如实记录，**不算失败** |
| 连接被拒 / 域名解析失败 | 该域当前未被拦截或服务未运行 —— 换一个域再试，或如实记录 |

## 三、G2 回归四项（每项看一眼即可）

| 项 | 怎么看 | 期望 |
|---|---|---|
| **拦截命中** | 打开被拦域名的页面 | 出现拦截页/占位图；应用内计数增加 |
| **放行 204** | 打开已放行的域名（或 `domain_allowlist.txt` 里的域） | 正常显示；`/internal-stats` 里对应计数增加，**不是**拦截 |
| **查询日志** | 应用内查询日志页 | 能看到刚才那几条请求，含类型与处置 |
| **证书状态** | 设置页证书状态行 | 显示四态之一，且与第一节 `ls` 结果一致 |

## 四、固定失败应用（边界 ② 的验收）

打开一个**做证书固定**的应用，然后看应用内该应用的 TLS 失败计数：

- `tls_failures` / 该应用失败数**上升** ✓；
- 文案必须显示「**（仅拒绝）**」一类表述（例如「…（仅拒绝：该应用不信任本机证书，连接是它自己拒绝的，不是拦截成功）」）✓；
- **不得**出现"成功/已拦截"这类误导性表述 —— 出现即由我修。

## 五、贴回什么

1. 第一节的完整输出（含两次尝试，如果第一次 permission denied）；
2. 设置页**证书状态那一段**的截图（顺带就把 C9 需要的那张截图给我了）；
3. 第二节的 issuer 输出；
4. 第三节四项各一句"看到什么"；
5. 第四节的失败计数与文案。
