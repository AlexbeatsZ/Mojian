# 笔触配置、函数与参数面板

Last verified: 2026-11-08

修改配置导入、预设选择、函数、工具参数或新笔画快照前阅读本文。历史 reader、物理速度和
标定依据见 `handwriting-engine.md`、`stored-ink-appearance.md`。

## 用户要求与交互

- 颜色行右侧保留「配置」；独立下拉框仅选择已保存配置。主面板保留笔宽，并按当前配置生成
  其他调节项。原来的压感、出墨、稳定性不能被移走，也不能成为对实际笔触无效的控件。
- 配置入口是文本导入窗口，按用户提供的参考图排列：大 JSON 输入框；底部「返回、选择文件、
  导入」。打开时填入当前配置，用户可复制、直接修改或粘贴。选择文件只替换框中的文本，
  不导入、不切换；按导入并校验通过才保存。返回/取消/校验失败不改变工具或配置库。
- 不再提供拖动控制点、另一个曲线参数弹窗或新建/修改按钮。函数、分段、名称、参数范围、
  主面板标签和绑定均由文件决定。改 id 新建；同 id 更新；不同 id 同名拒绝，避免含糊覆盖。
- 唯一预置配置是 **Notein风格**（id `notein`），对应添加预设功能之前的 `fountain-v2`。
  移除自行添加且未修改的自然钢笔、经典钢笔、均匀圆笔。用户修改过的配置保留。
- 旧工具的每笔颜色、笔宽、压感、出墨、稳定性继续保留。实际核对过的本机黑色笔是
  0.3 mm / 78% / 20% / 0%；这些是当前设备参数，不是给所有设备设置的新默认值。
- 2026-11-08 先完成源码、隔离预览和验证；用户随后明确授权直接替换可连接设备，发布/原位更新
  已授权。保留数据、签名与原参数，不强制关闭正在写入的产品。

## 文件与保存所有权

- 单文件 UTF-8 JSON，`format: "mojian-pen/v1"`。规范见
  `shared/pen-configuration.schema.json`；可导入原样例见
  `docs/examples/pen-configurations/notein.json`、`legacy-android.json`。
- `id` 为稳定引用，1–80 个 ASCII 字母、数字、点、横线或下划线；`name` 为 1–40 字符。
  `parameters` 定义默认数值，`controls` 决定显示项，`style` 定义引擎输入与函数。
- 继续由 ToolShelf 和既有设备设置写入器保存。导入内容是副本，不持有文件路径，不读网络
  引用，不增加文件监视器/写入器/同步服务。原生文件选择器只负责读文本。
- 每个工具按配置 id 保存参数值，切换配置再切回可恢复其参数。颜色/笔宽属于工具。
  `widthMm` 是只读工具变量，可用于起收尖相对笔宽长度，不能再声明一个同名主面板控件。
- Notein 控件通过 `legacyField` 继续绑定工具原字段：pressurePercent / inkFlowPercent /
  stabilityPercent。没有 legacyField 的参数只属于此配置，可被任意多个函数引用。
- 同 id 更新会先验证所有引用工具的实际参数，再整体替换；不得写入一半的配置。旧版存储的
  用户曲线可转为分段函数文本导入，原曲线快照仍能读取。

## 函数与面板

数值位置既可填常数，也可填 `"$参数名"`，或数学数据树：

```json
{
  "op": "multiply",
  "args": [1.4, {"op": "pow", "args": ["x", {"op": "divide", "args": ["$pressure", 100]}]}]
}
```

支持 add/subtract/multiply/divide/pow/min/max/abs/sin/cos/exp/log/clamp、五参 linear 和
smoothstep。linear/smoothstep 的参数为 `[输入, 区间起点, 区间终点, 输出起点, 输出终点]`；
区间外钳制。smoothstep 为 `3t²−2t³`。piecewise 用连续递增的 `from/to/value` 分段，
每段 value 是独立函数；超出总区间使用首/末端点值，边界取左段。

曲线对象含 function、可选 samples（默认 128）和 breaks；samples 是引擎离散化精度，breaks 是额外分段边界，
两者不是可拖动的控制点。piecewise 的区间边界自动保留。压力横轴 0–1，速度横轴为 0–speedMaximumCmPerSecond；输出是
名义宽度的倍率 0–3。Notein 压力用原来的 16 段，速度保留测得的分段边界，所以不是重新调参。
光滑函数导入时按指定采样编译到现有线性响应图；例如 256 段 smoothstep 的测试误差 <0.00002。
这不是原生解析函数的逐像素精确求值；更高阶函数应选择足够采样精度。

例如将一个面板项改成控制速度响应时间：

```json
{
  "parameters": {"responseMs": 25},
  "controls": [{"parameter":"responseMs","label":"速度平滑","min":0,"max":100,"step":1,"unit":" ms"}],
  "style": {"speedResponseMs":"$responseMs"}
}
```

上例是片段，实际导入还须 format/id/name。不必固定存在名叫稳定性的参数，也不必把它固定
绑定位置平滑；Notein 的三个项仍沿用原含义。一个参数可同时控制多个响应/公式。

## 当前共享引擎可配置范围

