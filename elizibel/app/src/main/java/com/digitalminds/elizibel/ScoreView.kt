package com.digitalminds.elizibel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Partitura horizontal que avanza: la nota que cruza la linea roja es la que suena ahora.
 * Piano: pentagrama doble (clave de sol arriba = mano derecha, clave de fa abajo = mano izquierda).
 * Flauta: un pentagrama en clave de sol (se escribe una octava abajo de lo que suena).
 * Dibujo con la fuente Bravura (SMuFL): el tamaño de fuente es 4 espacios.
 */
class ScoreView(ctx: Context) : View(ctx) {
    var tl: Timeline? = null
        set(v) { field = v; prepare(); invalidate() }
    var curT = 0f
        set(v) { field = v; invalidate() }
    var showNames = false
    var zoom = 1f
    /** 0 = ambas manos, 1 = derecha, 2 = izquierda. La otra se dibuja tenue. */
    var hand = 0
    /** Parte que practicas (0 o 1). */
    var userPart = 0
        set(v) { field = v; prepare(); invalidate() }
    var onDragStart: (() -> Unit)? = null
    var onDrag: ((Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    var onTap: (() -> Unit)? = null

    private var staves = 2
    private var sp = 10f
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glyphP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.music }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val glyphStrings = HashMap<Int, String>()
    private var userEv: List<PEv> = emptyList()
    private var beamOf = HashMap<PEv, Int>()
    private var groups = HashMap<Int, ArrayList<PEv>>()
    private var tieTarget = HashMap<Long, PEv>()
    private var autoBeam = false

    private fun gs(code: Int): String = glyphStrings.getOrPut(code) { String(Character.toChars(code)) }

    private fun prepare() {
        val t = tl ?: return
        staves = t.song.staves
        userEv = t.events.filter { it.e.part == userPart }
        autoBeam = userEv.none { it.e.beam != 0 }
        beamOf.clear(); groups.clear()
        var gid = 1
        if (autoBeam) {
            // agrupar corcheas y menores dentro del mismo pulso
            val last = HashMap<Int, PEv>()
            for (pe in userEv) {
                val e = pe.e
                if (e.rest || e.base >= 480) { last.remove(e.staff); continue }
                val prev = last[e.staff]
                val m = t.measures[t.pmAt(pe.t.toFloat())]
                val beat = if (m.m.den == 8) 720 else 480
                val sameBeat = prev != null && (prev.t - m.t) / beat == (pe.t - m.t) / beat && prev.t + prev.e.dur == pe.t && prev.t >= m.t
                val g = if (sameBeat) beamOf[prev!!]!! else gid++
                beamOf[pe] = g
                last[e.staff] = pe
            }
        } else {
            for (pe in userEv) if (pe.e.beam != 0 && !pe.e.rest) {
                // el mismo haz puede repetirse en otra vuelta: se separa por tiempo de reproduccion
                val key = pe.e.beam * 1000 + t.pmAt(pe.t.toFloat()) % 1000
                beamOf[pe] = key
            }
        }
        for ((pe, g) in beamOf) groups.getOrPut(g) { ArrayList() }.add(pe)
        for (l in groups.values) l.sortBy { it.t }
        // grupos de una sola nota no llevan haz
        val single = groups.filter { it.value.size < 2 }.keys
        for (k in single) { for (pe in groups[k]!!) beamOf.remove(pe); groups.remove(k) }
        tieTarget.clear()
        val open = HashMap<Long, PEv>()
        for (pe in userEv) for (n in pe.e.notes) {
            val k = (pe.e.staff.toLong() shl 8) or n.midi.toLong()
            if (n.tieIn) { open[k]?.let { tieTarget[key(it, n.midi)] = pe } }
            if (n.tieOut) open[k] = pe else open.remove(k)
        }
    }

    private fun key(pe: PEv, midi: Int): Long = (System.identityHashCode(pe).toLong() shl 8) or midi.toLong()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        sp = h / (if (staves == 2) 27f else 16f)
    }

