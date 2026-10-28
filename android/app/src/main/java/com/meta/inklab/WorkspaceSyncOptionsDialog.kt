package com.meta.inklab

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

internal class WorkspaceSyncOptionsDialog(
    private val context: Context,
    private val root: File,
    private val status: String,
    private val configure: (NativeSyncConfiguration, File?) -> Unit,
) {
    fun show() {
        val dedicated = File(root, ".mojian-native-workspace").isFile
        val configuration = if (dedicated) NativeSyncConfiguration.load(context, root) else NativeSyncConfiguration()
        val padding = (16 * context.resources.displayMetrics.density).toInt()
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, padding, padding, padding) }
        body.addView(TextView(context).apply {
            text = "$status\n本地先保存，打开或回到前台后通过 HTTPS 同步；关闭后允许暂停。冲突会保留双方副本，不自动覆盖。\n" +
                if (dedicated) "当前使用独立同步副本，原始笔记库不受影响。" else "启用时创建独立副本，原始笔记库及 Syncthing 保持不变。请先让一台设备完成上传，再接入其他设备。"
        })
        fun field(label: String, value: String, password: Boolean = false): EditText {
            body.addView(TextView(context).apply { text = label })
            return EditText(context).apply {
                setText(value)
                setSingleLine()
                inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                body.addView(this)
            }
        }
        val endpoint = field("服务地址（包含 /mojian-sync/v1/）", configuration.endpoint)
        val token = field("访问令牌（只保存在本机应用私有目录）", configuration.token, true)
        val target = if (dedicated) null else field("独立副本目录（必须不存在且位于 Syncthing 目录之外）", File(root.parentFile, "${root.name}-native").path)
        val enabled = CheckBox(context).apply { text = "启用原生同步"; isChecked = configuration.enabled || !dedicated }
        body.addView(enabled)
        val error = TextView(context).apply { setTextColor(Color.RED) }
        body.addView(error)
        val dialog = AlertDialog.Builder(context).setTitle("原生 HTTPS 同步")
            .setView(ScrollView(context).apply { addView(body) }).setNegativeButton("取消", null)
            .setPositiveButton(if (dedicated) "保存" else "创建副本并启用", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val next = NativeSyncConfiguration(endpoint.text.toString().trim(), token.text.toString(), enabled.isChecked)
                    if (next.enabled) { NativeSyncFiles.endpoint(next.endpoint); require(next.token.isNotBlank()) { "请输入访问令牌" } }
                    require(dedicated || next.enabled) { "尚未创建专用副本，无需停用" }
                    val destination = target?.let { File(it.text.toString()).canonicalFile }
                    configure(next, destination)
                    dialog.dismiss()
                } catch (failure: Exception) { error.text = failure.message }
            }
        }
        dialog.show()
    }
}
