package com.digitalminds.elizibel

import android.app.Dialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

fun logoView(ctx: Context, heightDp: Float): ImageView {
    val iv = ImageView(ctx)
    val bmp = ctx.assets.open("dm_logo.png").use { BitmapFactory.decodeStream(it) }
    iv.setImageBitmap(bmp)
    iv.adjustViewBounds = true
    iv.scaleType = ImageView.ScaleType.FIT_START
    iv.layoutParams = LinearLayout.LayoutParams((ctx.dp(heightDp) * bmp.width / bmp.height).toInt(), ctx.dpi(heightDp))
    return iv
}

/** Icono de texto: usa Bravura (musica) o Space Grotesk. */
class GlyphIcon(ctx: Context, private val text: String, private val music: Boolean, private val boxed: Boolean = false, private val sizeDp: Float = 30f) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        p.color = C.TEXT
        p.textAlign = Paint.Align.CENTER
        p.typeface = if (music) Fonts.music else Fonts.bold
        p.textSize = context.dp(sizeDp)
        val cx = width / 2f
        val cy = height / 2f
        if (boxed) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = context.dp(1.6f)
            r.set(cx - context.dp(16f), cy - context.dp(16f), cx + context.dp(16f), cy + context.dp(16f))
            c.drawRect(r, p)
        }
        p.style = Paint.Style.FILL
        val base = if (music) cy + p.textSize * 0.12f else cy + p.textSize * 0.35f
        c.drawText(text, cx, base, p)
    }
}

/** Dibujos para simbolos que no son un caracter de la fuente. */
class ShapeIcon(ctx: Context, private val kind: Int) : View(ctx) {
    companion object { const val TIE = 0; const val SLUR = 1; const val TUPLET = 2; const val VOLTA = 3; const val REPEAT = 4; const val HAIRPIN = 5; const val HAIRPIN_CLOSE = 6 }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h / 2
        val u = context.dp(1f)
        p.color = C.TEXT
        p.strokeWidth = u * 2f
        p.strokeCap = Paint.Cap.ROUND
        p.style = Paint.Style.FILL
        when (kind) {
            TIE, SLUR -> {
                val y = cy + u * 6
                p.textSize = u * 34
                p.typeface = Fonts.music
                p.textAlign = Paint.Align.CENTER
                val a = if (kind == TIE) 0xE0A4 else 0xE0A4
                c.drawText(String(Character.toChars(a)), cx - u * 18, y, p)
                c.drawText(String(Character.toChars(a)), cx + u * 18, y - (if (kind == SLUR) u * 6 else 0f), p)
                p.style = Paint.Style.STROKE
                path.reset()
                path.moveTo(cx - u * 18, y + u * 6)
                path.quadTo(cx, y + u * 20, cx + u * 18, y + u * 6 - (if (kind == SLUR) u * 6 else 0f))
                c.drawPath(path, p)
            }
            TUPLET -> {
                p.style = Paint.Style.STROKE
                c.drawLine(cx - u * 24, cy + u * 4, cx - u * 8, cy + u * 4, p)
                c.drawLine(cx + u * 8, cy + u * 4, cx + u * 24, cy + u * 4, p)
                c.drawLine(cx - u * 24, cy + u * 4, cx - u * 24, cy + u * 11, p)
                c.drawLine(cx + u * 24, cy + u * 4, cx + u * 24, cy + u * 11, p)
                p.style = Paint.Style.FILL
                p.typeface = Fonts.bold
                p.textAlign = Paint.Align.CENTER
                p.textSize = u * 17
                c.drawText("3", cx, cy + u * 10, p)
            }
            VOLTA -> {
                p.style = Paint.Style.STROKE
                c.drawLine(cx - u * 26, cy - u * 6, cx + u * 22, cy - u * 6, p)
                c.drawLine(cx - u * 26, cy - u * 6, cx - u * 26, cy + u * 8, p)
                p.style = Paint.Style.FILL
                p.typeface = Fonts.medium
                p.textAlign = Paint.Align.LEFT
                p.textSize = u * 15
                c.drawText("1.", cx - u * 21, cy + u * 8, p)
            }
            REPEAT -> {
                c.drawRect(cx - u * 4, cy - u * 18, cx - u * 1, cy + u * 18, p)
                c.drawRect(cx - u * 10, cy - u * 18, cx - u * 8.5f, cy + u * 18, p)
                c.drawCircle(cx - u * 15, cy - u * 6, u * 2.2f, p)
                c.drawCircle(cx - u * 15, cy + u * 6, u * 2.2f, p)
                c.drawRect(cx + u * 6, cy - u * 18, cx + u * 7.5f, cy + u * 18, p)
                c.drawRect(cx + u * 10, cy - u * 18, cx + u * 13, cy + u * 18, p)
                c.drawCircle(cx + u * 18, cy - u * 6, u * 2.2f, p)
                c.drawCircle(cx + u * 18, cy + u * 6, u * 2.2f, p)
            }
            HAIRPIN -> {
                p.style = Paint.Style.STROKE
                c.drawLine(cx - u * 26, cy, cx + u * 26, cy - u * 9, p)
                c.drawLine(cx - u * 26, cy, cx + u * 26, cy + u * 9, p)
            }
            HAIRPIN_CLOSE -> {
                p.style = Paint.Style.STROKE
                c.drawLine(cx - u * 26, cy - u * 9, cx + u * 26, cy, p)
                c.drawLine(cx - u * 26, cy + u * 9, cx + u * 26, cy, p)
            }
        }
    }
}

/** Pantalla con barra superior y boton atras, usada para los detalles de Aprender. */
class DetailFrame(ctx: Context, title: String, content: View, compact: Boolean = false, onBack: () -> Unit) : LinearLayout(ctx) {
    init {
        orientation = VERTICAL
        setBackgroundColor(C.BG)
        val bar = LinearLayout(ctx)
        bar.gravity = Gravity.CENTER_VERTICAL
        val back = FrameLayout(ctx)
        back.addView(IconView(ctx, IconView.BACK), FrameLayout.LayoutParams(ctx.dpi(28f), ctx.dpi(28f), Gravity.CENTER))
        back.tap { onBack() }
        bar.addView(back, lp(ctx.dpi(48f), ctx.dpi(if (compact) 38f else 48f)))
        bar.addView(if (compact) label(ctx, title.uppercase(), 12.5f, C.MUTED, Fonts.title) else label(ctx, title, 20f, C.TEXT, Fonts.bold), lp(WRAP, WRAP))
        addView(bar, lp(MATCH, ctx.dpi(if (compact) 38f else 52f)))
        addView(content, lp(MATCH, 0, 1f))
    }
}

fun card(ctx: Context): LinearLayout {
    val l = LinearLayout(ctx)
    l.orientation = LinearLayout.HORIZONTAL
    l.gravity = Gravity.CENTER_VERTICAL
    l.background = roundRect(C.CARD, ctx.dp(18f))
    l.setPadding(ctx.dpi(14f), ctx.dpi(14f), ctx.dpi(14f), ctx.dpi(14f))
    return l
}

fun cardLp(ctx: Context): LinearLayout.LayoutParams {
    val l = lp(MATCH, WRAP)
    l.setMargins(0, 0, 0, ctx.dpi(10f))
    return l
}

