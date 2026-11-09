package pl.matka.opp.android

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import pl.matka.opp.engine.StanWyspy

/** Okno nakładki nad innymi aplikacjami. */
class WyspaOverlay(private val ctx: Context, private val prefs: Prefs) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: WyspaView? = null
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    fun pokaz() {
        if (view != null || !Settings.canDrawOverlays(ctx)) return
        lp.x = prefs.wyspaX
        lp.y = prefs.wyspaY
        val v = WyspaView(
            ctx,
            onDrag = { dx, dy ->
                lp.x = (lp.x + dx).coerceIn(0, maxOf(0, ekranW() - (view?.width ?: 0)))
                lp.y = (lp.y + dy).coerceIn(0, maxOf(0, ekranH() - (view?.height ?: 0)))
                view?.let { runCatching { wm.updateViewLayout(it, lp) } }
            },
            onDragEnd = { prefs.wyspaX = lp.x; prefs.wyspaY = lp.y },
            onTap = {
                ctx.startActivity(
                    Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        )
        runCatching { wm.addView(v, lp) }.onSuccess { view = v }
    }

    fun ustaw(stan: StanWyspy) {
        // Uprawnienie cofnięte w trakcie – system usunął okno; po ponownym nadaniu pokażemy je od nowa.
        if (view != null && !Settings.canDrawOverlays(ctx)) ukryj()
        if (view == null) pokaz()
        view?.stan = stan
    }

    fun ukryj() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun ekranW() = ctx.resources.displayMetrics.widthPixels
    private fun ekranH() = ctx.resources.displayMetrics.heightPixels
}
