# MC Agent

服务端 LLM 虚拟玩家 mod —— 面向 **NeoForge 21.1.248 / Minecraft 1.21.1**。

目标服务器：`/serverstorage/minecraftserver/你好，新蒸程v1.7.5-Server`（258 个 mod）

---

## 它是什么

在服务器里加入一个或多个**虚拟玩家**。它们是**真正的 `ServerPlayer`** —— 有 UUID、出现在 tab 列表、
其他玩家能看到它们加入和说话、被 `/msg` 定位、触发 mod 的事件钩子 —— 只是没有网络客户端。

每个虚拟玩家由 LLM 驱动，能够：

| 能力 | 状态 |
|---|---|
| 与其他玩家沟通（真实聊天栏消息） | ✅ 已实测 |
| 寻路（A* 绕障，真实碰撞物理） | ✅ 已实测 |
| 环境感知（轻度透视容错 + 遮挡标记 + 空间/出口/危险语义） | ✅ 已实测 |
| 打开箱子（**内容必须先打开才可见**） | ✅ 已实测 |
| 取放物品 | ✅ 已实测 |
| 挖掘（**按工具和硬度计算真实耗时**） | ✅ 已实测 |
| 合成（按需获取任意物品，含 mod 配方） | ✅ 已实测 |
| 查询物品与合成表（**服务端全量，覆盖 mod**） | ✅ 已实测 |
| 放置方块 / 使用物品 / 受限命令 | 已实现，未全部端到端验证 |
| 近战攻击（追击、冷却连击、目标丢失处理） | ✅ 已实测 |
| 单次 LLM 调用批量计划（最多 24 步 + 执行期预取下一批） | ✅ 已实测 |
| 矿坑脱困（真实挖掘阶梯）/ 紧急返回重生点 | ✅ 已实测 |
| 本地连续掘进（下行楼梯 / 水平矿道，无需 LLM 逐格猜坐标） | 已实现 |
| 遮挡资源开采（自动挖两格高通道，不把透视坐标当可达点） | ✅ 已实测 |
| JEV/System One 快速决策（OpenCode Zen，shadow / active） | 已实现；active 待生产采样校准 |
| 玩家建筑保护（基地/房子/家具/地板下方禁止挖掘） | ✅ 隔离服验证通过（含反向对照） |
| JEV 说话闸门（花 LLM 之前决定要不要说话） | ✅ 隔离服验证通过 |
| 复读抑制（同一条话短时间内不重复发送） | ✅ 隔离服验证通过 |
| 无坐标 `spawn` 回到上次下线位置（含维度） | ✅ 隔离服验证通过 |
| 所有 bot 名参数的 tab 补全 | ✅ 隔离服验证通过 |

---

## 快速开始

### 1. 安装

需要**两个** jar，缺一不可：

```
<服务器目录>/mods/mcagent-<version>.jar              # core：入口 + 配置 + RuntimeHost
<服务器目录>/mcagent-runtime/mcagent-runtime.jar     # runtime：bot / brain / 感知 / 命令 等全部业务逻辑
```

runtime jar **不能**放进 `mods/`——NeoForge 会把它当成一个独立的 mod。core 启动时从服务器
工作目录下的 `mcagent-runtime/` 读取它；换掉这个文件再执行 `/mcagent reload` 即可生效，**不用重启服务器**。

### 2. 配置 LLM

首次启动会生成 `config/mcagent-llm.toml`，填入你的 endpoint：

```toml
["llm"]
	baseUrl = "https://api.example.com/v1"
	apiKey = "sk-..."
	model = "your-model-id"
	temperature = 0.3
	maxTokens = 1024
	timeoutSeconds = 90
```

任何 **OpenAI 兼容**的 `/chat/completions` 接口都可以。这个文件含密钥，建议收紧权限。

### 2.1 配置 JEV（可选，先以 shadow mode 运行）

