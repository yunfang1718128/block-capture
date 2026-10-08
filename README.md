# Block Capture

一个 **Fabric / NeoForge** 的 **客户端**模组：把 Minecraft 里的**任意方块**（含模组方块）
按 **1:1 原生分辨率**捕获成带颜色的体素捕获包 `.mcvox`。

它是像素画工具 [**MC Block Studio**](https://github.com/yunfang1718128/mc-block-studio) 的配套采集端，
与姊妹项目 [**Entity Capture**](https://github.com/yunfang1718128/entity-capture) 平级。

## 配套项目（联动）

三个项目只通过 `.mcvox` 一份格式解耦，可独立开发：

```
[Block Capture 模组]                          [MC Block Studio]
游戏内捕获方块  ──►  .mcvox  ──►  「选择方块」页签 → 上传 → 放大 → 空心/填充 → 配色方块 → 导出 .litematic
```

- 本模组只负责 **Minecraft 域**：方块模型 → 四边形采集 → 占用 + 表面色 → `.mcvox`。
- 放大、空心/填充、方块匹配、`.litematic` 导出全在 MC Block Studio 完成。
- 输出文件与 **Entity Capture** 完全同格式（同一个 `MCVX` 容器），只是头部多了
  `subject: "block"` 与 `blockState`，Studio 据此区分主体类型。

## 支持的加载器 / 版本

| 加载器      | Minecraft | Java | 额外前置   | 发布产物                            |
| -------- | --------- | ---- | ------ | ------------------------------- |
| Fabric   | 1.21.1    | 21   | Fabric API | `block-capture-<版本>-fabric.jar`   |
| NeoForge | 1.21.1    | 21   | 无      | `block-capture-<版本>-neoforge.jar` |

两种产物写出的 `.mcvox` 格式完全一致，仅头部 `modLoader` 字段区分为 `fabric` / `neoforge`。

## 下载 / 安装

- 下载：[GitHub Releases](https://github.com/yunfang1718128/block-capture/releases) 里对应加载器的 jar。
- Fabric：把 `block-capture-<版本>-fabric.jar` 放进 `.minecraft/mods/`，并装好 **Fabric API**。
- NeoForge：把 `block-capture-<版本>-neoforge.jar` 放进 `.minecraft/mods/`，需要 NeoForge **21.1.x**（Minecraft **1.21.1**）。

## 使用

进游戏后：

| 入口                        | 说明                                              |
| ------------------------- | ----------------------------------------------- |
| **`K`**（默认键）               | 捕获**准星所指的方块**，保留它当下的完整方块状态（如 `oak_stairs[facing=east,half=bottom,shape=straight]`） |
| `/captureblock`（无参）       | 等同 `K`：捕获准星方块                                  |
| `/captureblock <block>`   | 捕获该方块的**默认状态**，如 `/captureblock minecraft:oak_stairs`（带 tab 补全，支持模组方块） |

捕获在客户端主线程完成，瞬间返回；完成后聊天栏给出汇总。

## 输出

写入游戏根目录 `block-capture/`：

```
minecraft_oak_stairs_2026-10-08T02-15-30-123Z.mcvox
```

头部包含 `mcVersion`、`modLoader`、`unitsPerBlock`、`dimensions`、`solid`、`subject`、`blockState`、
`captureMode` 等字段，供 Studio 识别与还原。

栅格以 **16 单位 = 1 个方块**（Minecraft 模型分辨率），并至少覆盖该方块自己的那一格：

- 普通方块与完整立方体 → 16×16×16；资源包贴图更细时按整数倍超采样到 32³ / 64³，所以火把、按钮这类小方块也保持着「一个方块」的尺度。
- 几何合法地伸出自己格子的模型 → 栅格按实际范围扩大（旗帜的布大约 1.67 格高，就会得到 16×27×16 这样的尺寸），不会被裁掉。

## 支持哪些方块

捕获有两条路径，同一个文件格式、同一个体素化器：

1. **烘焙模型**（绝大多数方块）：直接读 `BakedModel.getQuads`，不渲染、不需要 mixin。楼梯、栅栏、火把、模组装饰都能拿到真实形状。
2. **方块实体渲染器**：约 **128 个原版方块状态完全没有烘焙几何**，它们的 blockstate 只是一个只有 `textures` 的空模型，全靠渲染器画出来。捕获时会新建一个默认方块实体并驱动渲染器，把它的四边形一起体素化。

| 靠方块实体渲染的家族 | 变体 | 拿到什么 |
|---|---|---|
| 潜影盒 | 17 | 闭合状态 |
| 床 | 16 | 半张床（`part=head` / `foot` 各自一格） |
| 旗帜 / 墙旗 | 32 | 纯色旗面（图案在 NBT，默认无） |
| 告示牌 / 墙牌 | 22 | 木板牌身（默认无字） |
| 悬挂告示牌 | 22 | 木板与锁链 |
| 头颅 / 头（7 种 × 2 形态） | 14 | 头模型；玩家头固定用默认皮肤 |
| 箱子 / 陷阱箱 / 末影箱 | 3 | 关闭状态 |
| 潮涌核心 | 1 | 未激活的外壳 |
| 装饰罐 | 1 | 素罐（无陶片图案） |
| 钟 | 1 | 钟体（补全原本只有挂架的模型） |
| 末地传送门 / 传送门方块 / 移动中的活塞 | 3 | ❌ 无法体素化或属瞬态，跳过 |

**模组方块只要有 `BlockEntityRenderer` 就会被捕获**，这是本模组的主要用途。

## 与 Entity Capture 的关系

两者共用同一个 `.mcvox` 容器，实现上互不依赖，各自带一份核心代码：

| | Entity Capture | Block Capture |
|---|---|---|
| 采集对象 | 实体的渲染模型 | 方块的烘焙模型 + 方块实体渲染器 |
| 采集方式 | 渲染到捕获用 buffer（mixins 插入渲染链、姿态预处理） | 优先直读 `BakedModel.getQuads`；仅方块实体走渲染捕获 |
| mixin | 6 个（渲染链与姿态） | **3 个**（只为了从 `RenderType` 里取出贴图位置） |
| 相机 / 姿态 | 需要摆中性姿态、镜像修复 | 不需要 |
| 头部 `subject` | `entity`（省略） | `block` |

## 已知限制

1. **方块实体捕获的是「默认形态」**：新建的方块实体不带 NBT，所以旗帜无图案、告示牌无字、装饰罐无陶片、潜影盒与箱子是闭合的、玩家头用默认皮肤。好处是同一方块每次产出完全一致（可复现）。
2. **12 个「画动画/特效而不是形状」的渲染器被跳过**：信标光柱、营火火焰、附魔台/讲台的浮空书、刷怪笼里旋转的生物（那会把实体渲染卷进来）、宝库与试炼刷怪笼的动画、结构方块线框、可刷方块尘、移动中的活塞、末地传送门/传送门方块。这些方块的**静态部分仍会照常捕获**（如营火的原木、讲台底座）。
3. **零厚度几何**（悬挂告示牌的锁链、旗帜的布在厚度方向只有 1 单位）体素化后是单层，可能看起来偏薄或有缺口。
4. **动画贴图只取当前帧**（岩浆、水、火等）。
5. **透明层**只按 alpha 阈值处理（低于 10% 视为不产生体素），半透明材质会丢失层次。
6. 捕获的是方块的**外观**，不记录红石状态、容器内容等逻辑数据。

## 本地开发

```bash
# 需要在 Windows PowerShell 下，脚本会自动选好 Minecraft 自带的 JDK 21
.\run.ps1 fabric        # Fabric 1.21.1 开发客户端
.\run.ps1 neoforge      # NeoForge 1.21.1 开发客户端

# 网络需要本地代理（如 Geph）时
.\run.ps1 neoforge -ProxyPort 9910
```

构建：

```powershell
$env:JAVA_HOME = "$env:APPDATA\.minecraft\runtime\java-runtime-delta"
.\gradlew.bat :fabric-1.21:build :neoforge-1.21:build --configure-on-demand --console=plain
```

产物：`<loader>/build/libs/block-capture-<版本>-<loader>.jar`

## 版权与免责声明

- 本模组是一个**本地工具**：它把游戏内方块的外观转成体素数据（`.mcvox`），供你本人导入像素画工具使用。
- **“能够捕获某个方块”并不代表你拥有该方块模型或贴图的任何版权。** 模型与贴图等知识产权归各自的模组作者及相关权利方所有；
  本模组不附带、也不授予任何此类权利。
- 请勿将包含第三方模组素材的捕获产物**公开分发、二次打包或用于商业用途**；由此产生的版权 / 许可纠纷由使用者自行承担。
- 本模组自身的源代码以 **MIT** 许可发布，见 [`LICENSE`](./LICENSE)。

## 许可证

MIT，见 [`LICENSE`](./LICENSE)。
