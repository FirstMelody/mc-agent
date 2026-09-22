# MC Agent — 进度与验证记录

## 2026-09-22（晚上）：把"降低 LLM 调用"从口号变成可测量的数字

### 先量化：到底降没降

生产 16:09–19:24（3 小时 15 分）精确统计：真实规划调用 **422** 次、路由拦下并生效
**407** 次、路由"无事可续"直接放行 **412** 次。反事实口径是 422 vs 约 829 —— **约 2 倍**。
但绝对值看不出降幅，因为**一半的调用来自一个路由层故意不碰的格子**：计划只有 1–3 步 →
队列一秒排空 → 空闲 → 买一轮 12k token 的规划。生产原文（16:59）就是每 4–7 秒一次 12k，
其中几次 `cache=0`。全天 3242 次调用的 prompt 中位数 **12073 token**。

### 三条优化，都落地了

1. **计划要长**：`plan` 工具描述与系统提示都改成"把已知的整段差事一次排完；计划一空就又要点一次
   规划，一步计划等于每个动作一次调用"。模型此前只用掉允许 24 步里的 1–3 步。
2. **空闲格交给 runtime**：`startIdleContinuation` —— 空闲、且**运维通过 `/mcagent goal` 设的目标
   读起来是挖矿类**时，runtime 自己起一趟矿程（90 秒冷却、背包要有空位、只认挖矿这一类技能），
   不问模型。生产实测 `idle_continuation=mining` 已生效。
3. **standing goal 现在会持久化**：以前它只活在 pinned 提示里，一次热更新就丢——三次部署把运维刚
   设的目标冲掉了三次，`idle_continuation` 因此一直不触发。现在存进 bot memory 的 system 值，
   重载后恢复并写日志。

### 现在能直接读到"省了多少"

新增每 5 分钟一行：

```
JEV STATS bot=Agent window=5m calls=8 intercepted=0 idle_continuation=3 idle_skipped=8
          escalated=0 avoided=3 avoided_percent=15
```

`avoided = intercepted + idle_continuation`，即"本会发生的调用里没发生的次数"；这是第一条把
"便宜层到底省了多少"写成可减的日志行。

### 挖矿恢复层：守卫修好了

生产 40 分钟内 67 次 `MINING_RECOVERY` 判定里 66 次被 `not_applied=newer_work` 丢掉，全部是
`mineJob=true` —— 判定要求"过期就别动手"的守卫用的是 `this.mineJob != null`，而**失败的正是那个
还在跑的 job**（MineJob 报一次失败后常继续换目标/回收掉落）。改成身份比较
（`this.mineJob != failedJob` 才算更新的工作），`JEVMINETEST` 全绿。

### 今天第二次"玩家说话 bot 不理"：这次是听不见

玩家答 bot 的问题说"转中文"，bot 一个字没回。日志证据：那一刻**既没有 CHAT 触发也没有
SPEECH_GATE 判定**，说明消息根本没进 bot 的收件箱。原因是 `ChatLog` 只在 64 格内**记录**：
bot 一边做杂活一边走远，玩家的回答落在耳语范围外。

两条修改：**记录不再按距离过滤**（距离只用来判断"是不是在对你说"），以及**服务器上只有一个 bot
时，它就是被搭话的对象，不论多远**（多个 bot 时仍然要求点名或近身）。同时把"要求换语言"这一类
消息从静音闸门里排除——闸门把它当闲聊，而玩家只是在纠正说话方式。`CHATINVOKETEST` 新增第三阶段
（把说话者传送到 200 格外再说"转中文"）与反向对照 `MCAGENT_CHAT_FAR=off`：规则开 → 听到且被点名
（PASS），规则关 → 听到但不被点名（CONTROL-PASS，正是生产丢消息的方式）。对照还顺手抓出我自己
写错的断言（用的是历史里上一阶段的横幅），改成只认这一条消息的横幅。

另外按用户要求把**默认语言钉成简体中文**：系统提示里明确"默认说中文，玩家要求换语言（转中文/
说中文/speak Chinese）必须立刻照办并记住"。

## 2026-09-22（下午四）：持久挖矿技能、一次规划一次矿程 + 三处生产事故

### 1. `start_mining`：一次理解意图，runtime 接管整趟矿程

模型不再需要为"去挖矿"生成逐格 `mine`/`dig_tunnel`/`escape_up` 队列。新工具 `start_mining`
启动一个 runtime 自己的长期目标（优先级资源列表、可选数量、可选隧道段数上限），之后每 tick
由 runtime 选择目标、复用那一条矿道、盯背包/血量/饥饿，并在结束时回基地——**期间不再买第二次
规划调用**。

`MINEGOALTEST`（`MCAGENT_MINING_GOAL_TEST=true`）在隔离服上给出正反两组数字：一次调用、
3 秒、6 块铁矿石进包、目标自行完成并返回；反向对照 `MCAGENT_MINING_GOAL=off` 时同样的场景
90 秒内**一次矿程都没启动**、矿石一块没少、却烧掉 **31 次**规划请求。这正是这个技能存在的理由。

第二个阶段验证中断契约：`interrupt` 取消 runtime 目标后，目标消失且**没有**留下"完成"记录——
"被取消"和"已完成"必须能分开。顺带修掉一个真 bug：`interrupt` 自己的回答原本到不了模型
（`abandonPlan` 把它这一格的返回值覆盖成通用的 `cancelled: ...`），并且同一轮里排在 `interrupt`
**之后**的步骤仍然会执行。现在被执行中的那一步保留自己的返回槽，其余步骤被如实取消，后面的
步骤不再运行。

### 2. 生产事故一：一个 schema 提示词让每一次规划调用都 503

`LlmClient.schema` 用第一个冒号切分"类型: 说明"。`"array of strings: 其余矿石"` 于是被原样
写成 `"type": "array of strings"`——不是合法 JSON Schema 类型，provider 对**每一个**请求回
`11129 invalid function call parameters`（外层 503 `no_healthy_account`）。开发服看不出来：
脚本模型不读 tool schema。修法两条：`start_mining` 改成手写 schema（数组带 `items`），以及
`LlmClient` 增加类型守卫——未知类型降级成 `string` 并告警，而不是把整个 bot 打死。
`MINEGOALTEST` 现在会检查真实请求里每个参数的 type 是否合法。

### 3. 生产事故二：热更新只合并、不替换，补全永远停在 9 月 20 日

`/mcagent pause <TAB>` 一直没有补全活跃 bot。服务端 `addChild` 是**合并**语义：同名节点已存在时
保留旧节点、只覆盖 executor 并合并它的子节点。生产 dispatcher 里 `pause` 的 argument 节点是
9 月 20 日注册的（那时还没有 `.suggests`），所以今天加的补全永远看不到。证据是备份 jar 本身：
`mcagent-runtime.jar.bak.20260920-*` 的 `BotCommands` 里 `suggests` 引用数为 0，今天的为 2。

修法是注册时先把本代**自己要注册的那些子命令节点**从活动 dispatcher 上摘掉，再注册新树——核心
模块的 `runtime`/`reload` 保留。新增 `/mcagent commands` 直接读活动 dispatcher 报告补全状态，
现在 10 个 bot 名命令全部 `[ok]`。

### 4. 生产事故三：`cp` 覆盖运行中的 jar，把当前这一代类加载器读坏

`cp` 覆盖同一个 inode，而正在运行的那一代 classloader 仍握着这个 jar：它之后每加载一个尚未加载
的类都会 `ZipException: ZipFile invalid LOC header` / `NoClassDefFoundError`。`deploy-hot.sh`
改成"写临时文件再 `mv`"（旧 inode 留给旧一代），并在部署后**核对日志里的 sha256**——以前它只
grep 最后一行，reload 根本没发生时也会报成功。另加 `tools/restore-core-commands.sh`：万一
`/mcagent reload` 从 dispatcher 上消失，可以在线重新注册核心命令，不必重启服务器。这个工具的
类名每次运行都不同，因为 HotSpot 会**记住第一个 attach 的 agent 类加载器**，同名重编译不会生效。

### 5. 强制恢复轮不再吞掉玩家的话

生产玩家问了两遍没人应：端点卡住 → bot 拿不到对话轮 → 看门狗强制 `trigger=STUCK` 决策 → 那一轮
把消息标记已读。现在看门狗强制的轮次保留未读消息给下一轮回答；同时 `markRead` 只标记**本次
观察真正包含**的消息数量（构建观察期间新到的消息不再被吞）。`CHATINVOKETEST` 新增第二阶段与
反向对照：规则开 → 消息保住（PASS），规则关 → 消息被吞（CONTROL-PASS）。

### 6. 顺带修掉的测试隔离问题

`STRUCTTEST` 依赖"挖隧道被拒"，但 dev world 里留着上一次反向对照挖出来的 `mine_route_v1`，
第二次运行时走向"复用旧入口"分支而失败——测试现在先清掉记忆里的矿道。`ESCAPETEST` 的竖井在
set 了重生点之后落在家半径内，地表保护（本轮之前就上线）会拒绝每一级台阶，测试把它建到 96 格
之外，并把监听者放在 64 格耳语范围内（`ChatLog` 是有距离过滤的），才算测了它想测的东西。

生产 runtime `sha256=6e2aacad903bad28`，LLM 端点为 OpenCode Go `deepseek-v4.1-flash`
（原 `10.0.6.6:7863` 配置备份在同目录 `mcagent-llm.toml.bak.20260922-160929-before-ocg`），
JEV 保持 `mode=active speech_gate=active routing=active`。

## 2026-09-22（下午三）：基地地表硬保护、唯一矿道入口与 JEV 恢复闭环

生产根因不是轻度透视，而是规划模型连续传入 `dig_tunnel(new_site=true)`，反复覆盖
`mine_route_v1`，每次都从基地附近另开一条下行楼梯；普通 `mine`、资源接近清障和 `escape_up`
也没有统一的地表边界。

现在服务端以 bot 的床/重生点为家：水平 96 格内，真实高度图表面及其下方 4 层禁止普通挖掘、
资源清障和逃生阶梯破坏；唯一例外是服务端自己为那一条持久化 `dig_tunnel` 路线签发的精确清障
坐标。LLM schema 删除 `new_site`，执行端也忽略模型私自附加的同名参数；已有入口存在时，任何试图
在家附近建立第二入口的调用都会失败并要求复用 breadcrumbs。

`TUNNELTEST` 新增并通过三条反向断言：普通续挖复用原入口、显式第二入口被拒绝、家附近地表方块
保持完整。部署后生产 Agent 从 Y=24 连续执行旧计划的 `escape_up`，到山坡下 Y=75 时只得到一条
2-step 的安全局部楼梯；下一次调用返回 `no safe rising staircase`，没有穿出地表，随后按提示
`return_to_spawn` 回到 `895,64,365`。这是真服高度图和现有地形上的闭环证据。

同时补齐 JEV mining recovery 的三个实装缺口：System One 和 chat 协议现在都校验候选白名单；
`SKIP_TARGET` 会按维度、坐标和原方块记录 6000 tick，资源搜索和直接挖掘不会立刻重新选中它，
方块变化或超时自动解除；遥测只把实际重试和成功回退记作 physical applied。修正后的
`JEVMINETEST` 证明有效 retry、一次上限、低置信弃权、未知选项拒绝、过期建议、规划并发落地与
持久 skip 全部通过（0 failures）。生产 runtime `sha256=d85b52189af27b43`，JEV 保持
`mode=active speech_gate=active routing=active`。

## 2026-09-22（下午三）：self-cleared 修复在生产的第一手证据 —— 61 格连续下潜

修复 13:30:45 上线，一个小时后自己开口说话了。

### 生产原文（14:30:40–14:31:10 的循环，每一格都是同一套）

```
(plan) called mine(878, 55, 396) -> target 878,55,396 was perceived through solid terrain;
                                    clearing the real approach starting with 878,56,396
finished breaking 2 block(s); 2 item(s) went straight into the pack      ← 清障 + 目标
treats 878, 56, 396 as already done: this bot removed it 9 tick(s) ago   ← 本会失败的步
(plan) called mine(878, 56, 396) -> already clear: 878,56,396 is gone …
(plan) called mine(878, 57, 396) -> started mining 878, 57, 396 (about 8 ticks)
finished breaking 1 block(s); 1 item(s) went straight into the pack
```

改之前，中间那一步就是 `failed: there is no block at 878, 56, 396`，然后整条隧道作废、队列清空、
一轮 11k token 的规划。现在它是一句 `already clear`，计划继续往下走。

### 数字（同一台服务器，同一份日志）

| | 修复前 13:00–13:30（30 分钟） | 修复后 13:30:45–14:33（63 分钟） |
|---|---|---|
| `no block at … open air` 失败 | **39** | **3** |
| self-cleared 免失败步 | 0（还没有这个机制） | **64** |
| plan 中止 | 15 | 15 |
| 规划轮次 | 112 | 102 |

`14:30:45–14:33:44` 那一波：**61 个本会杀掉计划的步子全部变成免失败步**，bot 的 Y 从 **60 降到 -1**
（61 格 / 179 秒 = **20.4 格/分钟**）。修复前同口径实测是 **2.4 格/分钟** —— 差 8.5 倍。

也就是说，这个 bug 一直在把"挖穿一条隧道"变成"每挖两格重新想一次"，而它花了整整一个上午才被认出来。

### 现在的 bot

`14:33` 时在 `872, -6, 399`（深板岩层，钻石深度），背包满了（`cannot fit 2 dropped item stack(s)`），
仍在往下挖。self-cleared 修复与 5 分钟前的说话纪律修复（jar `sha256=3e4d8b69cfabbc77`）同时在线。


## 2026-09-22（下午三）：拿着桶挖石头，以及挖不通却继续走

生产日志里 Agent 在 `881,61,401` 附近重复 `escape_up`，大量路径停在目标约 0.8 格外。
背包查询确认它手持 Bucket，但背包里有铁镐和钻石镐；原来的工具经济只尝试石镐，没带石镐时
就保持 Bucket。与此同时，异步挖掘即使只清掉了一个接近障碍、没有清掉真正目标，只要
`broken > 0` 就不会清掉后续 `goto`，于是 bot 会走向仍然封死的台阶。

修复：

- 每个实际开挖的方块都按 tag 重新选择现有的最低成本合适工具；镐、斧、铲、锄均有铁/钻石等回退，
  不再依赖模型填写 `item`。
- `STOP_DESTROY_BLOCK` 后等待最多 10 tick 的服务器确认窗口，避免 vanilla 延迟销毁被误判成
  `BREAK_REJECTED`。
- MineJob 单独跟踪“请求的 origin 是否真的清除”。即使接近障碍已经挖掉，只要 origin 仍存在，
  都会中止依赖它的后续移动，避免撞在未清除方块上反复重试。
- 直接开挖时从 pending 移除已激活的 origin，成功后不再产生假的 `TARGET_BECAME_AIR`。
- 第一次部署后的在线观察又捕获到独立的移动问题：恢复点 Y 是 `61.355`，向高一格的踏步移动时
  在目标中心 0.8 格外停住（恰好是方块半宽 0.5 + 玩家半宽 0.3）。移动驱动原来只信
  `onGround` 才跳；恢复中的 fake player 即使被碰撞面托住也可能短暂为 false。现在同时使用
  引擎的 `verticalCollisionBelow`，并在高踏步近前、垂直速度为零且连续无进展时执行一次受限跳跃。
  放弃路径日志也会记录精确位置、dy、两种支撑状态、垂直速度和剩余 waypoint，便于定位模组地形。

正向对照使用生产同款背包：选中 Bucket，携带铁镐和钻石镐，不传 `item`。旧实现立即报：

```
ESCAPETEST staircase : escape started mining stone while still holding a Bucket
ESCAPETEST VERDICT   : FAIL
```

修复后同一场景（并从生产同款 `Y + 0.355`、`onGround=false` 的恢复状态开始）：

```
ESCAPETEST staircase : ... sky visible=true ... iron fallback used=true
ESCAPETEST VERDICT   : PASS
```

相邻回归：`MINEDROP`、`TUNNEL`、`SELFCLEAR`、`STRUCTURE`、`JEV_MINE` 全部 PASS。

生产部署：runtime `sha256=7b7a8f1794f7faa8`，主 LLM 仍为私有
`deepseek-v4.1-flash`，JEV 独立保持 active。部署后 Agent 从 `879.6,-13.0,399.5` 继续工作，
先完成 24 格下行隧道到 `903,-37,399`，再完成 11 格下行到 `914,-48,399`，随后开始 24 格
水平分支；连续数十次 timed break 和 goto 没有出现 `BREAK_REJECTED`、假
`TARGET_BECAME_AIR` 或 0.8 格卡死。查询 SelectedItem 确认开挖时已从海带自动换为附魔钻石镐。

## 2026-09-22（下午二）：话还是多 —— 门说了"别说"，代码没听

用户截图：两小时里 bot 连发 6 条，全是"挖到钻石就喊你"的换皮版本。查下来是**三个各自独立的洞**。

### 生产原文（13:22 之前的两小时）

```
11:22:03  <FirstMelody> 去挖一组钻石给我呗
11:22:04  JEV ACTIVE … SPEECH_GATE choice=STAY_SILENT confidence=0.140
                            probabilities={STAY_SILENT=0.57, SPEAK=0.43}
11:22:04  JEV ACTIVE … SPEECH_GATE not_applied=low_confidence confidence=0.14   ← 门说了别说
11:22:07  Bot Agent called say(message="一组太多了，钻石不好挖。…挖到多少给你多少")  ← 还是说了
```

全日志只有 5 次 SPEECH_GATE，其中 **3 次被 `not_applied=low_confidence` 丢掉**（0.02 / 0.14 / 0.63）。
另外 5 条（12:45 / 12:56 / 12:58 / 13:08 / 13:14）根本没经过门：它们是 **IDLE 轮次**里模型自己
"顺手"说的，`say` 工具只在被点名时不受限，未点名的路径只有一条两分钟冷却和一张硬编码短语表。

### 三个洞与修法

**洞 1：门说了 STAY_SILENT，却因为置信度低被丢掉 → 交给 LLM 决定 → LLM 一定要说话。**
这正好和用户的意图相反（"在 llm 之前就决定要不要说话"）。0.85 门槛的前提是"门是玩家与沉默之间唯一的
东西"——它不是：**任何直接问句在问门之前就已经被送去规划模型**（`trySpeechGate` 里 `directedAtBot &&
looksLikeQuestion` 直接 return false）。所以能走到门前的，都是玩家并不在等的消息（已在执行的指令、
重复、闲聊）。修法：**沉默方向不看置信度，看选择**（`applied=stay_silent` 日志带上 confidence 和
probabilities 作为证据）。消息仍留在聊天记录里，下一轮模型照样看得到、照样能去做。

**洞 2：`looksLikeProgressNarration` 是张硬编码短语表，中文换皮全漏。**
原来的表里有"这就去/正往/还没挖到…"，而生产发的四句是
"下面挖到一片水洞，绕个方向继续往下走，挖到钻石立刻喊你"、"我重开一条往下打，挖到就喊你"、
"我在往下挖，挖到就给你留着"、"刚回到地面上重新找路下去，挖到钻石先给你留着"——一句都不匹配。
修法：把规则改成**意图**而不是词表——"承诺以后再汇报"本身就是废话（`就喊你/立刻喊你/先给你留着/
稍等/let you know/tell you when`），加上"还在做同一件事、还没有结果"（`继续往下/往下挖/重开一条/
绕个方向/still digging`）。

**洞 3：未点名的安静期只有 2 分钟，而生产那 6 条的间隔是 83 / 11 / 2.5 / 10 / 6 分钟 —— 只拦得住一条。**
修法：2 分钟 → **5 分钟**（6000 tick），并给"真的干完了"的汇报开一个例外
（`reportsFinishedWork`：挖到了/拿到了/放好了/done/finished…），否则玩家永远等不到钻石挖到的消息。
例外在叙事规则**之后**判定，所以"挖到就喊你"这种承诺仍然先被叙事规则拦下。

顺带把 `say` 工具的说明从"Speak in chat. Other players will see this message."改成明确的契约：
只用来回答玩家、汇报**已完成**的任务、或报告解决不了的阻塞；不要叙述正在进行的进度，也不要预告
以后会汇报。

### 验证（`MCAGENT_SPEECH_TEST=true`，扩了两个阶段）

阶段 8（低置信沉默）：stub 按生产原样回答 STAY_SILENT 0.14 / pSilent=0.57，发"去挖一组钻石给我呗"。

```
SPEECHTEST low-conf   : gateAsked=true modelTurnWithin30Ticks=false botSpoke=false
                        (STAY_SILENT at 0.14 must still suppress; a question never reaches the gate)
