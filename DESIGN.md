# MC Agent — 设计方案

> 目标：在 `你好，新蒸程v1.7.5-Server`（NeoForge 21.1.248 / MC 1.21.1）上，以**服务端 mod** 形式
> 加入一个或多个接入 LLM 的虚拟玩家，能与真实玩家沟通、寻路、开箱、以及做任何玩家能做的事，
> 能按需获取全部物品（含 mod）及其合成表，并具备受视野约束的环境感知能力。

---

## 0. 已确认的设计决策（来自用户）

| 项 | 决策 |
|---|---|
| **虚拟玩家形态** | **服务端 mod 内的假玩家（ServerPlayer），不做任何渲染，不需要客户端** |
| **移动** | 假玩家自行移动（服务端物理驱动），不需要外部客户端 |
| **区块信息** | 服务端直接获取（服务端本就持有全部已加载区块） |
| **可见性判定** | 不考虑 FOV；允许穿过最多 5 个实体方块的轻度透视容错，但遮挡目标只作兴趣点，行动前必须求真实开采路径 |
| **容器** | 箱子**默认不显示内容**，必须先真的打开才可见 |
| **寻路** | **可以作弊**，允许使用完整方块数据，不受感知层限制 |
| **LLM** | OpenAI 兼容 HTTP，默认指向 **DeepSeek-V4-Flash** |
| **权限** | 普通玩家能力 + **受限命令白名单**（`/home` `/spawn` `/msg` 等），**不给 OP** |
| **交付方式** | 逐步推进，先打通最小可用闭环再横向扩能力 |

### 一处重要更正（我之前的错误结论）

我先前说「服务端假玩家做不了真寻路」，**这是错的**。已确认的正确事实：

- `ServerPlayer` 继承 `Player` → `LivingEntity`，而 `LivingEntity.travel()` 是**服务端权威**的
  移动物理（重力、碰撞、台阶、游泳、梯子、骑乘都在服务端跑）。
- `ServerPlayer` 拥有 `zza`（前后）和 `xxa`（左右）输入字段，`LocalPlayer` 在客户端
  只是把它们填好后调用同一个 `travel()`。
- 因此**服务端直接写 `zza`/`xxa`、设置 `setYRot()`、按需 `jumpFromGround()`**，
  假玩家就会以**真实碰撞物理**行走 —— 和其他玩家走的完全是同一套代码路径。
- 服务端天然持有区块数据（`ServerLevel.getChunk`/`getBlockState`），无需从客户端"获取"。

所以：**A* 路径规划由我们写，路径执行交给原版物理**。这正是用户描述的方案，可行。

---

## 0.1 实现中发现的三个关键陷阱（已实测验证）

这三点是**读代码看不出来、必须实跑才发现**的，也是整个项目最大的技术风险，现已全部解决。

### 陷阱 1：客户端权威位置回滚（最致命）

原版把**位置权威交给客户端**。`ServerGamePacketListenerImpl.tick()` 的流程是：

```java
this.resetPosition();                    // firstGood* = 当前位置
this.player.doTick();                    // ← 真正的物理在这里跑
this.player.absMoveTo(firstGoodX, ..., firstGoodZ, ...);   // ⛔ 又把位置拽回去
```

`firstGood*` 全游戏**只在 `resetPosition()` 里赋值**，而它每 tick 的唯一调用者就是上面这行。
所以假玩家自己走出来的位移**每 tick 都被丢弃**，无论 `zza`/`xxa` 怎么写都会原地不动。

**解法**：自定义 `ServerGamePacketListenerImpl` 子类，重写 `tick()` 但**去掉最后那句
`absMoveTo`**。见 `BotGamePacketListener`。

### 陷阱 2：物理只在连接里跑

`ServerPlayer.tick()` 只是记账（`gameMode.tick()`、容器校验、无敌帧衰减），
**真正的物理在 `ServerPlayer.doTick()`**，而它全游戏**只有一个调用者** —— 包监听器的 `tick()`。

也就是说：**没有监听器 = 没有物理、没有挖掘进度、没有攻击冷却**。
所以假连接必须真的被每 tick 驱动一次。

### 陷阱 3：`connection.channel()` 不能为 null

NeoForge 把连接级状态（connection type、payload setup、已注册通道）存在 **Netty channel 属性**上，
并通过 `connection.channel().attr(...)` 读取。`PlayerList.placeNewPlayer` 会触发
`OnDatapackSyncEvent` → `ChannelAttributes.getPayloadSetup()` → 直接 NPE，**加入服务器直接失败**。