fun scrollOf(ctx: Context, build: (LinearLayout) -> Unit): FastScrollView {
    val fs = FastScrollView(ctx)
    val col = LinearLayout(ctx)
    col.orientation = LinearLayout.VERTICAL
    col.setPadding(ctx.dpi(16f), ctx.dpi(6f), ctx.dpi(24f), ctx.dpi(24f))
    build(col)
    fs.setContent(col)
    return fs
}

/** Selector segmentado estilo COMET: fondo grafito y pastilla roja activa. */
fun segmented(ctx: Context, names: List<String>, selected: Int, textSp: Float, padV: Float, onPick: (Int) -> Unit): LinearLayout {
    val pill = LinearLayout(ctx)
    pill.background = roundRect(C.CARD2, ctx.dp(24f))
    pill.setPadding(ctx.dpi(3f), ctx.dpi(3f), ctx.dpi(3f), ctx.dpi(3f))
    for ((i, n) in names.withIndex()) {
        val t = label(ctx, n, textSp, if (i == selected) C.TEXT else C.MUTED, if (i == selected) Fonts.bold else Fonts.medium)
        t.gravity = Gravity.CENTER
        t.maxLines = 1
        t.setPadding(ctx.dpi(4f), ctx.dpi(padV), ctx.dpi(4f), ctx.dpi(padV))
        if (i == selected) t.background = roundRect(C.RED, ctx.dp(20f))
        t.tap { onPick(i) }
        pill.addView(t, lp(0, WRAP, 1f))
    }
    return pill
}

/** Selector segmentado que se repinta solo al tocar (no hace falta reconstruir la pantalla). */
fun liveSegmented(ctx: Context, names: List<String>, selected: Int, textSp: Float, padV: Float, onPick: (Int) -> Unit): LinearLayout {
    val pill = LinearLayout(ctx)
    pill.background = roundRect(C.CARD2, ctx.dp(24f))
    pill.setPadding(ctx.dpi(3f), ctx.dpi(3f), ctx.dpi(3f), ctx.dpi(3f))
    val tvs = ArrayList<TextView>()
    fun paint(sel: Int) {
        for ((i, t) in tvs.withIndex()) {
            t.setTextColor(if (i == sel) C.TEXT else C.MUTED)
            t.typeface = if (i == sel) Fonts.bold else Fonts.medium
            t.background = if (i == sel) roundRect(C.RED, ctx.dp(20f)) else null
        }
    }
    for ((i, n) in names.withIndex()) {
        val t = label(ctx, n, textSp, C.MUTED, Fonts.medium)
        t.gravity = Gravity.CENTER
        t.maxLines = 1
        t.setPadding(ctx.dpi(4f), ctx.dpi(padV), ctx.dpi(4f), ctx.dpi(padV))
        t.tap { paint(i); onPick(i) }
        tvs.add(t)
        pill.addView(t, lp(0, WRAP, 1f))
    }
    paint(selected)
    return pill
}

/** Selector de instrumento con los cinco a la vista: Bajo, Trompeta, Saxo, Trombón y Tuba. */
/** Fila de pastillas de seleccion (una activa). */
fun pickRow(ctx: Context, names: List<String>, selected: Int, onPick: (Int) -> Unit): View {
    val sc = HorizontalScrollView(ctx)
    sc.isHorizontalScrollBarEnabled = false
    val r = LinearLayout(ctx)
    val chips = ArrayList<Chip>()
    for ((i, n) in names.withIndex()) {
        val c = Chip(ctx, n); c.setActive(i == selected)
        c.tap { for ((k, x) in chips.withIndex()) x.setActive(k == i); onPick(i) }
        chips.add(c)
        r.addView(c, lp(WRAP, WRAP).also { it.setMargins(0, 0, ctx.dpi(6f), 0) })
    }
    sc.addView(r)
    return sc
}

/** Pastillas en filas centradas (sin desplazamiento lateral): nunca se salen de la pantalla. */
fun pickGrid(ctx: Context, names: List<String>, selected: Int, perRow: Int, onPick: (Int) -> Unit): LinearLayout {
    val col = LinearLayout(ctx)
    col.orientation = LinearLayout.VERTICAL
    col.gravity = Gravity.CENTER_HORIZONTAL
    val chips = ArrayList<Chip>()
    var row: LinearLayout? = null
    for ((i, n) in names.withIndex()) {
        if (i % perRow == 0) { row = LinearLayout(ctx); row.gravity = Gravity.CENTER; col.addView(row, lp(MATCH, WRAP).also { it.topMargin = if (i > 0) ctx.dpi(6f) else 0 }) }
        val c = Chip(ctx, n); c.setActive(i == selected)
        c.textSize = 13f
        c.tap { for ((k, x) in chips.withIndex()) x.setActive(k == i); onPick(i) }
        chips.add(c)
        row!!.addView(c, lp(WRAP, WRAP).also { it.setMargins(ctx.dpi(3f), 0, ctx.dpi(3f), 0) })
    }
    return col
}

/** Titulo de seccion con la letra de pelicula (Michroma). */
fun headline(ctx: Context, text: String, sp: Float = 18f): TextView {
    val t = label(ctx, text.uppercase(), sp, C.TEXT, Fonts.title)
    t.letterSpacing = 0.06f
    return t
}

/** Ventana simple con un titulo y un texto. */
fun infoDialog(ctx: Context, title: String, text: String) {
    val d = Dialog(ctx)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val box = LinearLayout(ctx)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(ctx.dpi(22f), ctx.dpi(20f), ctx.dpi(22f), ctx.dpi(12f))
    box.background = roundRect(C.CARD, ctx.dp(22f))
    box.addView(label(ctx, title, 18f, C.TEXT, Fonts.bold))
    box.addView(label(ctx, text, 14f, C.MUTED).also { it.setLineSpacing(0f, 1.2f); it.setPadding(0, ctx.dpi(10f), 0, ctx.dpi(8f)) })
    val ok = label(ctx, "Entendido", 15f, C.RED_HI, Fonts.bold)
    ok.gravity = Gravity.END
    ok.setPadding(0, ctx.dpi(8f), 0, ctx.dpi(6f))
    ok.tap { d.dismiss() }
    box.addView(ok, lp(MATCH, WRAP))
    d.setContentView(box)
    d.window?.setBackgroundDrawable(ColorDrawable(0))
    val dm = ctx.resources.displayMetrics
    d.window?.setLayout(min(ctx.dpi(440f), (dm.widthPixels * 0.9f).toInt()), WRAP)
    d.showClean()
}

/** Signo de interrogacion en un circulo: al tocarlo explica. */
fun helpDot(ctx: Context, title: String, text: String): View {
    val f = FrameLayout(ctx)
    f.addView(IconView(ctx, IconView.HELP).also { it.color = C.MUTED }, FrameLayout.LayoutParams(ctx.dpi(20f), ctx.dpi(20f), Gravity.CENTER))
    f.tap { infoDialog(ctx, title, text) }
    return f
}

