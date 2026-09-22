# JEV 决策层：结构与"成熟"的判定标准

这份文件定义 JEV 决策层现在长什么样、每个决策点处于什么状态、以及**什么叫做成熟**（因为"无限 reload 直到
成熟"需要一个可判定的终点，而不是感觉）。

## 架构

```
任何输入/触发
      │
      ▼
 AgentBrain.startDecision()          ← 唯一的决策入口
      │  按 Trigger 分派
      ├── CHAT      → JEV: SPEAK / STAY_SILENT        （可拦截：省下一整轮规划）
      ├── IDLE      → JEV: CONTINUE / ESCALATE_LLM    （可拦截：省下一整轮规划）
      ├── RESUME    → JEV: CONTINUE / ESCALATE_LLM
      ├── COMMAND   → 直接规划模型（显式指令不二次猜测，只记一行 bypass 日志）
      └── STUCK     → 直接规划模型（看门狗判定的卡死同理）
      │
      ├── 挖矿失败  → JEV: RETRY / SKIP / BACKTRACK / GATHER_PERCEPTION / ESCALATE_LLM
      │                （白名单 + 每目标一次重试 + 过期丢弃）
      ▼
 规划模型（12k token 一轮）—— 所有不确定路径的兜底
```

两条铁律：

1. **fail-open**：JEV 超时、报错、熔断、低置信度、答案不在候选里 —— 一律回退规划模型，绝不让 bot
   因为便宜层出问题而停摆或误动作。
2. **确定性优先**：能用规则判准的（逐字重复的指令、被拒绝破坏的方块、只有唯一合法候选）不花模型。

## 各决策点状态

| 决策点 | 模式 | 阈值 | 标定证据 | 测试覆盖 | 生产证据 | 成熟? |
|---|---|---|---|---|---|---|---|
| CHAT 说话闸门 | **active** | 沉默方向无阈值；点名/问题先绕过 | 9 例：该沉默 0.89–0.99 / 该回答 0.47–0.77；生产低置信沉默样本用于修正规则 | SPEECHTEST 8 阶段（抑制/问句绕过/shadow/复读/熔断/chat 协议） | 低置信后台聊天也沉默；玩家问题确定性直达规划模型 | ✅ |
| MINING_RECOVERY | **active** | 0.6 | 8 例合成：0.50–0.85（中位 0.64）；**8 例真实失败**：SKIP×4/RETRY×3/GATHER×1，0.27–0.81，阈值下 4 动 4 弃 | JEVMINETEST（shadow/active retry/一次上限/低置信/未提供选项/过期/并发落地/持久 SKIP）；System One 与 chat 同样做候选白名单校验 | 8 条真失败样本，选择全部合理 | ✅ |
| ROUTING（IDLE/RESUME） | **active** | 0.85 | 6 例：CONTINUE 0.95–1.00 / ESCALATE 1.00（改提示词前 3/6，CONTINUE 永远达不到门槛）；**16 条真实样本重放**：15 CONTINUE（中位 1.00） | ROUTINGTEST 7 阶段（空队列不问/COMMAND 与 STUCK 绕过/shadow 只记/active CONTINUE 省一轮/ESCALATE 照常/routing=off 对照） | 翻 active 后规划轮次 6.8/min → 4.0/min（**约 -40%**），34 次 CONTINUE 生效，bot 仍在挖矿/重规划，watchdog 0 次 | ✅ |

**当前回归**：`JEV_MINE` / `ROUTING` / `SPEECH` / `CHAT` / `CHAT_INVOKE` / `STRUCTURE` / `MINEDROP` 七个 harness 全绿。

**三个决策点全部 active**（`mode=active speech_gate=active routing=active`）；另外两个触发（COMMAND / STUCK）按设计不经过廉价层，各留一行 bypass 日志。回归 **7/7 全绿**（JEV_MINE / ROUTING / SPEECH / CHAT / CHAT_INVOKE / STRUCTURE / MINEDROP）。