| 因素 | 文件字段 | 单位/约束 |
|---|---|---|
| 压力 | pressureEnabled, pressureCurve, pressureResponseMs | 压力 0–1，时间 0–100 ms |
| 速度 | speedEnabled, speedCurve, speedResponseMs, speedMaximumCmPerSecond | cm/s，量程 1–1000 |
| 方向 | directionEnabled, directionAmount, directionMinimum/Maximum, directionResponseMs | 归一化 Y -1–1，阈值递增，增粗 0–100% |
| 起笔 | startEnabled, startLengthMm, startMinimum, startResponseMs | 0.1–20 mm，尖端 0–100%，0–100 ms |
| 收笔 | endEnabled, endLengthMm, endMinimum, endResponseMs | 同上，可用 $widthMm 比例表达长度 |
| 输入建模 | stabilityPercent, inputWindowMs, upsamplingHz | 0–100%；窗口 0=原映射、1–500 ms；30–1000 Hz |
| 倾角 | tiltEnabled, tiltWidth, tiltMaximumRadians | 终值倍率 0.1–3，角度 0.01–π/2 |
| 转弯 | turnEnabled, turnAmount, turnMinimum/Maximum, turnResponseMs | 向负侧的横向加速度 cm/s²，阈值递增，增粗 0–100% |

位置/压力平滑先于速度、方向与加速度；各宽度响应相乘。Windows 重采样保留原采样时间，
每个原始间隔最多 100 个子段；Android 使用原生 SlidingWindowModel。高级窗口优先于百分比。
Android 输入模型缓存复用现有 owner，以窗口/重采样率为键，最多 64 项，避免调参无限保留 native 对象。

## 新旧笔触的实际对应

| 项目 | Notein风格 / 原 fountain-v2 | 旧 Android / createLegacy | 旧 Windows / HandwritingEngine |
|---|---|---|---|
| 压力 | 1.4·p^(压感/100)，16 段 | p=.18–.88 内线性，200% 为 .55–1.45 | .38+.78·clamp(p,.03,1)^(2/max(.2,压感/100)) |
| 速度 | 实际 cm/s 的标定分段，64 达上限 | 0–85 cm/s，100% 为 1.08→.82 | 文档单位/ms 的倒数函数，另有 .72+.28·flow |
| 起收尖 | 无固定距离收尖 | 1.25/2.5 个笔宽，.55/.18，12 ms | 按点索引进度的 12%/16% 收尖，最低 .24 |
| 平滑 | 输入窗口＋速度/方向 25 ms | 原 20 ms 输入窗口；压力 20/速度 18 ms | 位置 .2/.6/.2 三点平均，无相同时间建模 |
| 转弯 | 关 | -80→-230 cm/s²，最大 1.25× | 无相同项 |

`legacy-android.json` 已用函数、85 cm/s 量程、相对笔宽长度和各阻尼参数表达旧 Android 的
行为参数；它是可手动导入的对照文件，**没有预置进产品**。它和同一输入在 WPF 上的几何
并不宣称等于旧 Windows，也不以参数测试冒充 Android 原生笔感/像素验收。

旧 Windows 的按索引收尖、文档速度和三点平均不是该共享时间模型。若要严格复现它，须
暴露这些输入建模节点或显式保留旧引擎 adapter，不能用“经典”这个名称掩盖差异。
历史 v1 仍由原 reader 保留，其原始参数、轮廓和显示均不批量迁移。

## 尽可能覆盖引擎能力的目标与边界

目标是：已有引擎操作应能由文件组合，新增风格不新增风格专用代码。不能把 format 固定为
三个命名预设，也不能承诺纯数据能创造引擎本来没有的渲染能力。

本次对照了项目实际锁定的 AndroidX Ink 1.0.0 官方 sources.jar（BrushBehavior、BrushTip、
BrushFamily、BrushPaint、EasingFunction），而非假设最新版接口。其能力比当前两端共享的
圆形实色轮廓模型更广：

- 行为图有 Source/Constant/Noise、Fallback/ToolType filter、Damping、Response、BinaryOp、
  Interpolation、Target、PolarTarget 节点；source 有压力、倾角、方向、速度/加速度、时间、
  距离、预测/剩余量等 38 项；target 有尺寸/宽高、旋转/倾斜/挤压、位置偏移、颜色/透明度、
  纹理进度等 16 项，另有两个极坐标位置输出。
- 响应函数有 predefined、分段线性、CubicBezier、Steps。笔尖还可设置宽高、圆角、倾斜、
  pinch、旋转、粒子间距/时间，BrushFamily 可含多 coat，BrushPaint 可包含纹理层及混合。
- 上述完整 native 图、非圆笔尖、逐顶点颜色、纹理/多涂层/粒子尚未接入 WPF 与规范笔画
  存储，**不是本次 format v1 已实现的键**。未知字段/函数须明确拒绝，不能静默当成圆笔。

后续扩展应增加版本化的 nodes/tip/coats/inputModel 能力，Android 编译到这些原生节点，
Windows 使用能解释相同节点的实现或共享原生内核。完成形状、材质和动态颜色对应的规范
几何/材质快照、导出、擦除、选择和跨端测试后才能对外声明。把完整 Android JSON 直接
反射进 native 而 Windows 悄悄忽略，不符合这个目标。

## 校验、快照与验收

- 最多 128 KiB，JSON 深度 32，函数深度 20，分段最多 32，编译曲线最多 257 点；最多
  32 参数（含只读 widthMm）、12 个可见调节项，每个最多 10,000 档。不执行脚本，不使用 eval。
- 导入校验结构、变量、区间、输出倍率、有限值及引擎范围；运行调参也先求值，失败不改模型。
  JSON Schema 校验不能代替函数与当前工具实际参数的求值检查。
- 新笔画仍保存完整 resolved `pen-style-v1` 和规范填充轮廓，绘制过程不查可变配置定义。
  保存、复制、导出、移动/缩放、擦除继续使用现有 owner；改文件/配置不能改变历史墨迹。
- Windows 用临时库检查导入/取消/选文件、嵌套 dropdown 点击、参数实际变化、重启恢复和
  v2 逐样本等价；Android 检查同一函数/实例与旧参数对照，编译 native fixture、运行 JVM/lint。
  未运行 native fixture、未做实体笔验收时必须明确说明。更新需服从当次用户授权与设备状态。
