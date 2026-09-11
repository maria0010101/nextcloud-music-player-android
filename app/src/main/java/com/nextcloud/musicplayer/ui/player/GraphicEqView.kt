package com.nextcloud.musicplayer.ui.player

import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 10 頻段圖形等化器響應曲線視圖 (折線圖 / 響應圖)
 * 參考 32steps 原作者 (nulldio) 的視覺呈現與手勢交互設計：
 * - 橫軸 (X) 為 10 個標準音訊頻段 (31Hz ~ 16kHz)
 * - 縱軸 (Y) 為增益分貝 (-12 dB ~ +12 dB)，含 +10, +5, 0, -5, -10 dB 刻度線
 * - 支援多點觸控直接上下拖曳節點即時調節各頻段數值 (0.5 dB 步進 + 震動觸覺回饋)
 * - 支援雙擊節點快速將該頻段歸零 (0.0 dB)
 * - 0 dB 基準線至節點具備立體圓角長條 (bars) 與下方漸層響應等高填色
 */
class GraphicEqView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    fun interface OnGainChangeListener {
        fun onGainChanged(bandIndex: Int, gainDb: Float)
    }

    var listener: OnGainChangeListener? = null

    private val bandLabels = arrayOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    private val bandCount = 10
    private val gains = FloatArray(bandCount)

    private val minDb = -12f
    private val maxDb = 12f

    // Paints
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.6f)
    }
    private val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val gridTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(9.5f)
        textAlign = Paint.Align.RIGHT
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val activeBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val activeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }
    private val dbValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10.5f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val freqLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    // Layout dimensions
    private val cardPadding = dp(8f)
    private val leftPad = dp(28f)
    private val rightPad = dp(14f)
    private val topPad = dp(22f)
    private val bottomPad = dp(24f)
    private val dotRadius = dp(7f)
    private val barWidth = dp(7.5f)
    private val cardRadius = dp(16f)

    // Colors
    private var accentColor = 0xFF4A90E2.toInt()
    private var onSurfaceColor = Color.WHITE
    private var cardBgColor = 0xFF181C24.toInt()

    // Touch & State
    private var draggingBand = -1
    private var lastHapticStep = Int.MIN_VALUE
    private var lastTapTime = 0L
    private var lastTapBand = -1

    // Reusable objects to avoid GC churn in onDraw
    private val curvePath = Path()
    private val fillPath = Path()
    private val cardRect = RectF()
    private val bandX = FloatArray(bandCount)
    private val bandY = FloatArray(bandCount)

    private var chartLeft = 0f
    private var chartRight = 0f
    private var chartTop = 0f
    private var chartBottom = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        updateColors()
    }

    fun setThemeColors(accent: Int, onSurface: Int, cardBg: Int) {
        accentColor = accent
        onSurfaceColor = onSurface
        cardBgColor = cardBg
        updateColors()
        invalidate()
    }

    private fun updateColors() {
        cardPaint.color = cardBgColor
        borderPaint.color = withAlpha(onSurfaceColor, 30)
        gridLinePaint.color = withAlpha(onSurfaceColor, 25)
        zeroLinePaint.color = withAlpha(onSurfaceColor, 65)
        gridTextPaint.color = withAlpha(onSurfaceColor, 110)
        barPaint.color = withAlpha(accentColor, 100)
        activeBarPaint.color = withAlpha(accentColor, 180)
        curvePaint.color = accentColor
        fillPaint.color = withAlpha(accentColor, 28)
        dotPaint.color = accentColor
        dotRingPaint.color = withAlpha(accentColor, 200)
        activeRingPaint.color = Color.WHITE
        dbValuePaint.color = accentColor
        freqLabelPaint.color = withAlpha(onSurfaceColor, 170)
    }

    fun setGains(newGains: FloatArray) {
        // Don't interrupt if user is actively dragging a band
        if (draggingBand >= 0) return

        var changed = false
        for (i in 0 until bandCount.coerceAtMost(newGains.size)) {
            val clamped = newGains[i].coerceIn(minDb, maxDb)
            if (abs(gains[i] - clamped) > 0.01f) {
                gains[i] = clamped
                changed = true
            }
        }
        if (changed) {
            invalidate()
        }
    }

    fun getGains(): FloatArray = gains.copyOf()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val count = if (!isEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), 100)
            } else {
                -1
            }
        } else {
            -1
        }

        drawEqualizer(canvas)

        if (count != -1) {
            canvas.restoreToCount(count)
        }
    }

    private fun drawEqualizer(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. 卡片底色與外框 (Card background & subtle border)
        cardRect.set(1f, 1f, w - 1f, h - 1f)
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, cardPaint)
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, borderPaint)

        // 2. 圖表區域邊界 (Chart area offsets)
        chartLeft = cardPadding + leftPad
        chartRight = w - cardPadding - rightPad
        chartTop = cardPadding + topPad
        chartBottom = h - cardPadding - bottomPad
        val cW = chartRight - chartLeft
        val cH = chartBottom - chartTop
        if (cW <= 0 || cH <= 0) return

        // 3. 計算各頻段節點的 (X, Y) 座標
        val spacing = cW / (bandCount - 1)
        for (i in 0 until bandCount) {
            bandX[i] = chartLeft + i * spacing
            bandY[i] = dbToY(gains[i])
        }

        // 4. 水平刻度網格線與 dB 標籤 (+10, +5, 0, -5, -10 dB)
        val dbSteps = floatArrayOf(-10f, -5f, 0f, 5f, 10f)
        for (db in dbSteps) {
            val y = dbToY(db)
            val paint = if (db == 0f) zeroLinePaint else gridLinePaint
            canvas.drawLine(chartLeft, y, chartRight, y, paint)

            val label = when {
                db > 0 -> "+${db.toInt()}"
                db == 0f -> "0"
                else -> "${db.toInt()}"
            }
            canvas.drawText(label, chartLeft - dp(6f), y + gridTextPaint.textSize * 0.35f, gridTextPaint)
        }

        // 5. 基準線至節點之立體長條柱 (Vertical rounded bars from 0 dB to band gain)
        val zeroY = dbToY(0f)
        val halfBar = barWidth / 2f
        for (i in 0 until bandCount) {
            val top = minOf(bandY[i], zeroY)
            val bottom = maxOf(bandY[i], zeroY)
            if (bottom - top > 1f) {
                val p = if (i == draggingBand) activeBarPaint else barPaint
                canvas.drawRoundRect(
                    bandX[i] - halfBar, top,
                    bandX[i] + halfBar, bottom,
                    halfBar, halfBar, p
                )
            }
        }

        // 6. 曲線下方漸層填色 (Gradient contour fill from curve to bottom)
        fillPath.reset()
        fillPath.moveTo(bandX[0], chartBottom)
        for (i in 0 until bandCount) {
            fillPath.lineTo(bandX[i], bandY[i])
        }
        fillPath.lineTo(bandX[bandCount - 1], chartBottom)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)

        // 7. 折線響應曲線 (Response polyline curve)
        curvePath.reset()
        curvePath.moveTo(bandX[0], bandY[0])
        for (i in 1 until bandCount) {
            curvePath.lineTo(bandX[i], bandY[i])
        }
        canvas.drawPath(curvePath, curvePaint)

        // 8. 節點圓點、dB 增益值與頻率標籤
        for (i in 0 until bandCount) {
            val x = bandX[i]
            val y = bandY[i]

            // 節點圓點 + 光暈圈
            if (i == draggingBand) {
                canvas.drawCircle(x, y, dotRadius + dp(4f), activeRingPaint)
            }
            canvas.drawCircle(x, y, dotRadius + dp(1.2f), dotRingPaint)
            canvas.drawCircle(x, y, dotRadius, dotPaint)

            // dB 數值 (依垂直位置智慧放置於節點上方或下方，避免被邊緣裁切)
            val dbText = String.format(Locale.US, "%.1f", gains[i])
            val above = y > chartTop + dp(18f)
            val textY = if (above) y - dotRadius - dp(5f) else y + dotRadius + dp(14f)
            canvas.drawText(dbText, x, textY, dbValuePaint)

            // 頻率標籤 (如 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k)
            canvas.drawText(bandLabels[i], x, chartBottom + bottomPad - dp(4f), freqLabelPaint)
        }
    }

    private fun dbToY(db: Float): Float {
        return chartTop + (maxDb - db) / (maxDb - minDb) * (chartBottom - chartTop)
    }

    private fun yToDb(y: Float): Float {
        return maxDb - (y - chartTop) / (chartBottom - chartTop) * (maxDb - minDb)
    }

    // --- 觸控手勢處理 (Touch & Drag Interaction) ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || chartBottom <= chartTop) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 僅在圖表感應高度區域內響應觸控，避免誤觸邊界外之元件
                if (event.y < chartTop - dp(16f) || event.y > chartBottom + dp(16f)) {
                    return false
                }

                val band = findNearestBand(event.x)
                if (band >= 0) {
                    val now = System.currentTimeMillis()
                    // 雙擊節點 (450ms 內同一頻段點擊兩次) 快速歸零重設為 0.0 dB
                    if (band == lastTapBand && (now - lastTapTime) < 450L) {
                        gains[band] = 0f
                        listener?.onGainChanged(band, 0f)
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        invalidate()
                        draggingBand = -1
                        lastTapBand = -1
                        lastTapTime = 0L
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return true
                    }

                    lastTapBand = band
                    lastTapTime = now
                    draggingBand = band

                    // 請求父容器 (BottomSheet / ScrollView) 不要攔截手勢
                    parent?.requestDisallowInterceptTouchEvent(true)
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    lastHapticStep = gainToStep(gains[draggingBand])
                    updateDrag(event.y)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingBand >= 0) {
                    updateDrag(event.y)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingBand >= 0) {
                    draggingBand = -1
                    lastHapticStep = Int.MIN_VALUE
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findNearestBand(x: Float): Int {
        if (bandCount == 0) return -1
        val spacing = (chartRight - chartLeft) / (bandCount - 1)
        val touchZone = spacing * 0.7f

        var best = -1
        var bestDist = touchZone
        for (i in 0 until bandCount) {
            val dist = abs(x - bandX[i])
            if (dist < bestDist) {
                bestDist = dist
                best = i
            }
        }
        return best
    }

    private fun updateDrag(y: Float) {
        val rawDb = yToDb(y).coerceIn(minDb, maxDb)
        // 以 0.5 dB 為最小調整步進單位
        val rounded = (rawDb * 2f).roundToInt() / 2f
        if (gains[draggingBand] != rounded) {
            gains[draggingBand] = rounded
            listener?.onGainChanged(draggingBand, rounded)

            // 每改變 0.5 dB 觸發微震動刻度感
            val step = gainToStep(rounded)
            if (step != lastHapticStep) {
                lastHapticStep = step
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
                } else {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            invalidate()
        }
    }

    private fun gainToStep(db: Float): Int = (db * 2f).roundToInt()

    // --- Helpers ---
    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
