package com.digitalminds.elizibel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Teclado de piano. Las teclas que debes tocar se encienden: rojo = mano derecha, gris claro = mano izquierda.
 * Las que pulsas (pantalla o piano USB) muestran un punto blanco. Con "notas que caen" se ve encima
 * lo que viene, bajando hacia cada tecla.
 */
class KeyboardView(ctx: Context) : View(ctx) {
    var lo = 21
        private set
    var hi = 108
        private set
    var labels = true
    var falling = false
        set(v) { field = v; invalidate() }
    var tl: Timeline? = null
    var curT = 0f
        set(v) { field = v; invalidate() }
    var hand = 0
    var userPart = 0
    var onKey: ((Int, Boolean) -> Unit)? = null
    /** midi -> 1 derecha, 2 izquierda (lo que suena ahora en tu parte) */
    val lit = HashMap<Int, Int>()
    val pressed = HashSet<Int>()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val touching = HashMap<Int, Int>()

    fun setRange(a: Int, b: Int) {
        var l = a; var h = b
        while (Names.isBlack(l)) l--
        while (Names.isBlack(h)) h++
        lo = l.coerceIn(21, 108); hi = h.coerceIn(21, 108)
        invalidate()
    }

    private fun whiteCount(): Int { var n = 0; for (m in lo..hi) if (!Names.isBlack(m)) n++; return n }
    private fun keyTop(): Float = if (falling) height * 0.55f else 0f

    /** Posicion horizontal (izquierda, derecha) de una tecla. */
    private fun keyX(m: Int, out: FloatArray) {
        val ww = width.toFloat() / whiteCount()
        var wi = 0
        for (k in lo until m) if (!Names.isBlack(k)) wi++
        if (!Names.isBlack(m)) { out[0] = wi * ww; out[1] = (wi + 1) * ww }
        else { val bw = ww * 0.62f; out[0] = wi * ww - bw / 2; out[1] = wi * ww + bw / 2 }
    }

    private val kx = FloatArray(2)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val top = keyTop()
        c.drawColor(C.BG)
        if (falling) drawFalling(c, top)
        val ww = w / whiteCount()
        val kh = h - top
        // blancas
        var wi = 0
        for (m in lo..hi) {
            if (Names.isBlack(m)) continue
            r.set(wi * ww + 1f, top, (wi + 1) * ww - 1f, h)
            val l = lit[m]
            p.style = Paint.Style.FILL
            p.color = when (l) { 1 -> C.RED_HI; 2 -> C.ICON_BG; else -> 0xFFEDEDEF.toInt() }
            c.drawRoundRect(r, ww * 0.12f, ww * 0.12f, p)
            if (labels && m % 12 == 0) {
                p.color = if (l != null) C.BG else 0xFF8A8C93.toInt()
                p.textAlign = Paint.Align.CENTER; p.typeface = Fonts.medium; p.textSize = min(ww * 0.42f, context.dp(11f))
                c.drawText("Do" + (m / 12 - 1), (wi + 0.5f) * ww, h - context.dp(7f), p)
            }
            if (m in pressed) { p.color = if (l != null) C.TEXT else C.RED; c.drawCircle((wi + 0.5f) * ww, h - kh * 0.22f, min(ww * 0.28f, context.dp(7f)), p) }
            wi++
        }
        // negras
        for (m in lo..hi) {
            if (!Names.isBlack(m)) continue
            keyX(m, kx)
            r.set(kx[0], top, kx[1], top + kh * 0.62f)
            val l = lit[m]
            p.color = when (l) { 1 -> C.RED; 2 -> 0xFF8E8F95.toInt(); else -> 0xFF101012.toInt() }
            c.drawRoundRect(r, ww * 0.08f, ww * 0.08f, p)
            if (m in pressed) { p.color = C.TEXT; c.drawCircle((kx[0] + kx[1]) / 2, top + kh * 0.5f, min(ww * 0.22f, context.dp(6f)), p) }
        }
        p.color = C.RED; c.drawRect(0f, top, w, top + context.dp(2f), p)
    }

    private fun drawFalling(c: Canvas, top: Float) {
        val t = tl ?: return
        val span = 1920f * 1.5f   // cuanto tiempo hacia adelante se ve
        val pxPerTick = top / span
        val i0 = t.firstAt(curT - 1920 * 4)
        var i = i0
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
                val dim = (hand == 1 && e.staff == 1) || (hand == 2 && e.staff == 0)
                p.color = if (e.staff == 0) (if (dim) 0x66DC0000 else C.RED_HI) else (if (dim) 0x669A9CA4 else C.ICON_BG)
                r.set(kx[0] + 2f, max(0f, y0), kx[1] - 2f, min(top, y1))
                c.drawRoundRect(r, context.dp(4f), context.dp(4f), p)
            }
        }
        // lineas de octava (Do) como guia
        p.color = 0xFF151517.toInt()
        for (m in lo..hi) if (m % 12 == 0) { keyX(m, kx); c.drawRect(kx[0], 0f, kx[0] + 1f, top, p) }
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
        val idx = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val m = keyAt(e.getX(idx), e.getY(idx))
                if (m >= 0) { touching[e.getPointerId(idx)] = m; pressed.add(m); onKey?.invoke(m, true); invalidate() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val m = touching.remove(e.getPointerId(idx))
                if (m != null) { pressed.remove(m); onKey?.invoke(m, false); invalidate() }
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) { for (k in touching.values) onKey?.invoke(k, false); touching.clear(); pressed.clear() }
            }
        }
        return true
    }
}