将 [示例配置](docs/mcagent-jev.properties.example) 复制为服务器的
`config/mcagent-jev.properties`：

```properties
enabled=true
endpoint=https://opencode.ai/zen/v1/systemone
apiKey=
model=jev-1.13-free
timeoutMillis=5000
shadowMode=true
```

`apiKey` 留空时复用 `mcagent-llm.toml` 的 key。这个文件由 runtime 自己读取，所以编辑后执行
`/mcagent reloadconfig` 即可，不需要重启服务器。默认 shadow 只记录挖矿失败后的候选动作、choice、
confidence 和概率分布。设为 `shadowMode=false` 后，它只可执行白名单恢复：一次不同入口重试、沿矿道
breadcrumbs 回退、跳过目标、重新感知或升级给规划 LLM；低于 0.6 置信度、已过期或与新工作冲突的
结果一律不执行。HTTP 失败也不会阻塞主 LLM 或确定性控制器。

同一个文件里还有**说话闸门**（`speechGate`），它回答的是另一个问题：**这条聊天值不值得花一整轮
LLM**。

```properties
speechGate=shadow   # off | shadow | active
```

- `off`：从不询问，仍然由规划模型决定要不要回话（闸门出现之前的行为）。
- `shadow`：照常询问并记录 choice / confidence / 概率分布，但**不拦截**，供生产采样校准。
- `active`：`STAY_SILENT` 会**直接跳过这一轮 LLM 调用**，只留一条日志和一条行动报告。

安全规则是硬编码的：玩家点名 bot、问句，以及只对唯一在场 bot 说的话会直接交给规划模型，不经过
闸门；JEV 超时或报错也退回规划模型。只有已经排除这些情况的后台聊天/重复指令才允许
`STAY_SILENT` 生效，因此沉默方向不再设置信度门槛。日志形如：

```
JEV ACTIVE bot=Agent event=SPEECH_GATE choice=STAY_SILENT confidence=0.930 probabilities={...} newest_message=<FirstMelody> agent 我让你去挖钻石这些高级矿物
```

### 2.2 玩家建筑保护（默认开启，无需配置）

bot 以前把玩家的基地当普通地形：在营地地板下开竖井、为了出去把房子墙挖穿。原因是
`dig_tunnel` / `escape_up` 只认识流体、下落方块、方块实体和不可破坏地形，**没有「这是人建的」这个
概念**。现在 `rt/perception/PlayerStructure.java` 用确定性规则补上：

- **家具/机器（fixture）**：床、箱子、桶、熔炉、工作台、门、玻璃、灯笼、火把、告示牌，以及任何
  带方块实体的方块（模组机器、容器）。这些在任何地方都不允许挖。
- **建筑（structure）**：在 21×13×21 的范围内扫描「建筑调色板」方块（木板、楼梯、台阶、砖、羊毛、
  玻璃、金属块……按方块 id 词根匹配，因此模组建筑也覆盖），只要同时存在至少 1 个家具、并且累计
  ≥16 个建筑方块，就把整块包围盒列为保护区，**并且向下延伸 6 格**（地板和地基同样不能挖）。

保护区一旦成立，四条破坏路径全部拒绝：`mine` / `mine_resource`（含半径扩散与清理方块）、
`dig_tunnel`（不允许在建筑内开新矿道，沿已有矿道的「走回去」不受影响）、`escape_up`（改走门/开口，
真的走出去而不是拆墙）、以及挖矿可达性规划器（不会规划穿墙路线）。拒绝时会说明是哪一个结构、
哪些家具、以及应该怎么绕开。

`escape_up` 在建筑内找不到可挖阶梯时，会改为**非破坏脱困**：在结构外找可站立点、用普通寻路走出去
（会自己开门），失败才建议 `return_to_spawn`。

运维开关：`MCAGENT_STRUCTURE_GUARD=off` 可以整体关掉保护（给需要拆除机器人的场景用）；它是环境
变量门控，生产服不会误开。观察里也会明确写出来：

