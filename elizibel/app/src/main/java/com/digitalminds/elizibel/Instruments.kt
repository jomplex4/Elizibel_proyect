package com.digitalminds.elizibel

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Teclado de piano. Lo que debes tocar se enciende: ROJO = mano derecha, ROSADO = mano izquierda.
 * Lo que pulsas (pantalla o piano USB) muestra un punto. Con "notas que caen" se ve encima lo que viene:
 * cada barra tiene el ancho exacto de su tecla y unas lineas tenues separan las teclas.
 */
class KeyboardView(ctx: Context) : View(ctx) {
    var lo = 21
        private set
    var hi = 108
        private set
    /** Nombres en las teclas: 0 ninguno, 1 solo Do, 2 todas las blancas. */
    var names = 1
    var falling = false
        set(v) { field = v; invalidate() }
    var tl: Timeline? = null
    var curT = 0f
        set(v) { field = v; invalidate() }
    var hand = 0
    var userPart = 0
    var onKey: ((Int, Boolean) -> Unit)? = null
    /** Arrastre vertical sobre las notas que caen: abajo adelanta, arriba retrocede (en ticks). */
    var onDragStart: (() -> Unit)? = null
    var onDrag: ((Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    private var dragY = -1f
    private var dragMoved = false
    /** midi -> 1 derecha, 2 izquierda (lo que te toca tocar ahora) */
    val lit = HashMap<Int, Int>()
    val pressed = HashSet<Int>()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val touching = HashMap<Int, Int>()
    private val kx = FloatArray(2)
    private var whites = 52
    /** Zoom con dos dedos (explorador): 1 = todo el rango visible. offset en pixeles. */
    var zoomable = false
    var zoom = 1f
        private set
    var offset = 0f
        set(v) { field = v.coerceIn(0f, max(0f, width * zoom - width)); invalidate(); onView?.invoke() }
    var onView: (() -> Unit)? = null
    private val scaler = android.view.ScaleGestureDetector(ctx, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
            val nz = (zoom * d.scaleFactor).coerceIn(1f, 4f)
            val fx = d.focusX
            val newOff = (offset + fx) * (nz / zoom) - fx
            zoom = nz
            offset = newOff
            return true
        }
    })
    private val solf = arrayOf("Do", "", "Re", "", "Mi", "Fa", "", "Sol", "", "La", "", "Si")

    fun setRange(a: Int, b: Int) {
        var l = a; var h = b
        while (Names.isBlack(l)) l--
        while (Names.isBlack(h)) h++
        lo = l.coerceIn(21, 108); hi = h.coerceIn(lo, 108)
        whites = 0; for (m in lo..hi) if (!Names.isBlack(m)) whites++
        invalidate()
    }

    private fun keyTop(): Float = if (falling) height * 0.6f else 0f

    private fun keyX(m: Int, out: FloatArray) {
        val ww = width.toFloat() * zoom / max(1, whites)
        var wi = 0
        for (k in lo until m) if (!Names.isBlack(k)) wi++
        if (!Names.isBlack(m)) { out[0] = wi * ww - offset; out[1] = (wi + 1) * ww - offset }
        else { val bw = ww * 0.6f; out[0] = wi * ww - bw / 2 - offset; out[1] = wi * ww + bw / 2 - offset }
    }