fun songCard(ctx: Context, title: String, sub: String, extra: String, icon: View, help: Pair<String, String>? = null, onOpen: () -> Unit): LinearLayout {
    val c = card(ctx)
    val ib = FrameLayout(ctx)
    ib.background = roundRect(C.CARD2, ctx.dp(14f))
    ib.addView(icon, FrameLayout.LayoutParams(ctx.dpi(28f), ctx.dpi(28f), Gravity.CENTER))
    c.addView(ib, lp(ctx.dpi(50f), ctx.dpi(50f)))
    val tc = LinearLayout(ctx)
    tc.orientation = LinearLayout.VERTICAL
    tc.setPadding(ctx.dpi(14f), 0, ctx.dpi(8f), 0)
    val tr = LinearLayout(ctx)
    tr.gravity = Gravity.CENTER_VERTICAL
    tr.addView(label(ctx, title, 17f, C.TEXT, Fonts.bold), lp(0, WRAP, 1f).also { it.weight = 0f; it.width = WRAP })
    if (help != null) tr.addView(helpDot(ctx, help.first, help.second), lp(ctx.dpi(34f), ctx.dpi(28f)))
    tc.addView(tr)
    tc.addView(label(ctx, sub, 13f, C.MUTED).also { it.setPadding(0, ctx.dpi(3f), 0, 0) })
    if (extra.isNotEmpty()) tc.addView(label(ctx, extra, 12f, C.RED_HI, Fonts.medium).also { it.setPadding(0, ctx.dpi(3f), 0, 0) })
    c.addView(tc, lp(0, WRAP, 1f))
    val pb = FrameLayout(ctx)
    pb.background = roundRect(C.RED, ctx.dp(22f))
    pb.addView(IconView(ctx, IconView.PLAY), FrameLayout.LayoutParams(ctx.dpi(20f), ctx.dpi(20f), Gravity.CENTER))
    c.addView(pb, lp(ctx.dpi(42f), ctx.dpi(42f)))
    c.tap { onOpen() }
    return c
}

private fun stars(level: Int): String = "●".repeat(level.coerceIn(1, 5)) + "○".repeat(5 - level.coerceIn(1, 5))

fun instIcon(ctx: Context, piano: Boolean): View = IconView(ctx, if (piano) IconView.KEYS else IconView.FLUTE).also { it.color = C.TEXT }

/** Selector Piano / Flauta delgado y limpio. */
fun instSwitch(ctx: Context, sel: Int, onPick: (Int) -> Unit): View = liveSegmented(ctx, listOf("Piano", "Flauta dulce"), sel, 13f, 5f, onPick)

// ====================== PARTITURAS ======================
class LibraryTab(private val host: Host, private val songs: List<SongInfo>, private val open: (Song) -> Unit) : LinearLayout(host as Context) {
    private val ctx: Context = host as Context
    private var inst = host.prefs.getInt("inst", 0)
    private val list = FrameLayout(ctx)

    init {
        orientation = VERTICAL
        addView(list, lp(MATCH, 0, 1f).also { it.topMargin = ctx.dpi(2f) })
        fill()
    }

    private fun fill() {
        list.removeAllViews()
        val piano = inst == 0
        val mine = songs.filter { it.inst == (if (piano) "piano" else "flute") && it.group == "main" }
        list.addView(scrollOf(ctx) { col ->
            for (s in mine) {
                val best = host.prefs.getInt("best_" + s.id, -1)
                val sub = listOf(s.composer, stars(s.level)).filter { it.isNotEmpty() }.joinToString("   ")
                val extra = (if (s.arr.isNotEmpty()) s.arr else "") + (if (best >= 0) "   Récord $best %" else "")
                col.addView(songCard(ctx, s.title, sub, extra.trim(), instIcon(ctx, piano)) { open(Library.load(ctx, s.id)) }, cardLp(ctx))
            }
            col.addView(label(ctx, if (piano) "Conecta tu piano digital con un cable USB OTG: la app escucha cada tecla que tocas." else "Pon el teléfono frente a ti: en Espera y Evaluar la app escucha tu flauta.", 12.5f, C.DIM).also { it.setPadding(ctx.dpi(4f), ctx.dpi(8f), ctx.dpi(4f), 0) })
        }, FrameLayout.LayoutParams(MATCH, MATCH))
    }
}

// ====================== APRENDER ======================
class LearnTab(private val host: Host, private val songs: List<SongInfo>, private val open: (Song) -> Unit, private val push: (String, Boolean, () -> View) -> Unit) : LinearLayout(host as Context) {
    private val ctx: Context = host as Context
    private var inst = host.prefs.getInt("inst", 0)
    private val body = FrameLayout(ctx)

    init {
        orientation = VERTICAL
        addView(body, lp(MATCH, 0, 1f).also { it.topMargin = ctx.dpi(2f) })
        fill()
    }

    private fun item(col: LinearLayout, icon: View, title: String, desc: String, help: String, action: () -> Unit) {
        val c = card(ctx)
        val ib = FrameLayout(ctx)
        ib.background = roundRect(C.CARD2, ctx.dp(14f))
        ib.addView(icon, FrameLayout.LayoutParams(ctx.dpi(30f), ctx.dpi(30f), Gravity.CENTER))
        c.addView(ib, lp(ctx.dpi(50f), ctx.dpi(50f)))
        val tc = LinearLayout(ctx)
        tc.orientation = VERTICAL
        tc.setPadding(ctx.dpi(14f), 0, ctx.dpi(8f), 0)
        val tr = LinearLayout(ctx)
        tr.gravity = Gravity.CENTER_VERTICAL
        tr.addView(label(ctx, title, 17f, C.TEXT, Fonts.bold))
        tr.addView(helpDot(ctx, title, help), lp(ctx.dpi(34f), ctx.dpi(28f)))
        tc.addView(tr)
        tc.addView(label(ctx, desc, 13f, C.MUTED).also { it.setPadding(0, ctx.dpi(3f), 0, 0); it.setLineSpacing(0f, 1.1f) })
        c.addView(tc, lp(0, WRAP, 1f))
        c.addView(IconView(ctx, IconView.CHEVRON_RIGHT).also { it.color = C.MUTED }, lp(ctx.dpi(22f), ctx.dpi(22f)))
        c.tap(action)
        col.addView(c, cardLp(ctx))
    }

