package pl.matka.opp.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import pl.matka.opp.engine.Kolor
import pl.matka.opp.engine.StanWyspy
import kotlin.math.abs

/** Rysuje wyspę: kropkę czuwania, zapowiedź, znak + dozwoloną prędkość z pierścieniem, podsumowanie. */
@SuppressLint("ViewConstructor")
class WyspaView(
    ctx: Context,
    private val onDrag: (dx: Int, dy: Int) -> Unit,
    private val onDragEnd: () -> Unit,
    private val onTap: () -> Unit,
) : View(ctx) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    var stan: StanWyspy = StanWyspy.Czuwanie(false)
        set(v) {
            val zmianaRozmiaru = rozmiar(field) != rozmiar(v)
            field = v
            if (zmianaRozmiaru) requestLayout()
            invalidate()
        }

    private val tlo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 28, 28, 30) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val txtL = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        color = Color.WHITE
    }
    private val rect = RectF()

    private val pad = dp(8f)
    private val s = dp(60f)
    private val gap = dp(10f)
    private val h = pad * 2 + s

    private fun rozmiar(st: StanWyspy): Pair<Int, Int> = when (st) {
        is StanWyspy.Czuwanie -> (dp(30f).toInt() to dp(30f).toInt())
        is StanWyspy.Zapowiedz -> ((pad * 2 + s + gap + dp(78f)).toInt() to h.toInt())
        is StanWyspy.NaOdcinku ->
            if (st.nieznanyWjazd) ((pad * 2 + s + gap + dp(112f)).toInt() to h.toInt())
            else ((pad * 2 + s + gap + s).toInt() to h.toInt())
        is StanWyspy.Podsumowanie -> ((pad * 2 + s + gap + dp(84f)).toInt() to h.toInt())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val (w, hh) = rozmiar(stan)
        setMeasuredDimension(w, hh)
    }

    private fun kolor(k: Kolor): Int = when (k) {
        Kolor.ZIELONY -> Color.rgb(67, 200, 80)
        Kolor.ZOLTY -> Color.rgb(253, 216, 53)
        Kolor.CZERWONY -> Color.rgb(239, 68, 60)
        Kolor.NIEBIESKI -> Color.rgb(66, 165, 245)
        Kolor.SZARY -> Color.rgb(158, 158, 158)
    }

    override fun onDraw(c: Canvas) {
        when (val st = stan) {
            is StanWyspy.Czuwanie -> rysujKropke(c, st.gps)
            is StanWyspy.Zapowiedz -> {
                rysujTlo(c)
                rysujZnak(c, pad, pad, st.odcinek.limit)
                rysujTekst(c, pad + s + gap, listOf("za" to 13f, "${st.dystansM} m" to 20f, "odcinek" to 12f))
            }
            is StanWyspy.NaOdcinku -> {
                rysujTlo(c)
                rysujZnak(c, pad, pad, st.odcinek.limit)
                if (st.nieznanyWjazd) {
                    rysujTekst(c, pad + s + gap, listOf("Brak danych" to 14f, "od wjazdu –" to 14f, "trzymaj limit" to 14f))
                } else {
                    rysujPierscien(c, pad + s + gap, pad, st)
                }
            }
            is StanWyspy.Podsumowanie -> {
                rysujTlo(c)
                rysujZnak(c, pad, pad, st.odcinek.limit)
                val v = st.sredniaKmh?.let { "$it km/h" } ?: "—"
                rysujTekst(c, pad + s + gap, listOf("koniec" to 12f, "średnia" to 13f, v to 18f))
            }
        }
    }

    private fun rysujTlo(c: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(rect, h / 2, h / 2, tlo)
    }

    private fun rysujKropke(c: Canvas, gps: Boolean) {
        val r = width / 2f
        fill.color = Color.argb(200, 120, 120, 120)
        c.drawCircle(r, r, r - dp(1f), fill)
        stroke.color = if (gps) Color.argb(230, 255, 255, 255) else Color.rgb(239, 68, 60)
        stroke.strokeWidth = dp(2f)
        c.drawCircle(r, r, r - dp(2f), stroke)
        if (!gps) {
            c.drawLine(r * 0.55f, r * 0.55f, r * 1.45f, r * 1.45f, stroke)
        } else {
            fill.color = Color.WHITE
            c.drawCircle(r, r, dp(3.5f), fill)
        }
    }

    private fun rysujZnak(c: Canvas, x: Float, y: Float, limit: Int) {
        val r = s / 2
        val cx = x + r
        val cy = y + r
        fill.color = Color.WHITE
        c.drawCircle(cx, cy, r, fill)
        stroke.color = Color.rgb(213, 0, 0)
        stroke.strokeWidth = s * 0.12f
        c.drawCircle(cx, cy, r - stroke.strokeWidth / 2, stroke)
        txt.color = Color.BLACK
        val t = limit.toString()
        txt.textSize = if (t.length >= 3) s * 0.34f else s * 0.42f
        c.drawText(t, cx, cy - (txt.descent() + txt.ascent()) / 2, txt)
    }

    private fun rysujPierscien(c: Canvas, x: Float, y: Float, st: StanWyspy.NaOdcinku) {
        val r = s / 2
        val cx = x + r
        val cy = y + r
        val sw = dp(6f)
        val col = kolor(st.kolor)
        rect.set(cx - r + sw / 2, cy - r + sw / 2, cx + r - sw / 2, cy + r - sw / 2)
        stroke.strokeWidth = sw
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.color = Color.argb(70, 255, 255, 255)
        c.drawArc(rect, 0f, 360f, false, stroke)
        stroke.color = col
        stroke.strokeCap = Paint.Cap.ROUND
        c.drawArc(rect, -90f, 360f * st.postep.coerceIn(0f, 1f), false, stroke)
        stroke.strokeCap = Paint.Cap.BUTT

        txt.color = col
        val v = st.dozwolonaKmh?.toString() ?: "—"
        txt.textSize = if (v.length >= 3) s * 0.34f else s * 0.42f
        c.drawText(v, cx, cy - (txt.descent() + txt.ascent()) / 2, txt)
        if (st.szacowane) {
            txt.textSize = dp(11f)
            c.drawText("≈ GPS", cx, cy + r - dp(13f), txt)
        }
    }

    private fun rysujTekst(c: Canvas, x: Float, linie: List<Pair<String, Float>>) {
        val wys = linie.sumOf { (dp(it.second) * 1.2f).toDouble() }.toFloat()
        var yy = (height - wys) / 2
        for ((t, size) in linie) {
            txtL.textSize = dp(size)
            txtL.typeface = if (size >= 18f) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            yy += dp(size) * 1.2f
            c.drawText(t, x, yy - dp(size) * 0.25f, txtL)
        }
    }

    // --- przesuwanie ---
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var ciagniecie = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; lastX = e.rawX; lastY = e.rawY; ciagniecie = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!ciagniecie && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) ciagniecie = true
                if (ciagniecie) {
                    onDrag((e.rawX - lastX).toInt(), (e.rawY - lastY).toInt())
                    lastX = e.rawX; lastY = e.rawY
                }
            }
            MotionEvent.ACTION_UP -> if (ciagniecie) onDragEnd() else onTap()
            MotionEvent.ACTION_CANCEL -> if (ciagniecie) onDragEnd()
        }
        return true
    }
}