**解法**：给假连接一个**真实注册的 `LocalChannel`**（原版内存连接用的就是它），
并重写 `channel()` 返回它（因为 `Connection.channel` 是私有字段，只在 Netty 的
`channelActive` 回调里赋值，手动注册时永远不会触发）。

### 另外两个已确认的坑

- **`awaitingPositionFromClient` 会永久锁死**：它只由 `ServerboundAcceptTeleportationPacket` 清除，
  假玩家永远不发这个包。一旦被设置，`handleUseItemOn` 会**拒绝一切方块交互**（开箱、放置全废）。
  而 `placeNewPlayer` 在正常加入流程里**一定会**调用 `teleport()`。所以必须重写 `teleport()`。
- **不要继承 NeoForge 的 `FakePlayer`**：它把 `tick()` 和 `resetPosition()` 都置空、`openMenu` 返回空、
  且完全免疫伤害。它是为「物品自动化」设计的，不是为「像玩家一样活动」。

---

## 1. 为什么不能用 JEI 服务端 API 拿配方

已通过解包目标服实际 jar 验证：

- JEI `19.44.0.401` 的 `neoforge.mods.toml` 声明 `side="BOTH"`，依赖也全是 `BOTH`
  → **JEI 确实会加载进服务端**。
- 但 JEI 的配方数据 **100% 在客户端构建**：`mezz.jei.common.config.IServerConfig` 经反编译确认
  是个**空标记接口**，服务端不注册任何 `IRecipeManager` 插件。
- 结论：**服务端装 JEI 拿不到任何配方**。

### 采用的替代方案（且比 JEI 更"真实"）

服务端用原版 **`RecipeManager`**（`ServerLevel.getRecipeManager()`）枚举
`getRecipeMap()`，它**天然包含所有已加载 mod 的配方**（mod 通过 datapack/`RecipeType` 注册，
服务端一定持有）。这覆盖：

- 有序/无序合成、熔炼、烟熏、营火、切石、锻造台、酿造
- mod 自定义 `RecipeType`（Create 的 混合/压块/碾磨、AE2 的 充能/刻录 等）

再加上 **`BuiltInRegistries.ITEM`** 全量物品（含 mod），即得到「JEI 里所有物品及合成表」的
服务端等价物。物品→用途（反向查询）通过对 `getRecipeMap()` 建倒排索引得到。

---

## 2. 总体架构

```
┌─────────────────────── Minecraft Server (NeoForge 21.1.248) ───────────────────────┐
│                                                                                     │
│  ┌──────────────┐   ┌────────────────┐   ┌─────────────────┐   ┌────────────────┐  │
│  │ VirtualPlayer│   │  Perception    │   │  ActionLayer    │   │ KnowledgeLayer │  │
│  │ (ServerPlayer│   │  (LOS raycast, │   │  (mine/place/   │   │ (RecipeManager │  │
│  │  + FakeConn) │◄──│   container    │   │   craft/open/   │   │  + registries, │  │
│  │              │   │   gating)      │   │   attack/use/   │   │  inverted      │  │
│  │  driven by   │   │                │   │   chat/commands)│   │  recipe index) │  │
│  │  zza/xxa/yaw │   └────────────────┘   └─────────────────┘   └────────────────┘  │
│  └──────┬───────┘           ▲                    ▲                      ▲          │
│         │                   │                    │                      │          │
│         │           ┌───────┴────────────────────┴──────────────────────┴───────┐  │
│         │           │ AgentBrain (事件/合法候选 → JEV shadow/active → LLM)     │  │
│         │           │   observe → semantic scene → think → controller → act     │  │
│         │           └───────────────────────┬───────────────────────────────────┘  │
│         │                                   │                                      │
│  ┌──────┴───────┐                  ┌────────┴─────────┐                            │
│  │ Pathfinder   │                  │  LlmClient       │                            │
│  │ (A* + mover) │                  │ (OpenAI-compat)  │                            │
│  └──────────────┘                  └────────┬─────────┘                            │
│                                             │                                      │
└─────────────────────────────────────────────┼──────────────────────────────────────┘
                                              │ HTTPS
                                     ┌────────▼─────────┐
                                     │  LLM endpoint    │
                                     │ (DeepSeek-V4 etc)│
                                     └──────────────────┘
```

---

## 3. 模块拆分

