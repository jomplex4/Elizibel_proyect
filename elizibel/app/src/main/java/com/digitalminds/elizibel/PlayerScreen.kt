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
    val pop = PopupWindow(sv, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
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
 * Modos: Libre (suena todo), Espera (se detiene en cada nota hasta que la tocas: piano USB, teclado de la
 * pantalla o, en la flauta, el microfono) y Evaluar (no espera y cuenta tus aciertos).
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
    private var speed = prefs.getInt("speed_" + song.id, if (song.level >= 4) 70 else 100)
    private var mode = 0
    private var hand = prefs.getInt("hand_" + song.id, 0)
    private var userPart = 0
    private var guide = prefs.getBoolean("guide", true)
    private var countIn = prefs.getBoolean("countin", true)
    private var metroOn = false
    private var accVol = prefs.getInt("accvol", 0)
    private var autoUp = prefs.getBoolean("autoup", false)
    private var loopA = -1f
    private var loopB = -1f
    private var countdown = 0
    private var schedIdx = 0
    private var lastBeat = -1

    private class Active(val key: Int, val off: Float)
    private val active = ArrayList<Active>()
    private var keyCounter = 1

    // modo espera / evaluar
    private val stops = ArrayList<Int>()          // indices de tl.events que exigen notas tuyas
    private var stopIdx = 0
    private val hitSince = HashSet<Int>()
    private val pressTime = HashMap<Int, Long>()
    private var evalTotal = 0
    private var evalHits = 0
    private var evalIdx = 0
    private var waiting = false
    private var detector: PitchDetector? = null
    private var micMidi = -1

    private val score = ScoreView(ctx)
    private val keys = KeyboardView(ctx)
    private val fluteV = FluteView(ctx)
    private val playBtn = PlayButton(ctx)
    private val seek = SeekView(ctx) { f -> seekTo(f * tl.total) }
    private val measureV = label(ctx, "", 13f, C.TEXT, Fonts.bold)
    private val subV = label(ctx, "", 12f, C.MUTED, Fonts.medium)
    private val statusV = label(ctx, "", 12.5f, C.MUTED, Fonts.medium)
    private val speedBtn = MenuButton(ctx, "Velocidad")
    private val modeBtn = MenuButton(ctx, "Modo")
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

    init {
        setBackgroundColor(C.BG)
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        addView(col, LayoutParams(MATCH, MATCH))

        // barra superior
        val top = LinearLayout(ctx)
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(ctx.dpi(4f), ctx.dpi(4f), ctx.dpi(10f), 0)
        val back = FrameLayout(ctx)
        back.addView(IconView(ctx, IconView.BACK), LayoutParams(ctx.dpi(26f), ctx.dpi(26f), Gravity.CENTER))
        back.tap { host.closePlayer() }
        top.addView(back, lp(ctx.dpi(44f), ctx.dpi(44f)))
        val tcol = LinearLayout(ctx)
        tcol.orientation = LinearLayout.VERTICAL
        val title = label(ctx, song.title.uppercase(), 14f, C.TEXT, Fonts.title)
        title.maxLines = 1
        tcol.addView(title)
        subV.maxLines = 1
        tcol.addView(subV)
        top.addView(tcol, lp(0, WRAP, 1f))
        statusV.setPadding(ctx.dpi(8f), 0, ctx.dpi(8f), 0)
        top.addView(statusV, lp(WRAP, WRAP))
        if (!flute) {
            top.addView(liveSegmented(ctx, listOf("Ambas", "Derecha", "Izquierda"), hand, 12f, 6f) { setHand(it) }, lp(ctx.dpi(230f), WRAP).also { it.marginEnd = ctx.dpi(8f) })
        }
        if (song.partNames.size >= 2) {
            val pb = MenuButton(ctx, "Tocas")
            pb.setText(song.partNames[0])
            pb.tap { dropdown(pb, song.partNames, userPart) { i -> userPart = i; pb.setText(song.partNames[i]); applyPart() } }
            top.addView(pb, lp(WRAP, WRAP).also { it.marginEnd = ctx.dpi(8f) })
        }
        metroChip.tap { metroOn = !metroOn; metroChip.setActive(metroOn) }
        top.addView(metroChip, lp(WRAP, WRAP))
        col.addView(top, lp(MATCH, WRAP))

        col.addView(middle, lp(MATCH, 0, 1f))
        buildMiddle()

        // barra de posicion
        val sr = LinearLayout(ctx)
        sr.gravity = Gravity.CENTER_VERTICAL
        sr.setPadding(ctx.dpi(14f), 0, ctx.dpi(14f), 0)
        measureV.background = roundRect(C.RED, ctx.dp(14f))
        measureV.setPadding(ctx.dpi(12f), ctx.dpi(4f), ctx.dpi(12f), ctx.dpi(4f))
        sr.addView(measureV)
        sr.addView(seek, lp(0, ctx.dpi(26f), 1f).also { it.marginStart = ctx.dpi(8f) })
        col.addView(sr, lp(MATCH, WRAP))

        // controles
        val bar = LinearLayout(ctx)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(ctx.dpi(14f), ctx.dpi(2f), ctx.dpi(14f), ctx.dpi(6f))
        speedBtn.tap { pickSpeed() }
        bar.addView(speedBtn, lp(WRAP, WRAP))
        modeBtn.tap { dropdown(modeBtn, listOf("Libre: escucha y toca", "Espera: avanza cuando tocas", "Evaluar: mide tus aciertos"), mode) { setMode(it) } }
        bar.addView(modeBtn, lp(WRAP, WRAP).also { it.marginStart = ctx.dpi(8f) })
        bar.addView(View(ctx), lp(0, 1, 1f))
        val restart = TransportIcon(ctx, false)
        restart.tap { seekTo(if (loopA >= 0) loopA else 0f) }
        bar.addView(restart, lp(ctx.dpi(40f), ctx.dpi(40f)))
        skipBack.seconds = skipSec; skipFwd.seconds = skipSec
        skipBack.tap { skip(-1) }
        skipFwd.tap { skip(1) }
        bar.addView(skipBack, lp(ctx.dpi(46f), ctx.dpi(46f)).also { it.marginStart = ctx.dpi(6f) })
        playBtn.tap { if (playing) pause() else play() }
        bar.addView(playBtn, lp(ctx.dpi(54f), ctx.dpi(54f)).also { it.setMargins(ctx.dpi(8f), 0, ctx.dpi(8f), 0) })
        bar.addView(skipFwd, lp(ctx.dpi(46f), ctx.dpi(46f)))
        loopIcon.tap { pickLoop() }
        bar.addView(loopIcon, lp(ctx.dpi(42f), ctx.dpi(42f)).also { it.marginStart = ctx.dpi(6f) })
        bar.addView(View(ctx), lp(0, 1, 1f))
        val gear = FrameLayout(ctx)
        gear.addView(IconView(ctx, IconView.GEAR), LayoutParams(ctx.dpi(26f), ctx.dpi(26f), Gravity.CENTER))
        gear.tap { settings() }
        bar.addView(gear, lp(ctx.dpi(44f), ctx.dpi(44f)))
        col.addView(bar, lp(MATCH, WRAP))

        score.tl = tl
        score.showNames = prefs.getBoolean("names", false)
        score.zoom = floatArrayOf(0.75f, 1f, 1.35f)[prefs.getInt("zoom", 1).coerceIn(0, 2)]
        score.hand = hand
        score.onDragStart = { if (playing) pause() }
        score.onDrag = { d -> seekTo(curT + d, quiet = true) }
        score.onDragEnd = { }
        score.onTap = { if (playing) pause() else play() }
        keys.tl = tl
        keys.labels = prefs.getBoolean("keylabels", true)
        keys.hand = hand
        keys.onKey = { m, on -> onUserNote(m, on, 0.8f, false) }
        applyRange()
        speedBtn.setText("$speed %")
        modeBtn.setText(modes[mode])
        MidiIn.start(ctx)
        MidiIn.addListener(midiListener)
        MidiIn.onStatus = { refreshStatus() }
        rebuildStops()
        seekTo(0f)
        refreshStatus()
    }

    // ---------- armado ----------
    private fun buildMiddle() {
        middle.removeAllViews()
        (score.parent as? ViewGroup)?.removeView(score)
        (keys.parent as? ViewGroup)?.removeView(keys)
        (fluteV.parent as? ViewGroup)?.removeView(fluteV)
        if (flute) {
            val row = LinearLayout(ctx)
            row.addView(fluteV, lp(0, MATCH, 0.27f))
            row.addView(score, lp(0, MATCH, 0.73f))
            middle.addView(row, LayoutParams(MATCH, MATCH))
            return
        }
        val falling = prefs.getBoolean("falling", false)
        keys.falling = falling
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        if (falling) c.addView(keys, lp(MATCH, 0, 1f))
        else {
            c.addView(score, lp(MATCH, 0, 0.6f))
            c.addView(keys, lp(MATCH, 0, 0.4f).also { it.setMargins(ctx.dpi(6f), ctx.dpi(2f), ctx.dpi(6f), ctx.dpi(2f)) })
        }
        middle.addView(c, LayoutParams(MATCH, MATCH))
    }

    private fun applyRange() {
        val full = prefs.getBoolean("keys88", false)
        if (full) keys.setRange(21, 108)
        else {
            var lo = (tl.minMidi / 12) * 12; var hi = (tl.maxMidi / 12) * 12 + 11
            if (hi - lo < 35) { val extra = (36 - (hi - lo + 1)) / 12; lo -= 12 * ((extra + 1) / 2); hi += 12 * (extra / 2) }
            keys.setRange(max(21, lo), min(108, hi + 1))
        }
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
        rebuildStops()
        stopIdx = firstStopAt(curT)
        score.invalidate(); keys.invalidate()
        refreshStatus()
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

    private fun mine(e: Ev): Boolean = e.part == userPart && (flute || hand == 0 || (hand == 1 && e.staff == 0) || (hand == 2 && e.staff == 1))

    private fun rebuildStops() {
        stops.clear()
        for ((i, pe) in tl.events.withIndex()) if (!pe.e.rest && mine(pe.e) && pe.e.notes.any { !it.tieIn }) stops.add(i)
    }

    private fun firstStopAt(t: Float): Int {
        var lo = 0; var hi = stops.size
        while (lo < hi) { val mid = (lo + hi) / 2; if (tl.events[stops[mid]].t < t - 0.5f) lo = mid + 1 else hi = mid }
        return lo
    }

    /** Las notas de un instante que exige el modo espera (puede haber varios eventos a la vez). */
    private fun requiredAt(t: Int): Set<Int> {
        val s = HashSet<Int>()
        var i = stopIdx
        while (i < stops.size && tl.events[stops[i]].t == t) { for (n in tl.events[stops[i]].e.notes) if (!n.tieIn) s.add(n.midi); i++ }
        return s
    }

    // ---------- reloj ----------
    private fun ticksPerMs(): Float {
        val pm = tl.measures[tl.pmAt(max(0f, curT))]
        return song.bpmAt(pm.index) * speed / 100f * 480f / 60000f
    }

    fun play() {
        if (playing) return
        if (curT >= tl.total - 1) seekTo(if (loopA >= 0) loopA else 0f)
        playing = true
        playBtn.playing = true
        lastMs = SystemClock.uptimeMillis()
        if (countIn) {
            val pm = tl.measures[tl.pmAt(curT)].m
            countdown = if (pm.den == 8) pm.num / 3 else pm.num
            countdownBeat()
        } else handler.post(frame)
    }

    private fun countdownBeat() {
        if (!playing) return
        if (countdown <= 0) { lastMs = SystemClock.uptimeMillis(); handler.post(frame); statusV.text = ""; refreshStatus(); return }
        val pm = tl.measures[tl.pmAt(curT)].m
        val beatTicks = if (pm.den == 8) 720f else 1920f / pm.den
        val ms = (beatTicks / ticksPerMs()).toLong()
        host.synth.click(if (countdown == (if (pm.den == 8) pm.num / 3 else pm.num)) 2 else 1)
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
        // modo espera: detenerse en la siguiente nota tuya hasta que la toques
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
        // bucle
        if (loopA >= 0 && loopB > loopA && next >= loopB) {
            if (autoUp && speed < 150) { speed = min(150, speed + 5); speedBtn.setText("$speed %") }
            seekTo(loopA); return
        }
        // metronomo dentro de la cancion
        if (metroOn) {
            val pm = tl.measures[tl.pmAt(next)]
            val beat = if (pm.m.den == 8) 720 else 1920 / pm.m.den
            val bi = ((next - pm.t) / beat).toInt()
            val gb = pm.t / 10 + bi
            if (gb != lastBeat) { lastBeat = gb; host.synth.click(if (bi == 0) 2 else 1) }
        }
        curT = next
        // apagar lo que termina
        val it = active.iterator()
        while (it.hasNext()) { val a = it.next(); if (curT >= a.off) { host.synth.noteOff(a.key); it.remove() } }
        // encender lo que empieza
        while (schedIdx < tl.events.size && tl.events[schedIdx].t <= curT) { trigger(tl.events[schedIdx]); schedIdx++ }
        if (mode == 2) evaluate()
        updateLit()
        render()
        if (curT >= tl.total) {
            pause()
            if (mode == 2 && evalTotal > 0) showResult()
            seekTo(tl.total.toFloat())
        }
    }

    private fun trigger(pe: PEv) {
        val e = pe.e
        if (e.rest) return
        val isUser = e.part == userPart
        val audible = if (isUser) (if (mine(e)) mode == 0 && guide else true) else accVol < 3
        if (!audible) return
        val gain = if (isUser) 0.78f else floatArrayOf(0.7f, 0.45f, 0.25f, 0f)[accVol]
        for (n in e.notes) {
            if (n.tieIn) continue
            val k = keyCounter++
            val inst = if (flute) "flute" else if (!isUser && song.id == "listen" && n.midi >= 55) "flute" else "piano"
            val midi = if (inst == "flute" && flute) n.midi + 12 else n.midi
            host.synth.noteOn(k, inst, midi, gain)
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
        d.show()
    }

    // ---------- entradas del usuario ----------
    private fun onUserNote(m: Int, on: Boolean, vel: Float, fromMidi: Boolean) {
        if (on) {
            hitSince.add(m)
            pressTime[m] = SystemClock.uptimeMillis()
            if (!fromMidi) host.synth.play("piano", m, 700, vel)
        }
        if (fromMidi) { if (on) keys.pressed.add(m) else keys.pressed.remove(m) }
        keys.invalidate()
        if (on && mode == 1 && !playing) { }
    }

    private fun startMic() {
        host.ensureMic { ok ->
            if (!ok) { host.toast("Falta el permiso del micrófono"); return@ensureMic }
            if (detector != null) return@ensureMic
            val d = PitchDetector({ 440.0 }, { 0.012f }, 0.13f, true) { f ->
                // la flauta suena una octava arriba de lo escrito
                micMidi = if (f.voiced && f.clarity > 0.85f) Math.round(f.midi) - 12 else -1
                if (micMidi >= 0) { hitSince.add(micMidi); pressTime[micMidi] = SystemClock.uptimeMillis() }
            }
            if (d.start()) detector = d else host.toast("No se pudo abrir el micrófono")
        }
    }

    private fun stopMic() { detector?.stop(); detector = null; micMidi = -1 }

    // ---------- posicion ----------
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

    private fun skip(dir: Int) {
        val t = curT + dir * skipSec * 1000f * ticksPerMs()
        seekTo(t)
    }

    private fun updateLit() {
        keys.lit.clear()
        var i = tl.firstAt(curT - 1920 * 4)
        var next = -1
        while (i < tl.events.size && tl.events[i].t <= curT + 1) {
            val pe = tl.events[i]; i++
            if (pe.e.rest || pe.e.part != userPart) continue
            if (curT < pe.t + pe.e.sound) for (n in pe.e.notes) keys.lit[n.midi] = if (pe.e.staff == 0) 1 else 2
        }
        if (flute) {
            var cur = -1
            var j = tl.firstAt(curT - 1920 * 4)
            while (j < tl.events.size) {
                val pe = tl.events[j]; j++
                if (pe.e.rest || pe.e.part != userPart) continue
                if (pe.t <= curT && curT < pe.t + pe.e.sound) cur = pe.e.notes[0].midi
                if (pe.t > curT + 1) { next = pe.e.notes[0].midi; break }
            }
            if (cur < 0) { cur = next; next = -1; var k = tl.firstAt(curT + 1); var found = false
                while (k < tl.events.size) { val pe = tl.events[k]; k++; if (pe.e.rest || pe.e.part != userPart) continue; if (!found) { found = true; continue }; next = pe.e.notes[0].midi; break } }
            fluteV.midi = cur; fluteV.next = next
        }
    }

    private fun render() {
        score.curT = curT
        keys.curT = curT
        seek.value = curT / max(1, tl.total)
        val pm = tl.measures[tl.pmAt(curT)]
        measureV.text = "Compás ${pm.index + 1}"
        if (mode == 1) {
            if (waiting) {
                val req = requiredAt(tl.events[stops[stopIdx]].t)
                statusV.text = "Toca: " + req.sorted().joinToString(" ") { Names.pitchClass(it) }
                statusV.setTextColor(C.RED_HI)
            } else if (countdown <= 0) { statusV.text = "" }
        }
    }

    private fun refreshStatus() {
        val handTxt = if (flute) "Flauta dulce" else when (hand) { 1 -> "Mano derecha"; 2 -> "Mano izquierda"; else -> "Dos manos" }
        val midi = MidiIn.connectedName
        val bpm = song.bpmAt(tl.measures[tl.pmAt(curT)].index) * speed / 100
        subV.text = listOf(song.composer, handTxt, "$bpm bpm", if (!flute) (if (midi != null) "Piano USB: $midi" else "") else "").filter { it.isNotEmpty() }.joinToString(" · ")
        loopIcon.on = loopA >= 0
        if (mode != 1 && countdown <= 0 && !(mode == 2 && evalTotal > 0)) statusV.text = ""
    }

    private fun pickSpeed() {
        val vals = (25..150 step 5).toList()
        dropdown(speedBtn, vals.map { "$it %" + (if (it == 100) "  (original)" else "") }, vals.indexOf(speed)) { i ->
            speed = vals[i]
            prefs.edit().putInt("speed_" + song.id, speed).apply()
            speedBtn.setText("$speed %")
            refreshStatus()
        }
    }

    private fun pickLoop() {
        val items = listOf("2 compases desde aquí", "4 compases desde aquí", "8 compases desde aquí", "Desde aquí hasta el final", "Quitar bucle")
        dropdown(loopIcon, items, -1) { i ->
            if (i == 4) { loopA = -1f; loopB = -1f }
            else {
                val pi = tl.pmAt(curT)
                loopA = tl.measures[pi].t.toFloat()
                val n = intArrayOf(2, 4, 8, 100000)[i]
                val end = min(tl.measures.size, pi + n)
                loopB = if (end >= tl.measures.size) tl.total.toFloat() else tl.measures[end].t.toFloat()
                seekTo(loopA)
                host.toast("Bucle: compases ${tl.measures[pi].index + 1} a ${tl.measures[end - 1].index + 1}")
            }
            refreshStatus()
        }
    }

    private fun settings() {
        if (playing) pause()
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sv = FastScrollView(ctx)
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(ctx.dpi(20f), ctx.dpi(18f), ctx.dpi(26f), ctx.dpi(14f))
        box.background = roundRect(C.CARD, ctx.dp(22f))
        sv.setContent(box)
        box.addView(label(ctx, "AJUSTES", 15f, C.TEXT, Fonts.title).also { it.letterSpacing = 0.15f; it.setPadding(0, 0, 0, ctx.dpi(8f)) })
        fun section(t: String) { box.addView(label(ctx, t, 11.5f, C.RED_HI, Fonts.bold).also { it.letterSpacing = 0.12f; it.setPadding(0, ctx.dpi(14f), 0, ctx.dpi(4f)) }) }
        fun row(t: String, desc: String, opts: List<String>, sel: Int, pick: (Int) -> Unit) {
            val r = LinearLayout(ctx)
            r.orientation = LinearLayout.VERTICAL
            r.setPadding(0, ctx.dpi(6f), 0, ctx.dpi(6f))
            r.addView(label(ctx, t, 14.5f, C.TEXT, Fonts.medium))
            r.addView(label(ctx, desc, 12f, C.MUTED).also { it.setPadding(0, ctx.dpi(2f), 0, ctx.dpi(6f)) })
            r.addView(pickRow(ctx, opts, sel, pick))
            box.addView(r, lp(MATCH, WRAP))
        }
        val yn = listOf("Sí", "No")
        section("PARTITURA")
        row("Nombres de las notas", "Escribe Do, Re, Mi junto a cada nota.", yn, if (score.showNames) 0 else 1) { score.showNames = it == 0; prefs.edit().putBoolean("names", it == 0).apply(); score.invalidate() }
        row("Ancho de la partitura", "Más ancho separa las notas rápidas.", listOf("Compacto", "Normal", "Amplio"), prefs.getInt("zoom", 1)) { prefs.edit().putInt("zoom", it).apply(); score.zoom = floatArrayOf(0.75f, 1f, 1.35f)[it]; score.invalidate() }
        if (!flute) {
            section("TECLADO")
            row("Notas que caen", "Cambia la partitura por barras que bajan hacia cada tecla.", yn, if (prefs.getBoolean("falling", false)) 0 else 1) { prefs.edit().putBoolean("falling", it == 0).apply(); buildMiddle() }
            row("Teclas visibles", "Solo el rango de la canción (teclas más grandes) o las 88.", listOf("Canción", "88 teclas"), if (prefs.getBoolean("keys88", false)) 1 else 0) { prefs.edit().putBoolean("keys88", it == 1).apply(); applyRange() }
            row("Nombres en el teclado", "Marca cada Do con su octava.", yn, if (keys.labels) 0 else 1) { keys.labels = it == 0; prefs.edit().putBoolean("keylabels", it == 0).apply(); keys.invalidate() }
        }
        section("PRÁCTICA")
        row("Escuchar mi parte", "En modo Libre la app toca contigo lo que te toca a ti.", yn, if (guide) 0 else 1) { guide = it == 0; prefs.edit().putBoolean("guide", guide).apply() }
        row("Cuenta antes de empezar", "Un compás de clics para entrar a tiempo.", yn, if (countIn) 0 else 1) { countIn = it == 0; prefs.edit().putBoolean("countin", countIn).apply() }
        row("Subir velocidad en el bucle", "Cada vuelta del bucle sube 5 %, hasta 150 %.", yn, if (autoUp) 0 else 1) { autoUp = it == 0; prefs.edit().putBoolean("autoup", autoUp).apply() }
        if (song.hasAcc) row("Volumen del acompañamiento", "La otra parte que toca la app.", listOf("Igual", "Medio", "Bajo", "Apagado"), accVol) { accVol = it; prefs.edit().putInt("accvol", it).apply() }
        row("Salto de los botones", "Segundos que avanzan o retroceden.", listOf("5 s", "10 s", "15 s", "30 s"), listOf(5, 10, 15, 30).indexOf(skipSec).coerceAtLeast(0)) { skipSec = listOf(5, 10, 15, 30)[it]; prefs.edit().putInt("skip", skipSec).apply(); skipBack.seconds = skipSec; skipFwd.seconds = skipSec }
        val ok = FrameLayout(ctx)
        ok.background = roundRect(C.RED, ctx.dp(22f))
        ok.addView(label(ctx, "Listo", 15f, C.TEXT, Fonts.bold), LayoutParams(WRAP, WRAP, Gravity.CENTER))
        ok.tap { d.dismiss() }
        box.addView(ok, lp(MATCH, ctx.dpi(44f)).also { it.topMargin = ctx.dpi(12f) })
        d.setContentView(sv)
        d.window?.setBackgroundDrawable(ColorDrawable(0))
        val dm = ctx.resources.displayMetrics
        d.window?.setLayout(min(ctx.dpi(520f), (dm.widthPixels * 0.9f).toInt()), (dm.heightPixels * 0.92f).toInt())
        d.show()
    }

    fun release() {
        pause()
        stopMic()
        MidiIn.removeListener(midiListener)
        MidiIn.onStatus = null
        host.synth.allOff()
    }
}
