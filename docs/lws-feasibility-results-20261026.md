# libwebsockets 双端可行性取证结果（2026-10-26）

## 结论

**不建议当前适配方案迁入生产（NO-GO）。** Windows 公共 HTTP 挂载转发在正常构建与诊断构建中重复截断正文，未满足最基本的完整响应与生命周期门槛。这不是宣称所有 LWS 公共客户端/事件后端设计都不可行，也不是把宿主机结果当成 Android17 真机结论。

性能判断单独为 **证据不足（INCONCLUSIVE）**：完整五轮数据已经采集，不能因为基准脚本成功或零负载错误，就声称性能门槛通过。生产核心、应用版本/安装身份、稳定更新源均未改变；没有发布应用、安装真机或修改主机网络/证书。

## 源码、依赖与隔离

| 项目 | 实际冻结源码 | 说明 |
|---|---|---|
| Android 验证/共享源码 | `d710dd89ff9e77204ec5815394a8757fba033ce4` | 独立 `spike/lws-feasibility-v1`，不含原 UI 分支未提交改动 |
| Windows 消费者 | `f1f0e116d57c00ea1f73dd94040cd2be69a25f85` | 同名独立分支；逐文件固定 Android 来源，不改生产 portable core |
| Linux 测量源码 | `89e467120e778fb866576b78758bb3dc1ceaeea0` | 与冻结源码的服务端 C/头文件、CMake 和依赖锁逐项相同；后续只有探针、诊断、报告变化 |
| Windows 生产 core | `4470606366e614abdb308fdfe94734d28167439c` | 保持原固定版本和覆盖层 |

