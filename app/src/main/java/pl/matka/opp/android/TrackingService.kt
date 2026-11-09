package pl.matka.opp.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import pl.matka.opp.R
import pl.matka.opp.engine.Fix
import pl.matka.opp.engine.StanWyspy
import pl.matka.opp.engine.Tracker
import pl.matka.opp.engine.Zdarzenie
import java.util.concurrent.atomic.AtomicBoolean

/** Usługa pierwszego planu: GPS → silnik → wyspa + dźwięk. */
class TrackingService : Service(), LocationListener {

    companion object {
        const val ACTION_START = "pl.matka.opp.START"
        const val ACTION_STOP = "pl.matka.opp.STOP"
        const val ACTION_BT_POLACZONO = "pl.matka.opp.BT_ON"
        const val ACTION_BT_ROZLACZONO = "pl.matka.opp.BT_OFF"
        const val KANAL = "opp"
        const val NOTIF_ID = 1
        const val STOP_PO_ROZLACZENIU_MS = 2 * 60_000L
        private const val TAG = "TrackingService"

        @Volatile
        var dziala = false
            private set

        fun wyslij(ctx: Context, action: String) {
            val i = Intent(ctx, TrackingService::class.java).setAction(action)
            if (action == ACTION_STOP || action == ACTION_BT_ROZLACZONO) {
                if (dziala) ctx.startService(i)
            } else {
                ContextCompat.startForegroundService(ctx, i)
            }
        }

        fun utworzKanal(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(KANAL, "Odcinkowy pomiar – działanie w tle", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var repo: OdcinkiRepo
    private lateinit var tracker: Tracker
    private lateinit var wyspa: WyspaOverlay
    private lateinit var glos: Powiadamiacz
    private var lm: LocationManager? = null
    private var liczbaOdcinkow = 0
    private var ostatniTekst = ""
    private var naPierwszymPlanie = false
    private val aktualizacjaTrwa = AtomicBoolean(false)

    private val tick = object : Runnable {
        override fun run() {
            obsluz(tracker.onTick(SystemClock.elapsedRealtime()))
            handler.postDelayed(this, 1000)
        }
    }
    private val zatrzymaj = Runnable {
        prefs.rozlaczonoMs = 0L
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        repo = OdcinkiRepo(this)
        val dane = repo.wczytaj()
        liczbaOdcinkow = dane.odcinki.size
        tracker = Tracker(dane.odcinki, repo.wczytajFotoradary(), { prefs.fotoradary }) { prefs.zapasKmh }
        wyspa = WyspaOverlay(this, prefs)
        glos = Powiadamiacz(this, prefs)
        utworzKanal(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            prefs.rozlaczonoMs = 0L
            stopSelf()
            return START_NOT_STICKY
        }
        // startForeground tylko przy starcie – STOP/BT_OFF przychodzą zwykłym startService i nie mogą
        // ponownie przechodzić kontroli typu „location” z tła (Android 14+).
        if (!naPierwszymPlanie || action == ACTION_START || action == ACTION_BT_POLACZONO) {
            try {
                startForegroundCompat(powiadomienie("Czuwam – $liczbaOdcinkow odcinków"))
                naPierwszymPlanie = true
            } catch (e: Exception) {
                Log.e(TAG, "Nie można uruchomić usługi pierwszego planu", e)
                if (!naPierwszymPlanie) {
                    BluetoothReceiver.powiadomOStarcie(
                        this, BluetoothReceiver.brakiDoAutostartu(this).joinToString(", ").ifEmpty { null },
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        dziala = true
        getSystemService(NotificationManager::class.java).cancel(BluetoothReceiver.NOTIF_START_ID)
        when (action) {
            ACTION_BT_ROZLACZONO -> {
                prefs.rozlaczonoMs = System.currentTimeMillis()
                handler.removeCallbacks(zatrzymaj)
                handler.postDelayed(zatrzymaj, STOP_PO_ROZLACZENIU_MS)
            }
            null -> {
                // Restart przez system (START_STICKY) – timer z Handlera przepadł razem z procesem.
                val rozl = prefs.rozlaczonoMs
                if (rozl > 0) {
                    val zostalo = STOP_PO_ROZLACZENIU_MS - (System.currentTimeMillis() - rozl)
                    if (zostalo <= 0) {
                        prefs.rozlaczonoMs = 0L
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    handler.removeCallbacks(zatrzymaj)
                    handler.postDelayed(zatrzymaj, zostalo)
                }
            }
            else -> {
                prefs.rozlaczonoMs = 0L
                handler.removeCallbacks(zatrzymaj)
            }
        }
        startGps()
        wyspa.pokaz()
        wyspa.ustaw(tracker.stan)
        handler.removeCallbacks(tick)
        handler.post(tick)
        sprawdzAktualizacje()
        return START_STICKY
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (lm != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            aktualizujPowiadomienie("Brak uprawnień do lokalizacji")
            return
        }
        val m = getSystemService(LocationManager::class.java)
        m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        lm = m
    }

    override fun onLocationChanged(loc: Location) {
        val t = loc.elapsedRealtimeNanos / 1_000_000L
        val fix = Fix(
            timeMs = if (t > 0) t else SystemClock.elapsedRealtime(),
            lat = loc.latitude,
            lon = loc.longitude,
            speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
            bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
        )
        obsluz(tracker.onFix(fix))
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun obsluz(ev: List<Zdarzenie>) {
        ev.forEach { glos.obsluz(it) }
        val st = tracker.stan
        wyspa.ustaw(st)
        val tekst = when (st) {
            is StanWyspy.NaOdcinku -> "Na odcinku ${st.odcinek.nazwa} – limit ${st.odcinek.limit}"
            is StanWyspy.Zapowiedz -> "Za ${st.dystansM} m odcinek, limit ${st.odcinek.limit}"
            is StanWyspy.Podsumowanie -> "Koniec odcinka, średnia ${st.sredniaKmh ?: "—"} km/h"
            is StanWyspy.Czuwanie -> if (st.gps) "Czuwam – $liczbaOdcinkow odcinków" else "Czekam na GPS…"
        }
        if (tekst != ostatniTekst) aktualizujPowiadomienie(tekst)
    }

    private fun sprawdzAktualizacje() {
        if (!repo.czyPora()) return
        // Bez sieci każde połączenie z autem odpalałoby kolejny wątek – jeden naraz wystarczy.
        if (!aktualizacjaTrwa.compareAndSet(false, true)) return
        Thread {
            val r = try {
                repo.aktualizujFotoradary()
                    .onSuccess { nowe -> if (nowe) { val l = repo.wczytajFotoradary(); handler.post { tracker.ustawFotoradary(l) } } }
                    .onFailure { Log.w(TAG, "Aktualizacja fotoradarów nieudana", it) }
                repo.aktualizuj()
            } finally { aktualizacjaTrwa.set(false) }
            r.getOrNull()?.let { nowe ->
                handler.post {
                    if (tracker.ustawOdcinki(nowe.odcinki)) liczbaOdcinkow = nowe.odcinki.size
                }
            }
            r.exceptionOrNull()?.let { Log.w(TAG, "Aktualizacja odcinków nieudana", it) }
        }.start()
    }

    private fun powiadomienie(tekst: String): Notification {
        val otworz = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, KANAL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Odcinkowy pomiar")
            .setContentText(tekst)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(otworz)
            .addAction(0, "Zatrzymaj", stop)
            .build()
    }

    private fun aktualizujPowiadomienie(tekst: String) {
        ostatniTekst = tekst
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, powiadomienie(tekst))
    }

    override fun onDestroy() {
        dziala = false
        naPierwszymPlanie = false
        handler.removeCallbacksAndMessages(null)
        lm?.removeUpdates(this)
        lm = null
        wyspa.ukryj()
        glos.zamknij()
        super.onDestroy()
    }
}
