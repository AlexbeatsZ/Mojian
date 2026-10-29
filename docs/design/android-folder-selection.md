# Android 笔记文件夹选择

Last verified: 2026-10-28

## 入口和记忆

- 未保存过 `sharedWorkspaceRoot` 时显示选择页，不扫描或自动采用 Documents/Mojian，
  不隐式退回应用私有目录。之前明确保存的路径仍自动重开；不可读时显示错误与重新选择入口。
- 文件菜单的「打开文件夹」、同步菜单的「打开笔记文件夹」及 Syncthing 设置均调用同一系统
  `ACTION_OPEN_DOCUMENT_TREE` 选择器，不再要求输入绝对路径。
- 保留旧版应用私有目录及其偏好；存在旧笔记目录偏好时可明确选择「继续应用内原有笔记」。
  这不是迁移或复制操作。未加载编辑器时不分发绘图/编辑键盘快捷键。

## 访问边界

- 按 Android 官方共享文件文档及 `android/storage-samples` 的 ActionOpenDocumentTree
  流程选择目录，不自建文件管理器。当前仓库和原生同步需要 `java.io.File`、文件锁、原子替换及
  同目录恢复文件，故不将任意 content URI 伪装为磁盘路径。
- 当前支持 Android 11+、「所有文件访问」权限及系统本地存储提供者；支持主存储和已挂载 SD 卡。
  只有 `com.android.externalstorage.documents` 的树 ID 会在已枚举的本地卷中解析，拒绝路径穿越、
  云盘和虚拟提供者。此选择器不是通用 SAF/DocumentFile 存储后端；没有撤销已有广泛存储权限需求。
- 授权后返回选择器；取消授权/选目录不改变原路径。选择器返回时以及真正切换时均检查保存屏障。
- 现有目录必须有可读取的 `library.json`。空目录必须再次确认才创建 v2 标准笔记库；非空的
  非笔记库目录拒绝初始化。新建不会复制当前库、删除旧笔记或更改 Syncthing。
- 路径偏好提交成功才重新创建 Activity；读取、写入或提交失败保留原编辑器和路径。
  原生同步 incoming journal 在新编辑器正式加载前仍须离线恢复并重载，不能跳过恢复。

## 验证边界

`WorkspaceFolderSelectionTest` 覆盖主存储/可移除卷解析、路径穿越拒绝、v2 创建及已有文件保护。
Activity 的系统选择器、权限页往返、切换时保存失败和重启记忆仍需实体 Android 验收；编译不等于验收。
