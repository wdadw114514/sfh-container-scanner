# SlimefunHelper Container Scanner

SlimefunHelper 的客户端附属：区域容器扫描。

## 功能

- 扫描玩家周围指定半径内的容器方块（箱子、陷阱箱、末影箱、桶、潜影盒、漏斗、熔炉、高炉、烟熏炉、发射器、投掷器、合成器等）
- HUD 列表显示容器类型、坐标、距离
- 已打开过的容器显示 `ChestHistory` 缓存的内容摘要；未打开过的显示“未打开”
- 容器 ESP 边框和可选追踪线
- 全部配置自动接入 SlimefunHelper 的 ClickGui

## 依赖

- Minecraft 1.21.11
- Fabric Loader 0.18.4+
- Fabric API
- SlimefunHelper 1.2.1.1+（客户端模组）

本附属是纯客户端模组，不依赖 Jade 服务端。容器内容来自 SlimefunHelper 自带的 `ChestHistory` 缓存，因此需要先打开一次容器才能看到内容；未打开过的容器只显示位置和类型。

## 安装

1. 安装 SlimefunHelper 主模组。
2. 把本附属 jar 放进 `.minecraft/mods`。
3. 启动游戏，在 ClickGui 中找到 `ContainerScanner` 分组，打开“区域容器扫描”。

## 构建

```bash
./gradlew build
```

产物在 `build/libs/`。GitHub Actions 也会在推送后自动构建并上传 artifact。

## 开发说明

附属通过 Fabric entrypoint `slimefunhelper` 接入主模组：

```json
"entrypoints": {
  "slimefunhelper": ["me.matl114.addon.containerscanner.ContainerScannerAddon"]
}
```

入口实现 `me.matl114.plugins.SlimefunHelperPlugin`，在 `onConfigBootstrap` 注册配置、在 `onModuleBootstrap` 注册模块组。模块继承 `IRender2DColoredModule`，自动获得 HUD 位置、颜色、缩放、开关快捷键等配置。