    private fun fill() {
        body.removeAllViews()
        val piano = inst == 0
        body.addView(scrollOf(ctx) { col ->
            col.addView(headline(ctx, if (piano) "Aprende piano" else "Aprende flauta", 18f))
            col.addView(label(ctx, "Primero las herramientas, luego las canciones para aprender.", 13f, C.MUTED).also { it.setPadding(0, ctx.dpi(6f), 0, ctx.dpi(12f)) })
            item(col, GlyphIcon(ctx, "\uE050", true, false, 28f), "Explorador de notas",
                if (piano) "Toca una tecla y mira dónde se escribe, con su nombre y sonido." else "Sube y baja nota por nota y mira cómo se tapa la flauta.",
                if (piano) "Se abre en horizontal. A la izquierda está la clave de fa (mano izquierda, notas graves) y a la derecha la clave de sol (mano derecha, notas agudas). Cada tecla que tocas aparece en su pentagrama con su nombre." else "Cada nota muestra qué agujeros tapar (en rojo), con qué dedo y cómo soplar. La nota escrita suena una octava más aguda en la flauta dulce soprano.") {
                push("Explorador de notas", piano) { if (piano) PianoExplorer(host) else FluteExplorer(host) }
            }
            if (!piano) item(col, IconView(ctx, IconView.FLUTE).also { it.color = C.TEXT }, "Tabla de digitación", "Todas las notas de la flauta dulce soprano, de Do grave a Re agudo.",
                "Digitación barroca, la de la flauta escolar de plástico. Rojo = agujero tapado. Medio círculo en el pulgar = deja una rendija pequeña. Los agujeros 6 y 7 son dobles: a veces se tapa solo uno de los dos.") {
                push("Tabla de digitación", false) { FingeringChart(host) }
            }
            if (piano) item(col, IconView(ctx, IconView.KEYS).also { it.color = C.TEXT }, "Escalas y acordes", "Escalas mayores y menores con las dos manos y los acordes de cada tono.",
                "Una escala son las notas de un tono, una tras otra. Practicarlas da agilidad y te enseña qué teclas usa cada canción. Al final de cada escala están sus acordes principales (I, IV, V, I).") {
                push("Escalas y acordes", false) { ScalesView(host, open) }
            }
            item(col, GlyphIcon(ctx, "\uE0A4", true, false, 28f), "Símbolos de la partitura", "Figuras, silencios, alteraciones y signos, uno por uno.",
                "Todo lo que aparece en las partituras de la app, explicado con ejemplos sencillos.") {
                push("Símbolos", false) { SymbolsView(host, piano) }
            }
            col.addView(label(ctx, "Canciones para aprender", 14f, C.MUTED, Fonts.medium).also { it.setPadding(ctx.dpi(2f), ctx.dpi(12f), 0, ctx.dpi(8f)) })
            for (s in songs.filter { it.inst == (if (piano) "piano" else "flute") && it.group == "learn" }) {
                val best = host.prefs.getInt("best_" + s.id, -1)
                col.addView(songCard(ctx, s.title, (if (s.arr.isNotEmpty()) s.arr + "   " else "") + stars(s.level), if (best >= 0) "Récord $best %" else "", instIcon(ctx, piano)) {
                    open(Library.load(ctx, s.id))
                }, cardLp(ctx))
            }
        }, FrameLayout.LayoutParams(MATCH, MATCH))
    }
}

/** Un pentagrama (clave de sol o de fa) que muestra una nota. Deja espacio arriba y abajo para las lineas adicionales. */
class NoteStaffView(ctx: Context, private val treble: Boolean) : View(ctx) {
    var note: Pair<Int, Int>? = null   // (dia, alt)
        set(v) { field = v; invalidate() }
    var color = C.RED_HI
    var onClef: (() -> Unit)? = null
    private val p = Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var sp = 10f

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        sp = min(h / 17f, w / 9f)
        // clave de sol: sitio para Do7 arriba; clave de fa: sitio para Do2 abajo
        val top = if (treble) h - 5.5f * sp - 4 * sp else 2.5f * sp + (h - 17f * sp) / 2
        p.color = C.MUTED; p.strokeWidth = sp * 0.1f
        for (k in 0 until 5) c.drawLine(sp * 0.4f, top + k * sp, w - sp * 0.4f, top + k * sp, p)
        p.typeface = Fonts.music; p.textSize = sp * 4f; p.color = C.TEXT
        if (treble) c.drawText("\uE050", sp * 0.9f, top + 3 * sp, p) else c.drawText("\uE062", sp * 0.9f, top + sp, p)
        val n = note ?: return
        val base = if (treble) 30 else 18
        val pos = n.first - base
        val y = top + 4 * sp - pos * sp / 2f
        val x = w * 0.6f
        p.color = C.MUTED
        if (pos <= -2) { var q = -2; while (q >= pos) { val yy = top + 4 * sp - q * sp / 2f; c.drawLine(x - sp * 0.5f, yy, x + sp * 1.7f, yy, p); q -= 2 } }
        if (pos >= 10) { var q = 10; while (q <= pos) { val yy = top + 4 * sp - q * sp / 2f; c.drawLine(x - sp * 0.5f, yy, x + sp * 1.7f, yy, p); q += 2 } }
        p.color = color
        c.drawText("\uE0A2", x, y, p)
        if (n.second != 0) c.drawText(if (n.second > 0) "\uE262" else "\uE260", x - sp * 1.5f, y, p)
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_UP && e.x < sp * 4.5f) { performClick(); onClef?.invoke() }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

private fun spellSharp(m: Int): Pair<Int, Int> {
    val pc = ((m % 12) + 12) % 12
    val d = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)[pc]
    return Pair((Math.floorDiv(m, 12) - 1) * 7 + d, if (Names.isBlack(m)) 1 else 0)
}

/** Explorador del piano en horizontal: clave de fa a la izquierda (graves), clave de sol a la derecha (agudos), teclado a todo el ancho con zoom de dos dedos. */
class PianoExplorer(private val host: Host) : LinearLayout(host as Context) {
    private val ctx: Context = host as Context
    private val bass = NoteStaffView(ctx, false)
    private val treble = NoteStaffView(ctx, true)
    private val name = label(ctx, "Toca una tecla", 24f, C.TEXT, Fonts.bold)
    private val info = label(ctx, "", 12.5f, C.MUTED)
    private val keys = KeyboardView(ctx)
    private val bar = KeyScrollBar(ctx, keys)

    init {
        orientation = VERTICAL
        val row = LinearLayout(ctx)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(ctx.dpi(10f), 0, ctx.dpi(10f), 0)
        row.addView(bass, lp(0, MATCH, 1f))
        val mid = LinearLayout(ctx); mid.orientation = VERTICAL; mid.gravity = Gravity.CENTER
        name.gravity = Gravity.CENTER
        mid.addView(name, lp(MATCH, WRAP))
        info.gravity = Gravity.CENTER
        mid.addView(info, lp(MATCH, WRAP))
        row.addView(mid, lp(0, MATCH, 0.75f))
        row.addView(treble, lp(0, MATCH, 1f))
        addView(row, lp(MATCH, 0, 1f))
        bass.onClef = { host.toast("Clave de fa: mano izquierda, notas graves. La cuarta línea es Fa.") }
        treble.onClef = { host.toast("Clave de sol: mano derecha, notas agudas. La segunda línea es Sol.") }
        keys.setRange(36, 96)
        keys.names = 1
        keys.zoomable = true
        keys.onView = { bar.invalidate() }
        addView(keys, lp(MATCH, 0, 0.95f))
        addView(bar, lp(MATCH, ctx.dpi(14f)))
        keys.onKey = { m, on -> if (on) show(m) }
    }

    private fun show(m: Int) {
        host.synth.play("piano", m, 1200)
        val right = m >= 60
        keys.lit.clear(); keys.lit[m] = if (right) 1 else 2; keys.invalidate()
        val n = spellSharp(m)
        treble.color = C.RED_HI; bass.color = C.PINK
        treble.note = if (right) n else null
        bass.note = if (!right) n else null
        name.text = Names.withOctave(m)
        info.text = (if (m == 60) "Do central\n" else "") + (if (right) "Mano derecha" else "Mano izquierda") +
            (if (n.second != 0) "\nTambién se llama " + Names.spelled(n.first + 1, -1) else "")
    }
}