```

阶段 9（叙事规则），直接打 `sayAsTool`（这是策略，不是模型行为，模型在环里只会挡住观察）：

```
narration  : "下面挖到一片水洞，绕个方向继续往下走，挖到钻石立刻喊你" -> not sent: this is routine progress narration…
narration  : "到现在0颗，全是石头。…我重开一条往下打，挖到就喊你"      -> not sent: this is routine progress narration…
narration  : "行，我在往下挖，挖到就给你留着，一组太多得慢慢来"        -> not sent: this is routine progress narration…
narration  : "在的，刚回到地面上重新找路下去，挖到钻石先给你留着"      -> not sent: this is routine progress narration…
completion : "钻石挖到了，一组放进你基地的箱子里了"                    -> said: 钻石挖到了，一组放进你基地的箱子里了
SPEECHTEST VERDICT   : PASS
```

**正负对照**（`MCAGENT_NARRATION_GUARD=off`）：第一条生产原文真的**发出去了**（`said: 下面挖到一片水洞…`），
后三条被另一条守卫（一轮只能说一句）拦下 —— 这正是要看到的结果：规则关掉，闲聊就回来。

```
SPEECHTEST VERDICT : CONTROL-PASS (narration guard disabled and the production narration lines were sent again, as expected)
```

对照能成立，靠的是新增的测试接缝 `clearQuietPeriodForTest()`：测试里四句话是同一秒发的，5 分钟安静期
会把叙事规则整个遮住，对照就会"因为错误的原因通过"。生产里那几句话相隔几分钟，所以必须把安静期
拨开才测得到真正的那条规则。

### 回归

10/10 harness 全绿：JEV_MINE / ROUTING / SPEECH / CHAT / CHAT_INVOKE / STRUCTURE / MINEDROP /
SELFCLEAR / TUNNEL / PLAN。

部署：jar `sha256=3e4d8b69cfabbc77`，bot 从上次登出点 `881.3 61.3 401.3` 复活。


## 2026-09-22（下午）：隧道宏每 40 秒自杀一次 —— 两套"清理"系统抢同一格

### 现场

生产里 bot 在 875,52,38x 反复排下行的隧道，**每 40 秒重新规划一次**，Y 从 66 降到 52 用了 6 分钟
（2.4 格/分钟），期间排了 10 条隧道全部中途作废、跑了 23 轮规划。日志原文（13:21:34 那一次）：

```
13:21:34  planned local tunnel: 12-block down tunnel west
13:21:34  (plan) mine(874,51,381) -> target 874,51,381 was perceived through solid terrain;
                                    clearing the real approach starting with 874,52,381
13:21:57  finished breaking 2 block(s)          ← 874,52,381（清障）和 874,51,381（目标）都没了
13:21:57  (plan) mine(874,52,381) -> failed: there is no block at 874,52,381 - it is open air
13:21:57  JEV ROUTING skipped=no_work_to_continue  → 一整轮规划（11.3k token）
```

### 根因

两套"清理"系统会点同一格：

1. **隧道宏**（`AgentBrain` 里 `TunnelStep.clear()`）把自己打算清掉的格子排成 `tunnel_mine_*` 队列步；
2. **`MineJob`** 在目标被遮挡时**自己**把最近的遮挡块清掉（`firstBlockingBlock` → `pending.addFirst(blocker)`）。

第 2 套先动手，第 1 套的步子再打上去就是空气 → `startMine` 返回 `failed: there is no block at ...`
→ 而宏的每一步都是 `abortPlanOnFailure=true`（`schedulePlan` 里 `!continueOnFailure`），**整条隧道
（展开后近百步）当场作废**，队列清空 → IDLE → 又一轮 11k token 的规划 → 又排一条新隧道。

今天全日志：`there is no block at ... open air` **493 次**，隧道宏计划 **596 次**。

### 修法

`AgentBrain` 记住**自己**刚挖掉的格子，队列步再点到它时算"已完成"而不是失败：

- 新增 `selfCleared`（`Map<BlockPos, Long>`，`SELF_CLEARED_TICKS = 1200` = 60 秒窗口，写入时按时间
  剪枝，所以不会随世界增长）。记录点在唯一的破坏完成处（`job.broken++` 那一段），**只有 bot 自己
  挖掉的块才会进这个表** —— 模型编出来的坐标照样老实报 `failed:`。
- `startMine` 里空气分支：命中 `selfCleared` 就返回 `already clear: ...`（不以 `failed:` 开头，所以
  `isFailure` 为假，计划继续），并打一行"什么时候、为什么"的日志。
- `MCAGENT_SELFCLEARED=off` 只给正负对照用。

### 验证（`MCAGENT_SELFCLEAR_TEST=true`，新增 `SelfClearedStepSmokeTest`）

端到端，不是只比字符串：造出生产同款几何（目标被一格石头遮挡）→ 让真的 `MineJob` 自己清掉那格
遮挡 → 再通过**真的 plan 工具**（scripted 模型）排两步：第 1 步点那格已清的坐标，第 2 步点旁边一
块实心石头。问题只有一个：**第 2 步还跑不跑**。

```
SELFCLEARTEST terrain   : origin=140,-40,140 blocker=141,-39,140 target=142,-39,140 planStep2=140,-39,141
SELFCLEARTEST mine(142,-39,140) -> target 142,-39,140 was perceived through solid terrain;
                                   clearing the real approach starting with 141,-39,140
SELFCLEARTEST state ready : blocker 141,-39,140 removed by the bot's own job 14 tick(s) ago
SELFCLEARTEST result  : the plan survived the already-cleared step: step 1 named 141,-39,140
                        (removed by the bot itself 14 tick(s) earlier) and step 2 still ran -
                        140,-39,141 is gone, model calls=2
SELFCLEARTEST VERDICT : PASS
```

修复那一行自己的日志：

```
Bot SelfClearBot treats 141,-39,140 as already done: this bot removed it 7 tick(s) ago as
                 clearance for an earlier step of this plan
Bot SelfClearBot (plan) called mine(141,-39,140) -> already clear: 141,-39,140 is gone - this bot
                 removed it 7 tick(s) ago as clearance for an earlier step, so there is nothing left to break here
```

**正负对照**（`MCAGENT_SELFCLEARED=off`，同一个测试）：

```
SELFCLEARTEST result  : plan step 2 never ran: 140,-39,141 is still Block{minecraft:stone} after
                        1400 ticks; step 1 named the already-cleared 141,-39,140, so the plan was
                        aborted by 'failed: there is no block at ...'
SELFCLEARTEST VERDICT : CONTROL-PASS (fix disabled and the plan died on the already-cleared step, as expected)
```

即：这个测试确实能抓到那个 bug，不是"改完顺便变绿"。


## 2026-09-22（中午）：Jev 一天花多少钱 —— 实测 $0.21/天

问的是"按 Jev 现在的标准付费价，一天要多少钱"。答案是**一天两毛一美金**，而且这个数字不是估的，
是拿生产日志里的 state 原样重放给网关、读回它自己的 token 计数算出来的。

### 价格（来自网关自己的 `/v1/models`，不是猜的）

```
typesafe-ai/jev   pricing.input = 0.000000042  →  $0.042 / M input token
                  pricing.output = 0          →  输出免费
```

单位用同表的已知模型校验过：`openai/gpt-4o-mini` 的 `0.00000015` = $0.15/M，对得上。

### 每次调用多少 token（重放实测，不是按字符估）

把日志里的 `state=...` 原样取出，按 `JevClient.choose` 的报文格式重发，读 `usage.inputTokens`：

| 事件 | tokens/次 | 样本 | 拟合 | 固定开销 |
|---|---|---|---|---|
| routing | **600** | 16 | `249 + 0.2661×body字符`，R²=0.946 | 867 字符 |
| mining | **770** | 16 | `218 + 0.2773×body字符`，R²=0.907 | 1320 字符 |
| speech | 476 | 1 | 日志不记 state，用 200 字符占位 | 759 字符 |

固定开销是"指令 + 选项 + JSON 外壳"，主体是 state。单次实测响应（538 token 那一次）：

```
usage: {"inputTokens": 538, "outputTokens": 37}
gateway: cost:"0"  marketCost:"0.000022596"  surchargeCost:"0"
```

`538 × 0.000000042 = 0.000022596` —— 和 `marketCost` 分毫不差，也说明输出那 37 个 token 确实没算钱。
`cost:"0"` 是免费期内的实收；到期后按 `marketCost`（标价）扣，就是上面这张表。

### 今天实测（01:10–11:14 窗口外推到 24 小时）

`tools/jev-cost.py` 的输出：

```
routing  calls=  3709  tokens/call= 600  ->  2,269,903 input tokens  $0.0953
mining   calls=   158  tokens/call= 770  ->    122,197 input tokens  $0.0051
speech   calls=     3                    ->      1,428 input tokens  $0.0001

window: 11.34 h   calls=3870   tokens=2,393,529   $0.1005
24 h:   5,065,669 tokens  ->  $0.213/day  (6.38/month)
per call: 618 tokens  $0.0000260
```

- **$0.213/天 ≈ $6.4/月**，单次决策 **$0.000026**（两分六厘人民币的千分之一量级）。
- 只统计真的发出请求的行（带 `choice=` 或 `unavailable=`）。守卫跳过的不算钱：
  `skipped=no_work_to_continue` 今天就有 **2,336 次**——那正是这套设计省钱的地方。
- 繁忙时段（最近 3 小时 routing 508 次/h，比全天均值高 55%）外推是 **$0.32/天**；
  就算每次调用都取观测到的最大 state（routing 656 / mining 791），上限也只有 **$0.35/天**。

### 工具

`tools/jev-cost.py`：拉日志 → 重放采样量真实 token → 拟合 → 按标价外推 24 小时。
一次约花 $0.001 额度，采样之间隔 0.35 秒（网关对突发会回 429，而生产正在共用这个网关）。
注意它内嵌了一份 `JevPrompts` 的指令文本用于构造报文；改动措辞后要同步，否则固定开销那一列会漂。


## 2026-09-22（上午二）：抓到一次完整的"高置信 → 动手 → 真的挖到"链路

`tools/watch-mining-recovery.sh` 在 09:04:40 抓到一次，日志原文（一条完整链路）：

```
09:04:40.422  Bot Agent finished breaking 0 block(s)                      ← 真实失败
09:04:40.863  JEV ACTIVE … MINING_RECOVERY choice=RETRY_DIFFERENT_ACCESS confidence=0.690
09:04:40.867  JEV ACTIVE … MINING_RECOVERY applied=true result=walking to a reachable mining
              position for 833, 65, 324 before breaking it (Birch Log)     ← 廉价层动手
09:04:41.296  JEV ACTIVE … ROUTING trigger=IDLE choice=ESCALATE_LLM 0.830  ← 路由层同时判定"该想一下"
09:04:52.619  Bot Agent (plan) called mine(x=833, y=65, z=324, radius=5)
              -> started mining Birch Log at 833, 65, 324                 ← 真的走到并开采了目标
09:04:54.168  JEV ACTIVE … ROUTING trigger=IDLE choice=CONTINUE 0.990      ← 之后路由层持续省轮次
```

即：恢复决策 ≥0.6 → 应用 → bot 按它给的坐标走过去并把目标挖掉，两层决策点还协同工作（恢复动手、路由
省下随后的规划轮次）。

同一窗口内低于门槛的样本（0.26 / 0.41 / 0.42 / 0.45 / 0.46）全部弃权 ✅，两次被丢弃的是
`moving=true queue=1`（确有更新的工作，守卫按设计生效）。


## 2026-09-22（上午）：挖矿恢复在生产的真实分布 —— 93% 超过 0.6，并修掉残余竞态

持续采样后的答案很明确：**挖矿恢复的判定绝大多数都在 0.6 门槛之上**。

- **2005 / 2150 条样本 ≥ 0.6（93%）**；
- **8 次真实动手**（`applied=true`），全部 ≥ 0.6：

```
03:03:26  SKIP_TARGET 0.63  → marked 906, 59, 396 temporarily unreachable
03:12:43  SKIP_TARGET 0.71  → marked 871, 61, 380 temporarily unreachable
05:32:31  SKIP_TARGET 0.67  → marked 816, 64, 382 temporarily unreachable
06:05:06  RETRY       0.65  → walking to a reachable mining position for 882, 62, 398 (Coal Ore)
06:07:33  SKIP_TARGET 0.76  → marked 880, 63, 399 temporarily unreachable
07:03:06  SKIP_TARGET 0.84  → marked 858, 61, 328 temporarily unreachable
07:32:16  RETRY       0.61  → walking to a reachable mining position for 811, 58, 333 (Copper Ore)
07:38:26  SKIP_TARGET 0.74  → marked 805, 61, 330 temporarily unreachable
```

选择也合理：被拒绝破坏/目标消失 → SKIP；接近失败 → RETRY（重新找可达站位）。

### 残余竞态：还有 7 条 ≥0.6 的答案被丢弃

`not_applied=newer_work … thinking=true` 仍有 13 次，其中 **7 次置信度 ≥0.6**（03:33 的 0.61、
04:26 的 0.72、06:45 的 0.86、07:16 的 0.62…）。原因是上一轮那个"让出这一秒"的等待**只有 3 秒**，
而网关冷启动可以到 6 秒 —— 等待过期后 bot 已经开始规划，答案就又撞在 turn 上。

修法：等待上限改为 **200 tick（10 秒）**，由客户端自己的请求超时（8 秒）+ 余量推出，而不是拍一个数字。
这里等久一点很便宜：守卫只在"有活可继续"时才问，等待期间 bot 照常执行队列；而它可能省下的那一轮规划
要 10–15 秒、约 12k token。

### 持续监测

新增 `tools/watch-mining-recovery.sh`：盯生产日志，逐条打印新的挖矿恢复判定，标出"高于门槛却被丢弃"的
样本，并在出现 `applied=true` 时退出（默认窗口 90 分钟）。


## 2026-09-22（凌晨九）：生产数据抓出"廉价层永远无法生效"的竞态 —— 已修 + 反向对照

### 现象（只有生产数据能看到）

挖矿恢复刚转 active 就出现这条：

```
JEV ACTIVE bot=Agent event=MINING_RECOVERY choice=SKIP_TARGET confidence=0.830
JEV ACTIVE bot=Agent event=MINING_RECOVERY not_applied=newer_work
        mineJob=false combat=false moving=false queue=0 thinking=true state=idle
```

四个守卫里前四个都是 false，**唯一挡住它的是 `thinking=true`**：挖矿 job 一结束，bot 立刻开始一轮规划
（生产上 10–15 秒），而恢复答案只要约 1 秒就回来 —— 于是**每一次**答案都撞在进行中的 turn 上，被判成
"更新的工作"丢弃。也就是说：这个决策点在 active 模式下**一次都不可能生效**。

这个 bug 是上一轮加的 `not_applied=… mineJob=/combat=/moving=/queue=/thinking=` 诊断日志暴露的 ——
没有它，日志只会写 `not_applied=newer_work`，看起来像"守卫正常工作"。

### 修法

不是放宽守卫（那会让恢复和模型的决定打架），而是**让廉价层先落地**：恢复请求在飞时，普通（IDLE）
决策先让出这一秒 —— `startDecision()` 里发现 `jevRecoveryRequestedAt` 在 60 tick 内就释放闩锁、
下个 tick 再试。聊天不在此列（玩家的问题优先），且等待有上限，请求永不返回也不会卡住 bot。

### 反向对照（这次做对了）

新增 `JEVMINETEST` 第 13/14 阶段复现生产竞态：脚本模型故意慢（2.5 s），JEV stub 也慢（2.5 s），
并且**等到 bot 即将规划的那一刻**（`thinking=false` 且 `cooldownTicks<=5`）才报告失败 ——
否则这个阶段只是在抛硬币。

```
修复关闭: JEVMINETEST race : recoveryAppliedWhileThinking=false → VERDICT: FAIL
修复在位: JEVMINETEST race : recoveryAppliedWhileThinking=true  → VERDICT: PASS
```

前两版测试都是**假绿**：一次是断言"日志里存在 applied 行"（前面的阶段早就写过），一次是在 bot 空闲时
触发失败（根本不构成竞态）。两次都是"断言测错了东西"，和挖矿那三个错误同类。


## 2026-09-22（凌晨八）：JEV 决策层三个决策点全部转 active ✅

生产配置现在是 `mode=active speech_gate=active routing=active`（jar `743db4ea`），三个决策点都在真实环境里做决定：

| 决策点 | 标定 | 生产证据 |
|---|---|---|
| CHAT 说话闸门 | 9 例：该沉默 0.89–0.99 / 该回答 0.47–0.77，阈值 0.85 | 0.94 压住一次；0.63 按设计交给规划模型 |
| ROUTING（IDLE/RESUME） | 6 例：CONTINUE 0.95–1.00 / ESCALATE 1.00；改提示词前只有 3/6 | **16 条真实样本重放：15 CONTINUE（中位 1.00）**；翻 active 后规划轮次 **6.8/min → 4.0/min（约 -40%）**，34 次 CONTINUE 生效，同时段 bot 仍在挖矿、仍在重规划、watchdog 0 次 |
| MINING_RECOVERY | 8 例合成 0.50–0.85；**8 例真实失败** SKIP×4/RETRY×3/GATHER×1（0.27–0.81），阈值 0.6 下 4 动 4 弃 | 触发收窄后样本才干净；harness 7 阶段覆盖白名单/上限/阈值/过期/未知选项 |

COMMAND 与 STUCK 两个触发按设计**不经过**廉价层（显式指令与看门狗判定不二次猜测），各留一行 `JEV ROUTING … bypass=` / `skipped=` 日志。

### 本轮顺带修掉的三处"证据不可读"

1. 路由日志**不写 state**，而 `firstLine()` 又把 state 截到 160 字符 —— 正好切掉 goal/queued/reports 这些重放最需要的字段。现在两个决策点都用 `oneLineState()`（单行、700 字符），`tools/jev-replay` 已支持 ROUTING。
2. 挖矿恢复**成功应用时不打日志**（只有 `not_applied` 才打），等于"生效与否只能看世界状态"。现在补上 `applied=<true|false> choice=… confidence=… result=…`，并且把"升级给规划模型"如实记为 `applied=false`。
3. `tools/jev-replay` 连续打 16 个请求会**自己触发熔断**（3 次连续失败即暂停 45s），看起来像"端点全拒"。已加请求间隔。

### 置信度噪声

同一批真实状态重放，置信度上下浮动 ±0.2~0.3（同一条失败样本一次 0.67、一次 0.33）。所以阈值留余量，且阈值附近"两种结果都可接受"才算标定合格 —— 写进了 `docs/jev-maturity.md`。

### 回归

`JEV_MINE` / `ROUTING` / `SPEECH` / `CHAT` / `CHAT_INVOKE` / `STRUCTURE` / `MINEDROP` 七个 harness 全绿（本轮每次改动后重跑受影响的那些）。


## 2026-09-22（凌晨七）：路由层上线 shadow + 采样仪表修好

- 生产热部署 `75d98eb2`：`mode=shadow speech_gate=active routing=shadow protocol=systemone`，bot 回到下线位置。
- 路由层在真实环境立刻开始出样本（64 次里 63 次 CONTINUE、1 次 ESCALATE），但**只有 16 次 ≥0.85** —— 说明真实状态比标定用例更模糊（队列空、`standing_goal=(none)`、走路中等），这正是要采样而不是拍阈值的原因。
- 发现并修掉一个采样仪表的错：路由日志只写 `applied=`，**不写 state**，而 `firstLine()` 又把 state 截到 160 字符（正好切掉 goal/queued/reports 这些重放最需要的字段）。现在两个决策点都用 `oneLineState()`（单行、700 字符上限）落盘，`tools/jev-replay` 已支持 ROUTING 事件。修好后立刻能看到清晰样本：

```
JEV SHADOW bot=Agent event=ROUTING trigger=IDLE choice=CONTINUE confidence=1.000
  state=Minecraft bot decision. …; current_action=walking to (882, 63, 360); queue_size=5;
        long_action_running=true; queued_in_order=[mine_resource | mine_resource | goto | open_container | ...];
        standing_goal=(none); seconds_since_last_completed_turn=2