```
  - player-built structure: player-built structure around 889,63,362 (protected box ...; fixtures: white_bed@893,64,365, barrel@889,63,361, ...)
    This is a player's building, not terrain. Breaking any block inside it, or in the 6 blocks under it, is refused ...
```

### 2.3 复读抑制（默认开启）

bot 听不见自己说的话，所以以前可以对着同一条指令连发三次「收到……」。现在 `say` 会记录自己最近说过
的话（规范化后比较，忽略标点和空格），几分钟内重复或包含关系直接拒绝，并把「你已经说过」写进提示词
里，与 JEV 闸门互为补充：**闸门决定要不要说，复读抑制保证不会原样再说一遍**。

### 3. 使用

**生命周期**

```
/mcagent spawn <名字> [坐标] [fresh]  # 生成一个虚拟玩家（自动接入 LLM）
/mcagent list                     # 列出在线虚拟玩家 + 位置 + 是否在移动
/mcagent status                   # LLM 配置 / 知识库 / 每个 bot 的 token 与思考状态
/mcagent remove <名字>            # 移除（数据保留，下次 spawn 同名时原样回来）
/mcagent removeall                # 移除全部
```

> **不带坐标的 `spawn` 是"让它回来"，不是"把它放到我脚下"。** bot 自己的存档里记着它下线时的位置
> 和维度，所以 `/mcagent spawn Agent` 会让它出现在上次消失的地方（跨维度也正确）；只有从来没进过
> 这个世界的 bot 才会退回到"你站的位置"。命令的返回信息会说明用的是哪一种。
>
> **所有需要 bot 名字的地方都能 tab 补全**（`goto`/`pause`/`resume`/`think`/`stop`/`return`/`escape`/
> `remove`/`inventory`/`goal` 补全到在线 bot，`spawn` 补全到"有存档、可以叫回来"的名字）。
>
> **bot 的数据是默认保留的。** 背包、经验、状态效果和重生点都写在 `playerdata/<uuid>.dat` 里，
> 和真人玩家完全一样：**服务器重启、`remove`、`/mcagent reload` 都不会动它**，
> 再 `spawn` 同名 bot 就会带着原来的东西回来（包括睡前设过的重生点）。
>
> 唯一会销毁这些数据的是 `spawn` 的 **`fresh`** 参数：
>
> ```
> /mcagent spawn <名字> fresh              # 从零开始：先删掉该名字保存的背包/重生点/统计
> /mcagent spawn <名字> <坐标> fresh
> ```
>
> 这是刻意做成"要手打出来"的：毁掉一个 bot 的家当应该是一个决定，而不是重启的副作用。
> `remove` 之后 bot 不在线时才能 `fresh`，否则它的数据会在离开时被重新写回去。

**控制**

```
/mcagent pause <名字> | pause all     # 冻结：停止决策与行动，但 bot 仍留在世界里
/mcagent resume <名字> | resume all   # 解冻（会立刻决策，不等冷却）
/mcagent stop <名字>                  # 只停止移动，不停止思考
/mcagent think <名字>                 # 强制立刻决策一次（不等冷却）
/mcagent inventory <名字>             # 玩家远程打开该在线 bot 的 36 格背包
/mcagent goto <名字> <坐标>           # 寻路走到某处
/mcagent escape <名字> [工具]         # 真实挖出台阶脱离矿坑（可重复执行）
/mcagent return <名字>                # 直接传送到床/重生锚或世界出生点
```

`escape` 仍然走正常挖掘耗时、掉落和移动物理，适合 bot 被困在地下但还要继续带回矿物的情况；
`return` 是最后兜底，直接传送且不会死亡、掉落物品或清空背包。床/重生锚无效时会回世界出生点。

**目标（goal）**