/** Explicacion corta de como sacar cada nota en la flauta dulce (nota escrita). */
fun fluteTip(m: Int): String = when {
    m <= 62 -> "Tapa todos los agujeros con la yema plana y sopla muy suave, como diciendo «du»."
    m in intArrayOf(65, 66, 68, 70, 75, 77, 78) -> "Digitación de horquilla: fíjate en los dedos levantados del medio. Soplo suave y parejo."
    m <= 74 -> "Soplo normal y parejo. Empieza cada nota con la lengua, como diciendo «tu»."
    m <= 81 -> "Medio pulgar: deja una rendija pequeña en el agujero de atrás y sopla un poco más fuerte."
    else -> "Medio pulgar con una rendija muy pequeña y soplo firme y rápido."
}

class FluteExplorer(private val host: Host) : LinearLayout(host as Context) {
    private val ctx: Context = host as Context
    private val flute = FluteView(ctx)
    private val staff = NoteStaffView(ctx, true)
    private var m = 67
    private val name = label(ctx, "", 28f, C.TEXT, Fonts.bold)
    private val sub = label(ctx, "", 13f, C.MUTED)
    private val tip = label(ctx, "", 13.5f, C.TEXT)

    init {
        orientation = HORIZONTAL
        setPadding(ctx.dpi(14f), 0, ctx.dpi(6f), ctx.dpi(10f))
        val left = LinearLayout(ctx)
        left.orientation = VERTICAL
        left.gravity = Gravity.CENTER_HORIZONTAL
        name.gravity = Gravity.CENTER
        left.addView(name, lp(MATCH, WRAP))
        sub.gravity = Gravity.CENTER
        left.addView(sub, lp(MATCH, WRAP))
        left.addView(staff, lp(MATCH, ctx.dpi(130f)))
        val row = LinearLayout(ctx)
        row.gravity = Gravity.CENTER
        val dn = FrameLayout(ctx); dn.background = roundRect(C.CARD2, ctx.dp(24f))
        dn.addView(IconView(ctx, IconView.DOWN), FrameLayout.LayoutParams(ctx.dpi(24f), ctx.dpi(24f), Gravity.CENTER))
        val up = FrameLayout(ctx); up.background = roundRect(C.CARD2, ctx.dp(24f))
        up.addView(IconView(ctx, IconView.UP), FrameLayout.LayoutParams(ctx.dpi(24f), ctx.dpi(24f), Gravity.CENTER))
        dn.tap { set(m - 1) }; up.tap { set(m + 1) }
        row.addView(dn, lp(ctx.dpi(60f), ctx.dpi(48f)))
        row.addView(View(ctx), lp(ctx.dpi(12f), 1))
        row.addView(up, lp(ctx.dpi(60f), ctx.dpi(48f)))
        left.addView(row, lp(MATCH, WRAP))
        val play = Chip(ctx, "Escuchar"); play.tap { host.synth.play("flute", m + 12, 1300) }
        left.addView(play, lp(WRAP, WRAP).also { it.topMargin = ctx.dpi(10f) })
        tip.setLineSpacing(0f, 1.15f)
        tip.background = roundRect(C.CARD, ctx.dp(14f))
        tip.setPadding(ctx.dpi(12f), ctx.dpi(10f), ctx.dpi(12f), ctx.dpi(10f))
        left.addView(tip, lp(MATCH, WRAP).also { it.topMargin = ctx.dpi(12f) })
        addView(left, lp(0, MATCH, 1f))
        flute.compact = true
        addView(flute, lp(0, MATCH, 1f))
        set(m)
    }

    private fun set(v: Int) {
        var x = v.coerceIn(RecorderFingering.LOW, RecorderFingering.HIGH)
        if (RecorderFingering.of(x) == null) x = if (v > m) x + 1 else x - 1
        m = x.coerceIn(RecorderFingering.LOW, RecorderFingering.HIGH)
        flute.midi = m
        staff.note = spellSharp(m)
        name.text = Names.pitchClass(m)
        sub.text = "Escrita " + Names.withOctave(m) + " · suena " + Names.withOctave(m + 12)
        tip.text = fluteTip(m)
        host.synth.play("flute", m + 12, 900)
    }
}

class FingeringChart(private val host: Host) : FrameLayout(host as Context) {
    init {
        val ctx = host as Context
        addView(scrollOf(ctx) { col ->
            col.addView(label(ctx, "Rojo = tapado · medio círculo = medio pulgar · toca una flauta para oírla.", 13f, C.MUTED).also { it.setPadding(0, 0, 0, ctx.dpi(10f)) })
            var row: LinearLayout? = null
            var k = 0
            for (m in RecorderFingering.LOW..RecorderFingering.HIGH) {
                if (RecorderFingering.of(m) == null) continue
                if (k % 3 == 0) { row = LinearLayout(ctx); col.addView(row, lp(MATCH, ctx.dpi(230f)).also { it.bottomMargin = ctx.dpi(8f) }) }
                val f = FluteView(ctx)
                f.mini = true
                f.midi = m
                f.background = roundRect(C.CARD, ctx.dp(16f))
                f.tap { host.synth.play("flute", m + 12, 1100) }
                row!!.addView(f, lp(0, MATCH, 1f).also { it.setMargins(ctx.dpi(3f), 0, ctx.dpi(3f), 0) })
                k++
            }
            while (k % 3 != 0) { row!!.addView(View(ctx), lp(0, MATCH, 1f)); k++ }
        }, LayoutParams(MATCH, MATCH))
    }
}

