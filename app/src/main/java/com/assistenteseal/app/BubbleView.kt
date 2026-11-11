package com.assistenteseal.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Bolinha que fica por cima dos outros apps. Cor = estado; anel pulsa com a voz. */
class BubbleView(ctx: Context) : View(ctx) {

    var state: Int = IDLE
        set(v) {
            field = v
            postInvalidate()
        }

    /** Nível de som 0..1 (microfone ou voz da assistente). */
    @Volatile
    var level: Float = 0f

    private var smooth = 0f
    private var phase = 0f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    private val ticker = object : Runnable {
        override fun run() {
            phase += 0.12f
            smooth += (level - smooth) * 0.35f
            invalidate()
            if (isAttachedToWindow) postDelayed(this, 33)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.64f
        val color = when (state) {
            CONNECTING -> 0xFFF2A93B.toInt()
            LISTENING -> 0xFF22C55E.toInt()
            SPEAKING -> 0xFF7C5CFF.toInt()
            ERROR -> 0xFFEF4444.toInt()
            else -> 0xFF1F6FEB.toInt()
        }
        val pulse = when (state) {
            LISTENING -> max(smooth, 0.15f + 0.1f * sin(phase))
            SPEAKING -> max(smooth, 0.3f + 0.2f * sin(phase * 1.5f))
            CONNECTING -> 0.3f + 0.3f * sin(phase * 2f)
            else -> 0f
        }
        if (pulse > 0f) {
            fill.color = color
            fill.alpha = 70
            canvas.drawCircle(cx, cy, r * (1f + 0.5f * pulse.coerceIn(0f, 1f)), fill)
        }
        fill.color = color
        fill.alpha = if (state == IDLE) 215 else 255
        canvas.drawCircle(cx, cy, r, fill)

        // microfone
        val w = r * 0.36f
        rect.set(cx - w / 2, cy - r * 0.48f, cx + w / 2, cy + r * 0.08f)
        canvas.drawRoundRect(rect, w / 2, w / 2, icon)
        iconStroke.strokeWidth = r * 0.09f
        rect.set(cx - r * 0.34f, cy - r * 0.3f, cx + r * 0.34f, cy + r * 0.3f)
        canvas.drawArc(rect, 0f, 180f, false, iconStroke)
        canvas.drawLine(cx, cy + r * 0.3f, cx, cy + r * 0.46f, iconStroke)
        canvas.drawLine(cx - r * 0.2f, cy + r * 0.48f, cx + r * 0.2f, cy + r * 0.48f, iconStroke)
    }

    companion object {
        const val IDLE = 0
        const val CONNECTING = 1
        const val LISTENING = 2
        const val SPEAKING = 3
        const val ERROR = 4
    }
}