    private fun handColor(h: Int): Int = if (h == 1) C.RED_HI else C.PINK

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val top = keyTop()
        c.drawColor(C.BG)
        if (falling) drawFalling(c, top)
        val ww = w * zoom / max(1, whites)
        val kh = h - top
        p.shader = null
        p.style = Paint.Style.FILL
        var wi = 0
        for (m in lo..hi) {
            if (Names.isBlack(m)) continue
            val x0 = wi * ww - offset
            if (x0 > w || x0 + ww < 0) { wi++; continue }
            r.set(x0 + 1f, top, x0 + ww - 1f, h)
            val l = lit[m]
            p.color = if (l != null) handColor(l) else 0xFFEDEDEF.toInt()
            c.drawRoundRect(r, ww * 0.12f, ww * 0.12f, p)
            val label = when { names == 2 -> solf[m % 12]; names == 1 && m % 12 == 0 -> "Do" + (m / 12 - 1); else -> "" }
            if (label.isNotEmpty()) {
                p.color = if (l != null) C.TEXT else 0xFF7C7E86.toInt()
                p.textAlign = Paint.Align.CENTER; p.typeface = if (l != null) Fonts.bold else Fonts.medium
                p.textSize = min(ww * (if (names == 2) 0.34f else 0.3f), context.dp(11f))
                c.drawText(label, x0 + 0.5f * ww, h - context.dp(6f), p)
            }
            if (m in pressed) { p.color = if (l != null) C.TEXT else C.RED; c.drawCircle(x0 + 0.5f * ww, h - kh * 0.3f, min(ww * 0.26f, context.dp(7f)), p) }
            wi++
        }
        for (m in lo..hi) {
            if (!Names.isBlack(m)) continue
            keyX(m, kx)
            r.set(kx[0], top, kx[1], top + kh * 0.62f)
            val l = lit[m]
            p.color = if (l != null) (if (l == 1) C.RED else C.PINK_DEEP) else 0xFF101012.toInt()
            c.drawRoundRect(r, ww * 0.08f, ww * 0.08f, p)
            if (m in pressed) { p.color = C.TEXT; c.drawCircle((kx[0] + kx[1]) / 2, top + kh * 0.48f, min(ww * 0.2f, context.dp(6f)), p) }
        }
        p.color = C.RED; c.drawRect(0f, top, w, top + context.dp(2f), p)
    }

    private fun mine(staff: Int): Boolean = hand == 0 || (hand == 1 && staff == 0) || (hand == 2 && staff == 1)

    private fun drawFalling(c: Canvas, top: Float) {
        val t = tl ?: return
        p.style = Paint.Style.FILL
        p.shader = null
        // lineas guia tenues entre teclas blancas (mas visibles en cada Do)
        for (m in lo..hi) {
            if (Names.isBlack(m)) continue
            keyX(m, kx)
            p.color = if (m % 12 == 0) 0xFF2E2F35.toInt() else 0xFF17181B.toInt()
            c.drawRect(kx[0], 0f, kx[0] + 1f, top, p)
        }
        val span = 1920f * 1.5f
        val pxPerTick = top / span
        var i = t.firstAt(curT - 1920 * 4)
        val rad = context.dp(4f)
        while (i < t.events.size && t.events[i].t <= curT + span) {
            val pe = t.events[i]; i++
            val e = pe.e
            if (e.rest || e.part != userPart) continue
            for (n in e.notes) {
                if (n.tieIn) continue
                val m = n.midi
                if (m < lo || m > hi) continue
                val y1 = top - (pe.t - curT) * pxPerTick
                val y0 = top - (pe.t + e.sound - curT) * pxPerTick
                if (y1 < 0 || y0 > top) continue
                keyX(m, kx)
                val col = if (e.staff == 0) C.RED_HI else C.PINK
                r.set(kx[0] + 1.5f, max(0f, y0), kx[1] - 1.5f, min(top, y1))
                p.shader = LinearGradient(0f, r.top, 0f, r.bottom, lighten(col), col, Shader.TileMode.CLAMP)
                p.alpha = if (mine(e.staff)) 255 else 80
                c.drawRoundRect(r, rad, rad, p)
                p.shader = null; p.alpha = 255
            }
        }
    }

    private fun lighten(c: Int): Int {
        val rr = min(255, ((c shr 16) and 0xFF) + 60); val g = min(255, ((c shr 8) and 0xFF) + 60); val b = min(255, (c and 0xFF) + 60)
        return (0xFF shl 24) or (rr shl 16) or (g shl 8) or b
    }

    private fun keyAt(x: Float, y: Float): Int {
        val top = keyTop()
        if (y < top) return -1
        val kh = height - top
        if (y < top + kh * 0.62f) for (m in lo..hi) if (Names.isBlack(m)) { keyX(m, kx); if (x in kx[0]..kx[1]) return m }
        for (m in lo..hi) if (!Names.isBlack(m)) { keyX(m, kx); if (x in kx[0]..kx[1]) return m }
        return -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (zoomable) {
            scaler.onTouchEvent(e)
            if (e.pointerCount >= 2 || scaler.isInProgress) {
                // dos dedos = acercar o alejar: se sueltan las teclas tocadas
                for (k in touching.values) { pressed.remove(k); onKey?.invoke(k, false) }
                touching.clear(); invalidate()
                if (e.actionMasked != MotionEvent.ACTION_POINTER_DOWN || e.pointerCount > 1) return true
            }
        }
        val idx = e.actionIndex
        val top = keyTop()
        if (falling && (dragY >= 0f || (e.actionMasked == MotionEvent.ACTION_DOWN && e.y < top))) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dragY = e.y; dragMoved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.y - dragY
                    if (!dragMoved && kotlin.math.abs(dy) > context.dp(10f)) { dragMoved = true; parent.requestDisallowInterceptTouchEvent(true); onDragStart?.invoke() }
                    if (dragMoved) { onDrag?.invoke(dy / (top / (1920f * 1.5f))); dragY = e.y }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (dragMoved) onDragEnd?.invoke(); dragY = -1f; dragMoved = false }
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val m = keyAt(e.getX(idx), e.getY(idx))
                if (m >= 0) { touching[e.getPointerId(idx)] = m; pressed.add(m); onKey?.invoke(m, true); invalidate() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val m = touching.remove(e.getPointerId(idx))
                if (m != null) { pressed.remove(m); onKey?.invoke(m, false); invalidate() }
            }
            MotionEvent.ACTION_CANCEL -> { for (k in touching.values) { pressed.remove(k); onKey?.invoke(k, false) }; touching.clear(); invalidate() }
        }
        return true
    }
}

