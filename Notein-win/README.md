# 墨笺 Windows

这是 Android 墨笺（`com.meta.inklab`）的 Windows 桌面版，不是 Notein 客户端。界面沿用墨笺 0.5.x 的笔记本 / 分区 / 页面结构、四个功能区、全页工具栏和深色视觉；Windows 侧主要增加鼠标、触摸与 Windows Ink 数位笔输入。

顶部最小化、最大化/还原、关闭由 Windows DWM 原生绘制和命中，直接嵌入应用的 34 px 顶栏；应用不再用字符或自绘图标模拟系统按钮。Android 图标由源 vector drawable 自动转换，包含原始多色子路径。

## 输入方式

- 鼠标左键：使用当前工具书写、擦除、套索或放置文字。
- 鼠标中键或 `Space + 左键`：平移画布。
- 鼠标滚轮：纵向平移；`Shift + 滚轮` 横向平移；`Ctrl + 滚轮` 以指针位置缩放。
- 数位笔：读取 Windows Ink 的合并采样点、压力和倾角；反转笔尾时临时整笔擦除。
- 触摸：默认单指不移动页面，两指平移 / 缩放；选择“全景漫游”后可单指平移。
- `Ctrl+Z` / `Ctrl+Y`：撤销 / 重做；`Ctrl+S`：立即保存；`F11`：全页模式。

## 本地数据与同步

默认工作区位于 `%LOCALAPPDATA%\Mojian\Workspace`。可从“文件 → 信息 → 切换工作区”改到任意普通文件夹，再由 Syncthing 同步。页面和附件采用稳定的普通文件布局：

```text
library.json
settings.json
notebooks/<notebook-id>/pages/<page-id>.json
notebooks/<notebook-id>/assets/<page-id>/...
```

保存使用同目录临时文件加原子替换，避免同步端读到半写入 JSON。`.mjn` 导出包含 Android 兼容的 `manifest.json` / `pages` / `assets`，同时在 `windows/pages` 保留 Windows 可编辑的完整笔迹、文字和视图状态。

## 构建与测试

```powershell
dotnet test .\Notein-win.slnx
dotnet run --project .\src\Notein-win\Notein-win.csproj
dotnet publish .\src\Notein-win\Notein-win.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o .\artifacts\release
```
