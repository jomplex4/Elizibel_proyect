package com.digitalminds.elizibel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Barra de progreso tocable. */
class SeekView(ctx: Context, private val onSeek: (Float) -> Unit) : View(ctx) {
    var value = 0f
        set(x) { field = x.coerceIn(0f, 1f); invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        val th = context.dp(4f)
        val pad = context.dp(8f)
        r.set(pad, h / 2 - th / 2, w - pad, h / 2 + th / 2)
        p.color = C.CARD2; c.drawRoundRect(r, th, th, p)
        val x = pad + (w - 2 * pad) * value
        r.set(pad, h / 2 - th / 2, x, h / 2 + th / 2)
        p.color = C.RED; c.drawRoundRect(r, th, th, p)
        p.color = C.TEXT; c.drawCircle(x, h / 2, context.dp(7f), p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(true)
                val pad = context.dp(8f)
                val f = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
                value = f
                onSeek(f)
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/** Lista con barra lateral rapida: se arrastra el control rojo para llegar al final en un instante. */
class FastScrollView(ctx: Context) : android.widget.FrameLayout(ctx) {
    val sv = android.widget.ScrollView(ctx)
    private val bar = Bar(ctx)

    init {
        sv.isVerticalScrollBarEnabled = false
        sv.overScrollMode = OVER_SCROLL_NEVER
        addView(sv, LayoutParams(MATCH, MATCH))
        addView(bar, LayoutParams(ctx.dpi(16f), MATCH, android.view.Gravity.END))
        sv.setOnScrollChangeListener { _, _, _, _, _ -> bar.invalidate() }
    }

    fun setContent(v: View) {
        sv.addView(v, LayoutParams(MATCH, WRAP))
        post { bar.invalidate() }
    }

    private inner class Bar(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private var dragging = false

        private fun contentH(): Int = if (sv.childCount > 0) sv.getChildAt(0).height else 0
        private fun maxScroll(): Int = max(0, contentH() - sv.height)
        private fun thumbH(): Float {
            val ch = contentH().toFloat()
            if (ch <= 0f) return 0f
            return max(context.dp(40f), height * sv.height / ch).coerceAtMost(height.toFloat())
        }

        override fun onDraw(c: Canvas) {
            if (maxScroll() <= 0) return
            val w = width.toFloat()
            val th = thumbH()
            val frac = sv.scrollY.toFloat() / maxScroll()
            val top = frac * (height - th)
            val cx = w - context.dp(6f)
            p.color = C.CARD2
            r.set(cx - context.dp(1f), 0f, cx + context.dp(1f), height.toFloat())
            c.drawRoundRect(r, context.dp(1f), context.dp(1f), p)
            p.color = if (dragging) C.ICON_BG else C.MUTED
            r.set(cx - context.dp(2.5f), top, cx + context.dp(2.5f), top + th)
            c.drawRoundRect(r, context.dp(2.5f), context.dp(2.5f), p)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (maxScroll() <= 0) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    dragging = true
                    parent.requestDisallowInterceptTouchEvent(true)
                    val th = thumbH()
                    val f = ((e.y - th / 2f) / max(1f, height - th)).coerceIn(0f, 1f)
                    sv.scrollTo(0, (f * maxScroll()).toInt())
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; invalidate() }
            }
            return true
        }
    }
}


/** Boton circular de salto (atras o adelante) con los segundos dentro. */
class SkipButton(ctx: Context, private val forward: Boolean) : View(ctx) {
    var seconds = 10
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val path = android.graphics.Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val s = min(w, h)
        val cx = w / 2; val cy = h / 2
        val rad = s * 0.34f
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.05f
        p.strokeCap = Paint.Cap.ROUND
        p.color = C.TEXT
        r.set(cx - rad, cy - rad, cx + rad, cy + rad)
        // arco abierto arriba, con punta de flecha en el extremo
        if (forward) c.drawArc(r, -60f, 300f, false, p) else c.drawArc(r, 300f, -300f, false, p)
        val ang = Math.toRadians(if (forward) -60.0 else -120.0)
        val ex = (cx + Math.cos(ang) * rad).toFloat(); val ey = (cy + Math.sin(ang) * rad).toFloat()
        val dir = if (forward) 1f else -1f
        p.style = Paint.Style.FILL
        path.reset()
        path.moveTo(ex + dir * s * 0.02f, ey - s * 0.17f)
        path.lineTo(ex + dir * s * 0.17f, ey + s * 0.02f)
        path.lineTo(ex - dir * s * 0.08f, ey + s * 0.07f)
        path.close()
        c.drawPath(path, p)
        p.textAlign = Paint.Align.CENTER
        val t = seconds.toString()
        p.typeface = Fonts.medium
        p.textSize = s * (if (t.length > 1) 0.24f else 0.28f)
        c.drawText(t, cx, cy + p.textSize * 0.36f, p)
    }
}


