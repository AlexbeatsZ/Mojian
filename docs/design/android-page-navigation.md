# Android 页面加载与视图恢复

Last verified: 2026-10-28

## Save and mutation safety (2026-10-28)

Save boundaries return success/failure. A failed flush blocks workspace switching, destructive
navigation and copy operations rather than allowing unsaved UI state to be discarded. Copies
save the source first and stage its assets before publishing the target page; missing source
PDFs are errors rather than silently broken references. Required assets (including an owned source
PDF) are checked before copying any target file, and duplicate references copy once. Navigation to
an empty target still waits for the worker save barrier; it must not clear the outgoing layers
while an asynchronous save can fail.

`NavigationMutation` publishes the library before physical deletion cleanup. Until publication
succeeds, the previous library and clipboard state can be restored and source files remain.
Cleanup rechecks references/generation and skips files still owned by surviving pages. A cleanup
failure can leave unreferenced files; it must not delete still-referenced content. This is not a
cross-device transaction with Syncthing. Windows uses a separate recoverable deletion journal
because its existing deletion route stages files before index publication.

Clear-page follows the same save-before-cleanup order: first flush current content, then persist
the cleared document, and only after success delete assets not retained by immutable page
backgrounds. A refused save must never leave the old durable page referencing deleted files.
Local mutation epochs span the complete navigation/cleanup operation and repository writes so
native sync cannot publish an intermediate index/assets snapshot.

修改 Android 页面绑定、跨页会话、后台加载或同步刷新前阅读本文；共享存储契约见 `live-note-sync.md`。

## 用户报告和已确认路径

- 用户报告用完橡皮后过一段时间会回到 100%，而不是切换工具瞬间。
- 原同步刷新每三秒检查文件变化，收到变化后清空 `pageSessions`；重绑时 session 缺失便调用
  `viewport.reset()`，丢失当前缩放和平移。工具切换本身没有 reset。
- 用户另报告切换笔记偶尔卡死。原 `bindSelectedPageContent` 在主线程 flush 保存队列、
  读取/解析整页 JSON、构造全部 native strokes；大导入页会占住界面。
- 2026-10-19 实机另复现内存崩溃：打开 59.6 MB 导入页后切到另一张 60.2 MB 页，
  512 MiB Java heap 耗尽，主线程在 Choreographer 分配 16 bytes 时抛出 OutOfMemoryError。
  异步读取本身不足以限制峰值；读取新页时不能继续持有旧页的 JSON、native strokes 和 paths。
  仍不将所有用户报告的“卡死”都归因为同一条路径。

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
- 保存屏障成功后，工作线程先投递 UI 释放旧页的图层、scene 闭包和 session 内容引用，
  完成后才分配新页 JSON。仅保留旧页身份和独立 viewport；读取失败时重新读取旧页。
  保存失败时尚未释放旧内容，继续保留待恢复数据；不能先释放再等待保存。
- 普通切换不复用 session 的 native stroke 列表，避免排队的完成闭包继续钉住旧页。
  `SharedStrokeCodec` 使用带 ReferenceQueue 消费线程的弱身份缓存；WeakHashMap 仅在下次
  get/put 时清理已回收键，其值仍可能在下一页 JSON 解析期间保留整个旧笔迹图。
  仍被剪贴板等持有的 stroke 会继续保留其轮廓和 metadata。
- 本机页面选择和栏位开合只保存导航偏好，不在每次点页面时争用共享 library/page 文件写锁。
- `SharedStrokeCodec` 的全局弱引用记录表必须支持工作线程创建笔迹与 UI 使用旧笔迹并发。

## 验证边界

2026-10-28 最终 0.7.0 构建通过 228/228 单元测试、APK、native-test APK 和 lint
（0 errors / 136 warnings），新增覆盖空页导航保存失败、缺失源 PDF 和复制前资产预检。
本轮不连接设备，文字/导出原生测试只编译；下面的实机记录仍属于 2026-10-19。

`LatestPageLoaderTest` 检查后台准备/UI 投递边界、快速切换的过期结果、取消、读取失败，
以及保存屏障阻塞时导航调用仍返回。`PageViewportMemoryTest` 检查重绑保留缩放和平移、
页面独立和删除范围。180 项 Android 单元测试、APK、lint 和 native-test APK 构建通过
（0 lint errors / 132 warnings）。弱身份缓存另检查相等对象的独立 metadata 和替换。

2026-10-19 在 Xiaomi Pad 6 Pro / Android 15 上覆盖安装后，连续打开上述两张密集页成功，
进程保持同一 PID；此前步骤曾实际触发 OOM。加载期间可操作导航栏，快速切去大页并返回
原页后保持 400%，较早的结果未覆盖原页。选择橡皮并在 400% 下重新加载共享库后，倍率保持。
首次检查前后原页 383 strokes / 9,572 points 的笔迹内容逐项相同；后续电脑继续编辑并同步到平板，
因此不能把整个验收时段的最终文件与起始文件当作静态不变的数据比较。
未对真实笔迹执行擦除，物理笔接触后的用户验收仍需区分；native-test APK 只构建，未安装执行。
