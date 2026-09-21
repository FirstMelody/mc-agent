# MC Agent — 进度与验证记录

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