```

- 下一步（下一轮）：攒 ≥30 条带 state 的路由样本 → `tools/jev-replay` 重放 → 定阈值（考虑到"错判 CONTINUE"只是让 bot 把现有队列做完、代价有界，阈值可能低于 0.85）→ 转 `routing=active`。


## 2026-09-22（凌晨六）：JEV 决策层三件套到齐 —— 回归七连绿 + 路由提示词标定

### 1. 挖矿恢复：补齐测试覆盖（此前是空白）

新增 `JevMiningRecoverySmokeTest`（`MCAGENT_JEV_MINE_TEST=true`，7 阶段），把决策点**每一条失败路径**都钉住：

```
JEVMINETEST shadow     : targetMined=false (shadow must not act)
JEVMINETEST active RETRY: targetMined=true (a confident RETRY is applied)
JEVMINETEST cap setup   : targetSurvivedItsRetry=true (so the guard must still hold)
JEVMINETEST retry cap   : retryStillOffered=false (one automatic retry per exact target)
JEVMINETEST low conf    : targetMined=false (below the floor: abstain)
JEVMINETEST unknown     : targetMined=false (not offered => rejected)
JEVMINETEST stale       : targetMinedByStaleAdvice=false (newer work must win)
JEVMINETEST VERDICT     : PASS
```

开发过程里修掉三个**测试本身**的错（都是"断言测错了东西"，值得记下来）：
- 用瞬时 `mining` 标志判断"是否重试"——石头 1.5 s 就挖完，读到的时候早已归零 → 改为看**世界状态**（目标方块是否消失）；
- "一次重试上限"用物理结果断言——重试成功会把方块挖掉，而**方块消失后该坐标按设计视为新事件**、上限会被清除 → 改为断言**候选集里还有没有 RETRY**；
- 用整份请求体做子串匹配——提示词里本来就写着 `RETRY_DIFFERENT_ACCESS` → 改为解析 `questions.<id>.criteria` 的键集。

顺带把 `not_applied=newer_work` 的日志补成 `mineJob=/combat=/moving=/queue=/thinking=`，因为"被更新的工作取代"和"恰好在思考"需要相反的修法，日志必须能区分。

### 2. 路由层：提示词不标定就等于没做

subagent 完成的 router（`Trigger{CHAT,IDLE,COMMAND,STUCK,RESUME}` + `tryRoutingDecision`）harness 7 阶段全绿，但**打真实 jev 的标定暴露了问题**：第一版提示词下，三个"应该继续"的场景全部答 `ESCALATE_LLM`，置信度只有 **0.07–0.18** —— `routing=active` 会一次都不触发，白花配额。

改成对状态字段的有序判定过程后（`JevPrompts.ROUTING`）：

| 用例 | 旧提示词 | 新提示词 |
|---|---|---|
| 挖矿中·目标一致 | ESCALATE 0.09 | **CONTINUE 0.95** |
| 挖矿中·队列还有活 | ESCALATE 0.17 | **CONTINUE 1.00** |
| 走路中·队列还有活 | CONTINUE 0.47 | **CONTINUE 0.99** |
| 刚干完·无队列 | ESCALATE 0.96 | ESCALATE 1.00 |
| 动作失败·需重规划 | ESCALATE 0.99 | ESCALATE 1.00 |
| 背包满·目标未达成 | ESCALATE 0.97 | ESCALATE 1.00 |

阈值 0.85 下新旧提示词分别 3/6 与 **6/6**。纪律：**提示词与阈值必须一起标定**，且标定用的状态串必须与运行时逐字一致（`JevPrompts` 单一来源）。

### 3. 工具与流程

- `tools/run-harness.sh`：目录锁，两个 agent 共用一个 dev server 不再互相把对方挤掉（此前真实发生过 `session.lock already locked`）。
- `tools/deploy-hot.sh`：生产热部署一键化（构建 → 备份 → 拷 jar → attach 注入 `mcagent reload` → `spawn` → 核对 hash 与配置）。
- `docs/jev-maturity.md`：每个决策点的模式/阈值/标定/测试覆盖/生产证据一览，以及"成熟"的六条判定。

### 4. 当前状态

生产：`sha256=9dcde4db076f678a`，`mode=shadow speech_gate=active routing=shadow protocol=systemone`，bot 已回到下线位置。
回归：**JEV_MINE / ROUTING / SPEECH / CHAT / CHAT_INVOKE / STRUCTURE / MINEDROP 七个 harness 全绿**。

两个 shadow 决策点（挖矿恢复、路由）现在都只差**生产样本**：攒够后用 `tools/jev-replay` 重放，确认选择分布与标定一致再转 active。


## 2026-09-22（凌晨五）：JEV 成为每一个触发的第一层 —— 路由层（routing）落地 + 隔离服七阶段验证

在这之前只有两件事会先问 jev：玩家说话（说话闸门）和挖矿失败（恢复）。其余每一个"该想了"的时刻——冷却到点、
被 `/mcagent think` 点名、看门狗发现卡死、解除暂停——都是直接打规划模型，一轮 12k token。本轮把**每个触发都先
过一层廉价类型化判断**，规划模型退回兜底。

### 1. 触发标签：Trigger 枚举 + pendingTrigger

`AgentBrain.Trigger = CHAT | IDLE | COMMAND | STUCK | RESUME`。每个调用点打标，`startDecision()` 顶部**消费**
（读走即复位成 IDLE），所以标签不会串到下一次决定：

| 触发 | 打标处 |
|---|---|
| CHAT | `tick()` 末尾，本 tick 有人点名时（否则 IDLE） |
| IDLE | 同上，普通冷却到点 |
| COMMAND | `requestDecisionNow()`（`/mcagent think`） |
| STUCK | 看门狗 `ticksSinceDecision > 1200` 分支 |
| RESUME | `setPaused(false, …)`，且此前确实暂停着 |

分派：CHAT → 原有说话闸门（那个方法一行没动）；IDLE/RESUME → 新路由层；**COMMAND 与 STUCK 直接进规划模型**。
理由写进了代码注释：一个是操作员明确的指令，一个是已经检测到的卡死，这两件事最不该被"继续干吧"的廉价回答挡掉。
两者都打一行 `bypass=`，而不是让人从"没有 JEV 行"里去猜：

```
JEV ROUTING bot=RouteBot trigger=COMMAND bypass=explicit_input
```

### 2. 路由层

`tryRoutingDecision(Trigger)` / `applyRoutingDecision(...)`，形状与说话闸门完全一致：`executor()` 上异步问，
再 `bot.server.execute(...)` 回主线程，`thinking` latch 全程不松（要么交给 `beginDecision()`，要么自己放掉），
失败/超时/低置信/过期一律 fail-open 回规划模型。问题 id 固定 `routing`，候选 `CONTINUE` / `ESCALATE_LLM`，
指令串走 `JevPrompts.ROUTING`（与 tools/jev-replay 共用；换个措辞就等于换了标定，见凌晨四那节）。

门槛 `MIN_ACTIVE_ROUTE_CONFIDENCE = 0.85`，沿用说话闸门的标定纪律而不是重新拍：同一个九例电池里"应沉默"落在
0.89–0.99、"必须回答"落在 0.47–0.77，0.85 落在缝里；路由问的是同一类问题，所以直接继承这个地板。

**护栏（最重要的一条）**：只有 `!queue.isEmpty() || isLongActionRunning()` 才问。空队列 + 没有长动作在跑时，
idle 的 bot 唯一诚实的答案是"去规划"，所以**根本不问**：

```
JEV ROUTING bot=RouteBot trigger=IDLE skipped=no_work_to_continue
```

否则一次 CONTINUE 就会让 bot 永远站着。

状态串 `routingState(Trigger)` ≤600 字符：bot / 维度 / 当前动作 / 队列长度与队列里的动作名 / 是否有长动作 /
常驻目标 / 距上次完成规划轮的秒数 / 最近 3 条 action report（只读不消费——`describeOngoing()` 才是消费者）。
观测：`debugState()` 新增 `pendingTrigger`、`jevRouting`（每触发 asked/continued/escalated/failed），
外加 `queueSize` / `longActionRunning` / `mineBroken`。日志一行可 grep，`applied ∈ true|false|low_confidence|stale|unavailable`：

```
JEV ACTIVE bot=RouteBot event=ROUTING trigger=IDLE choice=CONTINUE confidence=0.950 probabilities={…} applied=true
JEV ACTIVE bot=RouteBot event=ROUTING trigger=IDLE choice=ESCALATE_LLM confidence=0.950 probabilities={…} applied=false
```

`false` = 这一层**故意**没跳过（高置信 ESCALATE_LLM、shadow 模式的答案）；`low_confidence` = 答案没被信任。
两者分开，回看日志时不会把"它说别跳"读成"我们不信它"。

### 3. 隔离服验证：`MCAGENT_ROUTING_TEST=true`（7 个阶段全 PASS）

新增 `JevRoutingSmokeTest`：真 bot、真 loop、stub 的 System One 端点（**按 question id 回答**，因为
speech_gate / mining_recovery / routing 共用一个端点）、只数请求数的 `ScriptedLlmServer`；bot 旁边放 27 块泥土
+ 石头地板当"正在做的工作"，多到跑不完。原始日志：

```
ROUTINGTEST phase 0: routing=active, empty queue, nothing running (before=Snapshot[routingCalls=0, llmCalls=1, …])
JEV ROUTING bot=RouteBot trigger=IDLE skipped=no_work_to_continue
ROUTINGTEST guard      : routingAsked=0 modelTurns=2 queued=0 longAction=false busy=idle
                         (an idle bot with nothing to continue must plan without asking)

ROUTINGTEST phase 1: routing=shadow, work in flight
JEV SHADOW bot=RouteBot event=ROUTING trigger=IDLE choice=CONTINUE confidence=0.950 probabilities={…} applied=false
ROUTINGTEST shadow     : routingAsked=3 modelTurns=3 continued=0 escalated=3
                         (shadow must observe and still escalate)
ROUTINGTEST state      : [289 chars] Minecraft bot decision. bot=RouteBot; dimension=minecraft:overworld; trigger=IDLE;
                         current_action=mining Dirt at 32, -60, 29 (part of a 6-block job, 8 broken so far),
                         about 12 ticks left; queue_size=0; long_action_running=true; standing_goal=(none);
                         seconds_since_last_completed_turn=never

ROUTINGTEST phase 2: routing=active, stub answers CONTINUE 0.95
JEV ACTIVE bot=RouteBot event=ROUTING trigger=IDLE choice=CONTINUE confidence=0.950 probabilities={…} applied=true
ROUTINGTEST continue   : routingAsked=3 modelRequests=0 continued=3 mining=true blocksBroken=18
                         busy=mining Dirt at 32, -58, 29 (part of a 6-block job, 18 broken so far), about 5 ticks left
                         (not one planning request in the window)

ROUTINGTEST phase 3: routing=active, stub answers ESCALATE_LLM 0.95
JEV ACTIVE bot=RouteBot event=ROUTING trigger=IDLE choice=ESCALATE_LLM confidence=0.950 probabilities={…} applied=false
ROUTINGTEST escalate   : routingAsked=4 modelTurns=4 escalated=7
ROUTINGTEST report     : planningModelSawContinueReport=3 (the CONTINUE action report must be in the next observation)

ROUTINGTEST phase 4: requestDecisionNow with routing=active
JEV ROUTING bot=RouteBot trigger=COMMAND bypass=explicit_input
ROUTINGTEST command    : routingAsked=0 modelTurns=1 COMMAND.asked=0 pendingTrigger=IDLE
                         (an explicit instruction is never routed)

ROUTINGTEST phase 5: paused and resumed with no work
JEV ROUTING bot=RouteBot trigger=RESUME skipped=no_work_to_continue
ROUTINGTEST resume     : routingAsked=0 modelTurns=1 RESUME.asked=0 queued=0 longAction=false busy=idle

ROUTINGTEST phase 6: routing=off with work in flight (control)
ROUTINGTEST control    : routingAsked=0 modelRequests=4 longAction=true mining=true
                         (routing=off: the same situation must spend planning turns)

ROUTINGTEST counters   : {CHAT={asked=0,…}, IDLE={asked=10, continued=3, escalated=7, failed=0},
                          COMMAND={asked=0,…}, STUCK={asked=0,…}, RESUME={asked=0,…}}
ROUTINGTEST stub calls : total=10 perQuestion={routing=10}
ROUTINGTEST VERDICT    : PASS
```

第 6 阶段是**正对照**：同样的"有活在跑"场景，只把 routing 关掉，规划轮就回来了（4 次请求）。没有它，第 2 阶段的
"0 次请求"也可能被解释成"bot 根本叫不动模型"。第 0 阶段则是负断言：空队列时 jev 一次都没被问，规划照常发生。

### 4. 回归（说话闸门 / 聊天节流 / 聊天触发）

三个 harness 各跑一次，全部 PASS；之后在当前共享树上又整组复跑一次（含另一位 agent 之后的改动），仍然 PASS：

```
SPEECHTEST instruction: gateAsked=true modelTurnWithin30Ticks=false botSpoke=false
SPEECHTEST question   : gateAsked=false modelTurn=true
SPEECHTEST shadow     : gateAsked=true modelTurn=true
SPEECHTEST breaker    : messages=4 attempts=3 (three 429s must stop the fourth call)
SPEECHTEST chat proto : gateAsked=true modelTurn=false botSpoke=false
SPEECHTEST duplicate  : gateAsked=false modelTurn=false botSpoke=false
SPEECHTEST VERDICT   : PASS
CHATTEST VERDICT : PASS
CHATINVOKETEST VERDICT: PASS
```

### 5. 边界 / 已知（不吹）

- **STUCK 只做了代码层确认**：它与 COMMAND 共用同一条 `case COMMAND, STUCK ->` 分支，但隔离服只实测了 COMMAND。
  要真跑 STUCK 得让"1200 tick（≈60 s）内没有任何决定完成"成立（还不能有长动作在跑，否则计数器被清零），成本高、
  时序脆，因此没做。
- **护栏只实测了"长动作"那一半**：`!queue.isEmpty() || isLongActionRunning()` 是个"或"，七个阶段里让护栏放行的
  一直是"有长动作在跑"（状态串里 `queue_size=0; long_action_running=true`）。`queue` 非空那一半没有被单独隔离
  （这个代码库里队列非空几乎总伴随动作在跑，而 `tick()` 里 `runQueued()` 又在同一 tick 先执行），只做了代码层确认。
- **RESUME 实际上永远撞护栏**：`setPaused(true)` 会 `abandonPlan` + `abandonCurrentAction`，所以恢复时队列空、
  没有长动作在跑 → 直接规划、不问。日志里那行 `trigger=RESUME skipped=no_work_to_continue` 就是它。保留 RESUME
  标签是因为语义上它确实是独立触发，而且将来若暂停不再丢弃工作，这条路会自然开始生效。
- **生产是 `routing=shadow`**（配置早就写着，本轮才真正被读）：shadow 下**每个**"有活在跑"的 idle tick 都会多一次
  jev 往返（0.4–1.5 s、约 150 input token），挖矿期间约每 2 s 一次。先收这些日志做标定，再谈 active。
- `routing=off` 时 `tryRoutingDecision` 在问之前就返回，所以**不会**有 `event=ROUTING` 行，也不会有
  `skipped=` 行；`bypass=` 行同理（没配 jev 就没有"被绕过"这回事）。

## 2026-09-22（凌晨四）：挖矿恢复的标定 —— 状态补全 + 候选收窄 + 阈值 0.6（仍 shadow）

目标：让"挖矿失败 → JEV 决定怎么恢复"这条路也有资格开 active。做法与说话闸门同一条纪律：**先看状态够不够、
再标定阈值、最后才谈上线**。

### 1. 状态补全（之前 jev 看不到做决定需要的证据）

旧状态只有失败计数，于是它给出的两个选项都是 no-op。现在 `miningRecoveryState()` 会带上：
`target_block`（还在不在、是什么）、`failures_for_this_exact_target`、`retry_already_used_for_this_target`、
`saved_mine_route_in_this_dimension`、`holding`（手上工具）、`goal`（常驻目标）、`just_happened`（最近两条
行动报告）、以及 `SemanticScene`（围合/顶板/危险物）。日志同时输出 `state=`（单行截断），这样生产样本可以
离线重放，不必等下一次真失败。

### 2. 候选收窄：被拒绝的破坏不再提供 RETRY

标定里唯一一个**明确错误**的判定：`BREAK_REJECTED`（方块被保护/拒绝破坏）时模型选了
`RETRY_DIFFERENT_ACCESS` —— 而可达性规划器救不了一个"根本不让挖"的方块。现在这种失败**不提供** RETRY
候选（确定性收窄，不靠置信度兜）。

### 3. 阈值标定（8 个真实失败状态打 Vercel jev，收窄候选后）

| 用例 | jev | 置信度 |
|---|---|---|
| 被遮挡不危险·首次 | RETRY_DIFFERENT_ACCESS | 0.73 |
| 接近无进展 ×3·首次 | RETRY_DIFFERENT_ACCESS | 0.64 |
| 被保护/拒绝破坏 | SKIP_TARGET | 0.16（收窄后不再是 RETRY） |
| 找不到可站点 ×2·有矿道 | RETRY_DIFFERENT_ACCESS | 0.60 |
| 同一目标第三次·已重试过 | SKIP_TARGET | 0.64 |
| 目标已消失 | SKIP_TARGET | 0.85 |
| 没有矿道·不可达 | SKIP_TARGET | 0.50 |
| 目标正是当前目标所需 | RETRY_DIFFERENT_ACCESS | 0.67 |

结论：可辩护答案落在 **0.50–0.85（中位 ~0.64）**，比聊天的 0.89–0.99 平得多——"选哪种恢复"本来就比
"要不要说话"难。这里的代价不对称也和聊天相反：**弃权要花一整轮规划（约 12k token），而动手只是一次
有界、白名单、有日志的动作**（每个目标一次重试 / 一个标记 / 沿已知路标走回去）。因此阈值定 **0.6**
（而不是聊天的 0.85），把唯一明确错的判定用确定性规则排除。

### 4. 现状

- 仍 `shadowMode=true`：只记录不动作。触发已收窄（只问真失败），所以从现在起的样本是干净的；
- 下一步：积累真失败样本 → 用日志里的 `state=` 离线重放 → 若选择分布与标定一致，再开 active。


## 2026-09-22（凌晨三）：闸门第一次真实判定 —— 暴露两个缺口并修好（含阈值标定）

### 生产实测（00:27，Vercel 的 jev）

玩家连发三条「继续挖钻石 多挖点钻石」：

```
00:27:56  JEV ACTIVE bot=Agent event=SPEECH_GATE choice=STAY_SILENT confidence=0.020
          probabilities={STAY_SILENT=0.51, SPEAK=0.49} newest_message=<FirstMelody> 继续挖钻石 多挖点钻石
