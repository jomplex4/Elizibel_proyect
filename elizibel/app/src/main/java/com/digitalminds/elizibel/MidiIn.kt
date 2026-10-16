package com.digitalminds.elizibel

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

/**
 * Piano digital por cable USB (OTG). Escucha las teclas que pulsas: nota encendida y apagada.
 * Sin librerias: usa android.media.midi. Si el telefono no tiene MIDI, simplemente no hace nada.
 */
object MidiIn {
    private val main = Handler(Looper.getMainLooper())
    private var mm: MidiManager? = null
    private var device: MidiDevice? = null
    private var port: MidiOutputPort? = null
    @Volatile var connectedName: String? = null
        private set
    /** Teclas sostenidas ahora mismo (midi). */
    val held = HashSet<Int>()
    private val listeners = ArrayList<(Int, Boolean, Int) -> Unit>()
    var onStatus: ((String?) -> Unit)? = null

    fun addListener(l: (Int, Boolean, Int) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Int, Boolean, Int) -> Unit) { listeners.remove(l) }

    fun start(ctx: Context) {
        if (mm != null) return
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) return
        val m = ctx.getSystemService(Context.MIDI_SERVICE) as? MidiManager ?: return
        mm = m
        try {
            @Suppress("DEPRECATION")
            for (info in m.devices) if (info.outputPortCount > 0) { open(info); break }
            @Suppress("DEPRECATION")
            m.registerDeviceCallback(object : MidiManager.DeviceCallback() {
                override fun onDeviceAdded(info: MidiDeviceInfo) { if (device == null && info.outputPortCount > 0) open(info) }
                override fun onDeviceRemoved(info: MidiDeviceInfo) {
                    if (device?.info?.id == info.id) close()
                }
            }, main)
        } catch (_: Exception) { }
    }

    private fun open(info: MidiDeviceInfo) {
        val m = mm ?: return
        try {
            m.openDevice(info, { d ->
                if (d == null) return@openDevice
                device = d
                val p = d.openOutputPort(0) ?: return@openDevice
                port = p
                p.connect(receiver)
                val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "Piano MIDI"
                connectedName = name
                main.post { onStatus?.invoke(name) }
            }, main)
        } catch (_: Exception) { }
    }

    private fun close() {
        try { port?.close() } catch (_: Exception) { }
        try { device?.close() } catch (_: Exception) { }
        port = null; device = null; connectedName = null
        main.post { held.clear(); onStatus?.invoke(null) }
    }

    private val receiver = object : MidiReceiver() {
        private var status = 0
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            var i = offset
            val end = offset + count
            while (i < end) {
                val b = msg[i].toInt() and 0xFF
                if (b >= 0xF8) { i++; continue }               // reloj y mensajes de tiempo real
                if (b >= 0x80) { status = b; i++ }
                val type = status and 0xF0
                if (type == 0x90 || type == 0x80) {
                    if (i + 1 >= end) break
                    val note = msg[i].toInt() and 0x7F
                    val vel = msg[i + 1].toInt() and 0x7F
                    i += 2
                    val on = type == 0x90 && vel > 0
                    main.post {
                        if (on) held.add(note) else held.remove(note)
                        for (l in ArrayList(listeners)) l(note, on, vel)
                    }
                } else if (type == 0xC0 || type == 0xD0) i += 1 else if (type == 0xB0 || type == 0xE0 || type == 0xA0) i += 2 else i++
            }
        }
    }
}