class SymbolsView(host: Host, piano: Boolean) : FrameLayout(host as Context) {
    init {
        val ctx = host as Context
        val items = ArrayList<Triple<String, String, String>>()
        items.add(Triple("\uE050", "Clave de sol", if (piano) "En el piano marca el pentagrama de arriba: lo toca la mano derecha (notas agudas). La segunda línea es Sol." else "La partitura de la flauta usa siempre clave de sol. La segunda línea es Sol."))
        if (piano) {
            items.add(Triple("\uE062", "Clave de fa", "Marca el pentagrama de abajo: lo toca la mano izquierda (notas graves). La cuarta línea es Fa."))
            items.add(Triple("\uE000", "Llave", "Une los dos pentagramas del piano: se leen y tocan a la vez, uno con cada mano."))
        }
        items.addAll(listOf(
            Triple("\uE084", "Cifra de compás", "El número de arriba dice cuántos tiempos tiene cada compás; el de abajo, qué figura vale un tiempo (4 = negra)."),
            Triple("\uE262\uE262", "Armadura", "Sostenidos o bemoles al inicio de cada línea: valen para toda la canción en esas notas."),
            Triple("\uE1D2", "Redonda", "Dura 4 tiempos."),
            Triple("\uE1D3", "Blanca", "Dura 2 tiempos."),
            Triple("\uE1D5", "Negra", "Dura 1 tiempo."),
            Triple("\uE1D7", "Corchea", "Medio tiempo. Varias se unen con una barra llamada haz."),
            Triple("\uE1D9", "Semicorchea", "Un cuarto de tiempo. Llevan dos barras."),
            Triple("\uE1DB", "Fusa", "Un octavo de tiempo: caben 8 en una negra. Llevan tres barras."),
            Triple("\uE1DD", "Semifusa", "Un dieciseisavo de tiempo: caben 16 en una negra. Llevan cuatro barras."),
            Triple("\uE1D5\uE1E7", "Puntillo", "Suma la mitad de la duración: negra con puntillo = 1 tiempo y medio."),
            Triple("\uE883", "Tresillo", "Tres notas en el tiempo de dos. El número va sobre el grupo."),
            Triple("\uE4E3", "Silencio de redonda", "Todo el compás en silencio."),
            Triple("\uE4E4", "Silencio de blanca", "Dos tiempos en silencio."),
            Triple("\uE4E5", "Silencio de negra", "Un tiempo en silencio."),
            Triple("\uE4E6", "Silencio de corchea", "Medio tiempo en silencio."),
            Triple("\uE4E7", "Silencio de semicorchea", "Un cuarto de tiempo en silencio."),
            Triple("\uE4E8", "Silencio de fusa", "Un octavo de tiempo en silencio."),
            Triple("\uE4E9", "Silencio de semifusa", "Un dieciseisavo de tiempo en silencio."),
            Triple("\uE262", "Sostenido", "Sube medio tono. En el piano: la tecla de la derecha más cercana."),
            Triple("\uE260", "Bemol", "Baja medio tono. En el piano: la tecla de la izquierda más cercana."),
            Triple("\uE261", "Becuadro", "Anula el sostenido o bemol hasta el final del compás."),
            Triple("\uE1D3\uE1D3", "Ligadura de prolongación", "Une dos notas iguales: se toca la primera y se sostiene sin repetir."),
            Triple("\uE4A2", "Staccato", "Punto sobre la nota: tócala corta y separada."),
            Triple("\uE4A0", "Acento", "Toca esa nota más fuerte."),
            Triple("\uE4C0", "Calderón", "Sostén la nota un poco más de lo escrito."),
            Triple("\uE040", "Repetición", "Entre las barras con dos puntos se toca dos veces. La app ya hace las repeticiones."),
            Triple("\uE521\uE522", "Dinámicas", "p = suave, mp = medio suave, mf = medio fuerte, f = fuerte.")
        ))
        if (piano) {
            items.add(Triple("\uE510", "8va", "Toca una octava más arriba de lo escrito mientras dure la línea punteada (8vb: más abajo)."))
            items.add(Triple("\uE650", "Pedal", "Pisa el pedal derecho: las notas siguen sonando. El asterisco indica soltarlo."))
        }
        addView(scrollOf(ctx) { col ->
            for ((g, n, d) in items) {
                val c = card(ctx)
                val ib = FrameLayout(ctx)
                ib.background = roundRect(C.CARD2, ctx.dp(14f))
                ib.addView(GlyphIcon(ctx, g, true, false, 26f), LayoutParams(MATCH, MATCH))
                c.addView(ib, lp(ctx.dpi(56f), ctx.dpi(64f)))
                val tc = LinearLayout(ctx)
                tc.orientation = LinearLayout.VERTICAL
                tc.setPadding(ctx.dpi(14f), 0, 0, 0)
                tc.addView(label(ctx, n, 16f, C.TEXT, Fonts.bold))
                tc.addView(label(ctx, d, 13f, C.MUTED).also { it.setPadding(0, ctx.dpi(3f), 0, 0); it.setLineSpacing(0f, 1.1f) })
                c.addView(tc, lp(0, WRAP, 1f))
                col.addView(c, cardLp(ctx))
            }
        }, LayoutParams(MATCH, MATCH))
    }
}

/** Escalas generadas en la app: subida y bajada con las dos manos, con los dedos, y luego los acordes I IV V I. */
class ScalesView(private val host: Host, private val open: (Song) -> Unit) : FrameLayout(host as Context) {
    private class Sc(val name: String, val root: Int, val minor: Boolean, val key: Int, val rh: String, val lh: String)
    init {
        val ctx = host as Context
        val list = listOf(
            Sc("Do mayor", 60, false, 0, "1 2 3 1 2 3 4 5", "5 4 3 2 1 3 2 1"),
            Sc("Sol mayor", 67, false, 1, "1 2 3 1 2 3 4 5", "5 4 3 2 1 3 2 1"),
            Sc("Re mayor", 62, false, 2, "1 2 3 1 2 3 4 5", "5 4 3 2 1 3 2 1"),
            Sc("La mayor", 69, false, 3, "1 2 3 1 2 3 4 5", "5 4 3 2 1 3 2 1"),
            Sc("Fa mayor", 65, false, -1, "1 2 3 4 1 2 3 4", "5 4 3 2 1 3 2 1"),
            Sc("Si bemol mayor", 70, false, -2, "4 1 2 3 1 2 3 4", "3 2 1 4 3 2 1 3"),
            Sc("Mi bemol mayor", 63, false, -3, "3 1 2 3 4 1 2 3", "3 2 1 4 3 2 1 3"),
            Sc("La menor", 69, true, 0, "1 2 3 1 2 3 4 5", "5 4 3 2 1 3 2 1"),
            Sc("Mi menor", 64, true, 1, "1 2 3 1 2 3 4 5", "5 4 3 2 1 3 2 1"),
            Sc("Re menor", 62, true, -1, "1 2 3 1 2 3 4 5", "5 4 3 2 1 3 2 1")
        )
        val names = arrayOf("Fa#", "Do#", "Sol#", "Re#", "La#", "Mi#", "Si#")
        val flats = arrayOf("Sib", "Mib", "Lab", "Reb", "Solb", "Dob", "Fab")
        addView(scrollOf(ctx) { col ->
            col.addView(label(ctx, "Los números son los dedos: 1 pulgar, 2 índice, 3 medio, 4 anular, 5 meñique.", 13f, C.MUTED).also { it.setPadding(0, 0, 0, ctx.dpi(10f)) })
            for (s in list) {
                val alts = if (s.key > 0) "Tiene ${s.key} sostenido" + (if (s.key > 1) "s" else "") + ": " + names.take(s.key).joinToString(", ") + "."
                    else if (s.key < 0) "Tiene ${-s.key} bemol" + (if (s.key < -1) "es" else "") + ": " + flats.take(-s.key).joinToString(", ") + "." else "No tiene sostenidos ni bemoles: solo teclas blancas."
                val kind = if (s.minor) "Escala menor natural: suena más triste o melancólica. Sus notas son las de su relativa mayor, empezando desde otra nota." else "Escala mayor: suena alegre y luminosa. Sigue el patrón tono, tono, semitono, tono, tono, tono, semitono."
                val help = Pair(s.name, "$kind\n\n$alts\n\nSubes y bajas una octava con las dos manos a la vez y terminas con los acordes I, IV, V y I del tono. Los dedos aparecen sobre cada nota en la partitura.")
                col.addView(songCard(ctx, s.name, "Derecha: ${s.rh}", "Izquierda: ${s.lh}", instIcon(ctx, true), help) { open(build(s)) }, cardLp(ctx))
            }
        }, LayoutParams(MATCH, MATCH))
    }

