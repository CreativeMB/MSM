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

class CallForegroundService : Service() {

    companion object {
        // Canal para la notificación persistente del servicio
        private const val FOREGROUND_CHANNEL_ID = "call_sms_channel_persistent"
        private const val FOREGROUND_NOTIFICATION_ID = 101

        // Canal para las notificaciones de llamada perdida (estas sí deben ser visibles)
        private const val MISSED_CALL_CHANNEL_ID = "missed_call_channel_interactive"
        private const val MISSED_CALL_NOTIFICATION_ID_BASE = 200 // Usaremos esto como base para ids únicas
    }

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var phoneStateListener: PhoneStateListener

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels() // Creamos ambos canales al iniciar
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setContentTitle("MSM activo")
            .setContentText("Escuchando llamadas entrantes...")
            .setSmallIcon(R.drawable.icono) // Asegúrate de que este icono exista
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL // Tipo de servicio más apropiado
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

                // Usamos el número que llega en el evento, es más fiable.
                // En algunos teléfonos, el parámetro `number` solo llega con RINGING.
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
                            Log.d("CallService", "📨 Llamada NO contestada: mostrando notificación.")

                            val message = prefs.getString("custom_sms_message", null)
                                ?: "Hola, te devuelvo la llamada en breve."

                            // *** CAMBIO CLAVE: En lugar de abrir la app, mostramos una notificación ***
                            showMissedCallNotification(this.incomingNumber!!, message)
                        }

                        // Resetear estado para la siguiente llamada
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

    // En CallForegroundService.kt

    @SuppressLint("MissingPermission")
    private fun showMissedCallNotification(number: String, message: String) {
        // Obtenemos el gestor de notificaciones
        val notificationManager = NotificationManagerCompat.from(this)

        // --- VERIFICACIÓN DE PERMISOS (CLAVE #1) ---
        // Antes de hacer nada, comprobamos si tenemos permiso para enviar notificaciones.
        if (!notificationManager.areNotificationsEnabled()) {
            // Si no tenemos permiso, lo registramos en el Logcat y salimos de la función.
            // Esto evita el crash.
            Log.e("CallService", "Error: Permiso POST_NOTIFICATIONS denegado. No se puede mostrar la notificación.")
            return
        }

        // 1. Crear el Intent que queremos ejecutar (abrir SMS) - (Tu código original, está perfecto)
        val smsUri = Uri.parse("smsto:$number")
        val smsIntent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
            putExtra("sms_body", message)
        }

        // 2. Crear el PendingIntent - (Tu código original, está perfecto)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val requestCode = number.hashCode()
        val smsPendingIntent = PendingIntent.getActivity(this, requestCode, smsIntent, flags)

        // 3. Construir la notificación - (Tu código original, está perfecto)
        val notificationBuilder = NotificationCompat.Builder(this, MISSED_CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.icono)
            .setContentTitle("Llamada perdida")
            .setContentText("De: $number")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(smsPendingIntent)
            .addAction(
                R.drawable.icono,
                "Enviar Mensaje",
                smsPendingIntent
            )

        // --- LLAMADA SEGURA A NOTIFY (CLAVE #2) ---
        // Envolvemos la llamada a notify() en un bloque try-catch como última línea de defensa.
        try {
            Log.d("CallService", "Intentando mostrar notificación para $number. Permisos OK.")
            // Usamos el notificationManager que obtuvimos al principio.
            notificationManager.notify(MISSED_CALL_NOTIFICATION_ID_BASE + requestCode, notificationBuilder.build())
            Log.d("CallService", "Notificación enviada al sistema con éxito.")
        } catch (e: SecurityException) {
            // Si por alguna razón la verificación anterior falla, este catch evitará el crash.
            Log.e("CallService", "¡CRASH EVITADO! SecurityException: Falta el permiso POST_NOTIFICATIONS.", e)
        } catch (e: Exception) {
            // Capturamos cualquier otro error inesperado.
            Log.e("CallService", "¡CRASH EVITADO! Ocurrió una excepción inesperada al mostrar la notificación.", e)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Canal para el servicio en primer plano (baja importancia, sin sonido)
            val foregroundChannel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "Servicio Activo",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificación persistente para mantener el servicio activo"
            }

            // Canal para las llamadas perdidas (alta importancia, con sonido/vibración)
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