package com.digitalminds.elizibel

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

interface Host {
    val synth: Synth
    val prefs: SharedPreferences
    fun ensureMic(cb: (Boolean) -> Unit)
    fun toast(msg: String)
    fun closePlayer()
    fun reportError(e: Throwable)
    fun setOrientation(landscape: Boolean)
}

/** Menu desplegable anclado a un boton. Se abre hacia arriba cuando el boton esta en la parte baja de la pantalla. */
fun dropdown(anchor: View, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val ctx = anchor.context
    val box = LinearLayout(ctx)
    box.orientation = LinearLayout.VERTICAL
    box.background = roundRect(C.CARD, ctx.dp(16f), C.CARD2, ctx.dpi(1f))
    box.setPadding(ctx.dpi(6f), ctx.dpi(6f), ctx.dpi(6f), ctx.dpi(6f))
    val sv = android.widget.ScrollView(ctx)
    sv.addView(box)
    // sin foco: no despierta las barras del sistema; se cierra tocando afuera
    val pop = PopupWindow(sv, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false)
    pop.isOutsideTouchable = true
    pop.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
    pop.elevation = ctx.dp(8f)
    for ((i, t) in items.withIndex()) {
        val row = LinearLayout(ctx)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(ctx.dpi(14f), ctx.dpi(10f), ctx.dpi(18f), ctx.dpi(10f))
        if (i == selected) row.background = roundRect(C.RED, ctx.dp(12f))
        row.addView(label(ctx, t, 14.5f, if (i == selected) C.TEXT else C.MUTED, if (i == selected) Fonts.bold else Fonts.medium))
        row.tap { pop.dismiss(); onPick(i) }
        box.addView(row, lp(MATCH, WRAP))
    }
    box.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    val loc = IntArray(2)
    anchor.getLocationOnScreen(loc)
    val dm = ctx.resources.displayMetrics
    val maxH = (dm.heightPixels * 0.8f).toInt()
    val hgt = min(box.measuredHeight, maxH)
    pop.height = hgt
    val up = loc[1] > dm.heightPixels / 2
    val yoff = if (up) -(hgt + anchor.height + ctx.dpi(4f)) else ctx.dpi(4f)
    pop.showAsDropDown(anchor, 0, yoff)
}

/** Boton que muestra la opcion actual y abre un menu al tocarlo. */
class MenuButton(ctx: Context, title: String) : LinearLayout(ctx) {
    private val lab = label(ctx, "", 13f, C.TEXT, Fonts.medium)

    init {
        gravity = Gravity.CENTER_VERTICAL
        background = roundRect(C.CARD2, ctx.dp(20f))
        setPadding(ctx.dpi(12f), ctx.dpi(8f), ctx.dpi(6f), ctx.dpi(8f))
        if (title.isNotEmpty()) addView(label(ctx, "$title ", 12f, C.MUTED, Fonts.medium))
        addView(lab)
        addView(IconView(ctx, IconView.CHEVRON_DOWN), lp(ctx.dpi(18f), ctx.dpi(18f)))
    }

    fun setText(t: String) { lab.text = t }
}

/**
 * Reproductor de practica. El tiempo corre en ticks (480 por negra) sobre la linea de reproduccion.
 * Manos (piano): eliges que mano practicas; la otra la toca la app y se ve tenue.
 * Modos: Libre (suena todo), Espera (se detiene en cada nota tuya hasta que la tocas: piano USB,
 * teclado de la pantalla o, en la flauta, el microfono) y Evaluar (no espera y cuenta tus aciertos).
 */
class PlayerScreen(private val host: Host, private val song: Song) : FrameLayout(host as Context) {
    private val ctx: Context = host as Context
    private val prefs = host.prefs
    private val tl = Timeline(song)
    private val handler = Handler(Looper.getMainLooper())
    private val flute = song.flute

    private var curT = 0f
    private var playing = false
    private var lastMs = 0L
    private val speeds = listOf(40, 60, 80, 90, 100, 110, 120, 140, 150)
    private var speed = prefs.getInt("speed_" + song.id, 100).let { v -> speeds.minByOrNull { kotlin.math.abs(it - v) } ?: 100 }
    private var mode = 0
    private var hand = if (flute) 0 else prefs.getInt("hand_" + song.id, 0)
    private var userPart = 0
    private var guide = prefs.getBoolean("guide", true)
    private var countIn = prefs.getBoolean("countin2", false)
    private var metroOn = false
    private var appVol = prefs.getInt("appvol", 0)
    private var myVol = prefs.getInt("myvol", 0)
    private var autoUp = prefs.getBoolean("autoup", false)
    /** Partes silenciadas (por ejemplo Piano 2 o Flauta 1). */
    private val muted = BooleanArray(2) { prefs.getBoolean("mute${it}_" + song.id, false) }
    private var metroAccent = prefs.getBoolean("metro_accent3", true)
    /** Sonido de las manos (piano): 0 las dos, 1 sin derecha, 2 sin izquierda, 3 ninguna. */
    private var handSnd = prefs.getInt("handsnd", 0).coerceIn(0, 3)
    private val handSndItems = listOf("Las dos manos", "Sin mano derecha", "Sin mano izquierda", "Ninguna (silencio)")
    private val handSndShort = listOf("Dos manos", "Solo izq.", "Solo der.", "Ninguna")
    private var loopA = -1f
    private var loopB = -1f
    private var countdown = 0
    private var schedIdx = 0
    private var lastBeat = -1