```
/mcagent goal <名字>                     # 查看当前常驻目标
/mcagent goal <名字> <目标描述>          # 设置常驻目标
/mcagent goal <名字> clear               # 清除常驻目标
/mcagent goal <名字> once <指令>         # 一次性指令（不常驻）
```

**常驻目标与一次性指令的区别**（重要）：

- **常驻目标（goal）** 被**钉在提示词前缀里，不会被上下文压缩掉**。
  适合「长期任务」，例如 `goal <名字> 帮我在东边盖一座小木屋`。
  如果只用普通消息下达长任务，跑若干轮后会被压缩掉，bot 就忘了要干什么。

- **一次性指令（once）** 是普通消息，会随上下文正常压缩。适合「做一下这件事」。

**配置**（见下方第 4 节）

```
/mcagent llm ...            # LLM endpoint / key / model / budget / test
/mcagent reloadconfig       # 重新读取 config 文件（bot 不受影响）
/mcagent reload             # 重新加载 runtime jar（会移除所有 bot）
/mcagent runtime            # 显示当前 runtime jar 路径 / sha256 / 版本 / 状态
```

命令需要 **OP（权限等级 2）**。虚拟玩家自己**永远不是 OP**。

> `spawn` / `remove` / `pause` 等命令**不影响** bot 的世界存在性：
> `pause` 只是让它停止行动，它依然是一个真实玩家实体，会被看到、能被攻击、能收到聊天。
> 要真正移除请用 `remove`。

### 4. 运行时配置 LLM（不用重启）

不想编辑文件、或者服务器已经在跑，可以直接在游戏里/控制台配置：

```
/mcagent llm                          # 显示当前配置（密钥打码）
/mcagent llm endpoint <url>           # 设置 API 地址，如 http://10.0.6.6:7863/v1
/mcagent llm key <key>                # 设置 API key
/mcagent llm model <model-id>         # 设置模型
/mcagent llm budget <tokens>          # 设置上下文 token 预算
/mcagent llm test                     # 真实发一次请求，验证 endpoint/key/model 是否可用
/mcagent llm clear                    # 清空 key（bot 留在世界里但不再思考）
/mcagent reloadconfig                 # 重新读取 config 文件并立即生效（bot 不受影响）
```

设置会**立即生效**（无需重启），并**写回 `config/mcagent-llm.toml`** 以便重启后保留。
设置 key 时会自动把该文件权限收紧为 `rw-------`。

`/mcagent status` 还会显示每个 bot 自 brain 接入以来由供应商返回的累计 input/output/total token。
若接口返回 `usage.prompt_tokens_details.cached_tokens`（SCNet/OpenAI 格式）或
`prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`（DeepSeek 格式），还会显示累计缓存命中、
未命中 token 和命中率；供应商不返回缓存明细时明确显示 `cache=n/a`，不会误报成 0%。

`/mcagent llm test` 会真的发一次请求并给出可操作的结论，例如：

```
LLM test: OK in 954 ms - model replied "ok" tokens=53
LLM test: FAILED with HTTP 401 (the API key was rejected - check apiKey)
LLM test: FAILED to connect: Connection refused (is the host up and the port open?)
```

### 5. 配置热重载

NeoForge 会监听 `config/mcagent-llm.toml`，**直接编辑文件保存即可生效**（约 1 秒内），
无需重启、无需执行命令：

```
[FileWatcher-1-thread-1/INFO] [mcagent/config/]: Config mcagent-llm.toml reloaded - applying new settings
[Server thread/INFO] [mcagent/brains/]: LLM settings applied: http://... model=deepseek-v4.1-flash key=relo...rify
```

已有的 bot **不会被重建** —— 它们会带着自己的对话历史和常驻目标切换到新 endpoint，
这正是修 key 打错字时想要的行为。

如果文件监听没生效（某些编辑器写文件的方式、或网络挂载盘），用 `/mcagent reloadconfig` 强制重读（不会动 bot）。

### 6. 代码热重载（runtime jar）

