# Android 页面加载与视图恢复

Last verified: 2026-10-19

修改 Android 页面绑定、跨页会话、后台加载或同步刷新前阅读本文；共享存储契约见 `live-note-sync.md`。

## 用户报告和已确认路径

- 用户报告用完橡皮后过一段时间会回到 100%，而不是切换工具瞬间。
- 原同步刷新每三秒检查文件变化，收到变化后清空 `pageSessions`；重绑时 session 缺失便调用
  `viewport.reset()`，丢失当前缩放和平移。工具切换本身没有 reset。
- 用户另报告切换笔记偶尔卡死。原 `bindSelectedPageContent` 在主线程 flush 保存队列、
  读取/解析整页 JSON、构造全部 native strokes；大导入页会占住界面。
  在未取得实机 ANR 堆栈前，不将所有“卡死”都归因为这一条路径。

## 视图与内容生命周期

- `PageViewportMemory` 按 notebook/page 身份独立保存本机缩放与平移。同步使内容失效时，
  清除内容会话并保留活动页最新 viewport；不能拿较早保存的 session viewport 覆盖当前值。
- 仅真正首次打开且没有本机视图记录的页面使用 100%；删除页面时清除它自己的视图记录。
- 笔、橡皮、直接内容拖动、插入空间和导航手势尚未结束时不刷新共享内容。
  `CanvasInputHost` 记录完整 DOWN 到 UP/CANCEL；暂停时显式释放此状态。

## 异步绑定

- 在 UI 线程结束编辑、取消未完成的手势，提交当前页独立快照并保存视图；普通页面切换不在 UI
  线程 flush 保存队列。页面工人线程先等待队列完成，再读取目标页、解码笔迹并准备 vector paths。
- `LatestPageLoader` 每个请求都有递增 generation。旧请求排队、准备中或已投递到 UI，都不能绑定
  覆盖较新的选择；密集笔迹循环检查 generation，允许过期任务提前停止。
- 加载时显示“正在打开页面…”，保留笔记本/页面列表可点；拦住内容、Ribbon、键盘和滚轮操作，
  防止在目标页标题下修改尚未卸载的旧页。失败显示错误并优先回到仍有效的旧页，不创建可编辑空页。
- 完成结果回到 UI 时还要核对 Activity 生命周期、repository 身份和目标 notebook/page 身份。
  View、文本编辑器和图层绑定仍只在 UI 线程执行。
- 本机页面选择只保存导航偏好，不在每次点页面时争用共享 library/page 文件写锁。
- `SharedStrokeCodec` 的全局弱引用记录表必须支持工作线程创建笔迹与 UI 使用旧笔迹并发。

## 验证边界

`LatestPageLoaderTest` 检查后台准备/UI 投递边界、快速切换的过期结果、取消、读取失败，
以及保存屏障阻塞时导航调用仍返回。`PageViewportMemoryTest` 检查重绑保留缩放和平移、
页面独立和删除范围。179 项 Android 单元测试、APK、lint 和 native-test APK 构建通过。
这些结果不等于物理橡皮或原报告卡死的设备验收；实机检查需另记录。
