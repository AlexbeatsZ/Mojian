package com.meta.inklab

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

data class PenParameterControl(val parameter: String, val label: String, val min: Double, val max: Double, val step: Double, val unit: String, val legacyField: String?) {
    fun format(value: Double) = (if (step < 1) String.format(java.util.Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.') else value.roundToInt().toString()) + unit
}

/** Mathematical authoring data. No scripts, external references, or file writers. */
class PenConfiguration private constructor(private val json: JSONObject) {
    val id = json.getString("id")
    val name = json.getString("name").trim()
    val parameters: Map<String, Double> = (json.optJSONObject("parameters") ?: JSONObject()).let { p -> p.keys().asSequence().associateWith { p.getDouble(it) }.toMutableMap().apply { putIfAbsent("widthMm", .4) } }
    val controls: List<PenParameterControl>
    val text: String get() = json.toString(2)
    fun toJson() = JSONObject(json.toString())
    init {
        require(json.getString("format") == FORMAT) { "format 必须为 mojian-pen/v1。" }
        require(json.keys().asSequence().all { it in setOf("\$schema", "format", "id", "name", "parameters", "controls", "style") }) { "配置含未知字段。" }
        require(id.length in 1..80 && id.all { it.isAsciiLetterOrDigit() || it in "-_." }) { "id 无效。" }
        require(name.length in 1..40) { "名称须为 1–40 个字符。" }
        require(parameters.size <= 32 && parameters.all { (k, v) -> k.length in 1..40 && k.all { it.isAsciiLetterOrDigit() || it == '_' } && v.isFinite() }) { "parameters 无效。" }
        controls = (json.optJSONArray("controls") ?: JSONArray()).let { array -> (0 until array.length()).map { i ->
            val c = array.getJSONObject(i)
            require(c.keys().asSequence().all { it in setOf("parameter", "label", "min", "max", "step", "unit", "legacyField") }) { "调节项含未知字段。" }
            PenParameterControl(c.getString("parameter"), c.getString("label"), c.getDouble("min"), c.getDouble("max"), c.getDouble("step"), c.optString("unit", ""), if (c.has("legacyField")) c.getString("legacyField") else null).also { control ->
                require(control.parameter != "widthMm") { "widthMm 是当前工具笔宽，不可重复添加调节项。" }
                require(parameters.containsKey(control.parameter) && control.label.length in 1..20 && control.unit.length <= 12 && control.min.isFinite() && control.max.isFinite() && control.step.isFinite() && control.min < control.max && control.step > 0 && (control.max - control.min) / control.step <= 10000) { "调节项范围无效。" }
                require(parameters.getValue(control.parameter) in control.min..control.max) { "调节项默认值超出范围。" }
                require(control.legacyField in setOf(null, "pressurePercent", "inkFlowPercent", "stabilityPercent")) { "legacyField 无效。" }
            }
        } }
        require(controls.size <= 12 && controls.map { it.parameter }.distinct().size == controls.size && controls.mapNotNull { it.legacyField }.distinct().size == controls.count { it.legacyField != null }) { "调节项重复或超过 12 项。" }
        resolve(parameters)
    }
    fun valuesFor(tool: ToolPreset): MutableMap<String, Double> = parameters.toMutableMap().apply {
        tool.penParameterValues[id]?.forEach { (k, v) -> if (containsKey(k) && v.isFinite()) put(k, v) }
        put("widthMm", tool.widthMm.toDouble())
        controls.forEach { c ->
            c.legacyField?.let { put(c.parameter, when (it) { "pressurePercent" -> tool.pressurePercent; "inkFlowPercent" -> tool.inkFlowPercent; else -> tool.stabilityPercent }.toDouble()) }
            put(c.parameter, getValue(c.parameter).coerceIn(c.min, c.max))
        }
    }
    fun setValue(tool: ToolPreset, parameter: String, value: Double) {
        val c = controls.single { it.parameter == parameter }
        require(value.isFinite() && value in c.min..c.max) { "参数超出范围。" }
        val values = valuesFor(tool).apply { put(parameter, value) }
        val resolved = resolve(values)
        tool.penParameterValues[id] = values
        when (c.legacyField) { "pressurePercent" -> tool.pressurePercent = value.roundToInt(); "inkFlowPercent" -> tool.inkFlowPercent = value.roundToInt(); "stabilityPercent" -> tool.stabilityPercent = value.roundToInt() }
        tool.penStyle = resolved
    }
    fun resolve(values: Map<String, Double>): PenStyle {
        val source = json.getJSONObject("style")
        val result = PenStyle.fromLegacy().toJson()
        val speedMaximum = if (source.has("speedMaximumCmPerSecond")) evaluate(source.get("speedMaximumCmPerSecond"), values, 0.0) else 64.0
        source.keys().forEach { key ->
            require(result.has(key) && key != "version") { "不支持的笔触参数：$key" }
            when {
                key == "pressureCurve" || key == "speedCurve" -> result.put(key, curve(source.getJSONObject(key), values, if (key == "pressureCurve") 1.0 else speedMaximum))
                result.get(key) is Boolean -> result.put(key, source.getBoolean(key))
                else -> { val n = evaluate(source.get(key), values, 0.0); result.put(key, if (key.endsWith("Ms") || key in setOf("stabilityPercent", "upsamplingHz")) n.roundToInt() else n) }
            }
        }
        return requireNotNull(PenStyle.fromJson(result)) { "笔触参数超出引擎支持范围。" }
    }
    private fun curve(curve: JSONObject, values: Map<String, Double>, end: Double): JSONArray {
        require(curve.keys().asSequence().all { it in setOf("function", "samples", "breaks") } && curve.has("function")) { "曲线须使用 function、samples、breaks，不使用控制点。" }
        val n = curve.optInt("samples", 128); require(n in 0..256) { "samples 必须在 0–256 之间。" }
        val xs = sortedSetOf(0.0, end)
        fun boundaries(node: Any?) {
            when (node) {
                is JSONObject -> {
                    if (node.optString("op") == "piecewise") node.optJSONArray("segments")?.let { segments ->
                        for (i in 0 until segments.length()) for (key in listOf("from", "to")) {
                            val x = segments.getJSONObject(i).getDouble(key); if (x in 0.0..end) xs.add(x)
                        }
                    }
                    node.keys().forEach { boundaries(node.get(it)) }
                }
                is JSONArray -> for (i in 0 until node.length()) boundaries(node.get(i))
            }
        }
        boundaries(curve.get("function"))
        if (n > 0) for (i in 1 until n) xs.add(end * i / n)
        curve.optJSONArray("breaks")?.let { breaks -> for (i in 0 until breaks.length()) {
            val x = breaks.getDouble(i); require(x.isFinite() && x in 0.0..end) { "breaks 超出曲线定义域。" }; xs.add(x)
        } }
        require(xs.size <= 257) { "曲线采样超过 257 项。" }
        return JSONArray().apply { xs.forEach { x ->
            val y = evaluate(curve.get("function"), values, x)
            require(y in 0.0..3.0) { "曲线宽度倍率必须在 0–3 之间。" }
            put(JSONObject().put("x", x).put("y", y))
        } }
    }
    companion object {
        const val FORMAT = "mojian-pen/v1"
        const val MAXIMUM_BYTES = 131072
        private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
        fun fromStyle(preset: NamedPenStyle): PenConfiguration {
            fun curve(points: List<CurvePoint>) = JSONObject().put("breaks", JSONArray(points.map { it.x })).put("function", JSONObject().put("op", "piecewise").put("segments", JSONArray().apply {
                points.zipWithNext().forEach { (a, b) -> put(JSONObject().put("from", a.x).put("to", b.x).put("value", JSONObject().put("op", "linear").put("args", JSONArray(listOf("x", a.x, b.x, a.y, b.y))))) }
            }))
            val style = preset.style.toJson().apply { remove("version"); put("pressureCurve", curve(preset.style.pressureCurve)); put("speedCurve", curve(preset.style.speedCurve)); put("stabilityPercent", "\$stability") }
            return parse(JSONObject().put("format", FORMAT).put("id", preset.id).put("name", preset.name).put("parameters", JSONObject().put("stability", preset.style.stabilityPercent))
                .put("controls", JSONArray().put(JSONObject().put("parameter", "stability").put("label", "稳定性").put("min", 0).put("max", 100).put("step", 1).put("unit", "%").put("legacyField", "stabilityPercent"))).put("style", style).toString())
        }
        fun parse(text: String): PenConfiguration {
            require(text.toByteArray(Charsets.UTF_8).size <= MAXIMUM_BYTES) { "配置文件不能超过 128 KiB。" }
            // Bound nesting before JSONObject's recursive parser sees it.
            var depth = 0; var quoted = false; var escaped = false
            text.forEach { c -> if (escaped) escaped = false else if (quoted && c == '\\') escaped = true else if (c == '"') quoted = !quoted else if (!quoted) {
                if (c == '{' || c == '[') { depth++; require(depth <= 32) { "配置嵌套超过 32 层。" } }
                if (c == '}' || c == ']') depth--
            } }
            return try { PenConfiguration(JSONObject(text)) } catch (e: Exception) { throw IllegalArgumentException("配置格式无效：" + e.message, e) }
        }
        fun evaluate(expression: Any, values: Map<String, Double>, x: Double, depth: Int = 0): Double {
            require(depth <= 20) { "函数嵌套超过 20 层。" }
            if (expression is Number) return expression.toDouble().also { require(it.isFinite()) { "数字必须有限。" } }
            if (expression is String) {
                if (expression == "x") return x
                require(expression.startsWith('$') && values.containsKey(expression.drop(1))) { "未知函数变量：$expression" }
                return values.getValue(expression.drop(1))
            }
            val node = expression as JSONObject; val op = node.getString("op")
            val field = if (op == "piecewise") "segments" else "args"
            require(node.keys().asSequence().all { it == "op" || it == field } && node.has(field)) { "函数含未知或缺失字段。" }
            val a = (node.optJSONArray("args") ?: JSONArray()).let { array -> (0 until array.length()).map { evaluate(array.get(it), values, x, depth + 1) } }
            require(a.size <= 32) { "函数最多支持 32 个参数。" }
            fun arity(n: Int, fn: () -> Double): Double { require(a.size == n) { "$op 参数数量错误。" }; return fn() }
            val result = when (op) {
                "add" -> a.sum(); "multiply" -> a.fold(1.0) { p, q -> p * q }
                "subtract" -> arity(2) { a[0] - a[1] }; "divide" -> arity(2) { a[0] / a[1] }
                "pow" -> arity(2) { a[0].pow(a[1]) }; "min" -> arity(2) { min(a[0], a[1]) }; "max" -> arity(2) { max(a[0], a[1]) }
                "abs" -> arity(1) { abs(a[0]) }; "sin" -> arity(1) { sin(a[0]) }; "cos" -> arity(1) { cos(a[0]) }
                "exp" -> arity(1) { exp(a[0]) }; "log" -> arity(1) { ln(a[0]) }
                "clamp" -> arity(3) { a[0].coerceIn(a[1], a[2]) }
                "linear", "smoothstep" -> arity(5) {
                    require(a[1] != a[2]) { "函数区间不能为零。" }
                    var t = ((a[0] - a[1]) / (a[2] - a[1])).coerceIn(0.0, 1.0)
                    if (op == "smoothstep") t = t * t * (3 - 2 * t)
                    a[3] + (a[4] - a[3]) * t
                }
                "piecewise" -> {
                    val segments = node.getJSONArray("segments"); require(segments.length() in 1..32) { "分段函数须有 1–32 段。" }
                    var previous: Double? = null; var selected: JSONObject? = null
                    for (i in 0 until segments.length()) {
                        val s = segments.getJSONObject(i); val from = s.getDouble("from"); val to = s.getDouble("to")
                        require(s.keys().asSequence().all { it in setOf("from", "to", "value") }) { "分段函数含未知字段。" }
                        require(from.isFinite() && to.isFinite() && from < to && (previous == null || from == previous)) { "分段函数的区间必须递增且连续。" }
                        previous = to; evaluate(s.get("value"), values, x.coerceIn(from, to), depth + 1)
                        if (selected == null && x <= to) selected = s
                    }
                    val s = selected ?: segments.getJSONObject(segments.length() - 1)
                    evaluate(s.get("value"), values, x.coerceIn(s.getDouble("from"), s.getDouble("to")), depth + 1)
                }
                else -> throw IllegalArgumentException("不支持的函数：$op")
            }
            require(result.isFinite()) { "函数计算产生了非有限值。" }; return result
        }
    }
}