/** Digitacion de la flauta dulce soprano barroca (la de plastico comun). Se indexa por la nota escrita (midi). */
object RecorderFingering {
    // agujeros: [pulgar, 1, 2, 3, 4, 5, 6 grande, 6 chico, 7 grande, 7 chico]; 0 abierto, 1 tapado, 2 medio pulgar (apenas abierto)
    private val map = HashMap<Int, IntArray>()
    private fun f(m: Int, s: String) { map[m] = IntArray(10) { when (s[it]) { 'x' -> 1; 'h' -> 2; else -> 0 } } }
    init {
        f(60, "xxxxxxxxxx") // Do4
        f(61, "xxxxxxxxx-") // Do#4
        f(62, "xxxxxxxx--") // Re4
        f(63, "xxxxxxx---") // Re#4
        f(64, "xxxxxx----") // Mi4
        f(65, "xxxxx-xxxx") // Fa4 (horquilla)
        f(66, "xxxx-xxx--") // Fa#4
        f(67, "xxxx------") // Sol4
        f(68, "xxx-xxx---") // Sol#4
        f(69, "xxx-------") // La4
        f(70, "xx-xx-----") // Sib4
        f(71, "xx--------") // Si4
        f(72, "x-x-------") // Do5
        f(73, "-xx-------") // Do#5
        f(74, "--x-------") // Re5
        f(75, "--xxxxxx--") // Re#5
        f(76, "hxxxxx----") // Mi5
        f(77, "hxxxx-xx--") // Fa5
        f(78, "hxxx-x----") // Fa#5
        f(79, "hxxx------") // Sol5
        f(80, "hxx-x-----") // Sol#5
        f(81, "hxx-------") // La5
        f(82, "hxxx-xx---") // Sib5
        f(83, "hxx-xx----") // Si5
        f(84, "hx--xx----") // Do6
        f(86, "hx-xx-xx--") // Re6
    }
    fun of(writtenMidi: Int): IntArray? = map[writtenMidi]
    const val LOW = 60
    const val HIGH = 86
}