    private class Active(val key: Int, val off: Float)
    private val active = ArrayList<Active>()
    private var keyCounter = 1

    private val stops = ArrayList<Int>()
    private var stopIdx = 0
    private val hitSince = HashSet<Int>()
    private val pressTime = HashMap<Int, Long>()
    private var evalTotal = 0
    private var evalHits = 0
    private var evalIdx = 0
    private var waiting = false
    private var detector: PitchDetector? = null
    private var micMidi = -1
    private val userEv: List<PEv> = tl.events.filter { !it.e.rest }

    private val score = ScoreView(ctx)
    private val keys = KeyboardView(ctx)
    private val fluteV = FluteView(ctx)
    private val playBtn = PlayButton(ctx)
    private val seek = SeekView(ctx) { f -> seekTo(f * tl.total) }
    private val measureV = label(ctx, "", 12f, C.TEXT, Fonts.title)
    private val subV = label(ctx, "", 11f, C.MUTED, Fonts.medium)
    private val statusV = label(ctx, "", 12f, C.MUTED, Fonts.medium)
    private val speedBtn = MenuButton(ctx, "")
    private val modeBtn = MenuButton(ctx, "")
    private val loopIcon = TransportIcon(ctx, true)
    private val metroChip = Chip(ctx, "Metrónomo")
    private val middle = FrameLayout(ctx)
    private val skipBack = SkipButton(ctx, false)
    private val skipFwd = SkipButton(ctx, true)
    private var skipSec = prefs.getInt("skip", 10)
    private val modes = listOf("Libre", "Espera", "Evaluar")
    private val midiListener: (Int, Boolean, Int) -> Unit = { m, on, v -> onUserNote(m, on, v / 127f, true) }

    private val frame = object : Runnable {
        override fun run() {
            try { tick() } catch (e: Exception) { pause(); host.reportError(e) }
            if (playing) handler.postDelayed(this, 8)
        }
    }

    private val handsBtn = MenuButton(ctx, "")
    private val metroBtn = IconView(ctx, IconView.METRO)
    private val handItems = listOf("Practicar con las dos manos", "Practicar la mano derecha", "Practicar la mano izquierda")
    private val handShort = listOf("Dos manos", "Derecha", "Izquierda")