/** Digitacion de la flauta dulce soprano barroca (la de plastico comun). Se indexa por la nota escrita (midi). */
object RecorderFingering {
    // agujeros: [pulgar, 1, 2, 3, 4, 5, 6 grande, 6 chico, 7 grande, 7 chico]; 0 abierto, 1 tapado, 2 medio pulgar
    private val map = HashMap<Int, IntArray>()
    private fun f(m: Int, s: String) { map[m] = IntArray(10) { when (s[it]) { 'x' -> 1; 'h' -> 2; else -> 0 } } }
    init {
        f(60, "xxxxxxxxxx"); f(61, "xxxxxxxxx-"); f(62, "xxxxxxxx--"); f(63, "xxxxxxx---"); f(64, "xxxxxx----")
        f(65, "xxxxx-xxxx"); f(66, "xxxx-xxx--"); f(67, "xxxx------"); f(68, "xxx-xxx---"); f(69, "xxx-------")
        f(70, "xx-xx-----"); f(71, "xx--------"); f(72, "x-x-------"); f(73, "-xx-------"); f(74, "--x-------")
        f(75, "--xxxxxx--"); f(76, "hxxxxx----"); f(77, "hxxxx-xx--"); f(78, "hxxx-x----"); f(79, "hxxx------")
        f(80, "hxx-x-----"); f(81, "hxx-------"); f(82, "hxxx-xx---"); f(83, "hxx-xx----"); f(84, "hx--xx----")
        f(86, "hx-xx-xx--")
    }
    fun of(writtenMidi: Int): IntArray? = map[writtenMidi]
    const val LOW = 60
    const val HIGH = 86
}

/**
 * Silueta de la flauta dulce de pie, en blanco grisaceo. Agujero tapado = rojo.
 * Al costado de cada agujero va el dedo: mano izquierda arriba (pulgar, indice, medio, anular),
 * mano derecha abajo (indice, medio, anular, meñique).
 */