00:27:56  JEV ACTIVE bot=Agent event=SPEECH_GATE not_applied=low_confidence confidence=0.02
00:28:02  Bot Agent said: 好，正在往下挖，挖到就给你 👍
```

好消息：**Vercel 的 jev 真的通了**（0.9 s 返回，无 429）。坏消息：它给的是 **0.02 的抛硬币**，低于门槛 →
按设计交给规划模型 → 于是又回了一句确认。原因不是模型笨，是**我们喂的状态里没有"这是重复"的证据**：
reload 把内存里的聊天记录清了，state 里只有 new_messages + 空的 you_already_said。

### 两个修复

1. **确定性规则（不问模型，不花 token）**：同一条消息在 2 分钟内**逐字重复 ≥2 次**且不是问句 → 直接沉默。
   这正是玩家抱怨的场景，也是最不该花钱判断的场景。
2. **状态补全 + 提示词收紧**：state 现在带 `recent_conversation`（最近 6 条）、
   `this_exact_message_heard_times`（这条话最近被说了几次）、`you_last_spoke_seconds_ago`；
   提示词改成显式清单（什么情况 SPEAK、什么情况 STAY_SILENT）。

### 阈值标定（9 个用例打真实 jev，Vercel）

| 用例 | 期望 | jev | 置信度 | 0.85 阈值下的动作 |
|---|---|---|---|---|
| 闲聊 ×2 | SILENT | STAY_SILENT | 0.99 / 0.97 | 沉默 ✅ |
| 「继续挖钻石」/「多挖点钻石」（已在做） | SILENT | STAY_SILENT | 0.90 / 0.89 | 沉默 ✅ |
| 「帮我把营地的桶清一下」 | SPEAK | STAY_SILENT | 0.47 | 交给规划模型 ✅ |
| 「帮我做个箱子放东西」 | SPEAK | STAY_SILENT | 0.70 | 交给规划模型 ✅ |
| 「来我这边一下」 | SPEAK | STAY_SILENT | 0.77 | 交给规划模型 ✅ |
| 「把背包里的石头扔掉」 | SPEAK | STAY_SILENT | 0.66 | 交给规划模型 ✅ |
| 重复 ×3 | SILENT | STAY_SILENT | 0.97 | 沉默 ✅ |

结论：**应沉默的 4 例落在 0.89–0.99，应回答的 4 例落在 0.47–0.77**，因此把说话闸门门槛从 0.6 提到
**0.85**（挖矿恢复仍是 0.55，两个常量分开）。0.75 时会误吞「来我这边一下」(0.77)，0.85 不会。

### 验证

```
SPEECHTEST duplicate  : gateAsked=false modelTurn=false botSpoke=false (a verbatim repeat needs neither)
SPEECHTEST chat proto : gateAsked=true modelTurn=false botSpoke=false
SPEECHTEST breaker    : messages=4 attempts=3
SPEECHTEST VERDICT   : PASS
```

### 顺带：我自己会打指令了

生产服的控制台通道是死的（两个 MC 进程 ppid=1、pty helper 已消失、stdin socket 对端关闭），面板和
daemon 都够不着。改用 **JVM Attach API**：`<服务器>/mcagent-runtime/tools/console-agent.jar` 注入后
在 Server thread 上以 op 权限执行命令，与手打控制台等价。本轮 reload / spawn 都是这样完成的。


## 2026-09-22（凌晨二）：把 jev 模型本身接到 Vercel —— ✅ 真实客户端验证通过

### 先纠正一件事：jev 是模型，不是"快速决策层"的代称

`jev` = TypeSafe AI 的 **System One evaluation model**（Vercel 上 `typesafe-ai/jev`，OpenCode Zen 上
`jev-1.13-free`）。网关里的描述写得很清楚：*"evaluates shared state against typed questions and returns
choices, scores, and boolean probabilities"* —— 也就是我们一直在用的 `/systemone` 那套信封。
所以「换 Vercel」= **换的是这个模型的接入点，不是换模型**。此前我把 JEV 指到本地代理端点、又去试
`inclusionai/ling-*` 之类别的模型，都是跑偏，已回退。

### 关键发现：jev 在 Vercel 上要走 `/v1/evaluate`

- `POST /v1/chat/completions` + `typesafe-ai/jev` → **400**：
  `Model 'typesafe-ai/jev' is an evaluation model, not a language model. Use the evaluation generation API instead.`
- `POST /v1/evaluate` ✅ 返回的正是 System One 形状：
  `{"model":"typesafe-ai/jev","answers":{"q":{"type":"choice","choice":"A","probabilities":{...},"confidence":...}}}`

因为形状一致，**`JevClient` 一行代码都不用改**，只改配置：`protocol=systemone`（本来就是默认）。

### 真实客户端打 Vercel（编译好的 `JevClient`，非 curl 模拟）

```
model=typesafe-ai/jev endpoint=https://ai-gateway.vercel.sh/v1/evaluate protocol=system_one
重复指令   6125ms  failed=false choice=STAY_SILENT 0.90 probs={SPEAK=0.05, STAY_SILENT=0.95}
新指令      459ms  failed=false choice=SPEAK       0.99 probs={SPEAK=1.0,  STAY_SILENT=0.0}
闲聊        444ms  failed=false choice=STAY_SILENT 0.07 probs={SPEAK=0.47, STAY_SILENT=0.53}
breakerOpen=false
```

第一次调用 6.1 s（冷启动），热调用 0.4–0.6 s，因此 `timeoutMillis=8000`。闲聊那条置信度 0.07 低于 0.6
门槛 → 按设计交给规划模型，不会误吞。

### 计费（据网关 `/v1/models` 的 `pricing` 字段）

`{"input":"0.000000042","output":"0"}`：**输出免费，输入按 $0.000000042/token**（≈ $0.042 / 百万 token）。
一次门控调用约 150 输入 token ≈ **$0.0000063**。不是字面意义的零消耗，但量级上可以忽略。另外该模型
`zdr: all`、`no_training: all`。

### 边界（按要求）

- Vercel 的 key **只写在 `mcagent-jev.properties`**，只服务 JEV；`mcagent-llm.toml`（规划模型
  `deepseek-v4.1-flash` @ `http://10.0.6.6:7863/v1`）本次及之前**都没有被改动**（mtime 仍是 09-20 22:25）。
- 25 号 Vercel 停用后：配置里已写好两条退路（回到 Zen 的 `jev-1.13-free`，或 `speechGate=shadow/off`），
  且 JEV 失败一律 fail-open（熔断 + 回退规划模型），不会影响主模型。


## 2026-09-22（凌晨）：JEV 换成 OpenAI 兼容协议（Vercel 被卡在绑卡）—— ✅ 真实端点验证通过

### Vercel AI Gateway 现状（实测）

- 给的 key **能过鉴权**：`GET https://ai-gateway.vercel.sh/v1/models` → `200`，列出 **377 个模型**
  （`google/gemini-2.5-flash-lite`、`anthropic/claude-3-haiku`、`amazon/nova-lite`、
  `alibaba/qwen3.8-flash`、`deepseek/deepseek-v4-flash` 等）。
- 但**任何 completion 都被拒**：

```
HTTP 403  {"error":{"message":"AI Gateway requires a valid credit card on file to service requests.
           Please visit https://vercel.com/d?to=...ai?modal=add-credit-card to add a card and unlock
           your free credits.","type":"customer_verification_required"}}
```

  这是账户侧动作（在 Vercel 后台绑卡），代码和配置改不动它。

### 因此补上的能力：`protocol=chat`

OpenCode Zen 的 `/systemone` 是它独有的 typed-choice 信封；Vercel 和任何本地代理都只讲
`/chat/completions`。`JevClient` 现在支持两种协议（`protocol=systemone|chat`，默认 systemone）：

- chat 模式下把契约写进提示词（"只回一个 JSON：{choice, confidence}"），并把候选动作一起给出；
- 解析 `choices[0].message.content` 里的第一个 JSON 对象（容忍前后散文/代码块）；
- **校验 choice 必须是调用方给过的候选**，模型编一个没给过的选项 = 失败而不是"未知动作"；
- 内容为空（推理模型把预算全用在思考上）会明确报错并提示提高 `maxTokens`，不会被当成"沉默"。

### 现在的生产配置（本地端点顶上来）

Vercel 绑卡之前，先把 JEV 指到 bot 规划模型已经在用的本地 OpenAI 兼容端点（无 429）：

```
protocol=chat
endpoint=http://10.0.6.6:7863/v1/chat/completions
model=cn:deepseek-v4-flash        # 实测 1.3–1.9 s，返回干净 JSON
timeoutMillis=6000
maxTokens=800
speechGate=active
shadowMode=true
```

Vercel 那五行已写好并注释在同文件里，绑完卡注释切换 + `/mcagent reloadconfig` 即可。

### 验证

隔离服（stub 同时提供 `/systemone` 与 `/v1/chat/completions`）：

```
SPEECHTEST chat proto : gateAsked=true modelTurn=false botSpoke=false (a confident STAY_SILENT over chat/completions must suppress the turn)
SPEECHTEST VERDICT   : PASS
```

**真实端点**（直接用编译好的 `JevClient` 打本地端点，不是 curl 模拟）：

```
settings: model=cn:deepseek-v4-flash ... speech_gate=active protocol=chat
1868ms  failed=false choice=STAY_SILENT confidence=0.90   ← 「继续挖钻石 多挖点钻石」+ 已经说过话
1533ms  failed=false choice=SPEAK       confidence=1.00   ← 「Agent 帮我把营地的桶清一下」
1348ms  failed=false choice=STAY_SILENT confidence=0.75   ← 无关闲聊
breakerOpen=false
```

第一条正是玩家抱怨的那类重复指令，模型给出了 STAY_SILENT 0.90 —— 超过闸门 0.6 的门槛，会真正省下那一轮
LLM。


## 2026-09-22（凌晨）：它"像宕机"的两个真原因 —— ✅ 已定位并修复

玩家反馈「怎么感觉他宕机了」。先确认事实：**服务器和 bot 都活着**（日志持续写入，bot 在 23:54 还在
挖 (898,59,419)，6 分钟 25 轮 LLM）。真正的问题是下面两件事，都让它"看起来死了"。

### 原因 1：玩家不点名时，它拒绝回话

日志原文（23:52:45，玩家 23:52:40 连发三条「继续挖钻石 多挖点钻石」）：

```
Bot Agent called say(message="好的，正在往深处挖，有钻石就告诉你") -> not sent: this is routine progress narration. Keep working silently; ...
```

`ChatLog.record` 只在文本里出现 bot 名字时才把消息标成 `directed`；玩家那句话没点名 → `directlyAddressed=false`
→ `sayAsTool` 把它的回复判成「无人问津的进度播报」直接拒发。于是**它在认真干活、却一句话都不回**，
从游戏里看就是宕机。

**修复**：当**只有一个 bot 能听到**这条消息时，任何话都算对它说的（多 bot 在场时仍然要求点名，避免
抢别人的对话）。

### 原因 2：看门狗每 60 秒撕掉一次 plan

```
Bot Agent has not completed a decision in 1201 ticks (state: mining Stone at 897, 62, 416, about 645 ticks left); forcing one
```

`ticksSinceDecision` 每 tick 都加，一次长挖矿（30–40 s）叠一次慢 turn（10–15 s）就会超过 1200 tick；
看门狗随即 `abandonPlan("the bot was stuck and had to be restarted")`，把整条计划丢掉重来。最近 15 分钟
3 次，配套症状就是反复「挖空气 / 空 plan / 来回走」：

```
3 × failed: there is no block at ... it is open air
1 × failed: a plan needs at least 2 steps
2 × failed: could not deposit ...      ← 桶满了，lapis 87 个放不下
```

**修复**：看门狗在"正在进行的长时间动作"期间不再计数（移动与挖掘各自都有 no-progress 检查，安全性不减）。

### 顺带：minedrop 测试的用例被建筑保护挡下了

`MINEDROPTEST FAIL : the partial-fit job started` —— 它原来用**书架**做"背包只放得下一个"的用例，而书架
是玩家家具，被保护规则正确拒绝。改用**黏土块**（自然方块，固定掉落 4 个黏土球），断言从"3 本书"改为
"4 个黏土球，1 个进包、3 个留在地上"。

### 验证

```
CHATINVOKETEST latency=2 message=true ... modelStayedSilent=true unnamedCountsAsAddressed=true
CHATINVOKETEST VERDICT: PASS          ← 未点名的消息现在被当作"对我说"，旧代码必挂
CHATTEST VERDICT : PASS               ← 被点名时不被限流、逐字重复仍被拒
SPEECHTEST VERDICT : PASS             ← 闸门抑制/问句绕过/复读抑制/429 熔断
STRUCTTEST VERDICT : PASS             ← 建筑保护
MINEDROPTEST VERDICT : PASS           ← 半径挖掘、掉落、可达性通道
```

### 仍待解决

- **JEV 端点持续 429**：23:52:41 那次闸门调用仍然是 `unavailable=HTTP 429`，说话闸门依旧 fail-open。
- **bot 的储物满了**：`could not deposit minecraft:lapis_lazuli, count=87`。


## 2026-09-21（深夜）：JEV 生产数据分析 —— 限流是真问题，已加退避并收窄触发

### 生产实测（`latest.log`，21:29–23:34，bot=Agent）

**挖矿恢复（`shadowMode=true`，仍是 shadow）**：共 735 次调用，**485 次拿不到答案**
（480 × `HTTP 429`、4 × 连接超时、1 × 请求超时）——**失败率 66%**，且 429 从 21:29 一直持续到
23:34（每 10 分钟 20–100 次），属于持续限流而不是偶发。

有答案的 250 条：

| 项 | 值 |
|---|---|
| choice | `SKIP_TARGET` 154、`GATHER_PERCEPTION` 96 —— **从未出现 RETRY / BACKTRACK / ESCALATE** |
| confidence | min 0.27 / median 0.37 / p90 0.42 / max 0.70 / mean 0.373 |
| ≥ 0.55（active 门槛） | **3 / 250** |
| 触发源 | `TARGET_BECAME_AIR` 232、真失败 ~18（`APPROACH_NO_PROGRESS` 8+3、`BREAK_REJECTED` 6、`NO_REACHABLE_STAND` 3+1+2） |

结论：现在翻 active 依然没有收益（98.8% 会 abstain 并强制一次重规划），而且 2/3 的调用根本拿不到答案。

**说话闸门（`speechGate=active`）**：生产上第一次真实决策发生在 23:34:19，玩家
`<FirstMelody> 继续挖钻石 你现在有多少钻石了` → **闸门调用 429** → 按设计回退给规划模型 →
bot 回了一句「现在有10颗钻石，正在继续往下挖。」。也就是说：**闸门至今 0 次成功决策，全部 fail-open**，
"少说话"这个目标目前不是 JEV 在兑现。

同时暴露一个设计缺口：那句话**没有点名 bot**，而"问句不过闸门"的规则当时写的是"点名 + 问句"，
所以闸门真的被叫去了。端点正常时它有可能对"你现在有多少钻石了"判 STAY_SILENT —— 不可接受。

### 改动

1. **429 退避（`JevClient` 熔断）**：连续 3 次失败后暂停 JEV 调用 45 秒，并只告警一次。
   480 次被拒的请求里，每一次都是对着一个已经说"不"的端点再敲一遍——既浪费配额，又和说话闸门抢
   同一份额度。熔断期间 `choose()` 立即返回 `breaker open for another Ns`，调用方照旧回退。
2. **收窄挖矿恢复的触发**：`job.failures` 只有 `TARGET_BECAME_AIR` 时不再询问 JEV。那是竞态不是失败
   （方块在轮到它之前就没了），却占了 232/250 条有答案的调用，而且从未产出过值得执行的动作。
   这一条直接给说话闸门腾出配额。
3. **问句一律不过闸门**（不再要求点名）：任何看起来是问句的消息直接交给规划模型。

### 验证（隔离服日志原文，`MCAGENT_SPEECH_TEST=true`）

```
SPEECHTEST instruction: gateAsked=true modelTurnWithin30Ticks=false botSpoke=false
SPEECHTEST question   : gateAsked=false modelTurn=true      ← 未点名问句（生产原句）+ 点名问句，两个都绕过
SPEECHTEST shadow     : gateAsked=true modelTurn=true
SPEECHTEST repeat     : refused=true linesHeard=[收到，这就下矿挖钻石]
Jev endpoint failed 3 times in a row (HTTP 429); pausing Jev calls for 45s so the quota is not spent on refusals
SPEECHTEST breaker    : messages=4 attempts=3 (three 429s must stop the fourth call)
SPEECHTEST VERDICT   : PASS
```

熔断断言同样是"旧行为必挂"：4 条消息只允许 3 次 HTTP 尝试，第 4 次必须在本地被挡下。


## 2026-09-21（晚）：spawn 回到下线位置 + 全量 tab 补全 —— ✅ 隔离服验证通过

### 起因（玩家实操反馈）

- `/mcagent spawn <名字>` 不带坐标时用的是**命令执行者的位置**，所以一个在 200 格外挖矿的 bot
  被叫回来时会出现在操作者脚下；下线位置其实一直存在它自己的 `playerdata/<uuid>.dat` 里，没人读。
- 所有需要 bot 名字的参数（goto/pause/resume/think/stop/return/escape/remove/inventory/goal）
  **没有任何补全**，而 bot 名是大小写敏感的真实玩家名，只能凭记忆手打。

### 改动

- `BotManager.savedLogout(name)`：读 `world/playerdata/<offline-uuid>.dat` 的 `Pos` / `Rotation` /
  `Dimension`，返回位置、朝向和维度；文件不存在、维度未知或 y 越界时返回 null。
  （原版 `PlayerList.placeNewPlayer` 只会用存档里的维度切世界，**从不恢复坐标**——坐标平时由客户端
  提供，这就是 bot 会跑到操作者脚下的原因。）
- `spawn` 不带坐标时：先试 `savedLogout`，命中就"回到上次消失的地方（含维度）"，否则退回"你站的
  位置"，并在命令回执里说明用的是哪一种。`spawn` 的 `fresh` 依然先清档，因此不会"复活"。
- `BotManager.spawn(...)` 新增 yaw/pitch 重载，复活时连朝向一起恢复。
- 命令树：`botNameArgument()` 给所有在线 bot 名参数加补全（`pause`/`resume` 仍并列 `all`）；
  `spawnNameArgument()` 补全"有存档、可以叫回来"的名字——名单来自服务器自己的 `usercache.json`
  （原版 `GameProfileCache` 的条目类型是包私有的，`load()` 在外部用不了），再用
  `BotManager.hasSavedData` 过滤，缓存 30 秒。

### 验证（隔离服日志原文，`MCAGENT_CMD_UX_TEST=true`）

```
CMDUXTEST ran: /mcagent spawn UxBot 40 -36 40
CMDUXTEST spawn       : bot at 40, -36, 40 (requested 40, -36, 40)
CMDUXTEST ran: /mcagent remove UxBot
CMDUXTEST ran: /mcagent spawn UxBot
CMDUXTEST resume      : landed=40.5 -36.0 40.5 requested=40, -36, 40 distanceFromPlot=0.0 distanceFromCommandSource=62.1 (a resume must land on the plot, not on the operator)
CMDUXTEST completion  : 'mcagent goto' -> [UxBot] (has UxBot)
CMDUXTEST completion  : 'mcagent pause' -> [all, UxBot] (has UxBot)
CMDUXTEST completion  : 'mcagent resume' -> [all, UxBot] (has UxBot)
CMDUXTEST completion  : 'mcagent think' -> [UxBot] (has UxBot)
CMDUXTEST completion  : 'mcagent stop' -> [UxBot] (has UxBot)
CMDUXTEST completion  : 'mcagent return' -> [UxBot] (has UxBot)
CMDUXTEST completion  : 'mcagent escape' -> [UxBot] (has UxBot)
CMDUXTEST completion  : 'mcagent remove' -> [UxBot] (has UxBot)
CMDUXTEST completion  : 'mcagent inventory' -> [UxBot] (has UxBot)
CMDUXTEST completion  : 'mcagent goal' -> [UxBot] (has UxBot)
CMDUXTEST completion  : 'mcagent spawn' -> [ChatBot, ChatInvokeBot, ..., UxBot] (has UxBot)
CMDUXTEST failures    : 0
CMDUXTEST VERDICT     : PASS
```

两条断言都写成"旧行为必挂"的形式：复活落点与命令源相距 62.1 格（所以 `distanceFromPlot=0.0` 只能是
真的恢复了存档坐标），补全列表必须包含 bot 名（旧代码返回空列表）。补全用的是客户端 tab 键同一条
路径：`dispatcher.parse(整行)` → `getCompletionSuggestions(...).join()`。

