package pl.matka.opp.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import pl.matka.opp.R

/** Start/stop usługi po połączeniu/rozłączeniu z Bluetoothem auta. */
class BluetoothReceiver : BroadcastReceiver() {
    companion object {
        const val KANAL_ALERT = "opp-alert"
        const val NOTIF_START_ID = 2

        /**
         * Czego brakuje, żeby usługa mogła wystartować sama z tła (bez otwierania aplikacji).
         * Pusta lista = wszystko gotowe.
         */
        fun brakiDoAutostartu(ctx: Context): List<String> {
            fun ma(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
            val braki = ArrayList<String>()
            if (!ma(Manifest.permission.ACCESS_FINE_LOCATION)) braki += "lokalizacja"
            if (Build.VERSION.SDK_INT >= 29 && !ma(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                braki += "lokalizacja „Zezwalaj zawsze”"
            }
            // Od Androida 12 start usługi z tła jest dozwolony tylko bez optymalizacji baterii.
            if (Build.VERSION.SDK_INT >= 31 &&
                !ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
            ) braki += "bateria bez ograniczeń"
            return braki
        }

        fun powiadomOStarcie(ctx: Context, powod: String?) {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(KANAL_ALERT, "Odcinkowy pomiar – uruchomienie", NotificationManager.IMPORTANCE_HIGH)
            )
            val pi = PendingIntent.getActivity(
                ctx, 2,
                Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_URUCHOM)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val tekst = if (powod == null) "Dotknij, aby uruchomić odcinkowy pomiar"
            else "Dotknij, aby uruchomić. Do startu automatycznego brakuje: $powod"
            val n = NotificationCompat.Builder(ctx, KANAL_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Połączono z autem")
                .setContentText(tekst)
                .setStyle(NotificationCompat.BigTextStyle().bigText(tekst))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_START_ID, n) }
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val adres = dev?.address ?: return
        if (adres !in Prefs(ctx).urzadzenia) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                // Bez tych uprawnień Android zablokuje start albo usługa nie dostanie GPS – od razu prosimy o dotknięcie.
                val braki = brakiDoAutostartu(ctx)
                if (braki.isNotEmpty()) {
                    powiadomOStarcie(ctx, braki.joinToString(", "))
                    return
                }
                try {
                    TrackingService.wyslij(ctx, TrackingService.ACTION_BT_POLACZONO)
                } catch (e: Exception) {
                    // Android może zablokować start z tła mimo uprawnień (np. tryb oszczędzania producenta).
                    Log.w("BluetoothReceiver", "Start z tła zablokowany", e)
                    powiadomOStarcie(ctx, null)
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                TrackingService.wyslij(ctx, TrackingService.ACTION_BT_ROZLACZONO)
        }
    }
}