| 模块 | 职责 | 关键类 |
|---|---|---|
| **core** | mod 入口、配置、生命周期、命令 | `McAgentMod`, `AgentConfig`, `AgentCommand` |
| **player** | 假玩家实体、假连接、生成/移除、tick 驱动 | `VirtualPlayer`, `FakeConnection`, `VirtualPlayerManager` |
| **path** | A* 寻路 + 路径执行（写 `zza`/`xxa`/yaw/jump） | `PathFinder`, `PathNode`, `PathExecutor`, `MoveType` |
| **percept** | 视线检测、可见方块/实体收集、容器门控 | `Perception`, `Visibility`, `ObservationBuilder` |
| **action** | 白名单动作：挖/放/合成/开箱/取放/攻击/使用/聊天/命令 | `ActionRegistry`, `MineAction`, `PlaceAction`, ... |
| **knowledge** | 物品/配方查询，倒排索引，mod 配方覆盖 | `ItemKnowledge`, `RecipeIndex`, `RecipeQuery` |
| **brain** | LLM 循环、工具(schema)定义、调度、限流 | `AgentBrain`, `ToolSpec`, `ToolCall`, `BrainScheduler` |
| **llm** | OpenAI 兼容 HTTP 客户端（含 function calling） | `LlmClient`, `ChatMessage`, `ToolSchema` |

---

## 4. 假玩家实现要点（核心风险区）

### 4.1 生成一个真 `ServerPlayer`

不能用 `FakePlayer`（那是 `FakePlayer extends ServerPlayer` 但连接是 `null`，且被大量 mod
特判，且不参与正常 `PlayerList` 同步）。要的是一个**看起来就是普通玩家**的实体：

1. 构造 `GameProfile`（离线 UUID，`UUID.nameUUIDFromBytes("OfflinePlayer:"+name)`）
2. `new ServerPlayer(server, level, profile, ClientInformation.createDefault())`
3. 用 `PlayerList.placeNewPlayer(Connection, ServerPlayer, CommonListenerCookie)` 接入 —— 
   需要提供一个**假的 `Connection`**（继承 `Connection`，`send()` 空实现或转发到队列），
   这样才拥有正常的 `ServerGamePacketListenerImpl`，从而：
   - 出现在 tab 列表、其他玩家能看到、能收到聊天
   - `connection.teleport()` 等正常可用
   - 会被原版/其他 mod 当作真玩家对待

> ⚠️ 具体签名需以 moddev 反编译产物为准，构建完成后逐个核对。

### 4.2 驱动移动

```java
// 每个 server tick：
player.zza = forwardInput;   // -1..1
player.xxa = strafeInput;    // -1..1
player.setYRot(targetYaw); player.setXRot(targetPitch);
player.setDeltaMovement(...);  // 仅在需要时
if (shouldJump) player.jumpFromGround();
super.travel(...)  // 由原版 LivingEntity.tick() 自动调用
```

路径执行器每 tick 根据「当前坐标 → 下一个路径点」解算 yaw 与前进量，即可平滑行走。

### 4.3 注意事项

- **不要**让假玩家走原版 `ServerPlayer.tick()` 里依赖真实网络的部分（多数有 null 保护）。
- 假玩家需要加入 `PlayerList` 才可被 `getPlayerByName` 找到、被 `/msg` 目标、被其他 mod 识别。
- 保存/加载：bot 用离线 UUID 写进同一套 `playerdata/<uuid>.dat`，和真人玩家一样，
  这是**刻意的**——背包、经验、状态效果和重生点就是它对这个世界的记忆，
  重启、`remove`、`/mcagent reload` 都只保存、不删除；想清空只有 `/mcagent spawn <名字> fresh`。
  （早期版本为了绕开「死亡状态被存盘」而在 spawn 前删掉这个文件，代价是每次重启都把 bot 的家当清零；
  现在只修死亡状态本身，见 `BotManager.repairLoadedDeathState`。）
- 目标服 `online-mode=true`，所以必须走离线 UUID 路径接入。

---

## 5. 感知层（按用户决策）

