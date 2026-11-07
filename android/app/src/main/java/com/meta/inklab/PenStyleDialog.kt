package com.meta.inklab

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlin.math.roundToInt

class PenStyleDialog(private val activity: Activity, private val shelf: ToolShelf, private val tool: ToolPreset, private val saved: () -> Unit) {
    private var draft = shelf.effectivePenStyle(tool)
    private var editingId: String? = tool.penStyleId
    private val name = EditText(activity).apply { setSingleLine(); filters = arrayOf(InputFilter.LengthFilter(40)); hint = "预设名称" }
    private val parameters = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val errorLabel = TextView(activity).apply { setTextColor(Color.rgb(255, 145, 135)); visibility = View.GONE }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    fun show() {
        val body = column().apply { setPadding(dp(18), dp(4), dp(18), dp(8)) }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val selector = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("新建预设") + shelf.penStyles.map { it.name })
        }
        row.addView(selector, LinearLayout.LayoutParams(0, dp(44), 1f))
        row.addView(Button(activity).apply {
            text = "新建预设"
            setOnClickListener { selector.setSelection(0); editingId = null; name.setText(""); name.requestFocus(); errorLabel.visibility = View.GONE }
        }, LinearLayout.LayoutParams(wrap, dp(44)))
        body.addView(row)
        body.addView(name, LinearLayout.LayoutParams(match, dp(48)))
        body.addView(ScrollView(activity).apply { addView(parameters, ViewGroup.LayoutParams(match, wrap)) }, LinearLayout.LayoutParams(match, 0, 1f))
        body.addView(errorLabel)
        selector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) { editingId = null; name.setText(""); render(); return }
                val preset = shelf.penStyles[position - 1]
                editingId = preset.id; draft = preset.style.snapshot(); name.setText(preset.name); errorLabel.visibility = View.GONE; render()
            }
        }
        selector.setSelection(shelf.penStyles.indexOfFirst { it.id == tool.penStyleId }.coerceAtLeast(0) + 1)
        val dialog = AlertDialog.Builder(activity).setTitle("笔触配置").setView(body).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.window?.setLayout(minOf(dp(540), activity.resources.displayMetrics.widthPixels - dp(32)), (activity.resources.displayMetrics.heightPixels * .86).toInt())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val preset = shelf.savePenStyle(editingId, name.text.toString(), draft)
                    shelf.applyPenStyle(tool, preset); saved(); dialog.dismiss()
                } catch (invalid: IllegalArgumentException) { errorLabel.text = invalid.message; errorLabel.visibility = View.VISIBLE }
            }
        }
        render(); dialog.show()
    }
    private fun render() {
        parameters.removeAllViews()
        curve("压力 → 宽度", draft.pressureEnabled, { draft.pressureEnabled = it }, draft.pressureCurve, 1.0, "压力 %") { draft.pressureCurve = it }
        curve("速度 → 宽度", draft.speedEnabled, { draft.speedEnabled = it }, draft.speedCurve, 64.0, "速度 cm/s") { draft.speedCurve = it }
        val direction = toggle(parameters, "方向 → 宽度", draft.directionEnabled) { draft.directionEnabled = it }
        number(direction, "增粗幅度", draft.directionAmount, 0.0, 1.0, .01, { "${(it * 100).roundToInt()}%" }) { draft.directionAmount = it }
        taper("起笔收尖", true); taper("收笔收尖", false)
        parameters.addView(label("稳定性"))
        number(parameters, "位置与压力平滑", draft.stabilityPercent.toDouble(), 0.0, 100.0, 1.0, { "${it.roundToInt()}%" }) { draft.stabilityPercent = it.roundToInt() }
        val advanced = column().apply { visibility = View.GONE }
        parameters.addView(Button(activity).apply { text = "高级 ▸"; setOnClickListener { advanced.visibility = if (advanced.visibility == View.GONE) View.VISIBLE else View.GONE; text = if (advanced.visibility == View.GONE) "高级 ▸" else "高级 ▾" } })
        parameters.addView(advanced)
        val tilt = toggle(advanced, "倾角 → 宽度", draft.tiltEnabled) { draft.tiltEnabled = it }
        number(tilt, "倾斜 60° 时宽度", draft.tiltWidth, .1, 3.0, .01, { "${(it * 100).roundToInt()}%" }) { draft.tiltWidth = it }
        val turn = toggle(advanced, "转弯增粗", draft.turnEnabled) { draft.turnEnabled = it }
        number(turn, "增粗幅度", draft.turnAmount, 0.0, 1.0, .01, { "${(it * 100).roundToInt()}%" }) { draft.turnAmount = it }
        number(advanced, "压力响应平滑", draft.pressureResponseMs.toDouble(), 0.0, 100.0, 1.0, { "${it.roundToInt()} ms" }) { draft.pressureResponseMs = it.roundToInt() }
        number(advanced, "速度响应平滑", draft.speedResponseMs.toDouble(), 0.0, 100.0, 1.0, { "${it.roundToInt()} ms" }) { draft.speedResponseMs = it.roundToInt() }
        number(advanced, "方向响应平滑", draft.directionResponseMs.toDouble(), 0.0, 100.0, 1.0, { "${it.roundToInt()} ms" }) { draft.directionResponseMs = it.roundToInt() }
    }
    private fun label(text: String) = TextView(activity).apply { this.text = text; setTextColor(Color.LTGRAY); setPadding(0, dp(10), 0, dp(4)); textSize = 13f }
    private fun toggle(parent: LinearLayout, title: String, enabled: Boolean, change: (Boolean) -> Unit): LinearLayout {
        val child = column()
        val check = CheckBox(activity).apply { text = title; isChecked = enabled; setPadding(0, dp(8), 0, dp(4)) }
        fun state(v: View, on: Boolean) { v.isEnabled = on; if (v is ViewGroup) for (i in 0 until v.childCount) state(v.getChildAt(i), on) }
        check.setOnCheckedChangeListener { _, on -> change(on); state(child, on); child.alpha = if (on) 1f else .4f }
        parent.addView(check); parent.addView(child)
        child.alpha = if (enabled) 1f else .4f
        child.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> state(child, check.isChecked) }
        return child
    }
    private fun curve(title: String, enabled: Boolean, onToggle: (Boolean) -> Unit, points: List<CurvePoint>, maxX: Double, axis: String, change: (List<CurvePoint>) -> Unit) {
        val body = toggle(parameters, title, enabled, onToggle)
        val editor = PenCurveEditor(activity, points, maxX, axis, change)
        body.addView(editor, LinearLayout.LayoutParams(match, dp(146)))
        body.addView(Button(activity).apply { text = "删除控制点"; setOnClickListener { editor.deleteSelected() } }, LinearLayout.LayoutParams(wrap, dp(40)).apply { gravity = Gravity.END })
    }
    private fun taper(title: String, start: Boolean) {
        val body = toggle(parameters, title, if (start) draft.startEnabled else draft.endEnabled) { if (start) draft.startEnabled = it else draft.endEnabled = it }
        number(body, "长度", if (start) draft.startLengthMm else draft.endLengthMm, .1, 20.0, .1, { String.format(java.util.Locale.ROOT, "%.1f mm", it) }) { if (start) draft.startLengthMm = it else draft.endLengthMm = it }
        number(body, "尖端宽度", if (start) draft.startMinimum else draft.endMinimum, 0.0, 1.0, .01, { "${(it * 100).roundToInt()}%" }) { if (start) draft.startMinimum = it else draft.endMinimum = it }
    }
    private fun number(parent: LinearLayout, title: String, value: Double, min: Double, max: Double, step: Double, format: (Double) -> String, change: (Double) -> Unit) {
        val heading = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label(title), LinearLayout.LayoutParams(0, wrap, 1f))
        val current = label(format(value)); heading.addView(current); parent.addView(heading)
        parent.addView(SeekBar(activity).apply {
            this.max = ((max - min) / step).roundToInt(); progress = ((value - min) / step).roundToInt().coerceIn(0, this.max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) { val v = min + progress * step; current.text = format(v); change(v) } }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(match, dp(34)))
    }
}