    init {
        setBackgroundColor(C.BG)
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        addView(col, LayoutParams(MATCH, MATCH))

        col.addView(middle, lp(MATCH, 0, 1f))
        buildMiddle()

        // posicion: numero de compas (al tocarlo dice "Compas") y barra
        val sr = LinearLayout(ctx)
        sr.gravity = Gravity.CENTER_VERTICAL
        sr.setPadding(ctx.dpi(12f), ctx.dpi(4f), ctx.dpi(12f), 0)
        measureV.gravity = Gravity.CENTER
        measureV.background = roundRect(C.CARD2, ctx.dp(12f), C.RED, ctx.dpi(1f))
        measureV.setPadding(ctx.dpi(10f), ctx.dpi(2f), ctx.dpi(10f), ctx.dpi(2f))
        measureV.minWidth = ctx.dpi(44f)
        measureV.tap { host.toast("Compás " + measureV.text) }
        sr.addView(measureV, lp(WRAP, WRAP))
        sr.addView(seek, lp(0, ctx.dpi(20f), 1f).also { it.marginStart = ctx.dpi(8f) })
        statusV.setPadding(ctx.dpi(8f), 0, 0, 0)
        sr.addView(statusV, lp(WRAP, WRAP))
        col.addView(sr, lp(MATCH, WRAP))

        // abajo: manos a la izquierda, reproduccion al centro, metronomo y ajustes a la derecha
        val bar = FrameLayout(ctx)
        // abajo a la izquierda: volver, nombre de la cancion y manos (arriba queda libre para las notas)
        val left = LinearLayout(ctx)
        left.gravity = Gravity.CENTER_VERTICAL
        val back = FrameLayout(ctx)
        back.addView(IconView(ctx, IconView.BACK), LayoutParams(ctx.dpi(20f), ctx.dpi(20f), Gravity.CENTER))
        back.tap { host.closePlayer() }
        left.addView(back, lp(ctx.dpi(36f), ctx.dpi(40f)))
        val title = label(ctx, song.title.uppercase(), 11f, C.TEXT, Fonts.title)
        title.maxLines = 1; title.ellipsize = android.text.TextUtils.TruncateAt.END
        left.addView(title, lp(ctx.dpi(230f), WRAP))
        bar.addView(left, LayoutParams(WRAP, MATCH, Gravity.START or Gravity.CENTER_VERTICAL).also { it.marginStart = ctx.dpi(2f) })
        val center = LinearLayout(ctx)
        center.gravity = Gravity.CENTER
        val restart = TransportIcon(ctx, false)
        restart.tap { seekTo(if (loopA >= 0) loopA else 0f) }
        center.addView(restart, lp(ctx.dpi(28f), ctx.dpi(28f)))
        skipBack.seconds = skipSec; skipFwd.seconds = skipSec
        skipBack.tap { skip(-1) }
        skipFwd.tap { skip(1) }
        center.addView(skipBack, lp(ctx.dpi(32f), ctx.dpi(32f)).also { it.marginStart = ctx.dpi(8f) })
        playBtn.tap { if (playing) pause() else play() }
        center.addView(playBtn, lp(ctx.dpi(36f), ctx.dpi(36f)).also { it.setMargins(ctx.dpi(10f), 0, ctx.dpi(10f), 0) })
        center.addView(skipFwd, lp(ctx.dpi(32f), ctx.dpi(32f)))
        loopIcon.tap { pickLoop() }
        center.addView(loopIcon, lp(ctx.dpi(28f), ctx.dpi(28f)).also { it.marginStart = ctx.dpi(8f) })
        bar.addView(center, LayoutParams(WRAP, MATCH, Gravity.CENTER))
        val right = LinearLayout(ctx)
        right.gravity = Gravity.CENTER_VERTICAL
        if (!flute) {
            handsBtn.setText(handShort[hand])
            handsBtn.tap { dropdown(handsBtn, handItems, hand) { setHand(it) } }
            right.addView(handsBtn, lp(WRAP, WRAP).also { it.marginEnd = ctx.dpi(4f) })
        }
        val mb = FrameLayout(ctx)
        metroBtn.color = C.MUTED
        mb.addView(metroBtn, LayoutParams(ctx.dpi(24f), ctx.dpi(24f), Gravity.CENTER))
        mb.tap { metroOn = !metroOn; metroBtn.color = if (metroOn) C.RED_HI else C.MUTED; host.toast(if (metroOn) "Metrónomo encendido" else "Metrónomo apagado") }
        right.addView(mb, lp(ctx.dpi(42f), ctx.dpi(42f)))
        val gear = FrameLayout(ctx)
        gear.addView(IconView(ctx, IconView.GEAR), LayoutParams(ctx.dpi(22f), ctx.dpi(22f), Gravity.CENTER))
        gear.tap { settings() }
        right.addView(gear, lp(ctx.dpi(42f), ctx.dpi(42f)))
        bar.addView(right, LayoutParams(WRAP, MATCH, Gravity.END or Gravity.CENTER_VERTICAL).also { it.marginEnd = ctx.dpi(6f) })
        col.addView(bar, lp(MATCH, ctx.dpi(42f)))

        score.tl = tl
        score.showNames = false
        score.zoom = floatArrayOf(0.75f, 1f, 1.35f)[prefs.getInt("zoom", 1).coerceIn(0, 2)]
        score.hand = hand
        // arrastrar no pausa: si esta sonando, sigue sonando desde donde sueltes
        score.onDragStart = { dragStart() }
        score.onDrag = { d -> seekTo(curT + d, quiet = true) }
        score.onDragEnd = { dragEnd() }
        score.onTap = { if (playing) pause() else play() }
        keys.tl = tl
        keys.names = prefs.getInt("keynames", 1)
        keys.hand = hand
        keys.onKey = { m, on -> onUserNote(m, on, 0.8f, false) }
        keys.onDragStart = { dragStart() }
        keys.onDrag = { d -> seekTo(curT + d, quiet = true) }
        keys.onDragEnd = { dragEnd() }
        applyRange()
        MidiIn.start(ctx)
        MidiIn.addListener(midiListener)
        MidiIn.onStatus = { refreshStatus() }
        rebuildStops()
        seekTo(0f)
        refreshStatus()
    }

    /** Al arrastrar se detiene el sonido; al soltar, si estaba sonando, continua desde ahi sin tocar pausa. */
    private var resumeAfterDrag = false
    private fun dragStart() { resumeAfterDrag = playing; if (playing) pause() }
    private fun dragEnd() { if (resumeAfterDrag) { resumeAfterDrag = false; play() } }

    private fun buildMiddle() {
        middle.removeAllViews()
        (score.parent as? ViewGroup)?.removeView(score)
        (keys.parent as? ViewGroup)?.removeView(keys)
        (fluteV.parent as? ViewGroup)?.removeView(fluteV)
        if (flute) {
            // partitura a la izquierda y la flauta a la derecha
            val row = LinearLayout(ctx)
            row.addView(score, lp(0, MATCH, 0.7f))
            row.addView(fluteV, lp(0, MATCH, 0.3f))
            middle.addView(row, LayoutParams(MATCH, MATCH))
            return
        }
        val falling = prefs.getBoolean("falling", false)
        keys.falling = falling
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        if (falling) c.addView(keys, lp(MATCH, 0, 1f).also { it.setMargins(ctx.dpi(6f), 0, ctx.dpi(6f), 0) })
        else {
            c.addView(score, lp(MATCH, 0, 0.62f))
            c.addView(keys, lp(MATCH, 0, 0.38f).also { it.setMargins(ctx.dpi(6f), ctx.dpi(2f), ctx.dpi(6f), 0) })
        }
        middle.addView(c, LayoutParams(MATCH, MATCH))
    }