- LWS：`a842936f881921b6462b3328cce8e700b7a7ecd2`；OpenSSL：3.5.9 / `45e844fa2a14ec92d146bd8f5778ac130b6625fb`。
- Mongoose/图片 fixture 固定 Android `bfc8c7a3f5b11de8e738f3cfec09bb3d3c44ded8`。Windows 原固定 core 的 Mongoose C 文件摘要与该 fixture 一致：`651d514ee52761c26af774d74e91346bf150c915120d967ec4332a69ea1600f5`。
- 启用 HTTP1.1、TLS/OpenSSL、WebSocket、所需原始连接角色及受控本机 HTTP 挂载；单事件线程。关闭 H2/H3/QUIC、SChannel、外部 HTTP 代理继承、插件/附带应用及无关组件。实际解析选项保存在 `build-evidence.json`。
- 两后端共用处理器、P-256 证书/密钥生成、256 项证书缓存、128 入站连接与64转发上限、16KiB 输入限额和4KiB普通响应发送块。测试不通过增加容量掩盖错误。
- LWS 主许可证及组件通知见[固定提交 LICENSE](https://github.com/warmcat/libwebsockets/blob/a842936f881921b6462b3328cce8e700b7a7ecd2/LICENSE)。这里没有改变应用整体许可，也没有移除生产 Mongoose 及其 JSON/HTTP 工具依赖。

原 Android `fix/ui-observations-and-response-v1` 与 Windows `fix/export-dialog-lifecycle-v1` 工作区未混入本分支。源码等价校验保存在 [server-input-equivalence.json](lws-feasibility-results-20261026/server-input-equivalence.json)。

## 可执行正确性结果

| 平台/项目 | 结果 | 边界 |
|---|---|---|
| Linux LWS / Mongoose 实际原型 | 各24组通过 | 包括双栈/通配/独立绑定、状态/MIME/正文哈希、keep-alive、WS、动态 SNI、淘汰/过期/在途引用、端口冲突、输入限额、取消和64转发恢复 |
| Linux ASan / UBSan | 两后端通过 | 原型与相关 LWS/Mongoose 源码；OpenSSL 使用固定的未插桩静态前缀，不声称所有 crypto 内部也经插桩 |
| Android arm64-v8a / x86_64 / riscv64 | 全部构建通过 | ELF/构建选项有附件；ARM64和RISC-V没有运行环境，仅构建结论 |
| Android API30 / API35 x86_64 | 每个模拟器、每个后端18项真实原生检查通过 | 真正的 IPv4/IPv6 套接字；严格 TLS 链/名称与完整正文、图片哈希；不是 ADB 转发的伪 IPv6，不是完整宿主机 Python 套件或 Android17 真机 |
| Windows Mongoose | 24组完整正确性通过 | 使用生产一致的 `MG_ENABLE_POLL=1`，不再误用64项select上限 |
| Windows LWS | 前20组通过，第一条上游转发失败 | 后续完整上游 TLS/取消/转发饱和不计通过 |
| Windows 两后端原生客户端 | 各18项通过，损坏图片负例被拒绝 | 仅证明基础响应/双栈/严格TLS/正文；不抵销转发错误 |
| Windows 基础HTTP向量对照 | 10条字段逐项相同 | 动作状态、类型、声明/实际长度、ETag、正文哈希；明确 `source_runs_complete:false`，不冒充完整适配验收 |

当前 Android CI：[40256069922](https://github.com/wxcvm/AdAway/actions/runs/40256069922) 成功。当前 Windows 采集：[40256075862](https://github.com/wxcvm/AdAway-Windows/actions/runs/40256075862) **失败**；性能采集成功没有改变所需正确性步骤的失败结论。早先 Linux 全生产源码参考与插桩证据也可见 [40253250345](https://github.com/wxcvm/AdAway/actions/runs/40253250345)。

动态证书使用 LWS 公开 SSL_CTX 回调与 OpenSSL 的 per-SSL 证书回调，所有 TLS vhost 安装相同钩子。没有替换 LWS 的 SNI 处理器、读取其私有结构或维护上游补丁。验证失败没有通过禁用链/名称校验或改成 localhost 规避。

## Windows 阻断的字节证据

第一次失败是 IPv4 **明文 HTTP** `/forward/text`，不能解释为 HTTPS、证书固定或 CA 信任问题。正常及详细诊断重现见 [40253927417](https://github.com/wxcvm/AdAway-Windows/actions/runs/40253927417)、[40254812604](https://github.com/wxcvm/AdAway-Windows/actions/runs/40254812604) 和最终采集。

- 声明长度4096；客户端读到4018，HTTP 客户端报告仍少78字节。
- 完整已读数据的 SHA-256 为 `dc3a7abd84046b4c4bca9059c6ec5580da546aaa244b973f1ae57ceea69853dd`。
- 该摘要与 **4013个 `x` 加 `0\r\n\r\n`** 完全一致；因此真正正文缺83字节，另附5字节不适用于此 Content-Length 响应的结束标记。
- 日志先记录4013字节缓冲，随后 `close_and_handled`、关闭路径的 `COMPLETED_CLIENT_HTTP`、下游 `PROXY_TRANS_END` 与连接关闭。
- 以上证明现象和关闭/完成路径有问题，但没有把根因武断地全部归于库或适配器。后续独立公共客户端/事件后端方案未验证，不是本轮生产迁移批准。

证据保存在 [windows-forward-failure.json](lws-feasibility-results-20261026/windows-forward-failure.json)。没有提高缓冲/连接上限、填充缺失字节、吞掉 `IncompleteRead` 或改写 LWS 私有标志。当前两个 PR 保持草稿，不因“多数测试正常”自动合并。

## 配对性能原始数据

所有正式数据：同机顺序交替五轮，每次预热2秒、测量5秒，固定1/16/64并发；204、4KiB文本、现有WebP、HTTPS长连接、首次新域名证书握手；日志关闭/相同测试日志开启分别测量。每个平台300阶段，合计600。不是 quick 模式。Windows 测量前明确重建关闭诊断日志的版本，保存独立构建证据；诊断版本不进入数据。

| 平台 | 阶段 | 负载错误 | 波动超10%的分组 | 性能判定 |
|---|---:|---:|---:|---|
| linux | 300 | 0 | 9/30 | INCONCLUSIVE |
| windows | 300 | 0 | 25/30 | INCONCLUSIVE |

波动定义为五轮同机配对比率的 `(max-min)/median`。当前没有通过整体稳定性与15%退化准入；零负载错误不能抹掉未纳入负载工作集的转发失败，也不能外推 Android 真机性能/耗电。P50/P95/P99、吞吐、CPU/请求、空闲/峰值RSS、线程、句柄/FD、二进制体积及固定容量直方图均在原始数据中。

下表是**关闭测试日志、16并发**的五轮配对中位比率（LWS/Mongoose）。只是数据示例，不是挑选少数有利结果判通过；全部30组在CSV中。

### linux 观察值

| 工作负载 | P95比率 | CPU/请求比率 | 峰值RSS比率 |
|---|---:|---:|---:|
| empty | 1.062 | 1.541 | 1.049 |
| text | 0.923 | 1.881 | 1.041 |
| image | 1.197 | 1.350 | 1.088 |
| https-keep | 1.150 | 1.885 | 0.923 |
| tls-cold | 1.197 | 0.775 | 1.090 |

### windows 观察值

| 工作负载 | P95比率 | CPU/请求比率 | 峰值RSS比率 |
|---|---:|---:|---:|
| empty | 1.041 | 1.128 | 1.204 |
| text | 1.020 | 1.294 | 1.202 |
| image | 1.553 | 1.577 | 1.204 |
| https-keep | 1.105 | 1.304 | 1.068 |
| tls-cold | 0.980 | 0.857 | 1.104 |

## 独立生产源码参考

这不是用精简 LWS 对开启全部业务的生产服务做快慢比。完整生产源码/JNI树保持 `e0c8408c6369c01e28069fdc073ec6fefd378681`，仅提供 Linux 宿主 Android-log API shim；不是 Android 设备二进制。普通 `/` 的 IPv4/IPv6 HTTP/严格HTTPS均返回200、`image/webp`、109828字节，SHA-256为固定图片摘要。

单客户端、2秒预热/5秒测量的一次独立参考记录：HTTP约4691请求/秒，P95约0.229ms；HTTPS约4628请求/秒，P95约0.249ms，均0负载错误。这些绝对值只描述那台CI机器和该生产配置，不与其他job/精简fixture算迁移收益。原始参考也归档在能力证据包中。

## 可复现附件与长期保留

为了避免仅依赖 Actions 临时保留期，把原始JSON（含全部直方图/计数/元数据）逐字节gzip归档入仓库，压缩 mtime固定为0；这里没有归档基准exe、安装包、CA私钥或真实用户日志。全部归档和摘要共约281KB（274KiB）。

| 数据 | 公开CI附件 | 仓库归档 |
|---|---|---|
| linux | [Actions](https://github.com/wxcvm/AdAway/actions/runs/40254451625/artifacts/12622244559) | [raw-linux.json.gz](lws-feasibility-results-20261026/raw-linux.json.gz) / [全组比率CSV](lws-feasibility-results-20261026/paired-ratios-linux.csv) |
| windows | [Actions](https://github.com/wxcvm/AdAway-Windows/actions/runs/40256075862/artifacts/12624232063) | [raw-windows.json.gz](lws-feasibility-results-20261026/raw-windows.json.gz) / [全组比率CSV](lws-feasibility-results-20261026/paired-ratios-windows.csv) |

- [机器摘要与原始数据SHA-256](lws-feasibility-results-20261026/summary.json)
- [18份真实能力结果原文及逐文件SHA-256](lws-feasibility-results-20261026/capability-evidence.json.gz)
- [测量服务端输入等价证明](lws-feasibility-results-20261026/server-input-equivalence.json)

可在安装所需工具链的独立CI环境重跑（不是应用发布工作流）：

```sh
gh workflow run lws-feasibility.yml --repo wxcvm/AdAway --ref spike/lws-feasibility-v1
gh workflow run lws-feasibility.yml --repo wxcvm/AdAway-Windows --ref spike/lws-feasibility-v1
```

## 能力差距、审阅与后续边界

- 迁移清单列出 Android 29个、Windows 15个不同 `mg_*` 名称的词法调用依赖，含局部wrapper/测试，不冒充完整调用图。Windows GUI/qlog/更新器仍使用 Mongoose JSON/HTTP解析；只替换监听器不能移除整个依赖或解决全部许可组合问题。详见两端 `docs/lws-migration-inventory.json`。
- 初始迁移工作量估计17–30工程日，包含传输、工具API替换、qlog/统计契约、Root/MinGW整合、安装恢复和实机验收；不是完成承诺。当前不自动启动这项生产迁移。
- DeepSeek Flash 参与了有界只读审阅；接受的发现需经源码/可执行测试复核。关于已读完整正文、CA验证被移除等未被证实的模型判断没有作为结论。
- 保留动态证书公开回调方案；不加入私有源码补丁，不自动改投其他HTTP库。若继续实验，应另做公共客户端或事件后端适配，并先跑本次截断、分段/半关闭、背压与取消向量，再重复基准。
- Android17真机未测，ARM64/RISC-V仅构建；没有应用UI、完整生产qlog/累计统计、Windows安装更新或网络接管迁移验收。本轮没有DNS全局接管、QUIC/HTTP3或正式/预览发布。

**交付的是可复现验证工程、原始数据与当前方案的否决证据；不等于双端生产迁移已完成。所需 Windows 正确性未通过，两个草稿PR不合并。**