class FluteView(ctx: Context) : View(ctx) {
    var midi = -1
        set(v) { if (field != v) { field = v; invalidate() } }
    var next = -1
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Sin nombre grande de la nota (el explorador lo muestra aparte). */
    var compact = false
    /** Version chica para la tabla: solo flauta y nombre. */
    var mini = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val fingers = arrayOf("pulgar", "índice", "medio", "anular", "índice", "medio", "anular", "meñique")

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val u = context.dp(1f)
        val showName = !compact && !mini
        val nameW = if (showName) w * 0.32f else 0f
        val labels = !mini
        val area = w - nameW
        val top = h * (if (mini) 0.16f else 0.03f); val bot = h * 0.98f
        val len = bot - top
        val bodyW = min(area * (if (labels) 0.2f else 0.34f), len * 0.13f)
        val cx = if (labels) nameW + area * 0.5f else w / 2
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = C.FLUTE
        r.set(cx - bodyW * 0.6f, top, cx + bodyW * 0.6f, top + len * 0.17f); c.drawRoundRect(r, bodyW * 0.45f, bodyW * 0.45f, p)
        r.set(cx - bodyW / 2, top + len * 0.1f, cx + bodyW / 2, bot - len * 0.05f); c.drawRoundRect(r, bodyW * 0.25f, bodyW * 0.25f, p)
        r.set(cx - bodyW * 0.58f, bot - len * 0.09f, cx + bodyW * 0.58f, bot); c.drawRoundRect(r, bodyW * 0.3f, bodyW * 0.3f, p)
        p.color = 0xFFB4B6BB.toInt()
        c.drawRect(cx - bodyW * 0.5f, top + len * 0.165f, cx + bodyW * 0.5f, top + len * 0.175f, p)
        c.drawRect(cx - bodyW * 0.5f, bot - len * 0.095f, cx + bodyW * 0.5f, bot - len * 0.085f, p)
        p.color = 0xFF3A3B40.toInt()
        c.drawRect(cx - bodyW * 0.26f, top + len * 0.11f, cx + bodyW * 0.26f, top + len * 0.14f, p)
        val holes = if (midi >= 0) RecorderFingering.of(midi) else null
        val rr = bodyW * 0.24f
        val y0 = top + len * 0.25f
        val step = len * 0.083f
        val ys = FloatArray(8)
        ys[0] = y0 - step * 0.55f
        for (i in 1..3) ys[i] = y0 + (i - 1) * step
        for (i in 4..7) ys[i] = y0 + (i - 1) * step + step * 0.45f
        drawHole(c, cx - bodyW * 0.95f, ys[0], rr * 0.85f, holes?.get(0) ?: -1)
        for (i in 1..5) drawHole(c, cx, ys[i], rr, holes?.get(i) ?: -1)
        for (k in 0..1) {
            drawHole(c, cx - rr * 0.5f, ys[6 + k], rr * 0.62f, holes?.get(6 + k * 2) ?: -1)
            drawHole(c, cx + rr * 0.78f, ys[6 + k], rr * 0.45f, holes?.get(7 + k * 2) ?: -1)
        }
        if (labels) {
            p.typeface = Fonts.medium; p.textSize = min(u * 10.5f, step * 0.42f)
            val idx = intArrayOf(0, 1, 2, 3, 4, 5, 6, 8)
            for (i in 0..7) {
                val closed = (holes?.get(idx[i]) ?: 0) != 0
                p.color = if (closed) C.TEXT else C.DIM
                if (i == 0) { p.textAlign = Paint.Align.RIGHT; c.drawText(fingers[0], cx - bodyW * 0.95f - rr - u * 3f, ys[0] + p.textSize * 0.35f, p) }
                else { p.textAlign = Paint.Align.LEFT; c.drawText(fingers[i], cx + bodyW * 0.75f + (if (i >= 6) rr * 0.5f else 0f), ys[i] + p.textSize * 0.35f, p) }
            }
            p.textAlign = Paint.Align.RIGHT; p.typeface = Fonts.bold; p.textSize = min(u * 10f, step * 0.4f)
            p.color = C.RED_HI
            val bx = cx - bodyW * 0.75f
            c.drawText("IZQ.", bx, (ys[1] + ys[3]) / 2 + p.textSize * 0.4f, p)
            c.drawText("DER.", bx, (ys[4] + ys[7]) / 2 + p.textSize * 0.4f, p)
        }
        p.textAlign = Paint.Align.CENTER
        if (mini && midi >= 0) {
            p.color = C.TEXT; p.typeface = Fonts.bold; p.textSize = u * 14f
            c.drawText(Names.withOctave(midi), w / 2, h * 0.1f, p)
        }
        if (showName && midi >= 0) {
            val nx = nameW * 0.5f
            p.color = C.TEXT; p.typeface = Fonts.bold; p.textSize = u * 30f
            c.drawText(Names.pitchClass(midi), nx, h * 0.2f, p)
            p.color = C.MUTED; p.typeface = Fonts.medium; p.textSize = u * 11f
            c.drawText(if (holes == null) "fuera de rango" else Names.withOctave(midi), nx, h * 0.2f + u * 16f, p)
        }
        if (showName && next >= 0) {
            val nx = nameW * 0.5f
            p.color = C.DIM; p.typeface = Fonts.title; p.textSize = u * 9f
            c.drawText("LUEGO", nx, h * 0.42f, p)
            p.color = C.MUTED; p.typeface = Fonts.bold; p.textSize = u * 18f
            c.drawText(Names.pitchClass(next), nx, h * 0.42f + u * 20f, p)
        }
    }

    private fun drawHole(c: Canvas, x: Float, y: Float, rad: Float, state: Int) {
        p.style = Paint.Style.FILL
        p.color = 0xFF2B2C31.toInt()
        c.drawCircle(x, y, rad, p)
        when (state) {
            1 -> { p.color = C.RED_HI; c.drawCircle(x, y, rad * 0.92f, p) }
            2 -> { p.color = C.RED_HI; r.set(x - rad * 0.92f, y - rad * 0.92f, x + rad * 0.92f, y + rad * 0.92f); c.drawArc(r, 90f, 180f, true, p) }
        }
    }
}


/** Barra gris para recorrer el teclado cuando esta ampliado (solo aparece con zoom). */
class KeyScrollBar(ctx: Context, private val keys: KeyboardView) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var grab = 0f
    override fun onDraw(c: Canvas) {
        if (keys.zoom <= 1.01f) return
        val w = width.toFloat(); val h = height.toFloat()
        val total = keys.width * keys.zoom
        val tw = w * (keys.width / total); val tx = w * (keys.offset / total)
        p.color = C.CARD2; r.set(0f, h * 0.35f, w, h * 0.65f); c.drawRoundRect(r, h, h, p)
        p.color = C.MUTED; r.set(tx, h * 0.2f, tx + tw, h * 0.8f); c.drawRoundRect(r, h, h, p)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (keys.zoom <= 1.01f) return false
        val total = keys.width * keys.zoom
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { grab = e.x; parent.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> { keys.offset += (e.x - grab) * total / width; grab = e.x }
        }
        invalidate()
        return true
    }
}