    /** El teclado muestra solo las teclas que usa la canción (más una a cada lado), o las 88. */
    private fun applyRange() {
        if (prefs.getBoolean("keys88", false)) keys.setRange(21, 108)
        else keys.setRange(max(21, tl.minMidi - 1), min(108, tl.maxMidi + 1))
    }

    private fun applyPart() {
        score.userPart = userPart
        keys.userPart = userPart
        rebuildStops()
        seekTo(curT)
        refreshStatus()
    }

    private fun setHand(h: Int) {
        hand = h
        prefs.edit().putInt("hand_" + song.id, h).apply()
        score.hand = h; keys.hand = h
        handsBtn.setText(handShort[h])
        rebuildStops()
        stopIdx = firstStopAt(curT); evalIdx = stopIdx
        updateLit()
        score.invalidate(); keys.invalidate()
        host.toast(when (h) { 1 -> "Practicas la mano derecha. La izquierda la toca la app."; 2 -> "Practicas la mano izquierda. La derecha la toca la app."; else -> "Practicas con las dos manos." })
        refreshStatus()
    }

    /** Silencia lo que toca la app con cada mano. No cambia la mano que practicas. */
    private fun setHandSound(i: Int) {
        handSnd = i
        prefs.edit().putInt("handsnd", i).apply()
        releaseAll()
        host.toast(when (i) { 1 -> "La mano derecha no suena"; 2 -> "La mano izquierda no suena"; 3 -> "Ninguna mano suena"; else -> "Suenan las dos manos" })
    }

    private fun setMode(m: Int) {
        mode = m
        modeBtn.setText(modes[m])
        if (flute && m > 0) startMic() else stopMic()
        evalTotal = 0; evalHits = 0
        stopIdx = firstStopAt(curT); evalIdx = stopIdx
        if (!flute && m > 0 && MidiIn.connectedName == null) host.toast("Sin piano USB: toca las teclas de la pantalla")
        refreshStatus()
    }

    private fun mineStaff(staff: Int): Boolean = flute || hand == 0 || (hand == 1 && staff == 0) || (hand == 2 && staff == 1)
    private fun mine(e: Ev): Boolean = e.part == userPart && mineStaff(e.staff)

    private fun rebuildStops() {
        stops.clear()
        for ((i, pe) in tl.events.withIndex()) if (!pe.e.rest && mine(pe.e) && pe.e.notes.any { !it.tieIn }) stops.add(i)
    }

    private fun firstStopAt(t: Float): Int {
        var lo = 0; var hi = stops.size
        while (lo < hi) { val mid = (lo + hi) / 2; if (tl.events[stops[mid]].t < t - 0.5f) lo = mid + 1 else hi = mid }
        return lo
    }

    private fun requiredAt(t: Int): Set<Int> {
        val s = HashSet<Int>()
        var i = stopIdx
        while (i < stops.size && tl.events[stops[i]].t == t) { for (n in tl.events[stops[i]].e.notes) if (!n.tieIn) s.add(n.midi); i++ }
        return s
    }

    private fun ticksPerMs(): Float {
        val pm = tl.measures[tl.pmAt(max(0f, curT))]
        return song.bpmAt(pm.index) * speed / 100f * 480f / 60000f
    }

    /** Cuenta previa: tres negras (tan, tan, tin) siempre al tiempo de la cancion. */
    private val PRE = 1440f
    private var preBeat = -1

    fun play() {
        if (playing) return
        if (curT >= tl.total - 1) seekTo(if (loopA >= 0) loopA else 0f)
        playing = true
        playBtn.playing = true
        lastMs = SystemClock.uptimeMillis()
        val atStart = curT <= 1f || (loopA >= 0 && curT <= loopA + 1f)
        if (atStart) {
            // al empezar: la partitura avanza en blanco tres negras mientras suena la cuenta
            curT = (if (loopA >= 0) loopA else 0f) - PRE; preBeat = -1
            handler.post(frame)
        } else if (countIn) { countdown = 3; countdownBeat() } else handler.post(frame)
    }

    /** Al reanudar (si esta activado): tres clics de negra en el mismo lugar. */
    private fun countdownBeat() {
        if (!playing) return
        if (countdown <= 0) { lastMs = SystemClock.uptimeMillis(); handler.post(frame); statusV.text = ""; refreshStatus(); return }
        val ms = (480f / ticksPerMs()).toLong()
        host.synth.click(if (countdown == 1) 2 else 1)
        statusV.text = countdown.toString()
        statusV.setTextColor(C.RED_HI)
        countdown--
        handler.postDelayed({ countdownBeat() }, ms)
    }

    fun pause() {
        playing = false
        playBtn.playing = false
        handler.removeCallbacksAndMessages(null)
        releaseAll()
        countdown = 0
        refreshStatus()
    }

