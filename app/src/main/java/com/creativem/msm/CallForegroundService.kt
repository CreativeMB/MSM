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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.net.URLEncoder

class CallForegroundService : Service() {

    companion object {
        private const val FOREGROUND_CHANNEL_ID = "call_sms_channel_persistent"
        private const val FOREGROUND_NOTIFICATION_ID = 101
        private const val MISSED_CALL_CHANNEL_ID = "missed_call_channel_interactive"
        private const val MISSED_CALL_NOTIFICATION_ID_BASE = 200
    }

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var phoneStateListener: PhoneStateListener

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    // DESPUÉS (CÓDIGO FINAL)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setContentTitle("MSM activo")
            .setContentText("Servicio de llamadas en ejecución.") // Texto más adecuado para algo persistente
            .setSmallIcon(R.drawable.icono)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Prioridad baja es correcta para un servicio silencioso
            .setContentIntent(pendingIntent)
            // --- LÍNEAS NUEVAS AÑADIDAS ---
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // 1. Hacer visible en pantalla de bloqueo
            .setCategory(NotificationCompat.CATEGORY_SERVICE)      // 2. Clasificar como notificación de servicio
            // --------------------------------
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }

        startCallListener()
        return START_STICKY
    }

    private fun startCallListener() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        phoneStateListener = object : PhoneStateListener() {
            var isIncoming = false
            var isCallAnswered = false
            var incomingNumber: String? = null

            override fun onCallStateChanged(state: Int, number: String?) {
                val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
                val serviceActive = prefs.getBoolean("service_active", true)
                if (!serviceActive) return

                if (!number.isNullOrEmpty()) {
                    this.incomingNumber = number
                }

                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        isIncoming = true
                        isCallAnswered = false
                        Log.d("CallService", "📞 Llamada entrante de: ${this.incomingNumber}")
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        if (isIncoming) {
                            isCallAnswered = true
                            Log.d("CallService", "✅ Llamada fue contestada")
                        }
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        if (isIncoming && !isCallAnswered && this.incomingNumber != null) {
                            Log.d("CallService", "📨 Llamada NO contestada: preparando para enviar WhatsApp.")

                            val message = prefs.getString("custom_sms_message", null)
                                ?: "Hola, te devuelvo la llamada en breve."

                            // *** CAMBIO PRINCIPAL: Llamamos a la nueva función de WhatsApp ***
                            showWhatsAppNotification(this.incomingNumber!!, message)
                        }
                        isIncoming = false
                        isCallAnswered = false
                        this.incomingNumber = null
                    }
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } else {
            Log.e("CallService", "❌ Permiso READ_PHONE_STATE no otorgado")
        }
    }

    // =========================================================================================
    // FUNCIÓN MODIFICADA PARA CREAR UNA NOTIFICACIÓN QUE ABRE WHATSAPP
    // =========================================================================================
    @SuppressLint("MissingPermission")
    private fun showWhatsAppNotification(number: String, message: String) {
        val notificationManager = NotificationManagerCompat.from(this)
        if (!notificationManager.areNotificationsEnabled()) {
            Log.e("CallService", "Error: Permiso POST_NOTIFICATIONS denegado. No se puede mostrar la notificación.")
            return
        }

        try {
            // 1. FORMATEAR EL NÚMERO PARA WHATSAPP
            // WhatsApp necesita el código de país y no admite caracteres como '+' o '-'.
            // ¡IMPORTANTE! Debes ajustar esta lógica al prefijo de tu país. Ejemplo con +34 (España).
            var formattedNumber = number.replace("[^0-9]".toRegex(), "")
            if (formattedNumber.length > 8 && !formattedNumber.startsWith("57")) {
                // Si el número es largo y no tiene prefijo, se lo añadimos.
                // formattedNumber = "34$formattedNumber" // -> Descomenta y ajusta a tu país.
            }

            // 2. CODIFICAR EL MENSAJE PARA LA URL
            val encodedMessage = URLEncoder.encode(message, "UTF-8")

            // 3. CREAR EL INTENT PARA ABRIR WHATSAPP
            val whatsappUri = Uri.parse("https://api.whatsapp.com/send?phone=$formattedNumber&text=$encodedMessage")
            val whatsappIntent = Intent(Intent.ACTION_VIEW, whatsappUri).apply {
                // Es importante añadir este flag si el usuario no tiene WhatsApp instalado
                // y se abre el navegador, para que funcione correctamente.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // 4. CREAR EL PENDINGINTENT
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val requestCode = number.hashCode() // ID único para el PendingIntent
            val whatsappPendingIntent = PendingIntent.getActivity(this, requestCode, whatsappIntent, flags)

            // 5. CONSTRUIR LA NOTIFICACIÓN
            val notificationBuilder = NotificationCompat.Builder(this, MISSED_CALL_CHANNEL_ID)
                .setSmallIcon(R.drawable.icono)
                .setContentTitle("Llamada perdida")
                .setContentText("De: $number")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Pulsa para responder por WhatsApp a $number."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true) // La notificación desaparece al pulsarla
                .setContentIntent(whatsappPendingIntent) // Acción al pulsar la notificación
                .addAction( // Botón de acción explícito
                    R.drawable.icono, // Icono para el botón
                    "Enviar WhatsApp", // Texto del botón
                    whatsappPendingIntent
                )

            // 6. MOSTRAR LA NOTIFICACIÓN
            notificationManager.notify(MISSED_CALL_NOTIFICATION_ID_BASE + requestCode, notificationBuilder.build())
            Log.d("CallService", "Notificación para WhatsApp mostrada con éxito para el número $number.")

        } catch (e: Exception) {
            // Captura errores, por ejemplo, si WhatsApp no está instalado (ActivityNotFoundException)
            // o cualquier otro problema inesperado.
            Log.e("CallService", "¡CRASH EVITADO! No se pudo crear la notificación de WhatsApp.", e)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Canal para el servicio en primer plano
            val foregroundChannel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "Servicio Activo",
                // CAMBIA ESTO: De LOW a DEFAULT
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificación persistente para mantener el servicio activo"
                // Opcional: Puedes forzar que no tenga sonido, aunque DEFAULT ya es silencioso
                setSound(null, null)
            }

            // El canal para las llamadas perdidas ya está bien con IMPORTANCE_HIGH
            val missedCallChannel = NotificationChannel(
                MISSED_CALL_CHANNEL_ID,
                "Llamadas Perdidas",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones para responder llamadas perdidas"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(foregroundChannel)
            manager.createNotificationChannel(missedCallChannel)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null

    // El resto de tu código (onTaskRemoved, etc.) puede permanecer igual.
    // Lo incluyo aquí para que el archivo esté completo.
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("CallService", "Tarea removida. Verificando si se debe reiniciar el servicio.")

        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        if (prefs.getBoolean("service_active", true)) {

            var canRestart = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(Context.ROLE_SERVICE) as? RoleManager
                if (roleManager != null && roleManager.isRoleHeld(RoleManager.ROLE_CALL_REDIRECTION)) {
                    canRestart = true
                }
            } else {
                canRestart = true
            }

            if (canRestart) {
                Log.d("CallService", "Reiniciando servicio...")
                val restartIntent = Intent(applicationContext, CallForegroundService::class.java).apply {
                    setPackage(packageName)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(restartIntent)
                } else {
                    startService(restartIntent)
                }
            } else {
                Log.e("CallService", "No se puede reiniciar el servicio, falta el permiso de rol.")
            }
        }
        super.onTaskRemoved(rootIntent)
    }
}