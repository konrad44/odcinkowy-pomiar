package pl.matka.opp.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ekran ustawień i uprawnień. Budowany w kodzie – bez XML. */
class MainActivity : Activity() {

    companion object {
        const val ACTION_URUCHOM = "pl.matka.opp.URUCHOM"
        private const val RC_LOK = 1
        private const val RC_TLO = 2
        private const val RC_BT = 3
        private const val RC_NOTIF = 4
    }

    private lateinit var prefs: Prefs
    private lateinit var repo: OdcinkiRepo
    private lateinit var root: LinearLayout
    private val d by lazy { resources.displayMetrics.density }
    private var glosTest: Powiadamiacz? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        repo = OdcinkiRepo(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * d).toInt()
            setPadding(p, p, p, p)
        }
        val scroll = ScrollView(this).apply {
            addView(root)
            clipToPadding = false
            // Android 15+ rysuje pod paskami systemowymi – odsuwamy treść.
            if (Build.VERSION.SDK_INT >= 35) setOnApplyWindowInsetsListener { v, insets ->
                val typy = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                val i = insets.getInsets(typy)
                v.setPadding(i.left, i.top, i.right, i.bottom)
                insets
            }
        }
        setContentView(scroll)
        if (intent?.action == ACTION_URUCHOM) uruchom()
    }

    override fun onResume() {
        super.onResume()
        zbuduj()
    }

    override fun onDestroy() {
        glosTest?.zamknij()
        super.onDestroy()
    }

    private fun ma(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun zbuduj() {
        root.removeAllViews()
        naglowek("Odcinkowy Pomiar Prędkości", 22f)

        // --- Start / stop ---
        val stan = if (TrackingService.dziala) "Działa" else "Zatrzymana"
        tekst("Usługa: $stan")
        rzad(
            przycisk("Uruchom") { uruchom() },
            przycisk("Zatrzymaj") {
                TrackingService.wyslij(this, TrackingService.ACTION_STOP)
                root.postDelayed({ zbuduj() }, 300)
            },
        )

        // --- Uprawnienia ---
        sekcja("Uprawnienia")
        val lok = ma(Manifest.permission.ACCESS_FINE_LOCATION)
        uprawnienie("Lokalizacja (dokładna)", lok) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), RC_LOK
            )
        }
        if (Build.VERSION.SDK_INT >= 29) {
            uprawnienie("Lokalizacja w tle („Zezwalaj zawsze”)", ma(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                if (!lok) toast("Najpierw zwykła lokalizacja")
                else requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), RC_TLO)
            }
        }
        val nakladka = Settings.canDrawOverlays(this)
        uprawnienie("Wyświetlanie nad innymi aplikacjami", nakladka) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (!nakladka && Build.VERSION.SDK_INT >= 33) {
            tekst(
                "Przełącznik nieaktywny („Aplikacja nie otrzymała dostępu”)? Android blokuje to dla APK spoza sklepu. " +
                    "Otwórz ustawienia aplikacji → ⋮ (prawy górny róg) → „Zezwalaj na ustawienia z ograniczeniami”, " +
                    "potem wróć i dotknij „Nadaj”.", 13f,
            )
            root.addView(przycisk("Otwórz ustawienia aplikacji") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        }
        if (Build.VERSION.SDK_INT >= 31) {
            uprawnienie("Bluetooth (wykrywanie auta)", ma(Manifest.permission.BLUETOOTH_CONNECT)) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), RC_BT)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            uprawnienie("Powiadomienia", ma(Manifest.permission.POST_NOTIFICATIONS)) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_NOTIF)
            }
        }
        val pm = getSystemService(PowerManager::class.java)
        uprawnienie("Bateria bez ograniczeń (start po Bluetooth)", pm.isIgnoringBatteryOptimizations(packageName)) {
            @SuppressLint("BatteryLife")
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            startActivity(i)
        }

        // --- Bluetooth auta ---
        sekcja("Samochód (Bluetooth)")
        tekst("Zaznacz urządzenie auta – po połączeniu aplikacja uruchomi się sama, po rozłączeniu wyłączy po 2 min.", 13f)
        if (Build.VERSION.SDK_INT >= 31 && !ma(Manifest.permission.BLUETOOTH_CONNECT)) {
            tekst("Najpierw nadaj uprawnienie Bluetooth.", 13f)
        } else {
            listaBt()
        }

        // --- Zapas ---
        sekcja("Zapas prędkości")
        val zapasTxt = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            minWidth = (90 * d).toInt()
            text = "${prefs.zapasKmh} km/h"
        }
        rzad(
            przycisk("−") { prefs.zapasKmh = prefs.zapasKmh - 1; zapasTxt.text = "${prefs.zapasKmh} km/h" },
            zapasTxt,
            przycisk("+") { prefs.zapasKmh = prefs.zapasKmh + 1; zapasTxt.text = "${prefs.zapasKmh} km/h" },
        )
        tekst("Dozwolona prędkość liczona jest dla średniej = limit − zapas.", 13f)

        // --- Tryb ---
        sekcja("Powiadomienia dźwiękowe")
        val rg = RadioGroup(this)
        Tryb.entries.forEach { t ->
            rg.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = when (t) {
                    Tryb.SYGNAL -> "Sygnał dźwiękowy"
                    Tryb.GLOS -> "Komunikaty głosowe"
                    Tryb.CICHY -> "Cichy (tylko wyspa)"
                }
                isChecked = prefs.tryb == t
                setOnCheckedChangeListener { _, on -> if (on) prefs.tryb = t }
            })
        }
        root.addView(rg)
        root.addView(przycisk("Test dźwięku") {
            val g = glosTest ?: Powiadamiacz(this, prefs).also { glosTest = it }
            root.postDelayed({ g.test() }, if (glosTest == g) 300 else 800)
        })

        // --- Dane ---
        sekcja("Dane odcinków")
        val dane = repo.wczytaj()
        val spr = prefs.ostatnieSprawdzenie.let {
            if (it == 0L) "nigdy" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))
        }
        tekst("${dane.odcinki.size} odcinków (oba kierunki osobno), dane z ${dane.aktualizacja}.\nOstatnie sprawdzenie aktualizacji: $spr", 13f)
        root.addView(przycisk("Sprawdź aktualizację") {
            toast("Sprawdzam…")
            Thread {
                val r = repo.aktualizuj()
                runOnUiThread {
                    r.onSuccess { toast(if (it == null) "Brak zmian" else "Pobrano ${it.odcinki.size} odcinków") }
                        .onFailure { toast("Błąd: ${it.message}") }
                    zbuduj()
                }
            }.start()
        })
        tekst("Po aktualizacji danych uruchom usługę ponownie (lub poczekaj do następnej jazdy).", 12f)
    }

    @SuppressLint("MissingPermission")
    private fun listaBt() {
        val bm = getSystemService(BluetoothManager::class.java)
        val ad = bm?.adapter
        val sparowane = runCatching { ad?.bondedDevices?.toList() }.getOrNull().orEmpty()
        if (sparowane.isEmpty()) {
            tekst("Brak sparowanych urządzeń (lub Bluetooth wyłączony).", 13f)
            return
        }
        val wybrane = prefs.urzadzenia
        sparowane.sortedBy { it.name ?: it.address }.forEach { dev ->
            root.addView(CheckBox(this).apply {
                text = "${dev.name ?: "?"}  (${dev.address})"
                isChecked = dev.address in wybrane
                setOnCheckedChangeListener { _, on ->
                    prefs.urzadzenia = if (on) prefs.urzadzenia + dev.address else prefs.urzadzenia - dev.address
                }
            })
        }
    }

    private fun uruchom() {
        if (!ma(Manifest.permission.ACCESS_FINE_LOCATION)) {
            toast("Nadaj uprawnienie do lokalizacji")
            return
        }
        TrackingService.wyslij(this, TrackingService.ACTION_START)
        if (!Settings.canDrawOverlays(this)) toast("Bez uprawnienia nakładki wyspa się nie pokaże")
        root.postDelayed({ zbuduj() }, 300)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        zbuduj()
    }

    // --- pomocnicze widoki ---
    private fun naglowek(t: String, size: Float) {
        root.addView(TextView(this).apply {
            text = t
            textSize = size
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, (8 * d).toInt())
        })
    }

    private fun sekcja(t: String) {
        root.addView(TextView(this).apply {
            text = t
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (20 * d).toInt(), 0, (6 * d).toInt())
        })
    }

    private fun tekst(t: String, size: Float = 15f) {
        root.addView(TextView(this).apply { text = t; textSize = size })
    }

    private fun przycisk(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun rzad(vararg v: View) {
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            v.forEach { addView(it) }
        })
    }

    private fun uprawnienie(nazwa: String, ok: Boolean, nadaj: () -> Unit) {
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = if (ok) "✓  $nazwa" else "✗  $nazwa"
                setTextColor(if (ok) Color.rgb(46, 125, 50) else Color.rgb(198, 40, 40))
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (!ok) addView(przycisk("Nadaj") { nadaj() })
        })
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