    private fun releaseAll() {
        for (a in active) host.synth.noteOff(a.key)
        active.clear()
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastMs).coerceIn(0, 80)
        lastMs = now
        if (!playing) return
        var next = curT + dt * ticksPerMs()
        waiting = false
        if (mode == 1 && stopIdx < stops.size) {
            val st = tl.events[stops[stopIdx]].t.toFloat()
            if (next >= st) {
                val req = requiredAt(st.toInt())
                val ok = req.all { it in hitSince || it in MidiIn.held || it in keys.pressed || (flute && it == micMidi) }
                if (ok) {
                    hitSince.clear()
                    while (stopIdx < stops.size && tl.events[stops[stopIdx]].t <= st) stopIdx++
                } else { next = st; waiting = true }
            }
        }
        if (loopA >= 0 && loopB > loopA && next >= loopB) {
            if (autoUp) { val i = speeds.indexOf(speed); if (i >= 0 && i < speeds.size - 1) { speed = speeds[i + 1]; speedBtn.setText("$speed %") } }
            seekTo(loopA); return
        }
        if (metroOn && next >= 0f) {
            val pm = tl.measures[tl.pmAt(next)]
            val beat = if (pm.m.den == 8) 720 else 1920 / pm.m.den
            val bi = ((next - pm.t) / beat).toInt()
            val gb = pm.t / 10 + bi
            if (gb != lastBeat) { lastBeat = gb; host.synth.click(if (metroAccent && bi == 0) 2 else 1) }
        }
        // cuenta al empezar: la partitura corre en blanco y suenan tres negras
        val base0 = if (loopA >= 0) loopA else 0f
        if (next < base0) {
            val b = ((next - (base0 - PRE)) / 480f).toInt()
            if (b != preBeat && b in 0..2) { preBeat = b; host.synth.click(if (b == 2) 2 else 1); statusV.text = (3 - b).toString(); statusV.setTextColor(C.RED_HI) }
        } else if (preBeat >= 0) { preBeat = -1; statusV.text = "" }
        curT = next
        val it = active.iterator()
        while (it.hasNext()) { val a = it.next(); if (curT >= a.off) { host.synth.noteOff(a.key); it.remove() } }
        while (schedIdx < tl.events.size && tl.events[schedIdx].t <= curT) { trigger(tl.events[schedIdx]); schedIdx++ }
        if (mode == 2) evaluate()
        updateLit()
        render()
        if (curT >= tl.total) {
            pause()
            if (mode == 2 && evalTotal > 0) showResult()
            if (loopA < 0 && loopB < 0 && prefs.getBoolean("loopall_" + song.id, false)) { seekTo(0f); play() } else seekTo(tl.total.toFloat())
        }
    }

    /** Lo que suena: tu mano solo si "Escuchar mi parte" (en Libre); la otra mano y la otra parte las toca la app. */
    private fun trigger(pe: PEv) {
        val e = pe.e
        if (e.rest) return
        if (muted[e.part.coerceIn(0, 1)]) return
        if (!flute && ((e.staff == 0 && (handSnd == 1 || handSnd == 3)) || (e.staff == 1 && (handSnd == 2 || handSnd == 3)))) return
        val yours = mine(e)
        if (yours && !(mode == 0 && guide)) return
        val level = floatArrayOf(1f, 0.5f, 0f)[appVol]
        if (level <= 0f) return
        // la mano izquierda un poco mas suave: los graves del piano suenan mas fuertes
        val base = if (flute) 0.85f else if (e.staff == 1) 0.6f else 0.8f
        // acentuacion: en la derecha manda la nota mas aguda (la melodia); en la izquierda, la mas grave
        val top = e.notes.maxOfOrNull { it.midi } ?: 0
        val low = e.notes.minOfOrNull { it.midi } ?: 0
        for (n in e.notes) {
            if (n.tieIn) continue
            val voice = if (flute || e.notes.size < 2) 1f else if (e.staff == 0) (if (n.midi == top) 1f else 0.62f) else (if (n.midi == low) 1f else 0.7f)
            val k = keyCounter++
            val inst = if (flute) "flute" else "piano"
            val midi = if (flute) n.midi + 12 else n.midi
            host.synth.noteOn(k, inst, midi, base * level * voice)
            active.add(Active(k, pe.t + max(e.sound, 30).toFloat() - 6f))
        }
    }

    private fun evaluate() {
        val win = 0.4f * 480f * speed / 100f
        while (evalIdx < stops.size && tl.events[stops[evalIdx]].t + win < curT) {
            val pe = tl.events[stops[evalIdx]]
            val tms = SystemClock.uptimeMillis() - ((curT - pe.t) / ticksPerMs()).toLong()
            for (n in pe.e.notes) {
                if (n.tieIn) continue
                evalTotal++
                val pt = pressTime[n.midi]
                if (pt != null && abs(pt - tms) < 320) evalHits++
            }
            evalIdx++
        }
        if (evalTotal > 0) { statusV.text = "Aciertos ${evalHits * 100 / evalTotal}%"; statusV.setTextColor(C.TEXT) }
    }

    private fun showResult() {
        val pct = evalHits * 100 / max(1, evalTotal)
        val best = prefs.getInt("best_" + song.id, 0)
        if (pct > best) prefs.edit().putInt("best_" + song.id, pct).apply()
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(ctx.dpi(26f), ctx.dpi(22f), ctx.dpi(26f), ctx.dpi(16f))
        box.background = roundRect(C.CARD, ctx.dp(22f))
        box.addView(label(ctx, "RESULTADO", 13f, C.RED_HI, Fonts.title).also { it.letterSpacing = 0.2f })
        box.addView(label(ctx, "$pct %", 54f, C.TEXT, Fonts.bold).also { it.setPadding(0, ctx.dpi(6f), 0, 0) })
        box.addView(label(ctx, "$evalHits de $evalTotal notas a tiempo · velocidad $speed %", 13f, C.MUTED))
        box.addView(label(ctx, if (pct > best) "¡Nuevo récord!" else "Récord: $best %", 14f, if (pct > best) C.RED_HI else C.MUTED, Fonts.medium).also { it.setPadding(0, ctx.dpi(8f), 0, ctx.dpi(12f)) })
        val ok = label(ctx, "OK", 16f, C.RED_HI, Fonts.bold)
        ok.setPadding(ctx.dpi(16f), ctx.dpi(8f), ctx.dpi(16f), ctx.dpi(4f))
        ok.tap { d.dismiss() }
        box.addView(ok)
        d.setContentView(box)
        d.window?.setBackgroundDrawable(ColorDrawable(0))
        d.showClean()
    }

    private fun onUserNote(m: Int, on: Boolean, vel: Float, fromMidi: Boolean) {
        if (on) {
            hitSince.add(m)
            pressTime[m] = SystemClock.uptimeMillis()
            val lv = floatArrayOf(1f, 0.5f, 0f)[myVol]
            if (!fromMidi && lv > 0f) host.synth.play("piano", m, 700, vel * lv)
        }
        if (fromMidi) { if (on) keys.pressed.add(m) else keys.pressed.remove(m) }
        keys.invalidate()
    }

    private fun startMic() {
        host.ensureMic { ok ->
            if (!ok) { host.toast("Falta el permiso del micrófono"); return@ensureMic }
            if (detector != null) return@ensureMic
            val d = PitchDetector({ 440.0 }, { 0.012f }, 0.13f, true) { f ->
                micMidi = if (f.voiced && f.clarity > 0.85f) Math.round(f.midi) - 12 else -1
                if (micMidi >= 0) { hitSince.add(micMidi); pressTime[micMidi] = SystemClock.uptimeMillis() }
            }
            if (d.start()) detector = d else host.toast("No se pudo abrir el micrófono")
        }
    }

    private fun stopMic() { detector?.stop(); detector = null; micMidi = -1 }

    private fun seekTo(t: Float, quiet: Boolean = false) {
        val nt = t.coerceIn(0f, tl.total.toFloat())
        if (active.isNotEmpty()) { host.synth.mute(true); releaseAll(); handler.postDelayed({ host.synth.mute(false) }, 30) }
        curT = nt
        schedIdx = tl.firstAt(curT)
        stopIdx = firstStopAt(curT); evalIdx = stopIdx
        hitSince.clear()
        lastBeat = -1
        if (playing) lastMs = SystemClock.uptimeMillis()
        updateLit()
        render()
        if (!quiet) refreshStatus()
    }

    private fun skip(dir: Int) { seekTo(curT + dir * skipSec * 1000f * ticksPerMs()) }

    /** Busca el ultimo evento tuyo que empezo en o antes de t (binaria sobre userEv). */
    private fun lastUserAt(t: Float): Int {
        var lo = 0; var hi = userEv.size
        while (lo < hi) { val mid = (lo + hi) / 2; if (userEv[mid].t <= t) lo = mid + 1 else hi = mid }
        return lo - 1
    }

    private fun updateLit() {
        keys.lit.clear()
        var i = tl.firstAt(curT - 1920 * 4)
        while (i < tl.events.size && tl.events[i].t <= curT + 1) {
            val pe = tl.events[i]; i++
            if (pe.e.rest || !mine(pe.e)) continue
            if (curT < pe.t + pe.e.sound) for (n in pe.e.notes) keys.lit[n.midi] = if (pe.e.staff == 0) 1 else 2
        }
        if (flute) {
            // nota actual = la ultima que empezo; la siguiente = la primera que empieza despues (cambia en el acto)
            var ci = lastUserAt(curT + 1f)
            while (ci >= 0 && userEv[ci].e.part != userPart) ci--
            var cur = -1; var nextIdx = 0
            if (ci >= 0) {
                val pe = userEv[ci]
                cur = pe.e.notes.maxOf { it.midi }
                nextIdx = ci + 1
                while (nextIdx < userEv.size && (userEv[nextIdx].t <= pe.t || userEv[nextIdx].e.part != userPart)) nextIdx++
            }
            if (ci < 0) { var k = 0; while (k < userEv.size && userEv[k].e.part != userPart) k++; if (k < userEv.size) { cur = userEv[k].e.notes.maxOf { it.midi }; nextIdx = k + 1 } }
            val nx = if (nextIdx < userEv.size) userEv[nextIdx].e.notes.maxOf { it.midi } else -1
            fluteV.midi = cur; fluteV.next = nx
        }
    }

    private fun render() {
        score.curT = curT
        keys.curT = curT
        seek.value = max(0f, curT) / max(1, tl.total)
        val pm = tl.measures[tl.pmAt(curT)]
        measureV.text = (pm.index + 1).toString()
        if (mode == 1) {
            if (waiting && stopIdx < stops.size) {
                val req = requiredAt(tl.events[stops[stopIdx]].t)
                statusV.text = "Toca: " + req.sorted().joinToString(" ") { Names.pitchClass(it) }
                statusV.setTextColor(C.RED_HI)
            } else if (countdown <= 0) { statusV.text = "" }
        }
    }

    private fun refreshStatus() {
        val handTxt = if (flute) "Flauta dulce" else when (hand) { 1 -> "Practicas: mano derecha"; 2 -> "Practicas: mano izquierda"; else -> "Practicas: dos manos" }
        val midi = MidiIn.connectedName
        val bpm = song.bpmAt(tl.measures[tl.pmAt(curT)].index) * speed / 100
        subV.text = listOf(song.composer, handTxt, "$bpm bpm", if (!flute && midi != null) "USB: $midi" else "").filter { it.isNotEmpty() }.joinToString(" · ")
        loopIcon.on = loopA >= 0 || prefs.getBoolean("loopall_" + song.id, false)
        if (mode != 1 && countdown <= 0 && !(mode == 2 && evalTotal > 0)) statusV.text = ""
    }

    private fun pickSpeed() {
        dropdown(speedBtn, speeds.map { "$it %" + (if (it == 100) "  original" else "") }, speeds.indexOf(speed)) { i ->
            speed = speeds[i]
            prefs.edit().putInt("speed_" + song.id, speed).apply()
            speedBtn.setText("$speed %")
            refreshStatus()
        }
    }

    private fun pickLoop() {
        val items = listOf("Toda la canción en bucle", "2 compases desde aquí", "4 compases desde aquí", "8 compases desde aquí", "Desde aquí hasta el final", "Quitar bucle")
        val all = prefs.getBoolean("loopall_" + song.id, false)
        dropdown(loopIcon, items, if (all) 0 else -1) { i ->
            when (i) {
                0 -> { loopA = -1f; loopB = -1f; prefs.edit().putBoolean("loopall_" + song.id, true).apply(); host.toast("Toda la canción en bucle") }
                5 -> { loopA = -1f; loopB = -1f; prefs.edit().putBoolean("loopall_" + song.id, false).apply() }
                else -> {
                    prefs.edit().putBoolean("loopall_" + song.id, false).apply()
                    val pi = tl.pmAt(curT)
                    loopA = tl.measures[pi].t.toFloat()
                    val n = intArrayOf(0, 2, 4, 8, 100000)[i]
                    val end = min(tl.measures.size, pi + n)
                    loopB = if (end >= tl.measures.size) tl.total.toFloat() else tl.measures[end].t.toFloat()
                    seekTo(loopA)
                    host.toast("Bucle: compases ${tl.measures[pi].index + 1} a ${tl.measures[end - 1].index + 1}")
                }
            }
            refreshStatus()
        }
    }

    /** Ajustes: una fila por opcion con su valor; al tocarla se abre una ventanita con las opciones. */
    private fun settings() {
        if (playing) pause()
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sv = FastScrollView(ctx)
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(ctx.dpi(18f), ctx.dpi(16f), ctx.dpi(22f), ctx.dpi(12f))
        box.background = roundRect(C.CARD, ctx.dp(20f))
        sv.setContent(box)
        val head = LinearLayout(ctx)
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(label(ctx, "AJUSTES", 14f, C.TEXT, Fonts.title).also { it.letterSpacing = 0.15f }, lp(0, WRAP, 1f))
        val close = FrameLayout(ctx)
        close.addView(label(ctx, "Listo", 14f, C.RED_HI, Fonts.bold), LayoutParams(WRAP, WRAP, Gravity.CENTER))
        close.tap { d.dismiss() }
        head.addView(close, lp(WRAP, ctx.dpi(36f)))
        box.addView(head, lp(MATCH, WRAP))
        fun section(t: String) { box.addView(label(ctx, t, 10.5f, C.RED_HI, Fonts.title).also { it.letterSpacing = 0.14f; it.setPadding(0, ctx.dpi(14f), 0, ctx.dpi(2f)) }) }
        fun row(name: String, help: String, opts: List<String>, sel: Int, pick: (Int) -> Unit) {
            val r = LinearLayout(ctx)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, ctx.dpi(2f), 0, ctx.dpi(2f))
            r.addView(label(ctx, name, 14.5f, C.TEXT, Fonts.medium))
            r.addView(helpDot(ctx, name, help), lp(ctx.dpi(32f), ctx.dpi(30f)))
            r.addView(View(ctx), lp(0, 1, 1f))
            val v = MenuButton(ctx, "")
            v.setText(opts[sel.coerceIn(0, opts.size - 1)])
            var cur = sel
            v.tap { dropdown(v, opts, cur) { i -> cur = i; v.setText(opts[i]); pick(i) } }
            r.addView(v, lp(WRAP, WRAP))
            box.addView(r, lp(MATCH, ctx.dpi(46f)))
            val sep = View(ctx); sep.setBackgroundColor(C.CARD2)
            box.addView(sep, lp(MATCH, 1))
        }
        val onoff = listOf("Sí", "No")
        section("PRÁCTICA")
        row("Velocidad", "Qué tan rápido suena la canción. 100 % es la velocidad original; baja la velocidad para aprender y súbela cuando ya la domines.", speeds.map { "$it %" }, speeds.indexOf(speed)) { i -> speed = speeds[i]; prefs.edit().putInt("speed_" + song.id, speed).apply(); refreshStatus() }
        row("Modo", "Libre: la canción avanza sola y tocas encima.\nEspera: se detiene en cada nota hasta que la tocas (piano USB, teclado de la pantalla o micrófono en la flauta).\nEvaluar: no espera y al final te da el porcentaje de aciertos.", modes, mode) { setMode(it) }
        if (song.partNames.size >= 2) row("Tocas", "Qué parte practicas. La otra la toca la app.", song.partNames, userPart) { i -> userPart = i; applyPart() }
        row("Cuenta al reanudar", "Al empezar la canción siempre suena la cuenta (tan, tan, tin). Aquí eliges si también suena cuando pausas y vuelves a reproducir.", onoff, if (countIn) 0 else 1) { countIn = it == 0; prefs.edit().putBoolean("countin2", countIn).apply() }
        row("Bucle acelerado", "Con un bucle activo, cada vuelta sube a la siguiente velocidad, hasta 150 %.", onoff, if (autoUp) 0 else 1) { autoUp = it == 0; prefs.edit().putBoolean("autoup", autoUp).apply() }
        row("Salto", "Segundos que avanzan o retroceden los botones con flecha circular.", listOf("5 s", "10 s", "15 s", "30 s"), listOf(5, 10, 15, 30).indexOf(skipSec).coerceAtLeast(0)) { skipSec = listOf(5, 10, 15, 30)[it]; prefs.edit().putInt("skip", skipSec).apply(); skipBack.seconds = skipSec; skipFwd.seconds = skipSec }
        section("SONIDO")
        row("Guía", "En modo Libre, la app también toca lo que practicas tú, como referencia.", onoff, if (guide) 0 else 1) { guide = it == 0; prefs.edit().putBoolean("guide", guide).apply() }
        if (!flute) row("Suenan", "Silencia lo que toca la app con una mano o con las dos. No cambia la mano que practicas.", listOf("Dos manos", "Solo izquierda", "Solo derecha", "Ninguna"), handSnd) { setHandSound(it) }
        if (song.partNames.size >= 2) for (k in 0..1) {
            val nm = song.partNames[k]
            row("Sonido de $nm", "Enciende o silencia por completo $nm.", onoff, if (muted[k]) 1 else 0) { muted[k] = it == 1; prefs.edit().putBoolean("mute${k}_" + song.id, muted[k]).apply() }
        }
        row("Volumen de la app", "Volumen de todo lo que toca la app.", listOf("Normal", "Medio", "Sin volumen"), appVol) { appVol = it; prefs.edit().putInt("appvol", it).apply() }
        if (!flute) row("Volumen de mis teclas", "Volumen de las teclas que tocas en la pantalla. Con piano USB suena tu piano.", listOf("Normal", "Medio", "Sin volumen"), myVol) { myVol = it; prefs.edit().putInt("myvol", it).apply() }
        row("Clic del metrónomo", "Un sonido: todos los tiempos iguales. Dos sonidos: el primer tiempo del compás suena distinto.", listOf("Un sonido", "Dos sonidos"), if (metroAccent) 1 else 0) { metroAccent = it == 1; prefs.edit().putBoolean("metro_accent3", metroAccent).apply() }
        section("VISTA")
        row("Ancho", "Separación entre notas en la partitura.", listOf("Compacto", "Normal", "Amplio"), prefs.getInt("zoom", 1)) { prefs.edit().putInt("zoom", it).apply(); score.zoom = floatArrayOf(0.75f, 1f, 1.35f)[it]; score.invalidate() }
        if (!flute) {
            row("Notas que caen", "Cambia la partitura por barras que caen sobre cada tecla. Arrastra arriba o abajo para moverte.", onoff, if (prefs.getBoolean("falling", false)) 0 else 1) { prefs.edit().putBoolean("falling", it == 0).apply(); buildMiddle() }
            row("Nombres en teclas", "Escribe el nombre de la nota en las teclas blancas.", listOf("Todas", "Solo Do", "Ninguno"), when (keys.names) { 2 -> 0; 1 -> 1; else -> 2 }) { keys.names = intArrayOf(2, 1, 0)[it]; prefs.edit().putInt("keynames", keys.names).apply(); keys.invalidate() }
            row("Teclas", "Solo las que usa la canción (más grandes) o las 88.", listOf("Canción", "88"), if (prefs.getBoolean("keys88", false)) 1 else 0) { prefs.edit().putBoolean("keys88", it == 1).apply(); applyRange() }
        }
        d.setContentView(sv)
        d.window?.setBackgroundDrawable(ColorDrawable(0))
        val dm = ctx.resources.displayMetrics
        d.window?.setLayout(min(ctx.dpi(500f), (dm.widthPixels * 0.9f).toInt()), (dm.heightPixels * 0.92f).toInt())
        d.showClean()
    }

    fun release() {
        pause()
        stopMic()
        MidiIn.removeListener(midiListener)
        MidiIn.onStatus = null
        host.synth.allOff()
    }
}
