package com.digitalminds.elizibel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Una cabeza de nota: altura real (midi), posicion escrita (diatonica), alteracion, alteracion mostrada, ligaduras y octava (8va). */
class NoteH(val midi: Int, val dia: Int, val alt: Int, val shown: Int, val tie: Int, val oct: Int) {
    val tieIn get() = tie and 1 != 0
    val tieOut get() = tie and 2 != 0
}

/**
 * Evento de la partitura (nota, acorde o silencio). tick y dur en ticks (480 por negra).
 * staff 0 = pentagrama de arriba (mano derecha), 1 = de abajo (mano izquierda).
 * part 0 = la parte que practicas, 1 = acompañamiento.
 */
class Ev(
    val tick: Int, val dur: Int, val staff: Int, val stem: Int, val beam: Int, val dots: Int, val tup: Int,
    val part: Int, val rest: Boolean, val notes: Array<NoteH>, val base: Int
) {
    /** Duracion real que suena, sumando las ligaduras de prolongacion. */
    var sound = dur
}

class Measure(val num: Int, val den: Int, val key: Int, val start: Int, val len: Int)

class SongInfo(val id: String, val title: String, val composer: String, val inst: String, val level: Int, val group: String, val arr: String)

class Song(
    val id: String, val title: String, val composer: String, val arr: String, val inst: String, val level: Int,
    val bpm: Int, val tempo: List<IntArray>, val partNames: List<String>, val measures: List<Measure>,
    val order: List<Int>, val ev: List<Ev>
) {
    val flute get() = inst == "flute"
    val staves: Int = if (flute) 1 else 2
    val hasAcc = ev.any { it.part == 1 }

    /** Tempo del compas i (indice en la partitura). */
    fun bpmAt(mi: Int): Int {
        var b = bpm
        for (t in tempo) if (t[0] <= mi) b = t[1]
        return b
    }
}

object Library {
    fun index(ctx: Context): List<SongInfo> {
        val arr = JSONArray(ctx.assets.open("songs/index.json").bufferedReader().use { it.readText() })
        val out = ArrayList<SongInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(SongInfo(o.getString("id"), o.getString("title"), o.optString("composer"), o.getString("inst"), o.optInt("level", 1), o.optString("group", "main"), o.optString("arr")))
        }
        return out
    }

    fun load(ctx: Context, id: String): Song {
        val o = JSONObject(ctx.assets.open("songs/$id.json").bufferedReader().use { it.readText() })
        val ms = ArrayList<Measure>()
        val ma = o.getJSONArray("measures")
        for (i in 0 until ma.length()) {
            val m = ma.getJSONArray(i)
            ms.add(Measure(m.getInt(0), m.getInt(1), m.getInt(2), m.getInt(3), m.getInt(4)))
        }
        val ev = ArrayList<Ev>()
        val ea = o.getJSONArray("ev")
        for (i in 0 until ea.length()) {
            val e = ea.getJSONArray(i)
            val na = e.getJSONArray(9)
            val notes = Array(na.length()) {
                val n = na.getJSONArray(it)
                NoteH(n.getInt(0), n.getInt(1), n.getInt(2), n.getInt(3), n.getInt(4), n.getInt(5))
            }
            val base = if (e.length() > 10) e.getInt(10) else e.getInt(1)
            ev.add(Ev(e.getInt(0), e.getInt(1), e.getInt(2), e.getInt(3), e.getInt(4), e.getInt(5), e.getInt(6), e.getInt(7), e.getInt(8) == 1, notes, base))
        }
        ev.sortWith(compareBy({ it.tick }, { it.staff }))
        linkTies(ev)
        val tempo = ArrayList<IntArray>()
        o.optJSONArray("tempo")?.let { t -> for (i in 0 until t.length()) tempo.add(intArrayOf(t.getJSONArray(i).getInt(0), t.getJSONArray(i).getInt(1))) }
        val order = ArrayList<Int>()
        val oa = o.optJSONArray("order")
        if (oa != null) for (i in 0 until oa.length()) order.add(oa.getInt(i)) else for (i in ms.indices) order.add(i)
        val pn = ArrayList<String>()
        o.optJSONArray("partNames")?.let { for (i in 0 until it.length()) pn.add(it.getString(i)) }
        return Song(o.getString("id"), o.getString("title"), o.optString("composer"), o.optString("arr"), o.getString("inst"), o.optInt("level", 1),
            o.optInt("bpm", 90), tempo, pn, ms, order, ev)
    }

    /** Las notas ligadas no se vuelven a atacar: su duracion pasa a la primera. */
    private fun linkTies(ev: List<Ev>) {
        val open = HashMap<Long, Ev>()
        for (e in ev) {
            if (e.rest) continue
            for (n in e.notes) {
                val k = (e.part.toLong() shl 40) or (e.staff.toLong() shl 32) or n.midi.toLong()
                if (n.tieIn) {
                    val first = open[k]
                    if (first != null) first.sound = maxOf(first.sound, e.tick + e.dur - first.tick)
                }
                if (n.tieOut) { if (!n.tieIn || open[k] == null) open[k] = e } else open.remove(k)
            }
        }
    }
}