    private fun build(s: Sc): Song {
        val steps = if (s.minor) intArrayOf(0, 2, 3, 5, 7, 8, 10, 12) else intArrayOf(0, 2, 4, 5, 7, 9, 11, 12)
        val ev = ArrayList<Ev>()
        val sharpKey = s.key >= 0
        fun spell(m: Int): Pair<Int, Int> {
            val pc = ((m % 12) + 12) % 12
            val d = if (sharpKey) intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)[pc] else intArrayOf(0, 1, 1, 2, 2, 3, 4, 4, 5, 5, 6, 6)[pc]
            val natural = intArrayOf(0, 2, 4, 5, 7, 9, 11)[d]
            var alt = pc - natural; if (alt > 6) alt -= 12; if (alt < -6) alt += 12
            return Pair((Math.floorDiv(m, 12) - 1) * 7 + d, alt)
        }
        val keyAlt = { dia: Int ->
            val st = ((dia % 7) + 7) % 7
            if (s.key > 0 && st in intArrayOf(3, 0, 4, 1, 5, 2, 6).take(s.key)) 1 else if (s.key < 0 && st in intArrayOf(6, 2, 5, 1, 4, 0, 3).take(-s.key)) -1 else 0
        }
        fun nh(m: Int, f: Int = 0): NoteH { val (d, a) = spell(m); return NoteH(m, d, a, if (a != keyAlt(d)) a else 9, 0, 0, f) }
        val rf = s.rh.split(" ").map { it.toInt() }
        val lf = s.lh.split(" ").map { it.toInt() }
        var t = 0
        val idx = (0..7).toList() + (6 downTo 0).toList()
        for ((i, k) in idx.withIndex()) {
            val dur = if (i == idx.size - 1) 1920 else 480
            ev.add(Ev(t, dur, 0, 0, 0, 0, 0, 0, false, arrayOf(nh(s.root + steps[k], rf[k])), dur))
            ev.add(Ev(t, dur, 1, 0, 0, 0, 0, 0, false, arrayOf(nh(s.root - 12 + steps[k], lf[k])), dur))
            t += dur
        }
        t = ((t + 1919) / 1920) * 1920
        val third = if (s.minor) 3 else 4
        val chords = listOf(intArrayOf(0, third, 7), intArrayOf(5, 5 + third, 12), intArrayOf(7, 11, 14), intArrayOf(0, third, 7))
        val roots = intArrayOf(0, 5, 7, 0)
        for ((k, ch) in chords.withIndex()) {
            ev.add(Ev(t, 1920, 0, 0, 0, 0, 0, 0, false, ch.map { nh(s.root + it) }.toTypedArray(), 1920))
            ev.add(Ev(t, 1920, 1, 0, 0, 0, 0, 0, false, arrayOf(nh(s.root - 12 + roots[k] - (if (roots[k] >= 7) 12 else 0))), 1920))
            t += 1920
        }
        val ms = (0 until t / 1920).map { Measure(4, 4, s.key, it * 1920, 1920) }
        return Song("scale_" + s.root + (if (s.minor) "m" else ""), "Escala de " + s.name, "Escalas", "", "piano", 1, 80, emptyList(), emptyList(), ms, ms.indices.toList(), ev)
    }
}

// ====================== PRINCIPAL ======================
class HomeScreen(private val host: Host, private val open: (Song) -> Unit) : FrameLayout(host as Context) {
    private val ctx: Context = host as Context
    private val songs = Library.index(ctx)
    private val content = FrameLayout(ctx)
    private val detail = FrameLayout(ctx)
    private val navIcons = ArrayList<IconView>()
    private val navLabels = ArrayList<TextView>()
    private var tab = host.prefs.getInt("tab", 0).coerceIn(0, 1)
    private var metro: MetronomeTab? = null
    private var tuner: TunerTab? = null
    private class Page(val title: String, val landscape: Boolean, val make: () -> View)
    private val stack = ArrayList<Page>()
    private val instIcons = ArrayList<IconView>()

    /** El instrumento elegido se ve en rojo; el otro, gris. */
    private fun paintInst() {
        val k = host.prefs.getInt("inst", 0)
        for ((i, ic) in instIcons.withIndex()) ic.color = if (i == k) C.RED_HI else C.MUTED
    }

    init {
        setBackgroundColor(C.BG)
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        addView(root, LayoutParams(MATCH, MATCH))
        val header = LinearLayout(ctx)
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(ctx.dpi(18f), ctx.dpi(12f), ctx.dpi(14f), ctx.dpi(8f))
        header.addView(logoView(ctx, 22f))
        header.addView(View(ctx), lp(0, 1, 1f))
        for (k in 0..1) {
            val f = FrameLayout(ctx)
            val ic = IconView(ctx, if (k == 0) IconView.KEYS else IconView.FLUTE)
            instIcons.add(ic)
            f.addView(ic, FrameLayout.LayoutParams(ctx.dpi(26f), ctx.dpi(26f), Gravity.CENTER))
            f.tap { host.prefs.edit().putInt("inst", k).apply(); paintInst(); if (tab != 2) select(tab); host.toast(if (k == 0) "Piano" else "Flauta dulce") }
            header.addView(f, lp(ctx.dpi(40f), ctx.dpi(40f)))
        }
        paintInst()
        val about = FrameLayout(ctx)
        about.addView(IconView(ctx, IconView.INFO).also { it.color = C.MUTED }, FrameLayout.LayoutParams(ctx.dpi(26f), ctx.dpi(26f), Gravity.CENTER))
        about.tap { showAbout() }
        header.addView(about, lp(ctx.dpi(40f), ctx.dpi(40f)))
        root.addView(header, lp(MATCH, WRAP))
        root.addView(content, lp(MATCH, 0, 1f))
        val nav = LinearLayout(ctx)
        nav.setPadding(0, ctx.dpi(4f), 0, ctx.dpi(6f))
        val names = arrayOf("Partituras", "Aprender", "Metrónomo", "Afinador")
        val kinds = intArrayOf(IconView.NOTE, IconView.LEARN, IconView.METRO, IconView.TUNER)
        for (i in 0 until 4) {
            val item = LinearLayout(ctx)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER_HORIZONTAL
            val ic = IconView(ctx, kinds[i])
            item.addView(ic, lp(ctx.dpi(28f), ctx.dpi(28f)))
            val tv = label(ctx, names[i], 11.5f, C.MUTED, Fonts.medium)
            tv.gravity = Gravity.CENTER
            tv.maxLines = 1
            tv.setPadding(0, ctx.dpi(3f), 0, 0)
            item.addView(tv, lp(MATCH, WRAP))
            item.tap { select(i) }
            navIcons.add(ic); navLabels.add(tv)
            nav.addView(item, lp(0, ctx.dpi(56f), 1f))
        }
        root.addView(nav, lp(MATCH, WRAP))
        addView(detail, LayoutParams(MATCH, MATCH))
        detail.visibility = GONE
        select(tab)
    }