业务逻辑（`com.melody.mcagent.rt.*`）都在 runtime jar 里，改代码不需要重启服务器：

```bash
# 1. 重新打包 runtime jar（只含 com/melody/mcagent/rt/**）
gradle runtimeJar
# 2. 覆盖服务器上的那一份
cp build/libs/mcagent-runtime.jar <服务器目录>/mcagent-runtime/mcagent-runtime.jar
# 3. 游戏内 / 控制台执行
/mcagent reload
```

`/mcagent reload` 会打印新 jar 的 sha256 与版本，并在服务器**不重启**的情况下换上新代码：

```
[mcagent/runtime/]: MC Agent runtime 0.1.0 unloaded
[mcagent/runtime/]: MC Agent runtime loaded from /srv/mc/mcagent-runtime/mcagent-runtime.jar (202536 bytes, sha256=cc73...10fc)
[mcagent/runtime/]: MC Agent runtime status: mcagent-rt 0.1.0 | bots=0 | brains=0 | llm=ready
```

代价是**所有 bot 会被移除**（走正常移除路径，但**数据保留**），重载后需要重新 spawn。
这是刻意的：bot 实体属于旧代码那一代，把活着的玩家跨代迁移比重新生成危险得多。
移除时每个 bot 的 playerdata 会像真人下线一样被保存下来，所以重载后 `spawn` 同名 bot，
背包和重生点都还在；要让某个 bot 从头开始，用 `/mcagent spawn <名字> fresh`。

如果 jar 加载失败（文件缺失、编译错误），core 不会崩：`/mcagent` 只剩 `reload` / `runtime` 两条命令，
修好 jar 再执行一次 `/mcagent reload` 即可。用 `/mcagent runtime` 可以看到失败原因。

core 与 runtime 的边界由构建强制检查：`checkRuntimeBoundary`（已挂到 `build`/`check`）会在任何
core 类引用 `com.melody.mcagent.rt` 时直接让构建失败。这条规则是硬性的——core 只要静态持有
一个 runtime 对象（字段、线程、事件监听器），旧的 `ClassLoader` 就永远无法回收。

```bash
gradle deploySmokeServer   # core jar -> build/smoke-server/mods/，runtime jar -> build/smoke-server/mcagent-runtime/
```

> 注意：`gradle runServer` 是从构建输出（`build/classes`）加载 core 的，`deploySmokeServer`
> 生成的是**真实服务器**的目录布局。两者同时存在不会冲突，但改完代码直接 `runServer` 时，
> 起作用的是构建输出。

---

## 安全边界

虚拟玩家的能力被刻意限制为**普通生存玩家**：

- 不能执行任意命令，只能用一个白名单（默认 `home` / `spawn` / `msg` / `list` 等）
- 白名单之外还有服务端自身的权限检查兜底
- 可以在 `config/mcagent-common.toml` 里关掉挖掘 / 放置 / 攻击 / 开箱

```toml
["general"]
	brainsEnabled = true
	observeRadius = 24
	allowBreaking = true
	allowPlacing = true
	allowAttacking = true
	allowContainers = true
	allowedCommands = ["home", "spawn", "msg", "list"]
```

---

## 设计要点

### 为什么不用 JEI

JEI 是 BOTH 端 mod，但**它的配方数据 100% 在客户端构建** ——
`mezz.jei.common.config.IServerConfig` 经反编译确认是个空标记接口，服务端不注册任何配方类别。
所以服务端装 JEI **拿不到任何配方**。

本 mod 改用服务端原生的 `RecipeManager`，它**天然包含所有 mod 的配方**
（mod 通过 datapack / `RecipeType` 注册，服务端一定持有）。
这比 JEI 更"真实"：用的就是游戏实际合成时用的那份数据。

### 三个必须知道的技术陷阱

这三个都是**读代码看不出来、必须实跑才发现**的，也是这个 mod 能成立的关键：