/** Nota de la reproduccion: evento con su tiempo en la linea de reproduccion (las repeticiones se despliegan). */
class PEv(val e: Ev, val t: Int)

/** Compas desplegado en la linea de reproduccion. */
class PMeasure(val m: Measure, val index: Int, val t: Int)

/** Linea de tiempo de reproduccion: aplica el orden de compases (repeticiones). */
class Timeline(val song: Song) {
    val measures = ArrayList<PMeasure>()
    val events = ArrayList<PEv>()
    val total: Int

    init {
        var t = 0
        val byMeasure = HashMap<Int, ArrayList<Ev>>()
        for (e in song.ev) {
            val mi = measureOf(e.tick)
            byMeasure.getOrPut(mi) { ArrayList() }.add(e)
        }
        for (mi in song.order) {
            val m = song.measures[mi]
            measures.add(PMeasure(m, mi, t))
            for (e in byMeasure[mi] ?: emptyList<Ev>()) events.add(PEv(e, t + e.tick - m.start))
            t += m.len
        }
        total = t
        events.sortWith(compareBy({ it.t }, { it.e.staff }))
    }

    private fun measureOf(tick: Int): Int {
        var lo = 0; var hi = song.measures.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (song.measures[mid].start <= tick) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Indice del compas desplegado que contiene t. */
    fun pmAt(t: Float): Int {
        var lo = 0; var hi = measures.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (measures[mid].t <= t) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Primer evento con t >= x. */
    fun firstAt(x: Float): Int {
        var lo = 0; var hi = events.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (events[mid].t < x) lo = mid + 1 else hi = mid
        }
        return lo
    }

    val minMidi: Int by lazy { song.ev.filter { !it.rest }.minOfOrNull { e -> e.notes.minOf { it.midi } } ?: 60 }
    val maxMidi: Int by lazy { song.ev.filter { !it.rest }.maxOfOrNull { e -> e.notes.maxOf { it.midi } } ?: 72 }
}

object Names {
    private val solf = arrayOf("Do", "Do#", "Re", "Mib", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "Sib", "Si")
    private val diaN = arrayOf("Do", "Re", "Mi", "Fa", "Sol", "La", "Si")
    fun pitchClass(midi: Int): String = solf[((midi % 12) + 12) % 12]
    fun withOctave(midi: Int): String = pitchClass(midi) + (Math.floorDiv(midi, 12) - 1)
    /** Nombre escrito: usa la posicion y la alteracion de la partitura (Do# o Reb segun corresponda). */
    fun spelled(dia: Int, alt: Int): String {
        val a = when (alt) { 1 -> "#"; -1 -> "b"; 2 -> "x"; -2 -> "bb"; else -> "" }
        return diaN[((dia % 7) + 7) % 7] + a
    }
    fun isBlack(midi: Int): Boolean = when (((midi % 12) + 12) % 12) { 1, 3, 6, 8, 10 -> true; else -> false }
}