/** Silueta de la flauta dulce de pie con sus agujeros: negro relleno = tapado, mitad = medio pulgar. */
class FluteView(ctx: Context) : View(ctx) {
    var midi = -1
        set(v) { field = v; invalidate() }
    var next = -1
        set(v) { field = v; invalidate() }
    var labelSp = 22f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val u = context.dp(1f)
        val bodyW = min(w * 0.32f, u * 46f)
        val cx = w * 0.38f
        val top = h * 0.04f; val bot = h * 0.97f
        // cuerpo
        p.style = Paint.Style.FILL
        p.color = 0xFF2A2B2F.toInt()
        r.set(cx - bodyW * 0.62f, top, cx + bodyW * 0.62f, top + (bot - top) * 0.2f); c.drawRoundRect(r, bodyW * 0.5f, bodyW * 0.5f, p)
        r.set(cx - bodyW / 2, top + (bot - top) * 0.12f, cx + bodyW / 2, bot - (bot - top) * 0.06f); c.drawRoundRect(r, bodyW * 0.3f, bodyW * 0.3f, p)
        r.set(cx - bodyW * 0.6f, bot - (bot - top) * 0.1f, cx + bodyW * 0.6f, bot); c.drawRoundRect(r, bodyW * 0.3f, bodyW * 0.3f, p)
        // ventana de la boquilla
        p.color = C.BG
        r.set(cx - bodyW * 0.28f, top + (bot - top) * 0.13f, cx + bodyW * 0.28f, top + (bot - top) * 0.165f); c.drawRect(r, p)
        val holes = if (midi >= 0) RecorderFingering.of(midi) else null
        val rr = bodyW * 0.22f
        val y0 = top + (bot - top) * 0.27f
        val step = (bot - top) * 0.082f
        // pulgar (atras): se dibuja al costado
        drawHole(c, cx - bodyW * 0.95f, y0 - step * 0.6f, rr * 0.9f, holes?.get(0) ?: -1, true)
        for (i in 1..5) drawHole(c, cx, y0 + (i - 1) * step + (if (i > 3) step * 0.35f else 0f), rr, holes?.get(i) ?: -1, false)
        // 6 y 7 dobles
        for (k in 0..1) {
            val y = y0 + (5 + k) * step + step * 0.35f
            drawHole(c, cx - rr * 0.55f, y, rr * 0.62f, holes?.get(6 + k * 2) ?: -1, false)
            drawHole(c, cx + rr * 0.75f, y, rr * 0.45f, holes?.get(7 + k * 2) ?: -1, false)
        }
        // guia de manos
        p.color = C.DIM; p.textSize = u * 10f; p.typeface = Fonts.medium; p.textAlign = Paint.Align.LEFT
        c.drawText("pulgar", cx - bodyW * 1.5f, y0 - step * 1.25f, p)
        c.drawText("izq.", cx + bodyW * 0.8f, y0 + step * 0.9f, p)
        c.drawText("der.", cx + bodyW * 0.8f, y0 + step * 4.6f, p)
        // nombre de la nota
        p.textAlign = Paint.Align.CENTER
        if (midi >= 0) {
            p.color = C.TEXT; p.typeface = Fonts.bold; p.textSize = u * labelSp
            c.drawText(Names.pitchClass(midi), w * 0.82f, h * 0.18f, p)
            p.color = C.MUTED; p.typeface = Fonts.medium; p.textSize = u * 11f
            c.drawText(if (holes == null) "fuera de rango" else Names.withOctave(midi), w * 0.82f, h * 0.18f + u * 16f, p)
        }
        if (next >= 0) {
            p.color = C.DIM; p.typeface = Fonts.medium; p.textSize = u * 11f
            c.drawText("luego", w * 0.82f, h * 0.36f, p)
            p.color = C.MUTED; p.typeface = Fonts.bold; p.textSize = u * 16f
            c.drawText(Names.pitchClass(next), w * 0.82f, h * 0.36f + u * 18f, p)
        }
    }

    private fun drawHole(c: Canvas, x: Float, y: Float, rad: Float, state: Int, thumb: Boolean) {
        p.style = Paint.Style.FILL
        p.color = C.BG
        c.drawCircle(x, y, rad, p)
        when (state) {
            1 -> { p.color = if (thumb) C.ICON_BG else C.TEXT; c.drawCircle(x, y, rad * 0.86f, p) }
            2 -> { p.color = C.ICON_BG; r.set(x - rad * 0.86f, y - rad * 0.86f, x + rad * 0.86f, y + rad * 0.86f); c.drawArc(r, 90f, 180f, true, p) }
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = context.dp(1.2f); p.color = C.MUTED
        c.drawCircle(x, y, rad, p)
        p.style = Paint.Style.FILL
    }
}