1. **客户端权威位置回滚**
   原版把位置权威交给客户端：`ServerGamePacketListenerImpl.tick()` 跑完物理后，
   会立刻把玩家 `absMoveTo` 回 `firstGood*`。假玩家永远不发移动包，所以自己走出来的位移
   **每 tick 都被丢弃**，原地不动。
   → 解法：自定义监听器，重写 `tick()` 时**去掉那句 `absMoveTo`**。

2. **物理只在连接里跑**
   `ServerPlayer.tick()` 只是记账；真正的物理在 `ServerPlayer.doTick()`，
   而它全游戏**只有一个调用者** —— 包监听器。没有监听器就没有物理、没有挖掘进度、没有攻击冷却。
   → 解法：假连接必须真的被每 tick 驱动。

3. **`connection.channel()` 不能为 null**
   NeoForge 把连接状态存在 Netty channel 属性上并直接 `.attr(...)` 读取。
   加入服务器时 `OnDatapackSyncEvent` 会走到这里，null channel 直接 NPE，**加入失败**。
   → 解法：给假连接一个真实注册的 `LocalChannel`（只为放属性），并重写 `channel()` 返回它。

另外两个坑：`awaitingPositionFromClient` 会永久锁死并**静默禁用一切方块交互**（所以必须重写 `teleport()`）；
**不要继承 NeoForge 的 `FakePlayer`**（它 `tick()`/`openPosition()` 全空、免疫伤害，是为物品自动化设计的）。

### 感知的真实性

- **可见性 = 无遮挡**（不做朝向判定，符合"人对周围有感知"的直觉），一条从眼睛指向方块中心的
  raycast 判定；只有眼睛已经在方块内部这种角落情况才会偏保守（宁可少知道）
- **容器内容默认不可见** —— 必须真的 `open_container` 之后才知道里面有什么
- **寻路豁免**：A* 可以用完整方块数据（这是用户明确允许的"作弊"），
  只有**汇报给 LLM 的观察**受限

### 感知半径与地标扫描

- `observeRadius`（`config/mcagent-common.toml`，默认 **24**）真的会被读取：新 bot 接入时设置，
  已存在的 bot 在配置热重载时更新。12 太小 —— 开阔地上 25 格外的树林看不见。
- 扫描按**距离由近到远**遍历（预计算的距离有序偏移表），所以时间预算用尽时留下的是最近的视野，
  而不是坐标系里 x/y/z 最小的那个角。
- 每种方块报**最近一个实例的坐标 + 距离 + 绝对方位**，例如
  `Chest x1 - nearest at 2, -60, 0 (2 blocks east)` —— 计数只说"这里有什么"，坐标才回答"往哪走"。
- 地标方块（原木、树叶、流体、带方块实体的方块：箱子、熔炉、机器、告示牌）排在普通地形前面，
  不会被草和花的数量挤掉。
- 近场之外还有一遍**过滤后的远场扫描**（到 `observeRadius` 的两倍，上限 48 格），只找地标；
  过滤发生在任何射线投射之前，这是它负担得起的原因。

### 长期记忆

- 每个 bot 一个 JSON 笔记文件：`<世界目录>/mcagent-memory/<名字>.json`，
  按**名字**而不是 UUID 索引，所以 bot 被移除再生成后笔记还在。
- 记忆块嵌在 system prompt（index 0）里，**永远不会被上下文压缩掉**；写入后立刻重写那一条消息，
  下个请求就能看到。prompt 里最多内联 18 条，其余用 `recall` 查。
- 三个工具：`remember(key, value)` 记一条、`recall(query)` 查（省略 query 列全部）、`forget(key)` 删。
- 只有「打开过的容器里有什么」是自动记录的（键由坐标推导，重复打开是更新而不是堆重复项）；
  其余由模型自己判断什么值得记 —— 自动记录会充满噪声。

### 动作编排：可并行与串行

