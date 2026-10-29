package com.meta.inklab

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The scope/format/layout choices mirror the desktop export surface. */
class ExportOptionsDialog(private val context: Context, private val printing: Boolean,
    private val describe: (ExportOptions) -> String, private val action: (ExportOptions, Boolean) -> Unit,
    private val images: (ExportOptions) -> Pair<Boolean,List<ExportImageChoice>> = { false to emptyList() },
    private val summaryExecutor: java.util.concurrent.Executor? = null,
    private val onDismiss: () -> Unit = {},
    initialOptions: ExportOptions = ExportOptions(),
    private val onOptionsChanged: (ExportOptions) -> Unit = {}) {
    @Volatile private var refreshGeneration = 0
    @Volatile private var dismissed = false
    private var options = initialOptions.copy(printing=printing, format=if(printing) ExportFormat.PDF else initialOptions.format, invertedImageIds=null)
    private lateinit var summary: TextView
    private lateinit var layout: LinearLayout
    private lateinit var preview: TextView
    private lateinit var submit: TextView
    private lateinit var colorPanel: LinearLayout
    private val imageSelection=mutableMapOf<String,Boolean>()
    private val groups = mutableListOf<Pair<() -> Int,List<TextView>>>()
    private fun dp(value: Int) = (context.resources.displayMetrics.density*value).toInt()
    private fun column() = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
    private fun text(value: String, size: Float=14f) = TextView(context).apply { text=value; textSize=size; setTextColor(Color.rgb(240,237,243)); setPadding(dp(12),dp(10),dp(12),dp(10)) }
    private fun heading(parent: LinearLayout, value: String) { parent.addView(text(value,19f).apply { setTextColor(Color.rgb(218,202,231)) }) }
    private fun choices(parent: LinearLayout, labels: List<String>, selected: () -> Int, pick: (Int) -> Unit, horizontal: Boolean=false) {
        val rows = LinearLayout(context).apply { orientation=if(horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val views = labels.mapIndexed { i,label -> text(label).apply { setOnClickListener { pick(i); refresh() } } }
        views.forEach { rows.addView(it, if(horizontal) LinearLayout.LayoutParams(0, -2,1f) else LinearLayout.LayoutParams(-1,-2)) }
        groups += selected to views
        parent.addView(rows)
    }
    private fun refresh() {
        if (!::summary.isInitialized) return
        onOptionsChanged(options)
        groups.forEach { (selected,views) -> views.forEachIndexed { i,v -> v.setBackgroundColor(if(i==selected()) Color.rgb(61,54,68) else Color.rgb(36,36,36)) } }
        val packageFormat = !printing && options.format==ExportFormat.PACKAGE
        layout.visibility=if(packageFormat) View.GONE else View.VISIBLE
        preview.visibility=if(packageFormat) View.GONE else View.VISIBLE
        val request = ++refreshGeneration
        val snapshot = options
        val selections = imageSelection.toMap()
        val executor = summaryExecutor
        if (executor != null) {
            colorPanel.removeAllViews()
            summary.text="正在计算分页…"
            submit.isEnabled=false
            preview.isEnabled=false
            executor.execute {
                if (request == refreshGeneration && !dismissed) {
                    val result = runCatching {
                        val metadata = if(printing) images(snapshot) else false to emptyList()
                        val resolved = snapshot.copy(invertedImageIds=metadata.second.filter {
                            selections[it.id] ?: it.displayInverted
                        }.map { it.id }.toSet())
                        Triple(metadata, resolved, describe(resolved))
                    }
                    summary.post {
                        if (!dismissed && request == refreshGeneration) {
                            result.onSuccess { (metadata, resolved, description) ->
                                options=resolved
                                updateColorPanel(metadata)
                                summary.text=description
                                submit.isEnabled=true
                                preview.isEnabled=true
                            }.onFailure {
                                summary.text=it.message
                                submit.isEnabled=false
                                preview.isEnabled=false
                            }
                        }
                    }
                }
            }
        } else {
            runCatching {
                updateColorPanel(if(printing) images(options) else false to emptyList())
                describe(options)
            }.onSuccess { summary.text=it; submit.isEnabled=true; preview.isEnabled=true }
                .onFailure { summary.text=it.message; submit.isEnabled=false; preview.isEnabled=false }
        }
    }
    private fun updateColorPanel(metadata: Pair<Boolean,List<ExportImageChoice>>) {
        colorPanel.removeAllViews()
        val (darkPages,imageRows)=metadata
        colorPanel.visibility=if(darkPages) View.VISIBLE else View.GONE
        if(darkPages) {
            heading(colorPanel,"深色页面打印")
            colorPanel.addView(text("白色笔迹转黑、背景转白，其他颜色保持不变",12f))
            if(imageRows.isNotEmpty()) colorPanel.addView(text("选择要反色的图片（相对当前显示效果）",12f))
            imageRows.forEach { image ->
                colorPanel.addView(CheckBox(context).apply {
                    text=image.label; setTextColor(Color.LTGRAY); isChecked=imageSelection[image.id] ?: image.displayInverted
                    setOnCheckedChangeListener { _,checked -> imageSelection[image.id]=checked
                        options=options.copy(invertedImageIds=imageRows.filter { imageSelection[it.id] ?: it.displayInverted }.map { it.id }.toSet()) }
                })
            }
        }
        options=options.copy(invertedImageIds=imageRows.filter { imageSelection[it.id] ?: it.displayInverted }.map { it.id }.toSet())
    }
    fun show() {
        dismissed=false
        val root=column().apply { setPadding(dp(20),dp(12),dp(20),dp(20)); setBackgroundColor(Color.rgb(19,19,19)) }
        val body=LinearLayout(context).apply { orientation=if(context.resources.configuration.screenWidthDp>=600) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val left=column(); val right=column()
        heading(left,"1. 选择范围")
        choices(left,ExportScope.entries.map { it.label },{ options.scope.ordinal },{ options=options.copy(scope=ExportScope.entries[it]) })
        body.addView(left,LinearLayout.LayoutParams(if(body.orientation==LinearLayout.HORIZONTAL) dp(190) else -1,-2))
        body.addView(right,if(body.orientation==LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0,-2,1f) else LinearLayout.LayoutParams(-1,-2))
        if(!printing) {
            heading(right,"2. 选择格式")
            choices(right,ExportFormat.entries.map { it.label },{ options.format.ordinal },{ options=options.copy(format=ExportFormat.entries[it]) })
        }
        layout=column(); right.addView(layout)
        heading(layout,if(printing) "2. 分页设置" else "3. 分页设置")
        choices(layout,ExportPaper.entries.map { it.label },{ options.paper.ordinal },{ options=options.copy(paper=ExportPaper.entries[it]) })
        layout.addView(text("方向"))
        choices(layout,listOf("纵向","横向"),{ if(options.landscape) 1 else 0 },{ options=options.copy(landscape=it==1) },true)
        layout.addView(text("边距"))
        val margins=listOf(0f,6f,12f,20f)
        choices(layout,margins.map { "${it.toInt()} mm" },{ margins.indexOf(options.marginMm) },{ options=options.copy(marginMm=margins[it]) },true)
        layout.addView(CheckBox(context).apply { text="省略 A4 末尾空白页"; isChecked=options.trimTrailingBlank; setTextColor(Color.LTGRAY); setOnCheckedChangeListener { _,checked -> options=options.copy(trimTrailingBlank=checked); refresh() } })
        if(printing) layout.addView(CheckBox(context).apply { text="隐藏背景格式（横线、网格、点阵）"; isChecked=options.hideBackgroundFormat; setTextColor(Color.LTGRAY); setOnCheckedChangeListener { _,checked -> options=options.copy(hideBackgroundFormat=checked); refresh() } })
        layout.addView(text("输出页码（留空为全部，例如 1-3,5）",12f))
        layout.addView(EditText(context).apply { setSingleLine(); setText(options.pageRange); setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(38,38,38)); addTextChangedListener(object: android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { options=options.copy(pageRange=s.toString()); refresh() }
            override fun afterTextChanged(s:android.text.Editable?) {}
        }) })
        colorPanel=column(); right.addView(colorPanel)
        summary=text("").apply { setTextColor(Color.rgb(207,188,223)) }; right.addView(summary)
        val buttons=LinearLayout(context).apply { gravity=Gravity.START }
        val dialog=AlertDialog.Builder(context).setTitle(if(printing) "打印" else "导出").setView(ScrollView(context).apply { addView(root) }).setNegativeButton("取消",null).create()
        dialog.setOnDismissListener { dismissed=true; refreshGeneration++; onDismiss() }
        submit=text(if(printing) "打印…" else "导出…").apply { setBackgroundColor(Color.rgb(65,53,76)); setOnClickListener { dialog.dismiss(); action(options,false) } }
        preview=text("分页预览").apply { setOnClickListener { dialog.dismiss(); action(options,true) } }
        buttons.addView(submit); buttons.addView(preview); right.addView(buttons); root.addView(body)
        refresh(); dialog.show(); dialog.window?.setLayout(minOf(dp(850),context.resources.displayMetrics.widthPixels-dp(32)), -2)
    }
}