    private fun showAbout() {
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sv = FastScrollView(ctx)
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(ctx.dpi(22f), ctx.dpi(22f), ctx.dpi(26f), ctx.dpi(16f))
        box.background = roundRect(C.CARD, ctx.dp(24f))
        sv.setContent(box)
        val top = LinearLayout(ctx)
        top.gravity = Gravity.CENTER_VERTICAL
        val ic = ImageView(ctx)
        ic.setImageBitmap(ctx.assets.open("splash_icon.png").use { BitmapFactory.decodeStream(it) })
        top.addView(ic, lp(ctx.dpi(56f), ctx.dpi(56f)))
        val tt = LinearLayout(ctx)
        tt.orientation = LinearLayout.VERTICAL
        tt.setPadding(ctx.dpi(14f), 0, 0, 0)
        tt.addView(label(ctx, "ELIZIBEL", 21f, C.TEXT, Fonts.title).also { it.letterSpacing = 0.12f })
        tt.addView(label(ctx, "Versión " + BuildConfigLite.VERSION, 13f, C.MUTED).also { it.setPadding(0, ctx.dpi(4f), 0, 0) })
        top.addView(tt, lp(0, WRAP, 1f))
        box.addView(top)
        fun title(t: String) { box.addView(label(ctx, t, 11.5f, C.RED_HI, Fonts.title).also { it.letterSpacing = 0.12f; it.setPadding(0, ctx.dpi(18f), 0, ctx.dpi(6f)) }) }
        fun body(t: String) { box.addView(label(ctx, t, 14f, C.TEXT).also { it.setLineSpacing(0f, 1.2f) }) }
        title("QUÉ ES")
        body("Una app para aprender a tocar piano y flauta dulce con canciones reales. La partitura avanza y el teclado o la flauta te muestran qué tocar en cada momento.")
        title("PUNTOS CLAVE")
        for (k in listOf(
            "Partituras leídas símbolo por símbolo desde los PDF originales, con cada compás verificado.",
            "Piano: pentagrama doble, teclado ajustado a cada canción, notas que caen y práctica por mano.",
            "Piano digital por USB: la app escucha cada tecla que tocas.",
            "Flauta dulce: silueta con los dedos de cada agujero, consejos para cada nota y micrófono que te escucha.",
            "Modos Libre, Espera y Evaluar, bucles, velocidad de 40 a 150 % y récords.",
            "Metrónomo con subdivisiones y afinador que ignora la voz.",
            "Sin internet y sin anuncios."
        )) {
            val r = LinearLayout(ctx)
            r.addView(View(ctx).also { it.background = roundRect(C.RED, ctx.dp(3f)) }, lp(ctx.dpi(6f), ctx.dpi(6f)).also { it.setMargins(0, ctx.dpi(8f), ctx.dpi(10f), 0) })
            r.addView(label(ctx, k, 14f, C.TEXT).also { it.setLineSpacing(0f, 1.15f) }, lp(0, WRAP, 1f))
            box.addView(r, lp(MATCH, WRAP).also { it.bottomMargin = ctx.dpi(4f) })
        }
        title("CREADOR")
        body("Joel Mamani Pauccara")
        title("CRÉDITOS")
        body("Piano: Salamander Grand Piano V3 de Alexander Holm, licencia CC BY 3.0. Flauta dulce: Versilian Community Sample Library (VCSL), dominio público CC0. Tipografías: Space Grotesk, Michroma y Bravura, licencia SIL OFL.")
        val sep = View(ctx)
        sep.setBackgroundColor(C.CARD2)
        box.addView(sep, lp(MATCH, ctx.dpi(1f)).also { it.topMargin = ctx.dpi(18f) })
        val pb = label(ctx, "POWERED BY DIGITALMINDS", 11.5f, C.MUTED, Fonts.title)
        pb.letterSpacing = 0.2f
        pb.gravity = Gravity.CENTER
        pb.setPadding(0, ctx.dpi(14f), 0, ctx.dpi(8f))
        box.addView(pb, lp(MATCH, WRAP))
        val ok = FrameLayout(ctx)
        ok.background = roundRect(C.RED, ctx.dp(22f))
        ok.addView(label(ctx, "Cerrar", 15f, C.TEXT, Fonts.bold), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        ok.tap { d.dismiss() }
        box.addView(ok, lp(MATCH, ctx.dpi(46f)).also { it.topMargin = ctx.dpi(6f) })
        d.setContentView(sv)
        d.window?.setBackgroundDrawable(ColorDrawable(0))
        val dm = ctx.resources.displayMetrics
        d.window?.setLayout((dm.widthPixels * 0.9f).toInt().coerceAtMost(ctx.dpi(460f)), (dm.heightPixels * 0.88f).toInt())
        d.showClean()
    }

    private fun select(i: Int) {
        if (i <= 1) host.prefs.edit().putInt("tab", i).apply()
        tab = i
        hideDetail()
        metro?.deactivate(); tuner?.deactivate()
        content.removeAllViews()
        when (i) {
            0 -> content.addView(LibraryTab(host, songs, open), LayoutParams(MATCH, MATCH))
            1 -> content.addView(LearnTab(host, songs, open) { t, land, make -> showDetail(t, land, make) }, LayoutParams(MATCH, MATCH))
            2 -> { val m = MetronomeTab(host); metro = m; content.addView(m, LayoutParams(MATCH, MATCH)); m.activate() }
            3 -> { val t = TunerTab(host); tuner = t; content.addView(t, LayoutParams(MATCH, MATCH)); t.activate() }
        }
        for (k in 0 until 4) {
            navIcons[k].color = if (k == i) C.RED_HI else C.MUTED
            navLabels[k].setTextColor(if (k == i) C.TEXT else C.MUTED)
        }
    }

    private fun showDetail(title: String, landscape: Boolean, make: () -> View) { stack.add(Page(title, landscape, make)); renderDetail() }

    private fun renderDetail() {
        detail.removeAllViews()
        val top = stack.lastOrNull()
        if (top == null) { detail.visibility = GONE; host.setOrientation(false); return }
        host.setOrientation(top.landscape)
        detail.addView(DetailFrame(ctx, top.title, top.make(), top.landscape) { back() }, LayoutParams(MATCH, MATCH))
        detail.visibility = VISIBLE
    }

    private fun back() { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1); renderDetail() }
    private fun hideDetail() { val had = stack.isNotEmpty(); stack.clear(); detail.removeAllViews(); detail.visibility = GONE; if (had) host.setOrientation(false) }

    /** Al volver de una cancion abierta desde Aprender, se queda en la misma lista. */
    fun handleBack(): Boolean {
        if (stack.isNotEmpty()) { back(); return true }
        if (tab != 0) { select(0); return true }
        return false
    }

    /** Al volver del reproductor se reconstruye la pantalla abierta (por ejemplo, el explorador en horizontal). */
    fun onReturn() { if (stack.isNotEmpty()) renderDetail() }

    fun onLeave() { metro?.deactivate(); tuner?.deactivate() }

    /** Al volver a la app, el afinador vuelve a escuchar (el sistema apaga el microfono al salir). */
    fun reactivate() { if (tab == 3) tuner?.activate() }
}

object BuildConfigLite { const val VERSION = "1.9" }