回归：`MCAGENT_CMD_TEST`（命令树、pause/resume、goal、think）全部照常，`registered 'mcagent *': true`。


## 2026-09-21（下）：玩家建筑保护 + JEV 说话闸门 —— ✅ 隔离服验证通过（含反向对照）

### 起因（生产实例证据，不是推测）

- 生产日志 `latest.log` 21:05:03 显示 bot Agent 走进营地（889,63,362 的桶存矿），21:05:09 触发
  `escape_up`：`planned emergency escape from 888, 63, 362: digging a 2-step staircase east ... 4 block(s)
  will be mined`，实际挖掉 (889,66,362)、(890,65,362)（耗时 300 tick）、(890,66,362)、(890,67,362)
  —— 这就是「拆了房子为了出去」。
- 同一 session 的 `mine()` 调用记录里有 891,63,366 → 891,58,372、893–899,58–63,368、894–900,58–63,362
  等一串从营地地板向下开的矿道，即「在基地里挖了两个坑」。
- 根因：`dig_tunnel` / `escape_up` / `MiningAccessPlanner` 只认识流体、下落方块、方块实体和不可破坏
  地形，**没有「这是人建的」这一概念**；而矿道入口记忆恰好锚在营地内部（记忆里写着
  `entrance 891,63,368 (camp)`），于是每次挖矿都从基地里开洞。
- 聊天方面：同一条指令收到三次「收到…」。每轮 chat 触发的决策都会花一整轮 12k token 的模型问
  「要不要回话」，而模型每次都答「要」。

### 改动 1：玩家建筑保护（`rt/perception/PlayerStructure.java`，默认开启）

- **fixture（家具/机器）**：床、箱子、桶、熔炉、工作台、门、玻璃、灯笼、火把、告示牌、任何带方块
  实体的方块（含模组机器/容器），任何位置都不允许挖。
- **structure（建筑）**：21×13×21 扫描「建筑调色板」方块（按方块 id 词根匹配，覆盖模组），同时存在
  ≥1 个 fixture 且累计 ≥16 个建筑方块时，整个包围盒成为保护区，**向下延伸 6 格**（地板与地基同样
  不可挖）。8 格分块 memo 缓存 100 tick，避免可达性规划的上千次查询重复扫描。
- 四条破坏路径全部拒绝：`startMine`（mine/mine_resource/半径扩散/清理方块的总入口）、
  `dig_tunnel`（建筑内不许开新矿道；沿已有矿道的「走回去」不受影响）、`escape_up`、
  `MiningAccessPlanner`（不再规划穿墙路线）。拒绝信息说明是哪个结构、有哪些家具、该怎么绕开。
- `escape_up` 在建筑内改为**非破坏脱困**：结构外找可站立点 + 普通寻路走出去（会自己开门），
  失败才建议 `return_to_spawn`。
- 观察里明确写出保护区（`Semantic scene` 段），提示词与工具描述同步说明。
- 运维开关 `MCAGENT_STRUCTURE_GUARD=off`（环境变量门控，生产服不会误开）。

### 改动 2：JEV 说话闸门（`speechGate=off|shadow|active`，默认 shadow）

- 在 `startDecision()` 里，chat 触发的决策先问 JEV 一个 typed choice：`SPEAK` / `STAY_SILENT`。
  active 模式下高置信度（≥0.6）的 `STAY_SILENT` **直接跳过这一轮 LLM 调用**，只留日志和行动报告。
- 硬安全规则：**点名 bot 的问句永不过闸门**（直接交给规划模型）；JEV 超时/报错/低置信度一律回退到
  原行为。判断为「沉默」时消息标记已读，避免反复触发。
- 与 `shadowMode`（挖矿恢复）解耦：可以先让说话闸门 active、挖矿恢复继续 shadow。
- 配套：`say` 新增 3 分钟窗口的复读抑制（忽略标点/空格，包含关系也算），提示词里直接列出 bot 最近
  说过的话并明确要求「不要再发一次已经发过的确认」。原有「一次决策只说一句」「不许逐字重复上一句」
  继续保留，两层互补。

### 验证（隔离服日志原文）

```
STRUCTTEST: armed (guard on)
STRUCTTEST mine wall   : refused=true wallIntact=true botAt=-70, -36, -66
STRUCTTEST dig tunnel  : refused=true
STRUCTTEST escape_up   : walkOutOffered=true leftHouse=true
STRUCTTEST house blocks: before=180 after=180
STRUCTTEST control site : digTunnel away from the house -> digging and walking through a 3-block level tunnel east; 6 block(s) will be mined with normal timing
STRUCTTEST result  : control tunnel accepted away from the house and blocks were broken; leftHouseAfterEscape=true
STRUCTTEST VERDICT : PASS
```

反向对照（`MCAGENT_STRUCTURE_GUARD=off`，证明拦住 bot 的正是这个 guard）：

```
STRUCTTEST: armed (guard OFF)
STRUCTTEST mine wall   : refused=false wallIntact=false botAt=-70, -36, -72
STRUCTTEST dig tunnel  : refused=false
STRUCTTEST escape_up   : walkOutOffered=false leftHouse=false
STRUCTTEST house blocks: before=180 after=179
STRUCTTEST control     : guard OFF -> wallBroken=true refused=false (this is the damage the guard prevents, and the reason no production server sets MCAGENT_STRUCTURE_GUARD)
STRUCTTEST VERDICT : CONTROL-PASS (guard disabled and the house was damaged, as expected)
```

说话闸门（stub System One 固定返回 STAY_SILENT 0.95）：

```
SPEECHTEST instruction: gateAsked=true modelTurnWithin30Ticks=false botSpoke=false (active gate must suppress the turn for this message)
SPEECHTEST question   : gateAsked=false modelTurn=true
SPEECHTEST shadow     : gateAsked=true modelTurn=true
SPEECHTEST repeat     : refused=true linesHeard=[收到，这就下矿挖钻石]
SPEECHTEST VERDICT   : PASS
```

回归（相邻行为没有被破坏）：

```
CHATTEST VERDICT : PASS
CHATINVOKETEST latency=2 message=true self=true world=true allInventory=true activity=true modelStayedSilent=true
CHATINVOKETEST VERDICT: PASS
```

### 259 mod 环境的独立审核（只读扫描 261 个 jar / 24432 个模组方块 id）

发现并已修复：

- **崩溃**：`Set.of(path.split("_"))` 遇到重复词根的 id（如 `chipped:bricks_bricks`，本包 153 个）会抛
  `IllegalArgumentException`，异常会穿出 `protectionReason`/`detect` 逃到行动层。已改为
  `Set.copyOf(new LinkedHashSet<>(...))`，并给 `classify` 加了兜底（异常时按「建筑方块」处理并只告警一次）。
- **误判（会导致正常挖矿被锁）**：`moss_carpet`（繁茂洞穴地毯）、badlands 的 terracotta、
  `rose_quartz_*`（BOP 自然生成）、`obsidian`（岩浆湖/末地柱）、`suspicious_sand/gravel`、
  `endbloom`（"loom" 子串命中）—— 已从 token/标签里剔除，或加入 `NATURAL_PHRASES` 白名单。
- **漏判（该保护却没保护）**：Create 的 `*_casing`（无方块实体）、`iron_bars`、cobblestone /
  cobbled_deepslate、Railways `locometal`、IE 的 `hempcrete/blastbrick/treated_wood/sheetmetal`、
  可合成的 `beehive` —— 已补 token/phrase，并把自然方块实体改成整 id 精确匹配（`create:item_vault`
  不会再被 vanilla `vault` 豁免）。

## 2026-09-21：遮挡开采、语义感知与 JEV —— ✅ 隔离服验证通过

- 保留最多 5 个方块的轻度透视，但观察现在标记 `EXPOSED/OCCLUDED`，行动层用严格射线验证；
  遮挡资源不会再隔墙直接挖。
- 新增多目标可达站位搜索和 bounded mining-access planner。普通 A* 到不了时，规划两格高安全通道，
  拒绝流体、下落方块、方块实体、危险和不可破坏地形。
- 挖矿失败变为结构化原因，验证破坏后的真实方块状态，移动 100 tick 无进展即终止，不再依赖一分钟
  watchdog；新增 `find_resource` / `mine_resource`，避免 LLM 猜坐标。
- `plan` 新增原子的 `replace_current=true`，取代提示词要求但 schema 不允许的嵌套 `interrupt`。
- 矿道记忆从入口/工作面两点扩展为最多 24 个 breadcrumbs，返矿按分段路点行走。
- 新增确定性 semantic scene：围合/地下状态、顶板、局部连通区域、出口方向、危险物。
- 接入 OpenCode Zen `jev-1.13-free` System One client。默认 shadow 记录挖矿失败后的有限候选动作；
  active 模式只执行白名单恢复，带 0.55 置信度门槛、一次重试上限、过期响应拒绝和新工作冲突保护。
  配置是 runtime-only，无需服务器重启；active 分支已构建通过，仍应先用生产 shadow 样本校准。
- 隔离服封闭矿石用例：目标被宽 17、高 7 的实体石体包围；规划器生成 4 个移动节点、4 个预清理
  方块，最终脚部 5 格/头部 4 格通道打开并挖掉目标，`MINEDROPTEST VERDICT: PASS`。
- 原有 plan 并发预取回归仍为 `PLANTEST VERDICT: PASS`；完整 `build` 和 core/runtime 边界检查通过。
- 使用现有 OpenCode key 对 Zen `/systemone` 做真实请求：`jev-1.13-free` 返回 `PLAN_ACCESS`，
  confidence `0.91`，证明 endpoint、鉴权和请求/响应结构可用。

## 2026-09-20：真实玩家行为审计与生产热部署 —— ✅ 已完成

生产服在不重启的情况下切换到 `qwen3.8-flash`，runtime 已热加载并恢复原有 Agent。
Agent 的背包、生命/饥饿、重生点和 34 条长期记忆均已核验保留；生产端 LLM 测试返回 `OK`。

本轮补齐并实测了这些容易被“工具能调用”掩盖的真实玩家语义：

- `attack` 现在是会追击、按攻击冷却反复挥击、可中断的持续战斗，而不是站在原地只打一下；
  `nearest` 默认只选敌对生物，不会误伤旁边真人。
- 观察加入时间/天气、朝向、群系、光照、护甲、经验、主副手与装备耐久，以及近距离敌对生物
  `DANGER`；实体带坐标和方位，敌对生物不会再被掉落物挤出观察上限。
- 修复容器部分存入导致复制物品的严重问题；背包满时取物会原槽退回，不把溢出物丢到地上；
  自动拾取只使用普通背包槽，不会把材料塞进盔甲或副手槽。
- 3x3 合成采用有意的延迟妥协：无需寻找/走到合成台；配方校验和材料消耗仍完全真实。
- 新增隔离服战斗/观察/容器回归测试；战斗追击 3.87 格并用 3 次攻击击杀目标，容器数量守恒、
  采集掉落和原有寻路/合成/视线测试全部通过。
- 新增 `plan`：一次 LLM tool call 可携带最多 24 个严格顺序动作；队列低于 6 步时会在当前
  动作执行期间预取下一批。任一步默认失败即取消后续依赖步骤并立即重规划。隔离测试中第二次
  LLM 响应被故意延迟 8 秒，bot 仍在响应返回前完成了 3 段行走和最终聊天，证明调用间没有挂机。

### 下一阶段的真实玩家差距（按优先级）

1. **生存反应**：低血量撤退、主动进食、火/岩浆/溺水脱险、摔落风险和安全挖矿策略。
2. **装备决策**：自动选择工具/武器、穿脱护甲、盾牌格挡，以及弓弩等远程战斗。
3. **立体移动**：梯子、藤蔓、游泳、蹲行、防坠边缘与搭方块脱困；当前 A* 主要覆盖地面行走。
4. **复杂交互**：熔炉和各类 mod 机器 GUI、村民交易、附魔/铁砧等菜单语义。
5. **长任务执行**：蓝图式多方块建造、脚手架、材料预算、任务恢复和失败后的重规划。
6. **社交边界**：领地/容器所有权目前主要依赖提示词与记忆，仍需服务端权限层的硬约束。
7. **更强回归场景**：移动敌人、多人混战、远程怪物、网络/LLM 超时期间的安全行为。

## 里程碑 1：服务端虚拟玩家核心 —— ✅ 已实测通过

**验证时间**：2026-09-19
**验证方式**：真实启动 NeoForge 21.1.248 专用服务器，自动冒烟测试
**验证环境**：`/ymtc/Repos/mc-agent/build/smoke-server`（超平坦世界，独立于生产服）

### 实测输出（服务端日志原文）

```
SMOKETEST: spawning bot at (0.5, -58.0, 0.5)
SMOKETEST: walking to (12.5, -58.0, 0.5)
SMOKETEST: bot joined as a real player? name=SmokeBot uuid=99f0a0b2-... inPlayerList=true
SMOKETEST tick  20 | pos=(4.36,  -60.00, 0.50) | onGround=true | travel=5.38
SMOKETEST tick  40 | pos=(9.73,  -60.00, 0.50) | onGround=true | travel=10.74
SMOKETEST tick  60 | pos=(12.36, -60.00, 0.50) | onGround=true | travel=13.37
SMOKETEST tick 140 | pos=(12.36, -60.00, 0.50) | onGround=true | travel=13.37
SMOKETEST end          : (12.361075729740362, -60.0, 0.5)
SMOKETEST displacement : 12.03 blocks (required >= 3.0)
SMOKETEST reversals    : 0 (rewind indicator; 0 = no snap-back)
SMOKETEST onGround     : true
SMOKETEST health       : 20.0
SMOKETEST arrived      : true
SMOKETEST VERDICT      : PASS
SMOKETEST cleanup      : removed=true
```

### 这证明了什么（每条都是关键能力）

| # | 证明的结论 | 证据 |
|---|---|---|
| 1 | **假玩家是"真玩家"** | `inPlayerList=true`，有稳定 UUID，能进 `PlayerList`、被 `getPlayerByName` 找到 |
| 2 | **重力物理在跑** | 从 y=-58 落到 y=-60（超平坦地面），说明 `travel()` 在真实积分 |
| 3 | **能自己行走** | x 从 0.5 走到 12.36，位移 12.03 格，纯靠服务端写 `zza` + 原版物理 |
| 4 | **没有位置回滚** | `reversals=0`；位移单调累积而不是原地抖动 —— 客户端权威回滚已被消除 |
| 5 | **能正常停下** | 到达目标后 `arrived=true` 并静止（tick 60→140 位置不变） |
| 6 | **状态健康** | `onGround=true`（没穿地/没被弹出），`health=20.0` |
| 7 | **能干净移除** | `removed=true`，且不残留 playerdata |

### 结论

用户选定的方案（服务端 mod 内的假玩家，自行移动、无客户端渲染）**完全可行，已验证**。
最难的三个坑（客户端权威回滚、物理只在连接里跑、`channel()` 不能为 null）都已定位并解决。

---

## 关键技术决策（实测确认）

### 1. 假玩家 = 真 `ServerPlayer` + 假 `Connection` + 自定义监听器

- `BotPlayer extends ServerPlayer` —— 用 `UUIDUtil.createOfflineProfile(name)` 生成稳定的离线 UUID
- `NullConnection extends Connection` —— 持有真实注册的 `LocalChannel`（只为 channel 属性，不发数据）
- `BotGamePacketListener extends ServerGamePacketListenerImpl` —— 提供物理 tick，且**不做位置回滚**

**不要**继承 NeoForge 的 `FakePlayer`（它 `tick()`/`resetPosition()` 全空、`openMenu` 返回空、免疫伤害）。

### 2. 移动由物理引擎执行，我们只写输入

```java
bot.setYRot(Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz))));
bot.zza = 1.0F;              // 前进
bot.setSprinting(true);      // 服务端不会自动冲刺，必须显式设置
if (stuck && bot.onGround()) bot.setJumping(true);
```

偏航角公式由 `Entity.getInputVector` 的旋转约定推出（`zza=1` → 世界方向 `(-sin(yaw), cos(yaw))`）。
输入在**物理 tick 之后**写入（`aiStep` 每 tick 会 `*0.98` 衰减掉，写晚了会被吃掉）。

### 3. 每 tick 驱动顺序

`ServerTickEvent.Post` → `BotManager.tick()` → `bot.connection.tick()` →
(我们自己) `doTick()` 物理 + `ChunkMap.move()` 更新区块跟踪 → `MovementDriver.tick()` 决定下一 tick 输入。

**没有**注册到 `ServerConnectionListener`：因为 `isConnecting()` 对无 channel 连接恒为真，
会被它的循环跳过；而且会永久滞留在连接列表里。

---

## 里程碑 2：A* 寻路 —— ✅ 已实测通过

**验证方式**：在 bot 与目标之间**建一堵带单缺口的墙**，直线走法必然撞墙卡死。
能绕过去 = 真的在规划路径，而不是闷头直走。

### 实测轨迹（服务端日志原文，注意 z 坐标的变化）

```
目标：BlockPos{x=14, y=-60, z=0}（墙在 x=7，缺口在 z=3）

tick  20 | pos=( 3.47, -60.00, 0.56)   ← 朝 +X 前进
tick  40 | pos=( 6.56, -60.00, 3.47)   ← ★ 绕向缺口（z 从 0.56 → 3.47）
tick  60 | pos=(10.44, -60.00, 2.61)   ← ★ 已过墙，折返 z=0
tick  80 | pos=(14.06, -60.00, 0.94)   ← 接近目标
tick 100 | pos=(14.40, -60.00, 0.63)   ← 到达并静止

dist to goal : 0.16 blocks
passed wall  : true
reached goal : true
path length  : 17.74 blocks（直线距离 14.04，多出的 3.7 = 绕行缺口的路程）
reversals    : 0
VERDICT      : PASS
```

轨迹与「墙 + 单缺口」的几何完全吻合：先横向前进，在墙前折向 z=3 的缺口穿过，再折回 z=0。
路径长度多出的 3.7 格正是绕行的代价 —— 这是真实 A* 规划的直接证据。

### 实现要点

- 原版 `PathFinder.findPath(...)` **不能复用**：它硬编码 `Mob` 类型，而 `ServerPlayer` 不是 `Mob`；
  且它通过 `MoveControl` 驱动移动，玩家没有这个东西。所以自研 A*。
- 邻居模型贴合「会走路的玩家」：四方向 + 对角线、可上 1 格（自动上台阶）、可下落最多 3 格、
  不允许进入高度不足的列、避开岩浆/火/仙人掌/岩浆块/细雪/甜浆果丛。
- 对角线移动要求两个正交邻格都可通行，**避免穿墙角**。
- 启发函数用八向 octile 距离（对 8 向移动可采纳，保证 A* 最优性）。
- 展开节点上限 6000，避免病态请求卡住服务器 tick。
- 目标点解析：调用方常给「箱子所在的格子」，而那是站不上去的 —— 自动在附近搜索可站立格。
- `MovementDriver` 逐 waypoint 前进，到点自动切换下一个；无路径时回退直线并上报卡住。


---

## 里程碑 3：LLM 端到端闭环 —— ✅ 已实测通过

**验证方式**：真实启动服务器 + 真实调用 LLM。给 bot 一个具体任务：
「走到箱子那里，打开它，把钻石拿出来，然后在聊天里说你找到了什么。」

### 实测工具调用序列（服务端日志原文）

```
Bot BrainBot called goto(x=2, z=0)                -> walking to (2, -60, 0)
Bot BrainBot called goto(x=1.5, z=0.5)            -> walking to (2, -60, 1)
Bot BrainBot called open_container(x=2,y=-60,z=0) -> opened Chest (27 slots)
Bot BrainBot called withdraw(item="minecraft:diamond", count=3)
                                                  -> withdrew 3x minecraft:diamond
Bot BrainBot called say(message="Opened the chest at 2,-60,0 and took 3 diamonds.
                        It also had 7 blocks of iron (left those).")
```