/** Boton principal de reproducir/pausar: circulo blanco con el simbolo en negro (estilo reproductor). */
class PlayButton(ctx: Context) : View(ctx) {
    var playing = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = android.graphics.Path()
    private val r = RectF()
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val s = min(w, h); val cx = w / 2; val cy = h / 2
        p.style = Paint.Style.FILL
        p.color = C.TEXT
        c.drawCircle(cx, cy, s / 2f, p)
        p.color = C.BG
        if (playing) {
            val bw = s * 0.09f; val bh = s * 0.30f
            r.set(cx - s * 0.15f, cy - bh, cx - s * 0.15f + bw, cy + bh); c.drawRoundRect(r, bw / 2, bw / 2, p)
            r.set(cx + s * 0.15f - bw, cy - bh, cx + s * 0.15f, cy + bh); c.drawRoundRect(r, bw / 2, bw / 2, p)
        } else {
            path.reset()
            path.moveTo(cx - s * 0.12f, cy - s * 0.2f)
            path.lineTo(cx + s * 0.22f, cy)
            path.lineTo(cx - s * 0.12f, cy + s * 0.2f)
            path.close()
            c.drawPath(path, p)
        }
    }
}

/** Icono de transporte delgado: repetir (bucle) o volver al inicio. */
class TransportIcon(ctx: Context, private val loopKind: Boolean) : View(ctx) {
    var on = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = android.graphics.Path()
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val s = min(w, h); val cx = w / 2; val cy = h / 2
        p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = s * 0.05f
        if (loopKind) {
            p.color = if (on) C.RED_HI else C.MUTED
            p.style = Paint.Style.STROKE
            val x0 = cx - s * 0.26f; val x1 = cx + s * 0.26f; val y0 = cy - s * 0.12f; val y1 = cy + s * 0.12f
            path.reset(); path.moveTo(x0, y1 - s * 0.02f); path.lineTo(x0, y0); path.lineTo(x1 - s * 0.06f, y0); c.drawPath(path, p)
            path.reset(); path.moveTo(x1, y0 + s * 0.02f); path.lineTo(x1, y1); path.lineTo(x0 + s * 0.06f, y1); c.drawPath(path, p)
            p.style = Paint.Style.FILL
            path.reset(); path.moveTo(x1 - s * 0.08f, y0 - s * 0.07f); path.lineTo(x1 + s * 0.02f, y0); path.lineTo(x1 - s * 0.08f, y0 + s * 0.07f); path.close(); c.drawPath(path, p)
            path.reset(); path.moveTo(x0 + s * 0.08f, y1 - s * 0.07f); path.lineTo(x0 - s * 0.02f, y1); path.lineTo(x0 + s * 0.08f, y1 + s * 0.07f); path.close(); c.drawPath(path, p)
            if (on) c.drawCircle(cx, cy + s * 0.32f, s * 0.035f, p)
        } else {
            p.color = C.TEXT
            p.style = Paint.Style.FILL
            c.drawRoundRect(RectF(cx - s * 0.2f, cy - s * 0.18f, cx - s * 0.15f, cy + s * 0.18f), s * 0.02f, s * 0.02f, p)
            path.reset(); path.moveTo(cx + s * 0.2f, cy - s * 0.18f); path.lineTo(cx - s * 0.1f, cy); path.lineTo(cx + s * 0.2f, cy + s * 0.18f); path.close()
            p.style = Paint.Style.STROKE; c.drawPath(path, p)
        }
    }
}

/** Circulo pequeno con "?" que muestra u oculta la explicacion de una funcion. */
class HelpDot(ctx: Context) : View(ctx) {
    var open = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat(); val cx = width / 2f; val cy = height / 2f
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.07f
        p.color = if (open) C.RED_HI else C.MUTED
        c.drawCircle(cx, cy, s * 0.42f, p)
        p.style = Paint.Style.FILL
        p.typeface = Fonts.bold; p.textAlign = Paint.Align.CENTER; p.textSize = s * 0.56f
        c.drawText("?", cx, cy + s * 0.2f, p)
    }
}