- **可见性**不做 FOV 判定，并保留最多 5 个实体方块的轻度透视容错。它解决树林叶片、移动抖动和
  瞬时遮挡造成的“本应注意到却消失”；观察会明确标记 `EXPOSED` 或 `OCCLUDED by N blocks`。
  `OCCLUDED` 只是兴趣目标：挖掘前先搜索任意可达交互站位；仍无普通路径时，bounded excavation
  planner 生成两格高的真实通道，逐块按工具耗时挖掘和移动，禁止隔墙直接破坏目标。
  - 算法：从 bot 眼睛位置向目标方块/实体中心做 `ClipContext` 体素射线
    （`Block.COLLIDER`），未命中即视为可见；命中的若是目标本身也视为可见。
  - 单条射线指向方块中心，并在到达表面前 0.05 格停下，避免方块自身的碰撞体积遮挡自己。
    （早期版本对 6 个面的中心各投一条射线，成本是 6 倍；多出来的 5 条只在"眼睛已经在方块内部"
    这种情形下才会改变答案，而这个取舍让 24 格半径变得可负担，且偏向少知道一侧，符合真实性规则。）
- **范围**：可配置半径（`observeRadius`，默认 24）球体内的方块 + 实体。
  - 遍历**按距离由近到远**（预计算并按距离排序的偏移表），这样时间预算用完时留下的是**最近的**
    方块；按坐标顺序遍历会让采样永远偏向 x/y/z 最小的那个角落，排序补救不了一个从未被采样的样本。
  - 另外做一遍**过滤后的远场扫描**（半径 12 → 24，即两倍，上限 48），只找"有轮廓的东西"：
    原木、树叶、流体、带方块实体的方块。过滤发生在任何射线投射之前。近场已报告过的类型直接跳过。
- **容器**：`Container` 内容**默认不可见**；只有 bot 真实执行过 `openContainer` 后，
  才把该容器内容纳入观察（记录 `openedContainers` 集合，关闭后按策略保留/清除）。
  打开后其内容会写入长期记忆（见 §8.1）。
- **语义空间摘要**：每次新观察确定性计算天空/顶板、四面围合、局部可站立连通区域、边界出口方向和
  附近危险。LLM 先看到“封闭地下矿道、东侧有出口、附近有岩浆”，不必从几百个方块名反推几何。
- **寻路豁免**：A* 使用完整方块数据，不受上述限制（用户明确允许）。
- **输出给 LLM 的观察**是紧凑的文本/JSON，不是原始方块列表，需做聚合与限流。
  - 每种方块给出**最近一个实例的坐标 + 距离 + 绝对方位**（计数回答"这里有什么"，
    回答不了"我该往哪走"）。
  - **地标方块（原木/树叶/流体/带方块实体）永远排在截断之前**：按数量排序会让一棵黑暗森林里
    数量最多的十几种方块全是草和花，原木和树叶全被挤掉。

---

## 6. 动作层

全部为**结构化白名单动作**，无 OP、不可执行任意命令：

| 动作 | 说明 |
|---|---|
| `goto(x,y,z)` | A* 寻路 + 物理行走 |
| `dig_tunnel(direction,mode,length,item)` | 本地展开最多 24 格下行楼梯或水平矿道，逐格真实挖掘并行走 |
| `escape_up(item)` | 规划最多 8 级上升阶梯，以正常挖掘和行走脱离矿坑；可重复调用 |
| `return_to_spawn()` | 紧急直接传送到床/重生锚；无效时回世界出生点，不死亡、不丢物品 |
| `look_at(target)` | 设置 yaw/pitch |
| `mine(pos)` | 真实的挖掘进度（按工具/方块硬度）+ 掉落物拾取 |
| `place(pos, item, face)` | 放置校验（碰撞、可替换性） |
| `craft(recipe_id, count)` | 走 `RecipeManager`，素材从背包校验/扣除 |
| `open_container(pos)` / `withdraw` / `deposit` | 真实打开 + 物品转移 |
| `use_item` / `use_on(pos)` | 右击物品 / 对方块使用 |
| `attack(entity)` | 攻击，含冷却 |
| `chat(text)` | 以玩家身份发言（会被其他玩家看到） |
| `command(cmd)` | **仅白名单**：`/home` `/spawn` `/msg` `/sethome`(可选) |

---

## 7. 知识层

- `BuiltInRegistries.ITEM` → 全部物品（含 mod），带 `ItemStack` 以便渲染名/标签查询
- `serverLevel.getRecipeManager().getRecipeMap()` → 全部配方
- 建索引：
  - `itemId -> 产物配方列表`（"这东西怎么做"）
  - `itemId -> 作为原料的配方列表`（"这东西能干嘛"）
  - `tagId -> 物品列表`（矿辞/标签，mod 配方大量使用）
- 配方序列化为紧凑结构返回给 LLM（含 `RecipeType`、输入/输出、是否可合成判定）
- 结果做**分页 + 模糊搜索**，避免上下文爆炸

---

## 8. LLM 层