**最终背包：`3x minecraft:diamond`**

### 这证明了什么

| 能力 | 证据 |
|---|---|
| **LLM 真正在决策** | 5 次工具调用全部由模型自主选择，不是硬编码流程 |
| **寻路可用** | 模型先 `goto` 目标点，再微调站位到箱子旁（1.5, 0.5） |
| **感知层正确** | 观察文本里箱子显示为「not opened - contents unknown」 |
| **容器门控生效** | 必须先 `open_container` 才能 `withdraw`；开了之后才知道里面有 7 铁块 3 钻石 |
| **能真实取物** | 钻石真的进了背包（`3x minecraft:diamond`），且**只拿了钻石**（模型自己决定留下铁块） |
| **能截图式汇报** | 聊天内容准确描述了箱子位置和内容，说明感知数据是真的 |
| **无错误** | `Tool threw / Brain task failed / Brain tick failed` 计数均为 0 |

### 感知层实际输出（模型看到的原文）

```
=== YOUR STATE ===
Position: 0.5, -60.0, 0.5
Health: 20/20  Food: 20/20
Holding: nothing (hotbar slot 0)

=== WHAT YOU CAN SEE ===
Blocks you can see (270 total, grouped):
  - Grass Block x268
  - Chest x1
  - Block of Gold x1
Containers you can see:
  - Chest at 2, -60, 0 (not opened - contents unknown)
```

### 知识层实际输出（含 mod 物品）

```
Knowledge index built in 13 ms: 2666 items, 1290 recipes (0 skipped)

> iron_block
minecraft:iron_block ("Block of Iron")
Made by 1 recipe(s):
  - 1x minecraft:iron_block  <-  9x minecraft:iron_ingot  [minecraft:crafting]
Used in 4 recipe(s).
```

1290 条配方全部成功索引（0 跳过）。知识层用服务端 `RecipeManager`，
**天然覆盖全部 mod 配方**，不依赖 JEI 客户端数据。


---

## 里程碑 4：合成能力 —— ✅ 已实测通过

**验证方式**：给 bot 9 个铁锭，要求它「先合成铁块，再去箱子拿钻石，最后汇报」。
模型自主完成全部三步，且自己的总结与实际完全一致。

### 实测完整序列

```
craft(item="minecraft:iron_block", count=1)   -> crafted 1x minecraft:iron_block
goto(x=2, z=0)                                -> walking to (2, -60, 0)
open_container(x=2, y=-60, z=0)               -> opened Chest (27 slots)
withdraw(item="minecraft:diamond", count=3)   -> withdrew 3x minecraft:diamond
say("Crafted an iron block from my 9 ingots, then walked to the chest at 2,-60,0
     and grabbed all 3 diamonds from it. Done!")

最终背包: 1x minecraft:iron_block  8x minecraft:oak_planks  3x minecraft:diamond
错误计数: 0
```

### 合成实现的关键决策

**不复用 `ServerPlaceRecipe`**：它要求一个已打开的 `RecipeBookMenu` 和真实客户端来驱动格子点击，
headless bot 无法满足。改为：自己按配方布局摆放物品 → **交给游戏自己的 `RecipeManager.getRecipeFor`
判定** → 判定通过才扣除材料。这样 mod 配方的自定义匹配谓词无需我们理解也能正确工作。

**一个被实测抓出来的真 bug**：最初的匹配逻辑要求「每个背包槽位只能用一次」，
导致 9 个铁锭（在同一个堆叠里）永远无法合成铁块，但 1 个木板的按钮可以。
修复为**按槽位计数量预算**，与玩家实际体验一致。

这个 bug 只有真正跑起来才会暴露 —— 静态审查和编译都不会发现它。


---

## 里程碑 5：挖掘能力 —— ✅ 已实测通过

**验证**：让 bot 去挖一个金块。日志显示它先 `goto` 靠近、`look_at` 对准、再 `mine`。

```
goto(x=3, z=3)      -> walking to (3, -60, 3)
observe()           -> (感知确认周围)
look_at(x=3,y=-60,z=3)
mine(x=3,y=-60,z=3) -> started mining 3, -60, 3 (about 300 ticks)

最终: gold block at BlockPos{x=3, y=-60, z=3} is now Block{minecraft:air}
```

金块**真的被挖掉了**（方块状态变成空气）。而且挖掘时长是**按工具和方块硬度真实计算**的：
空手挖金块需要 300 ticks（15 秒），与 `BlockState.getDestroyProgress` 一致，
而不是瞬间破坏。这保证了「像玩家一样」的真实感。

**掉落物拾取**：掉落物以 `ItemEntity` 形式落在地上，需要 bot 走过去才会拾取
（原版 `ItemEntity.playerTouch` 行为）。测试结束时 bot 已走开，所以没捡到 —— 这是正确行为，不是 bug。


---

## 待办

- [x] A* 寻路（已实测绕过障碍）
- [x] 感知层（无遮挡 raycast 可见性 + 容器内容门控，已端到端实测）
- [x] 动作层（挖/放/开箱/取放/攻击/使用/聊天/受限命令/合成）
- [x] 物品与配方知识层（2666 物品 / 1290 配方，覆盖全部 mod）
- [x] LLM 客户端与决策循环（OpenAI 兼容 + function calling，已实测）
- [x] 部署到生产服（runtime 热加载，无需重启；2026-09-20 已验证状态保留）

---

## 里程碑 6：上下文管理 —— 发现并修复一个会导致线上静默失效的真 bug

### 发现的 bug：历史裁剪会破坏消息结构

原来的裁剪逻辑是 `history.remove(2)` —— **按裸索引删除**。但一轮对话是一个**结构组**：
`user`(观察) + `assistant`(请求调用工具) + 每个工具调用一条 `tool`(结果)。

按索引乱删会把 `assistant` 删掉却留下它的 `tool` 结果。**OpenAI 兼容 API 会校验这个结构，
直接返回 400** —— bot 会在跑够轮数后静默停止工作。

我的测试之所以没暴露它，是因为测试用的那个 endpoint 校验宽松。

**用模拟验证**（2000 组随机工具调用数）：

```
旧逻辑:  structurally broken : 965/2000   (~48% 的历史会变成非法结构)
新逻辑:  structurally broken : 0/5000
         over budget         : 0/5000
         turn boundary ok    : 5000/5000
```

### 修复内容

1. **按「轮」裁剪，不按索引** —— 只在 `user` 边界切分，永不拆散一组
2. **系统提示永不裁剪** —— 它承载 bot 身份和权限集，丢了会改变行为而不只是缩短上下文
3. **超长工具结果就地压缩** —— 观察文本是体积大头（每轮几 KB 的方块列表），
   把旧结果的正文替换为占位符，**保持消息结构不变**
4. **节流反馈** —— 超过单轮工具调用上限时明确告知模型「被限流了」，
   而不是让它以为动作执行成功了（原来这里还有个死变量 `results` 从没被用过）

### 顺带发现并修复的性能问题

对观察扫描加了**实测**（不猜）：

```
visibleBlocks radius=12 -> 622 visible (air=13124, budgetHit=false) in 7-23 ms
```

发现三件事：
- 单次观察 **7-23ms 且跑在服务端主线程上** —— 密集建筑群只会更慢
- 原来的 600 上限**在超平坦世界就已被打满**，说明它在真实世界会一直触发
- 而 `betweenClosed` 的迭代顺序是 x→y→z，**提前 break 会让采样偏向低坐标**

对应修复：加 25ms 时间预算（超时就用已有结果，部分视野好过卡 tick）、
放宽到 2 倍上限 + 按距离排序保证保留最近的部分、计时仅在上限打满时记录。


---

## 里程碑 7：Token 统计 + 上下文压缩 + 代码审查

### 1. Token 统计（已实测）

调用方返回的 `usage` 之前**完全没被解析**。现在解析并记录，且发现了一个严重问题：

**字符估算严重偏低 —— 实测偏差 2.4-4.9 倍**

```
turn  1: prompt=2349  (estimate was  484)   ← 4.9x
turn  5: prompt=3568  (estimate was 1231)   ← 2.9x
turn 10: prompt=4621  (estimate was 1943)   ← 2.4x
```

原因是**工具 schema 不在 history 里但要每次发送** —— 14 个工具的 JSON schema 约 1800 token。
所以设 12k 预算实际相当于 ~29k 真实 token，**bot 会在压缩触发前就撑爆上下文窗口**。

修复：把工具 schema 计入估算 + **用服务端返回的真实值做在线校准**（EMA）。
校准后误差降到 **1-3%**：

```
turn  1: real 2349  estimate 2349   (0%)
turn  4: real 2933  estimate 2904   (-1%)
turn 10: real 2878  estimate 2847   (-1%)
```

### 2. 上下文压缩 best practice（已查证并落地）

