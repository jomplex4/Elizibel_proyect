package com.digitalminds.elizibel

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import org.json.JSONObject
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Muestra grabada (PCM 16 bits) con su nota base, su afinacion fina y su zona de bucle. */
class Sample(val data: ShortArray, val root: Int, val ls: Int, val le: Int, val gain: Float, val cents: Float) {
    val looped get() = ls in 1 until le
}

/**
 * Banco de sonidos reales:
 * piano = Salamander Grand Piano V3 (Alexander Holm, CC BY 3.0), una muestra cada tercera menor.
 * flauta = flauta dulce soprano barroca de VCSL (Versilian Studios, CC0), con bucle para notas largas.
 * Se guardan en OGG y se decodifican al abrir la app (el APK queda liviano).
 */
object Bank {
    var rate = 32000
    private val inst = HashMap<String, Array<Sample?>>()   // una muestra por cada nota midi (la mas cercana)
    @Volatile var ready = false
    @Volatile var progress = 0f

    fun load(ctx: Context) {
        if (ready) return
        try {
            val idx = JSONObject(ctx.assets.open("sounds/index.json").bufferedReader().use { it.readText() })
            rate = idx.getInt("rate")
            val io = idx.getJSONObject("inst")
            var total = 0; var done = 0
            for (name in io.keys()) total += io.getJSONArray(name).length()
            for (name in io.keys()) {
                val arr = io.getJSONArray(name)
                val list = ArrayList<Sample>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val pcm = decode(ctx, "sounds/" + o.getString("file"))
                    if (pcm != null) list.add(Sample(pcm, o.getInt("root"), o.optInt("ls", -1), o.optInt("le", -1), o.optDouble("gain", 1.0).toFloat(), o.optDouble("cents", 0.0).toFloat()))
                    done++; progress = done.toFloat() / total
                }
                val table = arrayOfNulls<Sample>(128)
                for (m in 0 until 128) table[m] = list.minByOrNull { kotlin.math.abs(it.root - m) + (if (it.root < m) 0.1 else 0.0) }
                inst[name] = table
            }
            ready = true
        } catch (e: Exception) {
            ready = false
        }
    }

    fun pick(name: String, midi: Int): Sample? = inst[name]?.get(midi.coerceIn(0, 127))

    /** Decodifica un OGG del APK a PCM mono 16 bits con los decodificadores del propio Android. */
    private fun decode(ctx: Context, path: String): ShortArray? {
        val afd = ctx.assets.openFd(path)
        val ex = MediaExtractor()
        try {
            ex.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            if (ex.trackCount < 1) return null
            ex.selectTrack(0)
            val fmt = ex.getTrackFormat(0)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            val chans = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val out = java.io.ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var guard = 0
            while (!outDone && guard < 100000) {
                guard++
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    if (info.size > 0) {
                        val b = ByteArray(info.size)
                        ob.position(info.offset); ob.get(b)
                        out.write(b)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            codec.stop(); codec.release()
            val bytes = out.toByteArray()
            val sb = java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val all = ShortArray(sb.remaining()); sb.get(all)
            if (chans <= 1) return all
            val mono = ShortArray(all.size / chans)
            for (i in mono.indices) { var s = 0; for (c in 0 until chans) s += all[i * chans + c]; mono[i] = (s / chans).toShort() }
            return mono
        } catch (e: Exception) {
            return null
        } finally {
            ex.release()
            afd.close()
        }
    }
}

/**
 * Motor de audio de ELIZIBEL: hasta 48 voces simultaneas con muestras reales, apagado suave
 * (sin chasquidos), pedal de resonancia y metronomo independiente contado por muestras.
 */
class Synth {
    companion object { const val RATE = 44100 }

    private class Cmd(val type: Int, val key: Int = 0, val midi: Int = 0, val vel: Float = 1f, val inst: String = "", val ms: Int = 0)

    private class Voice {
        var s: Sample? = null
        var pos = 0.0
        var step = 0.0
        var gain = 1f
        var env = 0f
        var atk = 0f
        var rel = 0f
        var releasing = false
        var key = -1
        var active = false
        var autoOff = 0
        var flute = false
        var lp = 0f
        var lpk = 1f
    }

    private val queue = ConcurrentLinkedQueue<Cmd>()
    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    @Volatile var volume = 0.9f
    @Volatile private var muted = false
    @Volatile private var clearReq = false
    @Volatile var metroOn = false
    @Volatile var metroBpm = 100
    @Volatile var metroBeats = 4
    @Volatile var metroSub = 1
    @Volatile var metroAccent = true
    @Volatile var metroTick = 0
    @Volatile var metroBeatIndex = 0
    @Volatile var metroLevel = 1f

    fun start() {
        if (running) return
        try {
            val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(max(minBuf, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            track = t
            running = true
            t.play()
            thread = Thread({ render(t) }, "elizibel-audio").also { it.priority = Thread.MAX_PRIORITY; it.start() }
        } catch (e: Exception) {
            running = false
        }
    }

    fun stop() {
        running = false
        try { thread?.join(400) } catch (_: InterruptedException) { }
        thread = null
        try { track?.stop() } catch (_: IllegalStateException) { }
        track?.release()
        track = null
    }

    /** key identifica la nota para apagarla despues. midi = altura que suena. */
    fun noteOn(key: Int, inst: String, midi: Int, vel: Float) { queue.add(Cmd(1, key, midi, vel, inst)) }
    fun noteOff(key: Int) { queue.add(Cmd(2, key)) }
    fun allOff() { queue.add(Cmd(5)) }
    /** Silencia con rampa corta y descarta todo lo que suena; al quitarlo el audio vuelve limpio. */
    fun mute(on: Boolean) { if (on) { muted = true; clearReq = true } else muted = false }
    /** Nota corta de prueba (teclado, explorador, tablas). */
    fun play(inst: String, midi: Int, ms: Int = 900, vel: Float = 0.9f) { queue.add(Cmd(4, -2, midi, vel, inst, ms)) }
    /** Golpe de metronomo: 2 = acento, 1 = pulso, 0 = subdivision. */
    fun click(level: Int) { queue.add(Cmd(3, 0, level)) }

    private fun render(t: AudioTrack) {
        val n = 256
        val buf = ShortArray(n)
        val mix = FloatArray(n)
        val voices = Array(48) { Voice() }
        var clickPos = -1; var clickFreq = 1500.0; var clickAmp = 0.0
        var metroSamples = 0.0; var metroCount = 0; var wasMetro = false
        var gate = 1f
        val gateStep = 1f / (0.012f * RATE)

        fun freeVoice(): Voice {
            for (v in voices) if (!v.active) return v
            var best = voices[0]
            for (v in voices) if ((v.releasing && !best.releasing) || (v.releasing == best.releasing && v.env < best.env)) best = v
            return best
        }
        fun startNote(key: Int, inst: String, midi: Int, vel: Float, autoMs: Int) {
            if (key >= 0) for (v in voices) if (v.active && v.key == key && !v.releasing) v.releasing = true
            val s = Bank.pick(inst, midi) ?: return
            val v = freeVoice()
            val flute = inst == "flute"
            v.s = s; v.pos = 0.0
            v.step = Bank.rate.toDouble() / RATE * 2.0.pow((midi - s.root - s.cents / 100.0) / 12.0)
            // la dinamica cambia volumen y brillo, como en un piano real
            val vv = vel.coerceIn(0.05f, 1f)
            v.gain = s.gain * (0.25f + 0.75f * vv)
            v.lpk = if (flute) 1f else (0.25f + 0.75f * vv).coerceAtMost(1f)
            v.lp = 0f
            v.env = 0f; v.releasing = false; v.key = key; v.active = true; v.flute = flute
            v.atk = 1f / ((if (flute) 0.018f else 0.002f) * RATE)
            v.rel = 1f / ((if (flute) 0.07f else if (midi > 88) 0.5f else 0.28f) * RATE)
            v.autoOff = if (autoMs > 0) autoMs * RATE / 1000 else 0
        }

        while (running) {
            if (clearReq && muted && gate <= 0f) {
                clearReq = false
                queue.clear()
                for (v in voices) v.active = false
                clickPos = -1
            }
            var c = queue.poll()
            while (c != null) {
                when (c.type) {
                    1 -> startNote(c.key, c.inst, c.midi, c.vel, 0)
                    2 -> for (v in voices) if (v.active && v.key == c.key) v.releasing = true
                    3 -> { clickPos = 0; val lv = c.midi; clickFreq = if (lv >= 2) 2000.0 else if (lv == 1) 1400.0 else 1000.0; clickAmp = (if (lv >= 2) 0.8 else if (lv == 1) 0.55 else 0.3) * metroLevel }
                    4 -> startNote(-2, c.inst, c.midi, c.vel, c.ms)
                    5 -> for (v in voices) if (v.active) v.releasing = true
                }
                c = queue.poll()
            }
            java.util.Arrays.fill(mix, 0f)
            for (v in voices) {
                if (!v.active) continue
                val s = v.s ?: continue
                val d = s.data
                for (i in 0 until n) {
                    if (v.autoOff > 0) { v.autoOff--; if (v.autoOff == 0) v.releasing = true }
                    v.env = if (v.releasing) v.env - v.rel else min(1f, v.env + v.atk)
                    if (v.env <= 0f && v.releasing) { v.active = false; break }
                    val ip = v.pos.toInt()
                    if (ip + 1 >= d.size) { v.active = false; break }
                    val fr = (v.pos - ip).toFloat()
                    var smp = (d[ip] + (d[ip + 1] - d[ip]) * fr) / 32768f
                    if (v.lpk < 1f) { v.lp += (smp - v.lp) * v.lpk; smp = v.lp }
                    mix[i] += smp * v.gain * v.env
                    v.pos += v.step
                    if (s.looped && v.pos >= s.le - 1) v.pos -= (s.le - s.ls)
                }
            }
            val mOn = metroOn
            if (mOn && !wasMetro) { metroSamples = 0.0; metroCount = 0 }
            wasMetro = mOn
            val interval = RATE * 60.0 / max(20, metroBpm) / max(1, metroSub)
            for (i in 0 until n) {
                if (mOn) {
                    if (metroSamples <= 0.0) {
                        val sub = max(1, metroSub)
                        val within = metroCount % sub
                        val beatNo = metroCount / sub
                        val lv = if (within != 0) 0 else if (metroAccent && beatNo % max(1, metroBeats) == 0) 2 else 1
                        clickPos = 0
                        clickFreq = if (lv >= 2) 2000.0 else if (lv == 1) 1400.0 else 1000.0
                        clickAmp = (if (lv >= 2) 0.85 else if (lv == 1) 0.6 else 0.3) * metroLevel
                        if (within == 0) { metroBeatIndex = beatNo % max(1, metroBeats); metroTick++ }
                        metroCount++
                        metroSamples += interval
                    }
                    metroSamples -= 1.0
                }
                if (clickPos >= 0) {
                    val tt = clickPos.toDouble() / RATE
                    mix[i] += (sin(2.0 * PI * clickFreq * tt) * exp(-tt * 150.0) * clickAmp).toFloat()
                    clickPos++
                    if (clickPos > RATE * 0.05) clickPos = -1
                }
            }
            val vol = volume
            for (i in 0 until n) {
                if (muted) { if (gate > 0f) gate = max(0f, gate - gateStep) } else if (gate < 1f) gate = min(1f, gate + gateStep)
                var o = mix[i] * vol * gate
                // limitador suave: muchos acordes juntos no saturan
                o = if (o > 0.8f) 0.8f + (o - 0.8f) / (1f + (o - 0.8f) * 5f) else if (o < -0.8f) -0.8f + (o + 0.8f) / (1f - (o + 0.8f) * 5f) else o
                o = o.coerceIn(-1f, 1f)
                buf[i] = (o * 32000f).toInt().toShort()
            }
            if (t.write(buf, 0, n) < 0) break
        }
    }
}

fun midiToFreq(midi: Double, a4: Double = 440.0): Double = a4 * Math.pow(2.0, (midi - 69.0) / 12.0)
