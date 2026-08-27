# 墨笺 Windows

这是 Android 墨笺（`com.meta.inklab`）的 Windows 桌面版，不是 Notein 客户端。界面沿用墨笺 0.5.x 的笔记本 / 分区 / 页面结构、四个功能区、全页工具栏和深色视觉；Windows 侧主要增加鼠标、触摸与 Windows Ink 数位笔输入。

顶部最小化、最大化/还原、关闭由 Windows DWM 原生绘制和命中，直接嵌入应用的 34 px 顶栏；应用不再用字符或自绘图标模拟系统按钮。Android 图标由源 vector drawable 自动转换，包含原始多色子路径。

## 输入方式

- 鼠标左键：单击选择内容；已选文本再次普通单击进入编辑；空白单击产生临时文字光标，输入首个有效字符后才写入页面。按住已选内容/控制点拖动会移动/缩放，其他起点拖动一律执行完整包含式矩形框选。
- 画布双击不绑定动作；对象右键菜单提供打开/编辑、复制、层级、旋转、翻转、还原大小和设为背景。
- 鼠标中键或映射为中键的笔侧键、`Space + 左键`：平移画布；`Ctrl + 中键/笔侧键拖动` 远离画布中心放大、靠近中心缩小。
- 鼠标滚轮：纵向平移；`Shift + 滚轮` 横向平移；`Ctrl + 滚轮` 以指针位置缩放。
- 数位笔：读取 Windows Ink 的合并采样点、压力和倾角；反转笔尾时临时整笔擦除。
- 触摸：单指直接选择、编辑或框选内容；两指始终抢占未完成的单指动作并平移 / 缩放页面。
- “触控笔抓手”仅改变数位笔接触为平移，不改变手指或鼠标行为。
- “插入空白空间”可由手指或数位笔拖动执行；数位笔和鼠标悬浮会显示虚线预览，但鼠标左键仍执行直接内容操作。
- `Backspace` / `Delete`：非文本编辑状态下删除当前框选的混合内容。工具详情中不提供删除工具或删除所选按钮。
- `Ctrl+Z` / `Ctrl+Y`：撤销 / 重做；`Ctrl+S`：立即保存；`F11`：全页模式。

页面窗格的 `+` 同时提供普通页和 PDF：普通页记住纸张、基线与颜色，PDF 按原始页尺寸拆成可批注页面。套索可在自由圈选和矩形之间切换，笔迹、标签、文本框与非背景插入对象共用带八个控制点的选择框。

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

每次发布前删除旧 `artifacts` 候选目录，发布后运行 `dotnet clean .\Notein-win.slnx -c Release`
并清理剩余项目内 `bin/obj`；只保留 `artifacts/release/Mojian.exe` 这一套当前交付物。
