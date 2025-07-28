package com.creativem.msm

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.preference.PreferenceManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.net.URLEncoder
import android.Manifest
import androidx.core.app.ActivityCompat


class CallForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "call_sms_channel"
        private const val NOTIF_ID = 101
        private const val MISSED_CALL_NOTIF_ID = 200
    }

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var phoneStateListener: PhoneStateListener

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupCallListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notifIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MSM activo")
            .setContentText("Escuchando llamadas para enviar mensaje")
            .setSmallIcon(R.drawable.icono)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID, notification)
        return START_STICKY
    }

    private fun setupCallListener() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        phoneStateListener = object : PhoneStateListener() {
            var incomingNumber: String? = null
            var isIncoming = false
            var isAnswered = false

            override fun onCallStateChanged(state: Int, number: String?) {
                if (!PreferenceManager.getDefaultSharedPreferences(applicationContext)
                        .getBoolean("service_active", true)
                ) return

                if (!number.isNullOrBlank()) incomingNumber = number

                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        isIncoming = true
                        isAnswered = false
                        Log.d("CallService", "📞 Llamada entrante: $incomingNumber")
                    }

                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        if (isIncoming) isAnswered = true
                    }

                    TelephonyManager.CALL_STATE_IDLE -> {
                        if (isIncoming && !isAnswered && incomingNumber != null) {
                            val message = PreferenceManager.getDefaultSharedPreferences(applicationContext)
                                .getString("custom_sms_message", "Hola, te devuelvo la llamada en breve.")
                            showWhatsAppNotification(incomingNumber!!, message!!)
                        }
                        isIncoming = false
                        isAnswered = false
                        incomingNumber = null
                    }
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun showWhatsAppNotification(number: String, message: String) {
        val notificationManager = NotificationManagerCompat.from(this)
        if (!notificationManager.areNotificationsEnabled()) return

        val formattedNumber = number.replace("[^\\d]".toRegex(), "").let {
            if (it.length == 10 && !it.startsWith("57")) "57$it" else it
        }

        val uri = Uri.parse("https://wa.me/$formattedNumber?text=${URLEncoder.encode(message, "UTF-8")}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val pendingIntent = PendingIntent.getActivity(this, number.hashCode(), intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.icono)
            .setContentTitle("Llamada perdida")
            .setContentText("Pulsa para responder por WhatsApp")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.icono, "Responder", pendingIntent)
            .build()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        notificationManager.notify(MISSED_CALL_NOTIF_ID + number.hashCode(), notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Servicio de llamada",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Si deseas reiniciarlo, hazlo aquí. Sin roles ni restricciones.
        super.onTaskRemoved(rootIntent)
    }
}