- 普通 tool calls 单回合最多 12 个，严格按模型列出的顺序执行；需要占用 bot 的动作（`goto` /
  `mine` / `open_container` 等）排队，只需要嘴巴或眼睛的动作（`say` / `look_at` / `eat` /
  记忆与知识查询）可以在走路或挖掘期间立即执行。
- 长任务优先使用 `plan`：一次提交最多 24 个严格串行的步骤。队列低于 6 步时会提前请求下一批，
  因而慢速 LLM 返回前 bot 仍在执行已有工作，而不是原地挂机。
- 如果寻路失败，依赖该移动的后续步骤会被取消并立刻重新观察，避免 bot 没到地方却远程挖掘或开箱。
- `escape_up` 会规划最多 8 级向上的安全阶梯，使用正常 `mine` + `goto` 执行；矿坑更深时可重复调用。
  实在没有安全路线时才用 `return_to_spawn`，它会直接回有效重生点，不受距离和墙体限制。
- 下矿和分支挖矿使用 `dig_tunnel(direction, mode, length, item)`：`down` 连续挖下行楼梯，`level`
  连续挖两格高水平矿道，一次最多 24 格。服务器负责每一格的可达坐标，LLM 不再逐格猜测。bot 的
  家（床/重生点）周围 96 格只允许一条持久化矿道入口；后续调用必须沿该入口和 breadcrumbs 返回
  工作面。普通 `mine` / `mine_resource` / `escape_up` 不得破坏这一范围内的可见地表及其下方 4 层，
  从而避免在基地周围反复开洞或挖出浅沟。

### bot 的存档就是它对这个世界的记忆

- `playerdata/<uuid>.dat` 里装的是背包、经验、状态效果和重生点，跟真人玩家是同一套文件。
  同名 `spawn` 会**原样读回来**；`remove`、停服、`/mcagent reload` 都只保存、不删除。
- 曾经为了修「bot 复活后仍是死的」而在 spawn 前删掉这个文件，代价就是每次重启都把 bot 的家当清空。
  现在改成**只修死亡状态**：血量拉满、`deathTime` 归零、清掉着火、饿死的补上饱食度，
  并把读到的东西写进日志（`was loaded from saved playerdata in a dead state (health=0.0, deathTime=21)`），
  因为「bot 回来是死的」以前是完全看不见的。想真的从零开始只有 `spawn ... fresh`。
- bot 的长期记忆是另一份文件（`mcagent-memory/<名字>.json`），按**名字**而不是 UUID 索引，两者互不覆盖。

### 挖掘掉落物直接进包

- 每挖掉一个方块，**这一次破坏产生的掉落物立刻进背包**（`Actions.collectBreakDrops`），
  不再掉在地上等着走过去捡 —— 用户的原话是走过去捡太慢，砍完一棵树，走路比砍树还费时间。
- 只拿「刚掉出来的」：搜索范围限制在该方块周围，并且掉落物必须是新生成的（`age <= 2`）。
  否则 bot 会把别人丢的东西、自己扔的垃圾、上一次挖出来的东西一起吸走。
- 装不下的**原样留在地上**：只把拿走的数量从掉落物堆里扣掉，绝不凭空删除拿不走的东西。
- 收尾的「去捡掉落物」阶段保留为兜底（背包满、掉落物落在够不着的地方）。正常情况下它第一 tick
  就发现地上是空的、立刻结束；如果 bot 站在捡不起来的掉落物旁边，60 tick 后放弃，而不是永远站着。
- 这个兜底**只捡这次任务自己弄出来的东西**：掉落物要是比任务本身还老，说明它一开始就在地上，
  是别人丢的或 bot 自己扔的，走过去捡它不叫「收尾」叫「拿别人的东西」。`pickup` 工具不受此限制 ——
  那是模型明确要求去捡地上的东西。

### 话痨抑制