    private fun topOf(staff: Int): Float = if (staff == 0) 6f * sp else 17.5f * sp
    private fun bottomLine(staff: Int): Float = topOf(staff) + 4 * sp
    private fun baseDia(staff: Int): Int = if (staves == 2 && staff == 1) 18 else 30
    private fun yOf(staff: Int, dia: Int): Float = bottomLine(staff) - (dia - baseDia(staff)) * sp / 2f
    private val ppt get() = sp * 6.2f * zoom / 480f
    private fun panelW(): Float = sp * 10.5f
    private fun nowX(): Float = max(panelW() + sp * 5f, width * 0.24f)
    private fun xOf(t: Int): Float = nowX() + (t - curT) * ppt

    private fun glyph(c: Canvas, code: Int, x: Float, y: Float, color: Int, scale: Float = 1f) {
        glyphP.color = color
        glyphP.textSize = 4f * sp * scale
        c.drawText(gs(code), x, y, glyphP)
    }

    private fun colorOf(pe: PEv): Int {
        val dim = (hand == 1 && pe.e.staff == 1) || (hand == 2 && pe.e.staff == 0)
        return when {
            curT >= pe.t + max(pe.e.sound, 60) -> if (dim) C.DIM else C.MUTED
            curT >= pe.t - 1 -> C.RED_HI
            dim -> C.DIM
            else -> C.TEXT
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(C.BG)
        val t = tl ?: return
        val w = width.toFloat(); val h = height.toFloat()
        lineP.strokeWidth = sp * 0.1f
        lineP.color = C.MUTED
        for (s in 0 until staves) for (k in 0 until 5) c.drawLine(0f, topOf(s) + k * sp, w, topOf(s) + k * sp, lineP)
        val pw = panelW()
        c.save()
        c.clipRect(pw, 0f, w, h)
        val tMin = curT - (nowX() - pw) / ppt - 1920
        val tMax = curT + (w - nowX()) / ppt + 480
        // compases
        val pm0 = t.pmAt(max(0f, tMin))
        var i = pm0
        textP.typeface = Fonts.medium
        while (i < t.measures.size && t.measures[i].t <= tMax) {
            val pm = t.measures[i]
            val x = xOf(pm.t) - sp * 1.2f
            lineP.color = C.MUTED; lineP.strokeWidth = sp * 0.12f
            c.drawLine(x, topOf(0), x, bottomLine(staves - 1), lineP)
            textP.color = C.DIM; textP.textSize = sp * 1.15f
            c.drawText((pm.index + 1).toString(), x + sp * 0.6f, topOf(0) - sp * 1.6f, textP)
            i++
        }
        // eventos visibles
        val a = firstUser(tMin)
        var k = a
        val drawnBeams = HashSet<Int>()
        while (k < userEv.size && userEv[k].t <= tMax) {
            val pe = userEv[k]
            drawEvent(c, pe)
            val g = beamOf[pe]
            if (g != null && drawnBeams.add(g)) drawBeam(c, groups[g]!!)
            k++
        }
        c.restore()
        // panel fijo: claves, armadura y compas
        fillP.color = C.BG
        c.drawRect(0f, 0f, pw, h, fillP)
        lineP.color = C.MUTED; lineP.strokeWidth = sp * 0.1f
        for (s in 0 until staves) for (q in 0 until 5) c.drawLine(sp * 0.3f, topOf(s) + q * sp, pw, topOf(s) + q * sp, lineP)
        if (staves == 2) {
            lineP.strokeWidth = sp * 0.22f
            c.drawLine(sp * 0.3f, topOf(0), sp * 0.3f, bottomLine(1), lineP)
        }
        val pm = t.measures[t.pmAt(max(0f, curT))]
        for (s in 0 until staves) {
            if (s == 0) glyph(c, 0xE050, sp * 0.9f, topOf(0) + 3 * sp, C.TEXT) else glyph(c, 0xE062, sp * 0.9f, topOf(1) + sp, C.TEXT)
            var kx = sp * 3.9f
            val key = pm.m.key
            val bass = s == 1 && staves == 2
            if (key > 0) {
                val order = if (bass) intArrayOf(6, 3, 7, 4, 1, 5, 2) else intArrayOf(8, 5, 9, 6, 3, 7, 4)
                for (q in 0 until min(key, 7)) { glyph(c, 0xE262, kx, bottomLine(s) - order[q] * sp / 2f, C.TEXT); kx += sp * 0.95f }
            } else if (key < 0) {
                val order = if (bass) intArrayOf(2, 5, 1, 4, 0, 3, 6) else intArrayOf(4, 7, 3, 6, 2, 5, 1)
                for (q in 0 until min(-key, 7)) { glyph(c, 0xE260, kx, bottomLine(s) - order[q] * sp / 2f, C.TEXT); kx += sp * 0.85f }
            }
            val tx = max(kx + sp * 0.3f, sp * 6.4f)
            drawTimeDigits(c, pm.m.num, tx, topOf(s) + sp)
            drawTimeDigits(c, pm.m.den, tx, topOf(s) + 3 * sp)
        }
        // linea de ahora
        val nx = nowX()
        fillP.color = C.RED
        c.drawRect(nx - sp * 0.12f, topOf(0) - sp * 2.5f, nx + sp * 0.12f, bottomLine(staves - 1) + sp * 2.5f, fillP)
        path.reset(); path.moveTo(nx - sp * 0.7f, topOf(0) - sp * 3.2f); path.lineTo(nx + sp * 0.7f, topOf(0) - sp * 3.2f); path.lineTo(nx, topOf(0) - sp * 2.3f); path.close()
        c.drawPath(path, fillP)
    }

    private fun drawTimeDigits(c: Canvas, v: Int, x: Float, y: Float) {
        val s = v.toString()
        var xx = x
        for (ch in s) { glyph(c, 0xE080 + (ch - '0'), xx, y, C.TEXT); xx += sp * 1.6f }
    }

    private fun firstUser(x: Float): Int {
        var lo = 0; var hi = userEv.size
        while (lo < hi) { val mid = (lo + hi) / 2; if (userEv[mid].t < x) lo = mid + 1 else hi = mid }
        return lo
    }

    private fun stemUp(pe: PEv): Boolean {
        val e = pe.e
        if (e.stem == 1) return true
        if (e.stem == 2) return false
        val g = beamOf[pe]
        val list = if (g != null) groups[g]!! else listOf(pe)
        var sum = 0f; var n = 0
        for (q in list) for (nn in q.e.notes) { sum += nn.dia - baseDia(q.e.staff); n++ }
        return n == 0 || sum / n < 4f
    }

    private fun headCode(base: Int): Int = when { base >= 1920 -> 0xE0A2; base >= 960 -> 0xE0A3; else -> 0xE0A4 }

    private fun drawEvent(c: Canvas, pe: PEv) {
        val e = pe.e
        val x = xOf(pe.t)
        val col = colorOf(pe)
        val st = e.staff.coerceIn(0, staves - 1)
        if (e.rest) {
            val code = when { e.base >= 1920 -> 0xE4E3; e.base >= 960 -> 0xE4E4; e.base >= 480 -> 0xE4E5; e.base >= 240 -> 0xE4E6; e.base >= 120 -> 0xE4E7; else -> 0xE4E8 }
            val y = if (code == 0xE4E3) topOf(st) + sp else topOf(st) + 2 * sp
            glyph(c, code, x, y, if (col == C.RED_HI) C.RED_HI else C.DIM)
            for (d in 0 until e.dots) glyph(c, 0xE1E7, x + sp * (1.6f + d * 0.6f), topOf(st) + 1.5f * sp, C.DIM)
            return
        }
        val hw = sp * 1.18f
        val up = stemUp(pe)
        val code = headCode(e.base)
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        val sorted = e.notes.sortedBy { it.dia }
        var prevDia = -99
        var shiftNext = false
        for (n in sorted) {
            val y = yOf(st, n.dia)
            minY = min(minY, y); maxY = max(maxY, y)
            // segundas en un acorde: la cabeza se pasa al otro lado de la plica
            val second = n.dia - prevDia == 1 && !shiftNext
            shiftNext = second
            prevDia = n.dia
            val hx = if (second) (if (up) x + hw - sp * 0.05f else x - hw + sp * 0.05f) else x
            // lineas adicionales
            val pos = n.dia - baseDia(st)
            lineP.color = C.MUTED; lineP.strokeWidth = sp * 0.12f
            if (pos <= -2) { var p = -2; while (p >= pos) { val yy = bottomLine(st) - p * sp / 2f; c.drawLine(hx - sp * 0.4f, yy, hx + hw + sp * 0.4f, yy, lineP); p -= 2 } }
            if (pos >= 10) { var p = 10; while (p <= pos) { val yy = bottomLine(st) - p * sp / 2f; c.drawLine(hx - sp * 0.4f, yy, hx + hw + sp * 0.4f, yy, lineP); p += 2 } }
            glyph(c, code, hx, y, col)
            if (n.shown != 9) {
                val ac = when (n.shown) { -1 -> 0xE260; 0 -> 0xE261; 1 -> 0xE262; 2 -> 0xE263; -2 -> 0xE264; else -> 0xE261 }
                glyph(c, ac, x - sp * (if (n.shown == -2) 2.2f else 1.35f), y, col)
            }
            for (d in 0 until e.dots) {
                val dy = if (pos % 2 == 0) -sp / 2f else 0f
                glyph(c, 0xE1E7, x + hw + sp * (0.35f + d * 0.55f), y + dy, col)
            }
            if (n.tieOut) {
                val nxt = tieTarget[key(pe, n.midi)]
                val x2 = if (nxt != null) xOf(nxt.t) else x + e.dur * ppt
                lineP.color = col; lineP.strokeWidth = sp * 0.16f
                val yy = y + (if (up) sp * 0.7f else -sp * 0.7f)
                path.reset(); path.moveTo(x + hw, yy)
                path.quadTo((x + hw + x2) / 2f, yy + (if (up) sp * 0.9f else -sp * 0.9f), x2, yy)
                c.drawPath(path, lineP)
            }
            if (showNames) {
                textP.color = if (col == C.RED_HI) C.RED_HI else C.MUTED
                textP.textSize = sp * 1.05f; textP.typeface = Fonts.medium
                val ty = if (up) maxY + sp * 2.2f else minY - sp * 1.4f
                if (n === sorted.first() && !up || n === sorted.last() && up) { }
                c.drawText(Names.spelled(n.dia, n.alt), x + hw / 2, if (up) y + sp * 2.1f else y - sp * 1.3f, textP)
                if (ty < 0) { }
            }
            if (n.oct != 0 && n === sorted.last()) {
                textP.color = C.MUTED; textP.textSize = sp * 1.1f; textP.typeface = Fonts.bold
                c.drawText(if (n.oct > 0) (if (n.oct >= 24) "15ma" else "8va") else "8vb", x + hw / 2, if (n.oct > 0) min(topOf(st), minY) - sp * 2.4f else max(bottomLine(st), maxY) + sp * 2.8f, textP)
            }
        }
        if (e.base < 1920) {
            val sx = if (up) x + hw - sp * 0.07f else x + sp * 0.07f
            val g = beamOf[pe]
            val endY = if (g != null) beamY(groups[g]!!, sx) else if (up) minY - sp * 3.4f else maxY + sp * 3.4f
            lineP.color = col; lineP.strokeWidth = sp * 0.13f
            c.drawLine(sx, if (up) maxY else minY, sx, endY, lineP)
            if (g == null && e.base < 480) {
                val fl = when { e.base >= 240 -> if (up) 0xE240 else 0xE241; e.base >= 120 -> if (up) 0xE242 else 0xE243; else -> if (up) 0xE244 else 0xE245 }
                glyph(c, fl, sx - sp * 0.07f, endY, col)
            }
            if (e.tup > 0 && g == null) {
                textP.color = C.MUTED; textP.textSize = sp * 1.2f; textP.typeface = Fonts.bold
                c.drawText(e.tup.toString(), x + hw / 2, if (up) endY - sp * 0.6f else endY + sp * 1.6f, textP)
            }
        }
    }

    /** Altura de la linea del haz en x: recta entre la primera y la ultima plica del grupo. */
    private fun beamY(list: List<PEv>, x: Float): Float {
        val up = stemUp(list[0])
        val st = list[0].e.staff.coerceIn(0, staves - 1)
        fun ext(pe: PEv): Float {
            var ys = if (up) Float.MAX_VALUE else -Float.MAX_VALUE
            for (n in pe.e.notes) { val y = yOf(st, n.dia); ys = if (up) min(ys, y) else max(ys, y) }
            return if (up) ys - sp * 3.3f else ys + sp * 3.3f
        }
        val hw = sp * 1.18f
        val x0 = xOf(list.first().t) + (if (up) hw else 0f); val x1 = xOf(list.last().t) + (if (up) hw else 0f)
        var y0 = ext(list.first()); var y1 = ext(list.last())
        // ninguna plica del medio debe quedar corta
        val lim = list.map { ext(it) }
        val slope = ((y1 - y0) / max(1f, x1 - x0)).coerceIn(-0.25f, 0.25f)
        y1 = y0 + slope * (x1 - x0)
        var shift = 0f
        for ((idx, pe) in list.withIndex()) {
            val px = xOf(pe.t) + (if (up) hw else 0f)
            val yb = y0 + slope * (px - x0)
            shift = if (up) min(shift, lim[idx] - yb) else max(shift, lim[idx] - yb)
        }
        return y0 + shift + slope * (x - x0)
    }

    private fun beams(base: Int): Int = when { base >= 480 -> 0; base >= 240 -> 1; base >= 120 -> 2; base >= 60 -> 3; else -> 4 }

    private fun drawBeam(c: Canvas, list: List<PEv>) {
        if (list.size < 2) return
        val up = stemUp(list[0])
        val hw = sp * 1.18f
        val col = colorOf(list.last()).let { if (it == C.RED_HI) C.TEXT else it }
        fillP.color = if (curT >= list.last().t + list.last().e.dur) C.MUTED else col
        val th = sp * 0.48f
        val gap = sp * 0.75f
        val xs = list.map { xOf(it.t) + (if (up) hw - sp * 0.07f else sp * 0.07f) }
        for (lvl in 1..4) {
            var j = 0
            while (j < list.size) {
                if (beams(list[j].e.base) < lvl) { j++; continue }
                var k = j
                while (k + 1 < list.size && beams(list[k + 1].e.base) >= lvl) k++
                var xa = xs[j]; var xb = xs[k]
                if (k == j) { if (j > 0) xa = xs[j] - sp * 1.2f else xb = xs[j] + sp * 1.2f }
                val off = (lvl - 1) * gap * (if (up) 1 else -1)
                val ya = beamY(list, xa) + off; val yb = beamY(list, xb) + off
                path.reset()
                path.moveTo(xa, ya); path.lineTo(xb, yb)
                path.lineTo(xb, yb + (if (up) th else -th)); path.lineTo(xa, ya + (if (up) th else -th)); path.close()
                c.drawPath(path, fillP)
                j = k + 1
            }
        }
        val tup = list[0].e.tup
        if (tup > 0) {
            textP.color = C.MUTED; textP.textSize = sp * 1.2f; textP.typeface = Fonts.bold
            val mx = (xs.first() + xs.last()) / 2
            c.drawText(tup.toString(), mx, beamY(list, mx) + (if (up) -sp * 0.7f else sp * 1.7f), textP)
        }
    }

    private var downX = 0f
    private var lastX = 0f
    private var moved = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; lastX = e.x; moved = false }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && abs(e.x - downX) > sp * 1.5f) { moved = true; parent.requestDisallowInterceptTouchEvent(true); onDragStart?.invoke() }
                if (moved) { onDrag?.invoke(-(e.x - lastX) / ppt); lastX = e.x }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (moved) onDragEnd?.invoke() else if (e.actionMasked == MotionEvent.ACTION_UP) { performClick(); onTap?.invoke() }
                moved = false
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