- OpenAI 兼容 `POST {baseURL}/chat/completions`，支持 `tools` / `tool_calls`
- 配置：`baseURL` / `apiKey` / `model` / `temperature` / `maxTokens` / 超时 / 重试
- 默认指向 DeepSeek-V4-Flash
- **异步**：网络请求不阻塞 server tick。每个 bot 一个串行决策循环
  （同一 bot 不并发决策），所有世界状态读写都在 server tick 线程上执行
- 预算：限制每 tick / 每秒的 token 与动作数，防止拖垮服务器

### 8.1 长期记忆

- **对话记录不是记忆**：它会被压缩、有上限、服务器重启就没了。bot 花一下午被人带着认路，
  下次开局什么都不记得，又问一遍箱子在哪。
- 每个 bot 一个 JSON 文件：`<世界目录>/mcagent-memory/<名字>.json`，按**名字**而不是 UUID 索引，
  这样 bot 被移除再生成后仍保留笔记。
- **刻意不做自动记录**：自动写入的记忆会充满噪声，把真正值得留的东西埋掉。
  唯一例外是**打开过的容器里有什么** —— 那正是玩家不用刻意记也会记住的东西。
  键由坐标推导，所以重复打开是更新而不是堆积重复项。
- 记忆块**嵌在 system prompt（index 0）里**，因此永远不会被压缩掉；写入后立刻重写那一条消息。
- 上限：最多存 60 条，prompt 里内联显示最新的 18 条，其余用 `recall` 工具查询；
  prompt 会告诉模型还有多少条它没看到。工具：`remember` / `recall` / `forget`。

### 8.2 动作编排：可并行与串行

- 模型可以在**一个回合里给出有序的多个工具调用**，普通回合最多 12 个；它们**严格按顺序执行**。
- 长任务优先通过 `plan` 一次缓存最多 24 个步骤。剩余步骤少于 6 个时异步预取下一批，
  用执行时间覆盖 LLM 延迟，避免两个请求之间原地等待。
- 工具分为两类：
  - **可并行**：`say` / `look_at` / `eat` / `remember` / `forget` / `recall` /
    `find_item` / `find_uses` / `craftable_now` —— 只需要嘴巴或眼睛，**即使 bot 正在走路也立即执行**。
  - **其余必须排队**：它们要么移动 bot、要么改变它手上的东西、要么作用在世界里一个它必须先站到
    的位置上。
- 效果：「走到树那儿然后砍了」是**一个回合**，砍的动作在到达的那一 tick 就开始；
  「边走边说」用 `goto` + `say` 同时给。
- 关键机制是结果数组 + **从队首连续刷新**：provider 要求 assistant 消息里的每个 `tool_call`
  都必须有对应的结果消息，所以未完成的那一步会**挡住**它后面的结果而不是被跳过 ——
  这正好就是"这些动作按这个顺序发生"的保证。
- 计划**不跨回合**：新决策会先把剩余步骤以 `cancelled: <原因>` 如实收尾，否则转录会变成非法请求。
- 寻路失败时清空依赖它的计划并立即重新观察，防止后续步骤在错误位置继续执行。
- `escape_up` 是矿坑中的本地宏动作，但内部仍排入真实的 `mine` / `goto`；`return_to_spawn`
  是无路可走时的显式紧急兜底。

---

## 9. 部署

- 目标服当前 **正在运行且有玩家在线** —— 加 mod 需要重启，**必须经用户确认后**才动。
- 开发期使用独立 dev server（`build/smoke-server`），与生产环境隔离。
- 产出 `mcagent-<version>.jar` 放入 `mods/`。

---

## 10. 风险与对策

| 风险 | 对策 |
|---|---|
| 假 `Connection` API 复杂 / 版本差异 | 先做最小生成+tick 冒烟测试，逐个核对反编译签名 |
| 假玩家触发其他 mod 的 NPE（258 个 mod） | 优先在 dev server 上加装生产 mod 集做兼容测试 |
| 内存紧张（宿主仅剩 ~6G 可用） | bot 与主服同 JVM，限制数量（建议 ≤2）、限制观察半径、复用对象 |
| LLM 延迟导致 bot"发呆" | 异步决策 + 本地行为兜底（当前动作继续执行） |
| LLM 幻觉做出破坏行为 | 严格动作白名单 + 无 OP + 可配置禁用破坏类动作 |
| 配方量巨大（数千条） | 倒排索引 + 分页 + 只按需检索，绝不整体塞进上下文 |