参考 [Microsoft agent-framework compaction 文档](https://learn.microsoft.com/en-us/agent-framework/concepts/agents/conversations/compaction)
与 [OpenAI cookbook 的 session memory](https://developers.openai.com/cookbook/examples/agents_sdk/session_memory)，
业界共识是**分层压缩（gentle → aggressive）**，而非单一截断：

| 策略 | 激进度 | 是否需 LLM | 适用 |
|---|---|---|---|
| ToolResult 折叠 | 低 | 否 | 工具输出占大头时首选 |
| 摘要（Summarization） | 中 | 是 | 长对话需要保留语义 |
| Sliding window | 高 | 否 | 硬性轮数上限 |
| Truncation | 高 | 否 | 兜底 |

**而且该文档独立印证了我修的 bug**：原文明确说
「assistant 消息与其 tool result 必须作为一个**原子组**，拆开会导致 LLM API 报错」。

已落地的分层：
1. 折叠旧工具结果（保留头部 + 省略标记，**消息本身不删**，结构不变）
2. 仍超预算则整轮丢弃（只在 `user` 边界切分）
3. 最近 3 轮的工具结果永不折叠；身份提示与常驻目标**永不压缩**

**实测压缩生效**（预算 2500）：
```
Bot BrainBot compacted: dropped 1 oldest turn(s)   ← 反复触发
token 曲线: 2349 → 2545 → 2549 → 2544 → 2647 → 2568 → 2634
```
**稳定在 ~2550 不再增长**（修复前是无限增长）。

### 3. 常驻目标（回答用户的第 3 点质疑）

用户指出得对：跳过重复观察的前提是**那次观察还在 LLM 上下文里**。
实现时按此约束：仅当同时满足
1. bot 没走远（≤2 格），**且**
2. 那条观察结果**仍在 history 中**（没被压缩掉）

才复用。若已被压缩，说明模型确实"看不见"了，必须重新扫描 —— 否则等于喂给它已经不在上下文里的知识。

另外新增 `setStandingGoal()`：常驻目标被**钉在 pinned 前缀里**（与身份提示同级），
不会被压缩掉。否则长任务跑到一半 bot 就忘了自己要干什么。

### 4. 代码审查发现并修复的问题

| # | 问题 | 后果 | 修复 |
|---|---|---|---|
| 1 | `ChunkMap.move()` **每 tick 每 bot** 调用 | vanilla 只在真的移动时才调；它遍历全关卡已跟踪实体，纯浪费 | 按 section 变化门控 |
| 2 | 内存泄漏：`OpenedContainers` 从不清理 | 每个曾存在的 bot 的容器记忆永久驻留 JVM | 移除 bot 时清理 |
| 3 | 异步回调**没有 removed 守卫** | bot 被移除后 model 返回仍会执行动作，触碰死状态 | 回调 + `execute()` 双重守卫 |
| 4 | `EXECUTOR.shutdown()` 不可逆 | reload/重开世界后**所有 LLM 调用永久失败** | 改为可重建的懒加载单例 |
| 5 | 模型给的 NaN/Infinity 坐标 | `Mth.floor(NaN)` 得到垃圾位置 | 坐标钳制 + 非有限值兜底 |
| 6 | `compressOldToolResults` 是**死代码** | while 循环已保证 `size<=max`，`>max*2` 永不成立 | 重写为可达的分层压缩 |


---

## 里程碑 8：LLM 运行时配置 + 配置热重载

### 新增命令

```
/mcagent llm                    # 显示配置（key 打码）
/mcagent llm endpoint <url>     # 设置 API 地址
/mcagent llm key <key>          # 设置 key（并收紧文件权限）
/mcagent llm model <id>         # 设置模型
/mcagent llm budget <tokens>    # 设置上下文预算
/mcagent llm test               # 真实请求验证
/mcagent llm clear              # 清空 key
/mcagent reload                 # 重读 config 文件
```

### 实测验证

**运行时配置（从空配置开始）**：
```
CONFIGTEST initial: configured=false reason=baseUrl is empty
CONFIGTEST after set: configured=true desc=baseUrl=http://10.0.6.6:7863/v1 model=deepseek-v4.1-flash key=a401...13de
CONFIGTEST connection test: OK in 954 ms - model replied "" tokens=53
```
之后 bot 用这套运行时设置的配置**正常思考并完成全部任务**。

**文件热重载**（服务器运行中直接编辑文件）：
```
00:19:41 [FileWatcher-1-thread-1] Config mcagent-llm.toml reloaded - applying new settings
00:19:41 [Server thread]         LLM settings applied: http://10.0.6.6:7863/v1 model=deepseek-v4.1-flash
```
约 1 秒内生效，应用次数 = 1。

### 实现中发现并修复的三个问题

1. **`ModConfigEvent` 是 mod-bus 事件，不是 `NeoForge.EVENT_BUS` 事件**
   它实现 `IModBusEvent`。注册到 `NeoForge.EVENT_BUS` 会**编译通过但永远不触发** ——
   热重载看起来"做了"实际什么也没做。必须用 `modBus.addListener(...)`。

2. **配置事件在 FileWatcher 线程触发，不在 Server thread**
   直接在那里重建 client / 重指 brain 会与 tick 线程竞争。
   已改为用 `server.execute(...)` 派发到服务器线程。

3. **同一次编辑 watcher 会触发多次**
   日志里能看到连续两次应用。已加值比较去重（`Settings` 是 record，按字段比较），
   避免无谓地重建 HTTP client 和重指所有 brain。

### 一个安全改进

设置 key 时会自动把 `config/mcagent-llm.toml` 权限收紧为 `rw-------`。
NeoForge 默认按 umask 写文件，在常见服务器上会留下 world-readable 的密钥文件。

### 已知限制

- 文件监听依赖 NeoForge 的 FileWatcher。某些编辑器的保存方式或网络挂载盘可能不触发，
  所以提供了 `/mcagent reload` 作为确定性兜底。
- 热重载不会重建已有 bot 的 brain —— 这是刻意的（保留对话历史和常驻目标）。

---

## 里程碑 9：控制指令（pause / resume / goal / think）

### 背景：发现两个真实缺口

审查时发现：

1. **没有 pause/resume/goal/start** —— 只有 `stop`，而且它**只停移动不停思考**
2. **`setStandingGoal()` 是死代码** —— 里程碑 7 实现却没接任何命令

### 补齐的命令

```
/mcagent pause <名字> | pause all
/mcagent resume <名字> | resume all
/mcagent think <名字>                  # 强制立刻决策（不等冷却）
/mcagent goal <名字>                   # 查看
/mcagent goal <名字> <目标>            # 设置常驻目标
/mcagent goal <名字> clear
/mcagent goal <名字> once <指令>       # 一次性指令
```

`pause` 的语义是**暂停大脑**，不是移除 bot：bot 仍是真实玩家实体，能被看到/攻击/收到聊天，
只是不再决策和行动。暂停时也会**中止进行中的挖掘**并停止移动，
所以「暂停」是真的停下，而不是「还在走向之前决定的地方」。

### 实测验证（行为级，不只是注册）

先确认 10 条命令都真的注册到了 dispatcher，再把命令**通过服务器 dispatcher 真实派发**：

```
CMDTEST registered 'mcagent pause': true   (10/10 全部 true)
CMDTEST ran: /mcagent goal CmdBot Build a small shelter out of oak planks
CMDTEST ran: /mcagent pause CmdBot
CMDTEST ran: /mcagent resume CmdBot
CMDTEST ran: /mcagent think CmdBot
```

**关键**：用「暂停前后 LLM 调用次数」证明暂停真的生效，而不是只检查我自己设的标志位：

```
CMDTEST LLM turns          : before-pause=2  during-pause=0  after-resume=2
CMDTEST acted before pause : true      ← 说明"暂停期间 0 次"是有意义的，不是空验证
CMDTEST silent while paused: true
CMDTEST standing goal      : Build a small shelter out of oak planks   ← 目标真的设上了
CMDTEST bot still in world : true      ← 暂停不改变世界存在性
```

### 测试中发现的一个可用性 bug

最初的命令结构是 `goal <名字> set <目标>`，但自然语序（也是我在测试里顺手打出的）
是 `goal <名字> <目标>` —— **命令静默失败，目标没设上**。
第一次测试因此报 `standing goal: null`。

已改为与其它命令一致的 `<动词> <名字> [参数]` 语序，`set` 关键字去掉，
保留 `show` / `clear` / `once` 作为显式子命令。

---

## 里程碑 10：线上实测暴露的四个真 bug（听聊天 / 复活 / 空转 / 输出截断）

用户在生产服上实测后报告三件事：**「不理我说的话」「死了不会复活」「他一直在 fail」**。
三条都是真问题，而且**根因各不相同**。以下每一条都有线上日志原文。

### bug 1：bot 听不见 —— 它其实「听见了」，但没人教它回答

线上日志（`logs/latest.log`）：

```
01:12:44.116 <FirstMelody> 你好
01:12:44.143 <Agent> Hey FirstMelody! Watch out, there's a creeper...
01:12:52.262 <FirstMelody> 能说中文不
01:12:58.085 <Agent> It's getting dark with mobs about. Want to hole up...
```

第二条：玩家问「能说中文不」，bot 回了一句完全无关的天气话题，而且**还是英文**。

聊天确实被记录进了 `ChatLog`，也确实出现在 observation 里 —— 但它被埋在
一长串方块/背包清单**最底部**。模型读长文本时对**开头和结尾**最敏感，
而这里的结尾是「你能看到什么」的余韵，一句聊天混在里面就被略过了。

**修复**：把「有人正在对你说话」提到 observation 的**最顶部**，做成一个无法忽略的横幅，
并把被点名的未读消息单独列出来：

```
*** Speaker IS TALKING TO YOU RIGHT NOW: "BrainBot, can you hear me? Please reply in Chinese."
    Answer with the say tool as your very next action, in the same language they used
    (if they wrote Chinese, answer in Chinese). Do not narrate your surroundings instead.
***
```

**修复后线上实测**（`build/smoke-server`，走真实 `ServerChatEvent`）：

```
CHATTEST unheard after real chat event: 1
CHATTEST shouldRespondPromptly: true
CHATTEST banner present: true
...
Bot BrainBot called say(message="听到了！我在这，马上就去干活 😄") -> said: 听到了！我在这，马上就去干活 😄
```

**顺带修掉一个「测试假通过」**：原来的 CHATTEST 用 `ChatLog.record(bot, ..., List.of(bot))`
把 bot 自己当说话人，而 `record()` 第一件事就是 `if (bot == speaker) continue` ——
**这个测试什么都没验证，却一直是绿的**。现在改成 spawn 一个无脑的替身 bot 当说话人，
并真的 post `ServerChatEvent`（和 `ServerGamePacketListenerImpl` 处理真人聊天走同一条路）。

另外两条相关的修复：

- **模型用散文回答时，那句话以前会被直接吞掉。** `handleCompletion()` 里
  `if (!completion.hasToolCalls()) { cooldown = 60; return; }` ——
  模型写了一段完全正确的回答，因为没调工具，玩家永远看不到。
  现在会把这段文本用 `say` 说出来（限长 400 字符，并剥掉 `assistant:` 之类的角色前缀）。
- **正在走路时 bot 是聋的。** `tick()` 里有「移动中不决策」，于是 bot 走 30 秒就聋 30 秒。
  现在**被点名时优先回话**，直接中止当前行程。

### bug 2：死了不复活 —— 因为**根本没有人写复活代码**

`ServerPlayer` 的死亡是**客户端驱动的状态转换**：客户端显示死亡界面，
玩家点「重生」时发 `ServerboundClientCommandPacket`，服务端才走 `PlayerList.respawn`。
**合成玩家没有客户端，所以永远没有人点那个按钮。**

而且不处理会更糟：`LivingEntity.tickDeath()` 在 `deathTime >= 20` 时
直接 `remove(KILLED)`，所以 bot 不只是躺着，**20 tick 后会被整个删掉**。

**修复**：`BotManager.tick()` 检测死亡并在 `deathTime == 10` 时走 `PlayerList.respawn`
（留 10 tick 让死亡动画和死亡播报走完，但必须早于 20，否则没有身体可以交给 respawn）。
之后有两件事 vanilla 不会替我们做，必须手动补：

1. **vanilla 把「旧监听器」复制给了新身体**（`PlayerList.java:459`），
   而那个监听器持有的是**已经死掉的那个实体** —— 新身体会一点物理都没有。
   必须换一个绑定到新实体的 `BotGamePacketListener`。
2. **`BotHandle` / `MovementDriver` / `AgentBrain` 三处都持有 player 对象**，
   全部要重新指向新身体。UUID 在 respawn 中保持不变，所以对话记录和常驻目标都能保留。

**线上实测**（不是只跑测试，是在生产服上 `/kill Agent`）：

```
09:09:09.468 Agent was killed
09:09:09.911 Bot 'Agent' needs reviving: removed=false dead=true deathTime=10 health=0.0
09:09:09.937 Brain re-pointed to respawned body of Agent
09:09:09.937 Bot 'Agent' respawned at -1, 67, 10
09:09:28.458 Agent was slain by Zombie
09:09:28.913 Bot 'Agent' needs reviving: removed=false dead=true deathTime=10 health=0.0
09:09:28.927 Bot 'Agent' respawned at -1, 67, 6
```

之后它继续正常 observe / goto，`grep -c "mcagent.*ERROR"` = **0**。

### bug 3：修 bug 2 的过程中，我自己的修复把生产服的 bot 直接搞崩了

第一次部署后线上立刻炸：

```
Bot 'Agent' needs reviving: removed=true dead=true deathTime=21 health=0.0
Could not respawn bot 'Agent'; removing it to avoid a stuck corpse
java.lang.NullPointerException: ... because "objectset" is null
    at DistanceManager.removePlayer(DistanceManager.java:240)
    at ChunkMap.move(ChunkMap.java:1014)
    at BotGamePacketListener.teleport(BotGamePacketListener.java:118)
    at PlayerList.respawn(PlayerList.java:482)
```

**根因在我自己的 `teleport()` 重写里**：我额外加了 `chunkSource.move(bot)`
（vanilla 的 `teleport()` 只发包，不做这件事）。而 `PlayerList.respawn`
**在 482 行调用 teleport，直到 490 行才 `addRespawnedPlayer` 注册这个玩家** ——
`ChunkMap.move()` 去操作一个 distance manager 从没见过的玩家，
`playersPerChunk.get(i)` 返回 null，直接 NPE，**整个 respawn 被中断**。

**修复**：只在玩家真的已经注册进 level 时才 `move()`。延后是安全的 ——
`tick()` 本来就会在 section 变化时调用 `move()`，而 respawn 会换上一个
`lastMoveSection = MIN_VALUE` 的新监听器，所以下一 tick 必然会补上这次刷新。

### bug 4：bot 反复「出生即死亡」—— 因为**上一局的尸体被存盘了**

上面那条日志里 `deathTime=21` 是关键：bot 刚 join 80 毫秒，怎么可能已经死了 21 tick？
答案在磁盘上：

```python
# world/playerdata/d2cf65be-0811-3d4e-9c09-4546c45e4211.dat   (= offline UUID of "Agent")
Health 0.0
DeathTime 21
```

**上一局 bot 死了之后，死亡状态被原样存盘；下一次 join 时 `placeNewPlayer` 把它读了回来，
于是 bot 一出生就是死的**，20 tick 后被删掉。这也解释了为什么「死了不复活」
在重启之后依然存在 —— 光修 respawn 是不够的。

而尸体之所以能留在盘上，是**我另一处代码的 bug**：清理函数三个目录统一删 `<uuid>.json`，
但 **playerdata 和 stats 其实是 NBT 的 `.dat`，只有 advancements 是 `.json`**。
也就是说那个「防止 bot 在存档里堆积垃圾」的清理逻辑，**两个最重要的目录一个文件都没删掉过**。

**修复**：
- 清理函数按目录使用正确后缀（`.dat` / `.dat_old` / `.json`），并记录真的删掉了什么。
- `spawn()` 在 join **之前**先清掉同名残留存档，让「spawn 一个 bot」真的是全新玩家。
- 生产服上手动删掉已经中毒的那份 playerdata。

### bug 5：「一直在 fail」—— 输出被 1024 token 截断

线上日志里反复出现：

```
turn usage: prompt=10347 completion=1024 total=11316
turn usage: prompt=11075 completion=974 total=12049
```

`completion=1024` 是**正好撞上上限**。配置里 `maxTokens = 1024`，
而 `deepseek-v4.1-flash` 是思考模型 —— **思考本身也要吃这个预算**。
思考到一半预算就没了，工具调用根本没机会发出来，于是这一整轮白白浪费：
bot「想」了半天，什么都没做，下一轮再来一次。

**修复**：默认值 `1024 → 8192`，生产配置同步改为 8192，
并新增 `/mcagent llm maxtokens <n>` 供在线调整。
另外补上 `finish_reason` 解析：命中 `length` 时**明确告警**而不是静默丢弃
（这类症状原本极难排查：bot 表现是「发呆」，日志里却什么都没有）。

**思考深度按用户要求保持 provider 默认** —— 我们不发送任何 `reasoning_effort` 之类的参数。

### 顺带：`mine` 失败的提示以前教不会模型任何东西

原来的失败信息是 `there is no block there`。模型只见过分组后的方块摘要，
猜坐标是必然的；而这句话没有告诉它「为什么错、下一步该怎么办」，
所以它会**在同一轮里连猜四次**（线上实测：一轮内 4 个 `mine` 全部失败）。
现在会说明这里确实是空气、并提示「用你真正看见的坐标，不要猜」。

另外给 LLM 请求加了**一次瞬时失败重试**（5xx / 429 / 超时）：
网关偶发 500 会让 bot 白白丢一轮，表现上就是「它突然停住不动了」。

### 全部修复后的完整实测

```
CHATTEST banner present: true
Bot BrainBot called say(message="听到了！我在这，马上就去干活 😄")
Bot BrainBot called craft(item="minecraft:iron_block") -> crafted 1x minecraft:iron_block
Bot BrainBot called mine(x=3, y=-60, z=3) -> started mining 3, -60, 3 (about 300 ticks)
Bot BrainBot called goto(x=2, z=0) -> walking to (2, -60, 0)
Bot BrainBot called open_container(x=2, y=-60, z=0) -> opened Chest (27 slots)
DEATHTEST alive          : true
DEATHTEST health         : 20.0/20.0
DEATHTEST new body object: true
DEATHTEST old body gone  : removed=true still-in-level=false
DEATHTEST brain drives   : BrainBot (same object as the live player: true)
DEATHTEST PASS
```

生产服复测：bot 存活、挖矿、寻路、说话，`mcagent.*ERROR` 计数为 0。

---

## 里程碑 11：感知、寻路、长期记忆与动作编排

生产服实测又暴露四件事：**只看到叶子看不到树干**、**门开着却说没路，然后一头撞墙**、
**服务器一重启就什么都不记得**、**一次只能给一个动作**。这一节按根因分开写，每条都有日志原文。

> 线上那段日志跑的是改动**之前**的构建（生产服 `mods/mcagent-0.1.0.jar` 时间戳 09:05，
> 而这一节的源码改动都在 09:29 之后），所以它正好是这些症状的证据。
> 本节改完后的行为在 `build/smoke-server` 上实跑验证。

### 11.1 感知半径太小 —— 而那个配置项从来没被读过

`AgentConfig` 一直暴露 `observeRadius`（旧默认 12、范围 4~24），运维改了它、
看到它被写进 `config/mcagent-common.toml`，**但没有任何代码读它**。旧 `AgentBrain` 用的是常量：

```java
private static final int OBSERVE_RADIUS = 12;
...
String observation = ObservationBuilder.describe(this.bot, OBSERVE_RADIUS);
```

旧 `BrainManager` 里根本没有 `observeRadius` 这个字段，所以配置文件里那一行是**纯装饰**。
现在真正接通了：`AgentConfig.readSettings()` 读 `COMMON.observeRadius` →
`BrainManager.Settings.observeRadius` → `AgentBrain.setObserveRadius(...)`
（新 bot 在 `attachBrain` 时设一次，已存在的 bot 在 `applySettings` 时更新，所以热重载也生效）。

**默认 12 → 24**（范围放宽到 4~48）。12 太小：bot 站在开阔地，25 格外的树林它看不见，
于是宣布「周围没有树」，然后到处乱走：

```
09:13:36.110 <Agent> 你好！我刚在河边被怪追，现在在草地。这附近没看到树，你有木头吗？
09:23:55.517 <Agent> 好，我往西边走走找树干去
```

这个配置真的接通了吗？实跑证据：`build/smoke-server` 那个世界的 `observeRadius = 8`
（运维设的值，不是默认值），观测文本就真的按 8 报：

```
Blocks you can see within 8 blocks (45 total, grouped; nearest instance of each):
```

旧代码无论配置写什么都只会报 12。

**扫描顺序也有偏差。** 旧代码按坐标顺序遍历方块立方体，预算用完就中断：

```java
for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-radius, -radius, -radius), ...)) {
    ...
    // Stop once the cap is reached, but keep scanning a little further: iterating in
    // coordinate order means an early break would bias the sample toward low x/y/z, so we
    // allow some slack and rely on the distance sort to keep the nearest blocks.
    if (out.size() >= MAX_VISIBLE_BLOCKS * 2) {
        break;
    }
    if ((airSkipped + outOfRange + out.size()) % 512 == 0 && System.nanoTime() - startNanos > budgetNanos) {
        budgetExceeded = true;
        break;
    }
}
out.sort(Comparator.comparingDouble(SeenBlock::distance));
```

`betweenClosed` 的顺序是 x→y→z，提前 break 留下的**永远是 x/y/z 最小的那个角**，
远处的方块根本轮不到。旧注释自己承认了这个偏差，补偿办法是「多收一倍再按距离排序」——
但排序只能重排已经采到的样本，救不了一个**从未被采样**的样本。

现在改成**按距离由近到远遍历**：`Perception.offsetsByDistance(radius)` 预计算一张按距离平方
升序排好的偏移表（把「距离平方放在 `long` 高 32 位、7bit×3 的轴偏移放在低 32 位」，
直接 `Arrays.sort(long[])`，一个 `Integer` 都不装箱），扫描就按这张表走。
表**只缓存最近用过的那一个半径**（`cachedRadius` / `cachedOffsets`）：半径 48 的表就是 48³ 个条目，
把运维用过的每个半径都缓存起来是纯粹的泄漏。

顺带两个后果：因为表按距离有序，越过 `toRadius` 就能直接停（不用继续过滤剩下的格子）；
时间预算用尽时留下的是**最近**的那部分视野 —— 既是诚实的答案（「我周围有什么」），
也是稳定的答案（和它碰巧面朝哪边无关）。`MAX_VISIBLE_BLOCKS * 2` 的「多收一倍」也一并去掉了：
距离有序之后，截断本身就是「保留最近的」。另外 `getEyePosition()` 被提到循环外
（原来每个格子调一次，每次都分配对象）。

### 11.2 「看见」了却无法行动：只报数量不报坐标

旧输出是分组计数（里程碑 3 的原文）：

```
=== WHAT YOU CAN SEE ===
Blocks you can see (270 total, grouped):
  - Grass Block x268
  - Chest x1
  - Block of Gold x1
```

旧代码就是 `Map<String, Integer> counts` 按数量降序取前 12（`MAX_NAMED_BLOCKS = 12`）。
计数回答「这里有什么」，但回答不了「我该往哪走」—— 模型手上没有坐标，只能猜。

线上症状（bot 站在森林里，被告知的全是草和树叶的计数，于是说「没摸到树干」，跑去别处找树）：

```
09:27:31.412 <Agent> 在找树呢~我这边周围全是草,一棵树干都没摸到,西边具体大概什么坐标啊?
09:32:45.482 <Agent> 我出生点绑好了。树具体在哪个坐标？我这边近处都是草和树叶，看不到树干
09:34:10.838 <Agent> 哪棵呀?我这边看过去全是叶子,树干在哪儿?
```

现在每种方块都给**最近一个实例的坐标 + 距离 + 罗盘方位**：

```
Blocks you can see within 8 blocks (45 total, grouped; nearest instance of each):
  - Chest x1 - nearest at 2, -60, 0 (2 blocks east)
  - Grass Block x25 - nearest at 0, -61, 0 (2 blocks here)
  - Stone x18 - nearest at 7, -59, 0 (7 blocks east)
  - Block of Gold x1 - nearest at 3, -60, 3 (4 blocks south-east)
```

方位用**绝对方位**（north / north-east / … / here），不用「左/右」—— bot 的朝向一直在变，
相对方位等它真去用的时候就已经过期了。Minecraft 的坐标约定是 north = -Z、east = +X，
所以角度取 `atan2(dx, -dz)`；目标就在脚下时显示 `here`。

另外按数量排序会把树干挤掉：一个黑暗森林里数量最多的 12 种方块可能全是草、蕨和花，
橡木原木和树叶全在截断之外。现在**地标方块（原木、树叶、流体、带方块实体的方块）永远排前面**，
普通地形排后面（各自内部仍按数量降序）；上限 12 → **16**。
稀有 ≠ 不重要：一片花海里只有一个箱子，和一片草地里全是草，是同一个问题。

### 11.3 远场：单独一遍过滤后的地标扫描

近场扫描在自然地形里必然被草和石头淹没，所以它回答不了「那边有没有树林」——
上限先被近处的填充物填满，单加半径也没用（同样的填充物先到）。所以另加一遍**过滤后的远场扫描**：
`radius` → `radius × 2`（上限 48 = `Perception.BLOCK_RANGE`），只找「有轮廓的东西」：
原木、树叶、流体、带方块实体的方块（箱子、熔炉、机器、告示牌）。

判定是 tag / 能力驱动的，不是硬编码方块名，所以 200 个 mod 的整合包里照样工作：

```java
public static boolean isLandmark(BlockState state) {
    if (state.isAir()) {
        return false;
    }
    if (state.hasBlockEntity()) {
        return true;
    }
    if (!state.getFluidState().isEmpty()) {
        return true;
    }
    return state.is(net.minecraft.tags.BlockTags.LOGS)
            || state.is(net.minecraft.tags.BlockTags.LEAVES);
}
```

过滤发生在**任何射线投射之前**（`scan()` 里先做 `alreadySeen` / `isLandmark` 过滤，
再调 `canSeeBlock`）—— 这是 48 格扫描负担得起的原因：自然地形里绝大多数格子是普通地形，
一条射线都不用投。近场已经报过的方块类型在远场直接跳过（近场那份类型集合当 `alreadySeen` 传进去），
避免把同一棵树报两遍。扫描最多收 24 种（`MAX_LANDMARK_TYPES`），每种只留最近的实例，
写进 prompt 的最多 16 行（`MAX_NAMED_BLOCKS`），时间预算 15 ms。有地标时才出现这一节：

```
Further out, between 24 and 48 blocks away (things worth walking to):
```

### 11.4 性能：每个方块 6 条射线改成 1 条

旧 `canSeeBlock` 对每个方块采样 6 个面中心、最多投 6 条射线：

```java
// Sample the centre of each face, which is the surface a player would actually look at.
Vec3[] samples = new Vec3[6];
int i = 0;
for (Direction dir : Direction.values()) {
    samples[i++] = center.add(Vec3.atLowerCornerOf(dir.getNormal()).scale(0.5D));
}
```

实测（radius=12，日志原文，里程碑 6 引用过一次）：

```
visibleBlocks radius=12 -> 622 visible (air=13124, far=0, budgetHit=false) in 7 ms
visibleBlocks radius=12 -> 617 visible (air=13124, far=0, budgetHit=false) in 23 ms
```

（字段 `far=` 是旧格式，现在换成了 `types=` —— 也就是说这两行确实是「改之前」那版 6 射线的代码跑出来的。）

13k 个空气格每个只花一次 `getBlockState` 就 continue，真正投射线的是那 600 多个方块 ——
也就是说这 7~23 ms 的成本集中在射线投射上，不在方块读取上。24 格半径如果还按 6 条射线来，
这个数只会更难看。

现在 `canSeeBlock` 只投**一条**从眼睛指向方块中心的射线（在中心前 0.05 格停下，
避免方块自己的碰撞箱遮挡自己），MISS 或「第一个命中的就是这个方块」都算可见。
多出来的 5 条射线只在一种情况下会改变答案：眼睛已经在方块内部（那样中心射线被方块自己挡住，
而 6 面采样还有机会命中），代价是 6 倍。用这一点角落精度换 24 格半径是划算的，
而且它偏保守 —— 宁可少知道，符合真实性规则。

### 11.5 寻路：目标解析只往上找，于是「门开着也撞墙」

线上日志：bot 在玩家屋里，门开着，它距目标只有 6 格：

```
09:24:19.573 Bot Agent called goto(x=824, z=340) -> no path to (824, 63, 340); walking straight there instead
```

同一个目标它试了三次（09:28:52 → 09:29:54，跨 62 秒），每次换个 Y 再试，每次都是同一句：

```
09:28:52.247 Bot Agent called goto(x=811, z=343) -> no path to (811, 66, 343); walking straight there instead
09:29:35.566 Bot Agent called goto(x=811, z=343) -> no path to (811, 64, 343); walking straight there instead
09:29:54.238 Bot Agent called goto(x=811, y=65, z=343) -> no path to (811, 65, 343); walking straight there instead
09:29:54.237 <Agent> 我好像卡在你家里动不了……要不你直接把我tp到西边树林？
```

根因是 `PathFinder.resolveGoal`：旧版本只在 `dy = 0..+2` 里找可站立格，**只向上、从不向下**，
水平容差只有 2：

```java
for (int dy = 0; dy <= 2; dy++) {
    for (int dx = -GOAL_TOLERANCE; dx <= GOAL_TOLERANCE; dx++) {
        for (int dz = -GOAL_TOLERANCE; dz <= GOAL_TOLERANCE; dz++) {
            BlockPos candidate = goal.offset(dx, dy, dz);
            if (canStandAt(level, candidate)) {
                return candidate;
            }
        }
    }
}
return null;
```

模型报的 Y 是它从看到的方块上读来的、或者干脆猜的，偏几格完全正常。
目标比脚下地板低 4 格 → 一个候选都没有 → 整个搜索返回 null。

更糟的是**失败后的兜底**：旧 `setPathTarget` 返回 boolean，A* 失败就退化成直线走过去
（旧注释：「Falls back to straight-line steering when no route exists, so the bot always has an
intention」）。于是 bot 一头撞进墙里，而下一轮又会推导出同样的指令 ——
上面那三条同一目标的日志就是它在同一个地方反复撞，直到玩家把它传送走。

修复三件事：

1. `resolveGoal` 改成**按水平环由近到远**搜索（容差 2 → 3），每环内扫描整根竖直列
   （`±GOAL_VERTICAL_TOLERANCE = 8`），取最接近请求点的可站立格；高度偏差乘 1.5 的惩罚，
   让「平着差一格」赢过「同距离但要爬一格」。请求点本身能站就直接用它。
2. `setPathTarget` 返回 `Plan` 枚举：`FOUND` / `TOO_FAR` / `BLOCKED`，`goto` 把三种结果
   分别如实回报给模型；`BLOCKED` 时会明确说「看一圈，选一个你真的能走过去的近处目标，
   不要重复这一个」—— **不再直线撞墙**。
3. 超出规划范围（`GOTO_PLAN_RANGE = 128` 格）的目标**先做距离检查再搜索**：
   否则 A* 要耗尽 6000 个节点预算才发现到不了，而一次减法就能回答。

回归实测（`MCAGENT_SMOKETEST=true`，日志原文）：

```
SMOKETEST: A* path to BlockPos{x=14, y=-60, z=0} found=FOUND
SMOKETEST: A* path to BlockPos{x=14, y=-64, z=0} (4 blocks below the surface) found=FOUND
SMOKETEST: A* path to a goal 900 blocks away -> TOO_FAR (expected TOO_FAR)
...
SMOKETEST reversals    : 0 (rewind indicator; 0 = no snap-back)
SMOKETEST reached goal : true
SMOKETEST VERDICT      : PASS
```

第二行就是线上那个 bug 的回归用例：目标 Y 比地面低 4 格，现在必须解析到地板上方那一格；
第三行验证超范围目标被报成 `TOO_FAR`，而不是白跑一遍整轮 A*。这两条此后每次冒烟测试都会跑。

### 11.6 长期记忆：BotMemory

**为什么需要**：对话记录不是记忆 —— 它会被压缩掉、有上限、服务器一重启就没了。
bot 花一下午被人带着认路、认箱子，下次开局什么都不记得，又问一遍箱子在哪。

存储是每个 bot 一个 JSON 文件：`<世界目录>/mcagent-memory/<名字>.json`，
按**名字**索引而不是 UUID —— bot 被 `remove` 之后再用同名 `spawn` 出来，笔记还在。
名字虽然就是玩家名，但它是命令输入，所以非 `[A-Za-z0-9_-]` 的字符一律替换成 `_` 再拼路径，
不允许逃出目录。每个服务器进程只从磁盘读一次（`CACHE`），服务器停止时清掉。

实跑日志（`build/smoke-server`；这次运行开始前文件里已经有上一次会话写下的笔记）：

```
Loaded 4 remembered fact(s) for BrainBot
MEMTEST file: ./world/./mcagent-memory/BrainBot.json (exists=true)
MEMTEST rendered block:

=== THINGS YOU REMEMBER ===
Notes you wrote for yourself in earlier sessions. They are still true unless you
have seen otherwise since.
  - home chest 2,-60,0: My base chest at 2,-60,0 (overworld). Held 7x iron_block and 3x diamond; I took the 3 diamonds out, 7 iron blocks remain inside.
  - gold block mined: Gold block at 3,-60,3 (near home) was mined; that spot is now clear.
  - container at 2, -60, 0: Contents: - slot 0: 7x minecraft:iron_block - slot 1: 3x minecraft:diamond
  - home: my base is the chest at 2,-60,0
  - gold: gold block for the test is at 3,-60,3
```

其中 `home chest 2,-60,0`、`gold block mined` 是模型在早先会话里自己写的，
`container at 2, -60, 0` 是自动记录（见下）。接下来测试清掉缓存、重新从磁盘加载：

```
Loaded 5 remembered fact(s) for BrainBot
MEMTEST after reload size: 5
MEMTEST after reload recall('home'): [home chest 2,-60,0=My base chest at 2,-60,0 (overworld). ..., gold block mined=..., home=...]
MEMTEST survives reload: false
```

最后那行 `false` 是**测试断言写死了期望值**的产物：它判断的是 `reloaded.size() == 2`
（写测试时假设文件是空的），而这个世界的文件本来就有 4 条笔记，所以数字对不上。
持久化本身是成功的：`Loaded 4 remembered fact(s)` 是启动时从磁盘读出来的，
`clearCache()` 之后又读回 5 条、内容完整。

**刻意不做自动记录**：自动写入的记忆会充满噪声，把真正值得留的东西埋掉。
由模型判断什么值得记 —— `remember` 的工具说明就是这么要求的
（「只记那些否则你还得重新推一遍的东西」）。

唯一的自动记录是**打开过的容器里有什么**（`rememberContainer`，在 `open_container` 成功之后调用）：
容器内容在打开之前是不可见的，不记下来的话 bot 每个会话都会重新打开同一个箱子，
去回答一个它上次已经回答过的问题。键由坐标推导 —— `"container at " + pos.toShortString()` ——
所以重复打开同一个箱子是**更新**那条笔记，而不是堆一堆重复项。
（`BotMemory` 的类注释里还提到第二个自动项「where it has been living」，
但代码里没有对应的写入点；目前唯一的自动记录就是容器内容。）

**记忆块嵌在 system prompt（index 0）里**：`identityMessage()` 把 `memory.render()`
拼在身份提示后面，所以它永远不会被压缩掉（和身份、常驻目标同级）。
写入之后立刻重写那一条消息（`refreshIdentity()`），下一个请求模型就能看到，不用等重启。
prompt 里最多内联 **18 条**（`PROMPT_ENTRIES`，按写入时间新的优先 —— 快被截断时留在场上的应该是新笔记），
其余用 `recall` 工具查，而且会明确告诉模型还剩多少条没看到：

```
  ... and 3 older note(s) you can look up with the recall tool.
```

上限 60 条（`MAX_ENTRIES`），超了先丢最早写入的；值截断到 240 字符、键 64 字符。
每次修改立刻写盘（文件很小，而丢失是永久的）；读失败只警告不抛 ——
一个损坏的笔记文件不能让 bot 不思考。

三个新工具：`remember(key, value)`、`recall(query)`（省略 query 列全部，新的在前）、`forget(key)`。
实跑里模型规划完四件事之后自己记了笔记：

```
Bot BrainBot called remember(key="home chest 2,-60,0", value="Base chest at 2,-60,0 (overworld). After this session: 7x iron_block remain inside, dia...) -> updated memory 'home chest 2,-60,0': Base chest at 2,-60,0 (overworld). After this session: 7x iron_block remain inside, diamonds all taken. ...
Bot BrainBot called remember(key="loose item drops near home", value="Dropped items lying near the base around (0,-60,0): iron blocks (1 and 4) and 6...) -> remembered 'loose item drops near home': Dropped items lying near the base around (0,-60,0): ...
```

### 11.7 动作编排：可并行与串行

> 本节记录的是第一代 consecutive tool-call 实现。2026-09-20 起已由文件顶部记录的
> `plan`（单调用最多 24 步、低水位预取、失败停止依赖步骤）扩展；下面保留为演进历史。

用户原话：

```
tool call应该让他一次多call几个 并且call的时候要求一定要consecutive 保证动作连贯
比如寻路完成后干什么 或者寻路的同时说什么/吃东西 就是两种call 可并行和串行
```

实现方式：每个工具分成两类，`isConcurrent(tool)`：

- **可并行**（只需要嘴巴或眼睛）：`say`、`look_at`、`eat`、`remember`、`forget`、`recall`、
  `find_item`、`find_uses`、`craftable_now`
- **必须排队**：其余全部 —— 它们要么移动 bot、要么改变它手上的东西、
  要么作用在一个它必须先站过去的位置上

`runTurn()` 严格按模型给的顺序遍历：当前没有长动作在跑，或者这个工具可并行 → 立刻执行；
否则进队列，等前一个把 bot 让出来（`runQueued()` 每 tick 从队首取，
一连串瞬时的步骤在一个 tick 内排空，遇到第一个长动作就停下等它）。
当时 `MAX_TOOL_CALLS_PER_TURN` 从 4 提到 **8**；当前 sibling call 上限为 12，另有单调用
最多 24 步的 `plan`。

实跑日志：模型在一个回合里给了 7 个调用（`say` / `craft` 立刻执行，`goto` 出发，其余排队；
`mine` 在 `goto` 到达的那一刻开始，中间没有多一次模型往返）：

```
09:45:09.338 Bot BrainBot called say(message="听到了！我现在就去做这几件事：合成铁块、挖金块、拿钻石。") -> said: 听到了！我现在就去做这几件事：合成铁块、挖金块、拿钻石。
09:45:09.341 Bot BrainBot called craft(item="minecraft:iron_block") -> crafted 1x minecraft:iron_block
09:45:09.343 Bot BrainBot called goto(x=3, z=3) -> walking to (3, -60, 3) - call observe after you arrive
09:45:09.343 Bot BrainBot queued mine(x=3, y=-60, z=3) until the current action finishes
09:45:09.343 Bot BrainBot queued goto(x=2, z=0) until the current action finishes
09:45:09.343 Bot BrainBot queued open_container(x=2, y=-60, z=0) until the current action finishes
09:45:09.343 Bot BrainBot queued withdraw(x=2, y=-60, z=0, item="minecraft:diamond", count=3) until the current action finishes
09:45:10.166 Bot BrainBot (plan) called mine(x=3, y=-60, z=3) -> started mining 3, -60, 3 (about 300 ticks)
09:45:25.213 Bot BrainBot (plan) called goto(x=2, z=0) -> walking to (2, -60, 0) - call observe after you arrive
09:45:25.914 Bot BrainBot (plan) called open_container(x=2, y=-60, z=0) -> opened Chest (27 slots)
09:45:25.915 Bot BrainBot (plan) called withdraw(x=2, y=-60, z=0, item="minecraft:diamond", count=3) -> withdrew 3x minecraft:diamond
```

**关键机制是 `turnResults` 数组 + 从队首连续刷新**（`flushResults()`）：
provider 要求 assistant 消息里的每个 tool_call 都必须有对应的结果消息，
所以**未完成的那一步会挡住它后面的结果**，而不是被跳过 ——
这正好就是「这些动作按这个顺序发生」的保证，不需要另外维护什么顺序状态。
结果按索引记录，只有从队首起连续时才会被追加进转录。

**计划不跨回合**：新的决策会先把剩余步骤以 `"cancelled: 原因"` 如实收尾
（`abandonPlan()`，原因有 `the bot died before this step ran` / `the bot was paused` /
`the bot was spoken to and stopped to answer`），否则转录里会留下没有结果消息的 tool_call，
下一个请求就是非法请求 —— 和里程碑 6 修的是同一类结构问题。

**新增 `eat` 工具**：吃东西是物品使用，所以实现就是把食物换到手上（食物不在快捷栏里时，
先和当前选中的快捷栏格交换，可见且可逆），然后 `startUsingItem(MAIN_HAND)`；
vanilla 会在食物使用时长结束后自行消耗它 —— 这正是「可以边走边吃」的原因：
不需要我们每 tick 驱动，也不占用 bot 的动作。饥饿度已经满时直接拒绝（除非模型点名了具体食物），
避免浪费。返回文本会告诉它大概要多少 tick，并明说「你可以一边走一边吃」。

系统提示词里也把这条规则写清楚了（`ObservationBuilder.systemPrompt`）：一次可以要求多个动作、
严格按列出顺序执行、`goto` + `say` 是边走边说、`goto` + `mine` 是走到就挖。

### 本节尚未验证的部分

- 感知（半径 / 坐标 / 方位 / 远场地标）、寻路的两个回归、记忆、动作编排都在
  `build/smoke-server` 上实跑通过；**生产服还没换构建**，所以这一节的修复都还没在生产服上跑过。
- `eat` 工具只有实现和工具描述，没有被模型真实调用过的记录，属于端到端未验证。
- 远场扫描的具体耗时没有单独测量，只验证了它为什么负担得起：
  过滤发生在任何射线投射之前。

---

## 里程碑 12：热重载的 2 秒卡顿是**空等**，不是干活；顺带核对「复活 = 重进」

### 12.1 生产日志先把账算清楚

把 `logs/` 里所有归档（**排除 `debug-*.log.gz`，它们是编号归档的重复副本**）里的 reload 序列
按阶段拆开，51 次 reload 的分布是这样的：

| 阶段 | 实测 |
|---|---|
| 移除 bot（`bots.removeAll()`） | 271–497 ms |
| **`AgentBrain.shutdown()` 里的等待** | **要么 0–1 ms，要么 2005 ± 5 ms（18/51 次）** |
| 读 jar + 建 ClassLoader + 实例化 | 1–52 ms |
| 知识索引重建 | 100–591 ms |
| 命令重注册 + config | 21–248 ms |

那 2005 ms 是 `awaitTermination(2, TimeUnit.SECONDS)` 超时的精确指纹，而且**它永远不可能成功**：
模型调用是一个阻塞 HTTP 请求，自己的超时是几十秒，所以 reload 开始时在飞的请求，两秒后必然还在飞。
旧代码的顺序是「优雅 shutdown → 等 2 s → 才 `shutdownNow()`」，于是每次「bot 正在思考时热重载」
都把服务器主线程按住 2.0 s，然后**照样**打断它 —— 而打断只要约 8 ms（2008 ms 那个样本就是证据：
第二个 `awaitTermination` 在 8 ms 内返回）。服务器自己把这件事记成了
`Can't keep up! Is the server overloaded? Running 2895ms or 57 ticks behind`。

**修复**：`AgentBrain.shutdown()` 改成先 `shutdownNow()`（顺便丢掉还没开始跑的排队调用），
再用一个有上限的宽限（`DRAIN_GRACE_MILLIS = 1000`，实际 1–10 ms 就返回）等它退栈，超时就**明说**。
类加载器仍然只在调用真正退栈之后才关闭，安全性不变。

### 12.2 实跑验证（dev server）

新增 `MCAGENT_RELOAD_TEST`：起一个「收到请求后 30 s 才回答」的 stub 模型，让一个 bot 的请求
卡在飞行中，然后给 `AgentBrain.shutdown()` 计时（就是 reload 在主线程上调的那一个）。

```
# 对照组：MCAGENT_RELOAD_TEST=true MCAGENT_RELOAD_INTERRUPT=off
RELOADTEST 1 model call(s) in flight; the reload's shutdown of the model executor took 2004 ms (interrupt-first=false)
RELOADTEST VERDICT: CONTROL-PASS (...)

# 修复后：MCAGENT_RELOAD_TEST=true
RELOADTEST 1 model call(s) in flight; the reload's shutdown of the model executor took 1 ms (interrupt-first=true)
RELOADTEST VERDICT: PASS (the server thread is not held while a model call unwinds)
```

**2004 ms → 1 ms**，同样的在飞请求。`MCAGENT_RELOAD_INTERRUPT=off` 是正向对照，
它保留旧的「先等后打断」顺序，用来证明这个测试真的看得见卡顿（而不是因为当时没有请求在飞而空过）。

修复后一次 reload 的剩余成本约 **0.4–0.9 s**，其中最大的一项是移除 bot（271–497 ms，世界状态，
必须在主线程上），其次是知识索引（100–591 ms）。**注意：这两项都不是「异步就能省掉」的东西**——
真正省下 2 s 的是删掉那个注定失败的等待，而不是把它挪到别的线程。

**未部署**：这份修复还在 dev server 上验证过，生产服尚未换 jar。

### 12.3 「每次重生 = 一次进服 = 2 秒卡顿」是错的

三处证据，互相独立：

1. **代码路径不同。** 生产服上正在跑的 jar（`sha256=24c4220f…`）反编译后：
   `BotManager.respawn` 调的是 `PlayerList.respawn(ServerPlayer, boolean, RemovalReason)`；
   `placeNewPlayer`（登录路径）只出现在 `spawn` 里。vanilla 的 `PlayerList.respawn` 内部是
   `removePlayerImmediately` → `new ServerPlayer` → `restoreFrom` → `addRespawnedPlayer` →
   `initInventoryMenu`，**不经过** `placeNewPlayer`，没有「joined the game」、没有区块加载登录序列、
   没有各 mod 的 join 钩子。类注释里写的正是这件事：重新进服会重放整个 `placeNewPlayer`，
   「在重模组服务器上会让 tick 循环卡住数秒」。
2. **实测成本。** 全量日志里 56 次自动复活，`needs reviving` → `respawned at` 的间隔：
   **最小 7 ms / 中位 14 ms / 最大 104 ms**，一次 500 ms 以上的都没有。
3. **归因。** 全量 839 条 `Can't keep up` 里，前面 5 秒内有复活的：**0 条**（只有 1 次复活后面
   跟了一条 2817 ms 的告警，而同一时刻也有一次死亡，属于这台服务器本来就有的无关卡顿）。
   对照组：325 次登录里有 **266 次（82%）** 后面 5 秒内跟着一条 `Can't keep up`，中位 **2193 ms**。
   **2 秒卡顿属于「进服」，不属于「复活」。**

顺带核对了引用的统计数字：重启前那一段会话（20Sep 19:35 → 22Sep 21:34）实际是
**24 次自动复活、24 次死亡**（Zombie×7、窒息×6、Skeleton×5、Pillager×3、Spider×1、溺水×1、
Undead Miner×1），不是「34 次重生 / 7 次被怪打死」。数字 34 在 `PROGRESS.md` 和
`docs/jev-maturity.md` 里是 Jev ROUTING 的「34 次 CONTINUE 生效」，和复活无关。

结论：给 bot 穿装备带食物仍然值得（它会掉东西、会浪费回合），但**它不是卡顿的解药**；
卡顿的两个真实来源是「登录」和「reload」，后者本轮已经修掉。

### 12.4 铁砧与附魔台：**不能用**，而且 `use` 会骗模型说成功了

新增 `MCAGENT_ANVIL_TEST`（实跑日志见下）。脚本模型按最自然的顺序试了两个工具：
`open_container` 然后 `use`。

```
Bot AnvilBot called open_container(x=72, y=-36, z=70) -> failed: there is no container at 72, -36, 70
Bot AnvilBot called use(x=72, y=-36, z=70) -> used item on Anvil at 72, -36, 70
Bot AnvilBot called open_container(x=68, y=-36, z=70) -> failed: there is no container at 68, -36, 70
Bot AnvilBot called use(x=68, y=-36, z=70) -> used item on Enchanting Table at 68, -36, 70
ANVILTEST bot's open menu is now InventoryMenu -> AnvilMenu -> EnchantmentMenu
ANVILTEST tool surface: isContainer(anvil)=false isContainer(table)=false
ANVILTEST tool surface: withdraw(anvil) -> FAILED the bot has not opened that container
```

三个结论：

1. **`open_container` 两个都拒绝**：`Containers.containerAt` 只认 `Container`（箱子类方块实体），
   铁砧根本没有方块实体，附魔台有 `EnchantingTableBlockEntity` 但它不是 `Container`。
   所以 `withdraw`/`deposit` 也一起失效。
2. **`use` 报告成功，而且真的把原版菜单装到了 bot 身上**（`AnvilMenu` / `EnchantmentMenu`），
   但**没有任何工具能往菜单槽位里放东西、按附魔按钮、或把产物拿走** —— 模型被告知"做成了"，
   实际什么都没发生。这是本项目最忌讳的那种静默失败。
3. **这是接线问题，不是原版限制。** 手工驱动同一套菜单是通的：
   ```
   anvil menu probe: damaged sword + diamond -> result=[Diamond Sword] x1 cost=1 levels
   anvil menu probe: taking the result by hand -> carried=[Diamond Sword] levels 98 -> 97
   table menu probe: offers=6 8 30
   table menu probe: clickMenuButton(0) by hand -> item=[Diamond Sword] enchanted=true levels 97 -> 96
   ```
   （还顺带拿到了 `[Enchanter]` 成就。）菜单是活的，bot 只是没有手。

另外两点相关事实：

- **没有 `/enchant` 兜底**：命令白名单是 `home, sethome, spawn, msg, tell, w, r, list, tps, help`，
  而且 bot 不是 op。
- **铁砧连"看得见"都吃亏**：`Perception.isLandmark` 认的是"有方块实体 / 流体 / 原木 / 树叶"，
  铁砧没有方块实体，所以它**不会**出现在"things worth walking to"里，只会在普通方块清单里
  占一格（还可能被 24 种类型的上限挤掉）。附魔台有方块实体，是 landmark，正常显示坐标。

要修的话，缺的是一个"工作站"工具：铁砧放料 + `setItemName` + 读 `getCost()` + 取产物，
附魔台放料 + 读三档 `costs` + `clickMenuButton(player, index)`，并把结果如实回报
（"修好了钻石剑，花了 1 级"），完事 `closeContainer()`。顺带也该修 `use`：
在一个 bot 无法操作的 GUI 上不该只报一句成功。