进入规划模型的每一条路径都已核对：要么经过某个 JEV 决策（有日志），要么是有理由的 bypass（有日志），没有第三条。

## 置信度是有噪声的（第二次真实教训）

同一批真实状态重放时，置信度会上下浮动 ±0.2~0.3（同一条失败样本一次 0.67、一次 0.33）。所以：阈值要留余量、不要贴着观测带边缘设；阈值附近的用例**两种结果都可接受**时才算标定合格（挖矿恢复正是如此：0.54 弃权、0.61 动手，两者都合理）；单次标定 n 很小，结论只能是"方向可信"。

## 成熟判定（全部满足才算）

1. **覆盖**：所有触发都有归属 —— 要么过 JEV，要么有明确的 bypass 理由并留下日志。
2. **标定**：每个决策点都有一组真实状态打过真模型，记录 choice 分布与置信度区间，阈值由区间决定
   （而不是拍脑袋），并且**该动作与不该动作的区间能分开**。
3. **失败路径**：每个决策点的超时/报错/低置信度/未知选项四条路径都有测试断言（fail-open）。
4. **可观测**：每次决策一行日志（含 event/choice/confidence/applied），且 shadow 会写出 `state=`，
   使样本可用 `tools/jev-replay` 离线重放。
5. **回归**：`MCAGENT_SPEECH_TEST` / `MCAGENT_ROUTING_TEST` / `MCAGENT_CHAT_TEST` /
   `MCAGENT_CHAT_INVOKE_TEST` / `MCAGENT_STRUCTURE_TEST` / `MCAGENT_MINEDROP_TEST` 全绿。
6. **生产证据**：每个 active 决策点都有生产样本（≥30 条）与回看结论；shadow 决策点在样本足够前不转
   active。

## 提示词也是被测对象（一次真实教训）

路由层的第一个提示词是"当 current_action 已覆盖目标时选 CONTINUE"。打真实 jev 的结果是：**所有该继续的场景它都答 ESCALATE_LLM，置信度只有 0.07–0.18** —— 也就是说 `routing=active` 会一次都不生效，白拿一份配额却不省任何 token。把提示词改成对状态字段的**有序判定过程**后，同一批用例变成 CONTINUE 0.95–1.00 / ESCALATE 1.00，阈值 0.85 能干净分开。

结论写进纪律：**提示词与阈值必须一起标定**，并且标定用的状态串必须与运行时逐字一致（`JevPrompts` 单一来源 + `tools/jev-replay`）。

## 生产是唯一能发现某些 bug 的地方（第三次真实教训）

挖矿恢复转 active 后，生产立刻给出：

```
JEV ACTIVE … event=MINING_RECOVERY choice=SKIP_TARGET confidence=0.830
JEV ACTIVE … not_applied=newer_work mineJob=false combat=false moving=false queue=0 thinking=true
```

四个守卫里唯一挡住它的是 `thinking=true` —— job 一结束 bot 立刻开始 10–15 秒的规划，而答案 1 秒就回来，
于是**每次都被当成"更新的工作"丢掉，这个决策点在 active 下永远不可能生效**。harness 看不到它，因为
harness 的模型是瞬间返回的。

修法不是放宽守卫（那会让廉价层和模型的决定打架），而是**让廉价层先落地**：恢复请求在飞时，普通
（IDLE）决策让出这一秒（有上限、聊天除外）。回归测试也随之改成能复现：脚本模型慢 2.5 s、JEV stub 慢
2.5 s、并且**等到 bot 即将规划的那一刻**才报告失败 —— 关掉修复它会 FAIL，打开会 PASS。

## 迭代回路

```
改代码 → gradle build deploySmokeServer → 隔离服 harness（含反向断言）
      → 热部署生产（tools/ 里的 attach 通道，reload + spawn）
      → 读生产日志 → tools/jev-replay 重放 shadow 样本 → 定阈值
      → 转 active → 继续采样回看
```

判定"成熟"的证据一律以日志原文为准，不以"代码看起来对"为准。
