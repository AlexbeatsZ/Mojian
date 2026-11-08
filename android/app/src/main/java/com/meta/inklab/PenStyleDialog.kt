package com.meta.inklab

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputFilter
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

class PenStyleDialog(private val activity: Activity, private val shelf: ToolShelf, private val tool: ToolPreset, private val chooseFile: ((String) -> Unit) -> Unit, private val saved: () -> Unit) {
    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
    fun show() {
        val preset = shelf.penStyles.first { it.id == tool.penStyleId }
        val content = EditText(activity).apply {
            setText((preset.configuration ?: PenConfiguration.fromStyle(preset)).text)
            gravity = Gravity.TOP or Gravity.START; typeface = Typeface.MONOSPACE; textSize = 13f
            setTextColor(Color.LTGRAY); setBackgroundColor(Color.rgb(24, 24, 24)); setPadding(dp(12), dp(12), dp(12), dp(12))
            filters = arrayOf(InputFilter.LengthFilter(PenConfiguration.MAXIMUM_BYTES)); isHorizontalScrollBarEnabled = true
            setHorizontallyScrolling(true)
        }
        val error = TextView(activity).apply { setTextColor(Color.rgb(255, 145, 135)) }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(4))
            addView(TextView(activity).apply { text = "笔触配置 JSON"; setTextColor(Color.LTGRAY); setPadding(0, 0, 0, dp(10)) })
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(error)
        }
        val dialog = AlertDialog.Builder(activity).setTitle("笔触配置").setView(body)
            .setNegativeButton("返回", null).setNeutralButton("选择文件", null).setPositiveButton("导入", null).create()
        dialog.setOnShowListener {
            dialog.window?.setLayout(minOf(dp(820), activity.resources.displayMetrics.widthPixels - dp(24)), (activity.resources.displayMetrics.heightPixels * .88).toInt())
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { chooseFile { text -> content.setText(text); error.text = "" } }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val config = PenConfiguration.parse(content.text.toString()); config.resolve(config.valuesFor(tool))
                    val imported = shelf.importPenConfiguration(content.text.toString())
                    shelf.applyPenStyle(tool, imported); saved(); dialog.dismiss()
                } catch (invalid: Exception) { error.text = invalid.message }
            }
        }
        dialog.show()
    }
}
