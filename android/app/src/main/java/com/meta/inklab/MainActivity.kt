package com.meta.inklab

import android.app.Activity
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import java.util.Locale

class MainActivity : Activity() {
    private val activeStrokes = mutableMapOf<Int, InProgressStrokeId>()

    private lateinit var wetInkView: InProgressStrokesView
    private lateinit var dryInkView: DryInkView
    private lateinit var predictor: MotionEventPredictor
    private lateinit var statusText: TextView
    private lateinit var profileText: TextView
    private lateinit var predictionButton: TextView

    private var selectedSize = 4.8649f
    private var pressureCorrect = 2f
    private var speedCorrect = 1f
    private var currentBrush: Brush =
        BrushProfiles.create(selectedSize, pressureCorrect, speedCorrect)
    private var predictionEnabled = true
    private var predictedEventSeen = false
    private var movingInputAgeMs = 0f
    private var lastHistorySize = 0
    private var lastStatusUpdateMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.statusBarColor = RAIL_COLOR
        window.navigationBarColor = PAPER_COLOR
        setContentView(buildInterface())
        bindInkPipeline()
        updateSelectionUi()
        updateStatus(force = true)
        wetInkView.post { wetInkView.eagerInit() }
    }

    override fun onPause() {
        wetInkView.cancelUnfinishedStrokes()
        activeStrokes.clear()
        super.onPause()
    }

    private fun buildInterface(): View {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(PAPER_COLOR)
            }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val (top, bottom) =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    bars.top to bars.bottom
                } else {
                    insets.systemWindowInsetTop to insets.systemWindowInsetBottom
                }
            view.setPadding(0, top, 0, bottom)
            insets
        }

        root.addView(buildToolRail(), LinearLayout.LayoutParams(MATCH, dp(104)))

        val paper = FrameLayout(this)
        dryInkView = DryInkView(this)
        wetInkView = InProgressStrokesView(this)
        paper.addView(dryInkView, FrameLayout.LayoutParams(MATCH, MATCH))
        paper.addView(wetInkView, FrameLayout.LayoutParams(MATCH, MATCH))

        profileText =
            TextView(this).apply {
                setTextColor(Color.rgb(54, 68, 87))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = rounded(Color.argb(220, 247, 249, 252), dp(7).toFloat(), Color.rgb(207, 219, 235))
                isClickable = false
            }
        paper.addView(
            profileText,
            FrameLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = dp(16)
                topMargin = dp(14)
            },
        )

        root.addView(paper, LinearLayout.LayoutParams(MATCH, 0, 1f))

        statusText =
            TextView(this).apply {
                setBackgroundColor(Color.rgb(24, 29, 35))
                setTextColor(TELEMETRY_COLOR)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
        root.addView(statusText, LinearLayout.LayoutParams(MATCH, dp(34)))
        return root
    }

    private fun buildToolRail(): View {
        val rail =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(RAIL_COLOR)
            }

        val profileRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(5), dp(14), 0)
                setBackgroundColor(RAIL_COLOR)
            }

        profileRow.addView(
            TextView(this).apply {
                text = "INK / LAB"
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
                letterSpacing = 0.12f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, 0, dp(18), 0)
            }
        )

        profileRow.addView(
            TextView(this).apply {
                text = "研究钢笔 · 单一笔触"
                setTextColor(Color.rgb(145, 158, 174))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER_VERTICAL
            },
        )

        val optionRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), dp(5))
                setBackgroundColor(RAIL_COLOR)
            }

        optionRow.addView(
            parameterControl("笔宽", initial = 3, max = 19, value = { "${(it + 1) / 10f} mm" }) {
                selectedSize = ((it + 1) / 10f) * INTERNAL_UNITS_PER_MM
                rebuildBrush()
            },
        )
        optionRow.addView(
            parameterControl("压感", initial = 200, max = 400, value = { "$it%" }) {
                pressureCorrect = it / 100f
                rebuildBrush()
            },
        )
        optionRow.addView(
            parameterControl("出墨", initial = 100, max = 200, value = { "$it%" }) {
                speedCorrect = it / 100f
                rebuildBrush()
            },
        )

        optionRow.addView(divider())
        predictionButton = chip("预测 开") {
            predictionEnabled = !predictionEnabled
            predictedEventSeen = false
            updateSelectionUi()
            updateStatus(force = true)
        }
        optionRow.addView(predictionButton)
        optionRow.addView(chip("撤销") { dryInkView.undo(); updateStatus(force = true) })
        optionRow.addView(chip("清纸") { dryInkView.clear(); updateStatus(force = true) })

        rail.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(RAIL_COLOR)
                addView(profileRow, ViewGroup.LayoutParams(WRAP, MATCH))
            },
            LinearLayout.LayoutParams(MATCH, dp(52)),
        )
        rail.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(RAIL_COLOR)
                addView(optionRow, ViewGroup.LayoutParams(WRAP, MATCH))
            },
            LinearLayout.LayoutParams(MATCH, dp(52)),
        )

        return rail
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindInkPipeline() {
        predictor = MotionEventPredictor.newInstance(wetInkView)
        wetInkView.addFinishedStrokesListener(
            object : InProgressStrokesFinishedListener {
                override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                    dryInkView.addStrokes(strokes.values)
                    dryInkView.invalidate()
                    wetInkView.removeFinishedStrokes(strokes.keys)
                    updateStatus(force = true)
                }
            }
        )

        wetInkView.setOnTouchListener { _, event ->
            handleMotionEvent(event)
            true
        }
    }

    private fun handleMotionEvent(event: MotionEvent) {
        predictor.record(event)
        lastHistorySize = event.historySize
        val eventAge = (SystemClock.uptimeMillis() - event.eventTime).coerceAtLeast(0L).toFloat()
        movingInputAgeMs = if (movingInputAgeMs == 0f) eventAge else movingInputAgeMs * 0.86f + eventAge * 0.14f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (isStylus(event.getToolType(pointerIndex))) {
                    activeStrokes[pointerId] =
                        wetInkView.startStroke(
                            event = event,
                            pointerId = pointerId,
                            brush = currentBrush,
                        )
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val prediction = if (predictionEnabled) predictor.predict() else null
                predictedEventSeen = prediction != null
                for ((pointerId, strokeId) in activeStrokes) {
                    if (event.findPointerIndex(pointerId) >= 0) {
                        wetInkView.addToStroke(event, pointerId, strokeId, prediction)
                    }
                }
                prediction?.recycle()
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                val strokeId = activeStrokes.remove(pointerId)
                if (strokeId != null) wetInkView.finishStroke(event, pointerId, strokeId)
            }

            MotionEvent.ACTION_CANCEL -> {
                wetInkView.cancelUnfinishedStrokes()
                activeStrokes.clear()
            }
        }

        updateStatus(force = false)
    }

    private fun rebuildBrush() {
        currentBrush =
            BrushProfiles.create(selectedSize, pressureCorrect, speedCorrect)
        updateSelectionUi()
        updateStatus(force = true)
    }

    private fun updateSelectionUi() {
        setChipSelected(predictionButton, predictionEnabled)
        predictionButton.text = if (predictionEnabled) "预测 开" else "预测 关"
        profileText.text =
            String.format(
                Locale.US,
                "%s  /  笔宽 %.1f mm · 压感 %.0f%% · 出墨 %.0f%%",
                "研究钢笔",
                selectedSize / INTERNAL_UNITS_PER_MM,
                pressureCorrect * 100f,
                speedCorrect * 100f,
            )
    }

    private fun updateStatus(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastStatusUpdateMs < 100L) return
        lastStatusUpdateMs = now
        @Suppress("DEPRECATION")
        val refreshRate =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.refreshRate ?: 0f
            } else {
                windowManager.defaultDisplay.refreshRate
            }
        val predictionState =
            when {
                !predictionEnabled -> "OFF"
                predictedEventSeen -> "ACTIVE"
                else -> "WAIT"
            }
        statusText.text =
            String.format(
                Locale.US,
                "WET→DRY  ·  prediction %-6s  ·  input age %4.1f ms  ·  history %2d  ·  %5.1f Hz  ·  strokes %d",
                predictionState,
                movingInputAgeMs,
                lastHistorySize,
                refreshRate,
                dryInkView.strokeCount(),
            )
    }

    private fun isStylus(toolType: Int): Boolean =
        toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER

    private fun chip(label: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(211, 219, 230))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            minHeight = dp(42)
            minWidth = dp(54)
            setPadding(dp(13), 0, dp(13), 0)
            background = rounded(Color.rgb(42, 49, 58), dp(8).toFloat(), Color.rgb(64, 74, 86))
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(WRAP, dp(42)).apply { marginEnd = dp(7) }
        }

    private fun setChipSelected(view: TextView, selected: Boolean) {
        view.setTextColor(if (selected) Color.WHITE else Color.rgb(211, 219, 230))
        view.background =
            if (selected) rounded(ACTIVE_COLOR, dp(8).toFloat(), ACTIVE_COLOR)
            else rounded(Color.rgb(42, 49, 58), dp(8).toFloat(), Color.rgb(64, 74, 86))
    }

    private fun divider(): View =
        View(this).apply {
            setBackgroundColor(Color.rgb(70, 80, 92))
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(28)).apply {
                marginStart = dp(5)
                marginEnd = dp(12)
            }
        }

    private fun sectionLabel(label: String): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.rgb(145, 158, 174))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(0, 0, dp(9), 0)
        }

    private fun parameterControl(
        label: String,
        initial: Int,
        max: Int,
        value: (Int) -> String,
        onChanged: (Int) -> Unit,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(8), 0)
            val text =
                TextView(this@MainActivity).apply {
                    this.text = "$label ${value(initial)}"
                    setTextColor(Color.rgb(211, 219, 230))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    gravity = Gravity.CENTER_VERTICAL
                }
            addView(text, LinearLayout.LayoutParams(dp(94), dp(42)))
            addView(
                SeekBar(this@MainActivity).apply {
                    this.max = max
                    progress = initial
                    setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                                text.text = "$label ${value(progress)}"
                                if (fromUser) onChanged(progress)
                            }

                            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                        },
                    )
                },
                LinearLayout.LayoutParams(dp(108), dp(42)),
            )
        }

    private fun rounded(fill: Int, radius: Float, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radius
            setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value.toFloat(),
                resources.displayMetrics,
            )
            .toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val INTERNAL_UNITS_PER_MM = 12.16225f
        private val RAIL_COLOR = Color.rgb(27, 32, 39)
        private val PAPER_COLOR = Color.rgb(247, 249, 252)
        private val ACTIVE_COLOR = Color.rgb(45, 91, 255)
        private val TELEMETRY_COLOR = Color.rgb(233, 162, 59)
    }
}
