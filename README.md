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

头部包含 `mcVersion`、`modLoader`、`unitsPerBlock`、`dimensions`、`solid`、`subject`、`blockState` 等字段，
供 Studio 识别与还原。栅格固定覆盖**一个方块格**：16×16×16（资源包贴图更细时按整数倍超采样到 32³ / 64³），
因此火把、按钮这类小方块也保持着「一个方块」的尺度。

## 与 Entity Capture 的关系

两者共用同一个 `.mcvox` 容器，实现上互不依赖，各自带一份核心代码：

| | Entity Capture | Block Capture |
|---|---|---|
| 采集对象 | 实体的渲染模型 | 方块的烘焙模型 |
| 采集方式 | 渲染到捕获用 buffer，需要 mixin 插入渲染链 | 直接读 `BakedModel.getQuads`，**零 mixin** |
| 相机 / 姿态 | 需要摆中性姿态、镜像修复 | 不需要（方块模型是静态的） |
| 头部 `subject` | `entity`（省略） | `block` |

## 已知限制

1. **由方块实体渲染的方块不支持**（箱子、告示牌、旗帜、床、潜影盒等）——它们没有烘焙四边形。捕获时会明确报错。
2. **动画贴图只取第一帧**（岩浆、水、火等）。
3. **超出方块格的几何会被裁掉**：少数模型（大型垂滴叶等）会伸出自己的方块格，超出部分不参与捕获。
4. **透明层**只按 alpha 阈值处理（低于 10% 视为不产生体素），半透明材质会丢失层次。
5. 捕获的是方块的**外观**，不记录红石状态、容器内容等逻辑数据。

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