- 提示词里写明了：**不要预告要做什么、不要播报进度，大多数回合根本不该有聊天**；
- 听到范围内任何玩家消息都会跳过冷却，立刻把最新身体、环境、完整背包、在线玩家和动作队列交给模型；
  这只强制模型判断，不强制回复，只有模型显式调用 `say` 才会发到聊天栏；
  只在有人跟你说话、做完了一件被交代的事、需要提问或报告问题、或者真有值得说的事时才开口；
  连着两条意思一样的话不要发。
- 只靠提示词不够，所以 `say` 另有一层硬限制：**有人对你说话时永不限流**（忽略玩家才是最初的问题），
  否则距上次发言不足 200 tick（10 秒）就不发，并把
  `not sent: you spoke Ns ago and nobody has spoken to you since...` **作为工具结果回给模型**，
  让它自己纠正，而不是被静默丢弃；逐字重复上一句（即使被点名）也不发。

---

## 开发

```bash
# 编译 + 边界检查 + 打出两个 jar（build/libs/mcagent-<version>.jar 与 build/libs/mcagent-runtime.jar）
GRADLE_USER_HOME=/ymtc/Repos/.gradle-home \
  /root/.gradle/wrapper/dists/gradle-8.12-bin/cetblhg4pflnnks72fxwobvgv/gradle-8.12/bin/gradle \
  build --offline

# 玩家机制冒烟测试（加入/行走/绕障/移除 + 寻路回归）
MCAGENT_SMOKETEST=true ... runServer
MCAGENT_STRUCTURE_TEST=true ... runServer            # 建筑保护（含 new_site 矿道与走门脱困）
MCAGENT_STRUCTURE_TEST=true MCAGENT_STRUCTURE_GUARD=off ... runServer   # 反向对照：关掉保护就会拆房
MCAGENT_SPEECH_TEST=true ... runServer               # 说话闸门（stub System One）+ 复读抑制
MCAGENT_CMD_UX_TEST=true ... runServer               # spawn 回到下线位置 + 所有 bot 名参数 tab 补全

# LLM 端到端测试（合成 + 开箱 + 挖掘 + 听聊天 + 死亡复活）
MCAGENT_BRAIN_TEST=true ... runServer

# 命令层测试
MCAGENT_CMD_TEST=true ... runServer

# 重启存活测试：两次 runServer，同一个游戏目录，中间真的停服
MCAGENT_PERSIST_TEST=write  ... runServer    # 给 bot 背包 + 重生点，并把一个 bot 以死亡状态存盘
MCAGENT_PERSIST_TEST=verify ... runServer    # 同名 spawn，核对背包 / 重生点 / 复活

# 挖掘掉落物直接进包（半径挖掘、背包满时的余量、掉落物的 age 过滤）
MCAGENT_MINEDROP_TEST=true ... runServer

# 话痨抑制（脚本模型每回合都要说话，看真正进了聊天栏的有几句）
MCAGENT_CHAT_TEST=true ... runServer
```

所有测试都由环境变量门控，**在生产服上永远不会自动运行**。
`MCAGENT_BRAIN_TEST` 现在还会在任务跑完后**真的把 bot 杀掉**，验证它能自己复活。

`MCAGENT_SMOKETEST` 现在还回归测试两种「目标坐标不靠谱」的情况：目标 Y 比地面低
（必须解析到地板上方那一格，而不是报没有路）、以及超出规划范围的远距离目标
（必须报 `TOO_FAR`，而不是白跑一遍整轮 A*）。

`MCAGENT_PERSIST_TEST` 是唯一需要**跑两次**的测试，因为它验证的正是「停服再开服」：
第一次运行会被 `server.halt()` 正常停服，两次之间不能删 `build/smoke-server`。

文档：
- `DESIGN.md` —— 架构与设计决策
- `PROGRESS.md` —— 每个里程碑的实测证据（含日志原文）
- `docs/api/*.md` —— 从反编译源码提取的精确 API 契约
