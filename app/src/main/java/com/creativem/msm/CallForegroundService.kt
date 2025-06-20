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


class CallForegroundService : Service() {

    companion object {
        private const val FOREGROUND_CHANNEL_ID = "call_sms_channel_persistent"
        private const val FOREGROUND_NOTIFICATION_ID = 101
        private const val MISSED_CALL_CHANNEL_ID = "missed_call_channel_interactive"
        private const val MISSED_CALL_NOTIFICATION_ID_BASE = 200
    }

    // Manager por defecto para crear el específico de la SIM
    private lateinit var defaultTelephonyManager: TelephonyManager
    // Manager que escuchará en una SIM específica
    private var targetedTelephonyManager: TelephonyManager? = null
    private lateinit var phoneStateListener: PhoneStateListener

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setContentTitle("✅ Servicio de respuesta activo")
            .setContentText("Mensaje listo. Toca para enviar")
            .setSmallIcon(R.drawable.icono) // Asegúrate de tener este icono
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // Prioridad para visibilidad en lock screen
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
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

        // Inicia o reinicia la escucha con la configuración actual
        startCallListener()
        return START_STICKY
    }

    /**
     * MÉTODO MODIFICADO PARA LA LÓGICA DE SIM DUAL
     */
    private fun startCallListener() {
        // Inicializa el manager por defecto si no lo está
        if (!::defaultTelephonyManager.isInitialized) {
            defaultTelephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        }

        // Detiene cualquier escucha anterior para evitar duplicados
        targetedTelephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)

        // 1. LEE LA SIM SELECCIONADA POR EL USUARIO
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val selectedSubId = prefs.getInt("selected_sim_subscription_id", -1)

        if (selectedSubId == -1) {
            Log.w("CallService", "No se ha seleccionado una SIM. El servicio no escuchará llamadas.")
            return // Salimos si no hay SIM seleccionada
        }

        Log.d("CallService", "Configurando escucha para la Subscription ID: $selectedSubId")

        phoneStateListener = object : PhoneStateListener() {
            var isIncoming = false
            var isCallAnswered = false
            var incomingNumber: String? = null

            override fun onCallStateChanged(state: Int, number: String?) {
                val serviceActive = prefs.getBoolean("service_active", true)
                if (!serviceActive) return

                if (!number.isNullOrEmpty()) {
                    this.incomingNumber = number
                }

                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        isIncoming = true
                        isCallAnswered = false
                        Log.d("CallService", "📞 Llamada entrante de: ${this.incomingNumber} en la SIM monitoreada.")
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
                            val message = prefs.getString("custom_sms_message", null) ?: "Hola, te devuelvo la llamada en breve."
                            showWhatsAppNotification(this.incomingNumber!!, message)
                        }
                        isIncoming = false
                        isCallAnswered = false
                        this.incomingNumber = null
                    }
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            // 2. CREA UN TELEPHONYMANAGER ESPECÍFICO PARA ESA SIM
            targetedTelephonyManager = defaultTelephonyManager.createForSubscriptionId(selectedSubId)

            // 3. REGISTRA EL LISTENER EN ESE MANAGER ESPECÍFICO
            targetedTelephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            Log.i("CallService", "Escuchando llamadas activamente en la SIM con ID: $selectedSubId")
        } else {
            Log.e("CallService", "❌ Permiso READ_PHONE_STATE no otorgado")
        }
    }

    @SuppressLint("MissingPermission")
    private fun showWhatsAppNotification(number: String, message: String) {
        val notificationManager = NotificationManagerCompat.from(this)
        if (!notificationManager.areNotificationsEnabled()) {
            Log.e("CallService", "Error: Permiso POST_NOTIFICATIONS denegado.")
            return
        }

        try {
            var formattedNumber = number.replace("[^0-9]".toRegex(), "")
            if (formattedNumber.length == 10 && !formattedNumber.startsWith("57")) {
                formattedNumber = "57$formattedNumber"
            }

            // --- LÓGICA DE INTENT MEJORADA ---
            val whatsAppBusinessPackage = "com.whatsapp.w4b"
            val whatsAppPackage = "com.whatsapp"
            val packageManager = this.packageManager

            // Preparamos el Intent
            val whatsappIntent = Intent(Intent.ACTION_VIEW)
            val encodedMessage = URLEncoder.encode(message, "UTF-8")

            // Prioridad 1: Intentar con WhatsApp Business de forma directa
            if (isPackageInstalled(whatsAppBusinessPackage, packageManager)) {
                Log.d("CallService", "WhatsApp Business encontrado. Creando Intent directo.")
                val uri = Uri.parse("https://wa.me/$formattedNumber?text=$encodedMessage")
                whatsappIntent.data = uri
                whatsappIntent.setPackage(whatsAppBusinessPackage)
            }
            // Prioridad 2: Si no, intentar con WhatsApp Personal de forma directa
            else if (isPackageInstalled(whatsAppPackage, packageManager)) {
                Log.w("CallService", "WhatsApp Business no encontrado. Intentando con WhatsApp personal.")
                val uri = Uri.parse("https://wa.me/$formattedNumber?text=$encodedMessage")
                whatsappIntent.data = uri
                whatsappIntent.setPackage(whatsAppPackage)
            }
            // Prioridad 3: Como último recurso, usar la URL genérica (abrirá el navegador si no hay WhatsApp)
            else {
                Log.e("CallService", "No se encontró ninguna versión de WhatsApp. Usando URL genérica.")
                whatsappIntent.data = Uri.parse("https://api.whatsapp.com/send?phone=$formattedNumber&text=$encodedMessage")
            }

            whatsappIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // --- FIN DE LA LÓGICA DE INTENT MEJORADA ---


            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val requestCode = number.hashCode()
            val whatsappPendingIntent = PendingIntent.getActivity(this, requestCode, whatsappIntent, flags)

            val notificationBuilder = NotificationCompat.Builder(this, MISSED_CALL_CHANNEL_ID)
                .setSmallIcon(R.drawable.icono)
                .setContentTitle("Llamada perdida")
                .setContentText("De: $number")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Pulsa para responder por WhatsApp a $number."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(whatsappPendingIntent)
                .addAction(R.drawable.icono, "Enviar WhatsApp", whatsappPendingIntent)

            notificationManager.notify(MISSED_CALL_NOTIFICATION_ID_BASE + requestCode, notificationBuilder.build())
            Log.d("CallService", "Notificación para WhatsApp mostrada para el número $number.")
        } catch (e: Exception) {
            Log.e("CallService", "¡CRASH EVITADO! No se pudo crear la notificación de WhatsApp.", e)
        }
    }

    private fun isPackageInstalled(packageName: String, packageManager: PackageManager): Boolean {
        return try {
            // Usar getPackageInfo para una verificación robusta
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val foregroundChannel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "Servicio Activo",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificación persistente para mantener el servicio activo"
                setSound(null, null)
            }

            val missedCallChannel = NotificationChannel(
                MISSED_CALL_CHANNEL_ID,
                "Llamadas Perdidas",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(foregroundChannel)
            manager.createNotificationChannel(missedCallChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // Es muy importante detener la escucha para liberar recursos y evitar memory leaks
        targetedTelephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        Log.d("CallService", "Servicio destruido, escucha de llamadas detenida.")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("CallService", "Tarea removida. Verificando si se debe reiniciar el servicio.")
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        if (prefs.getBoolean("service_active", true)) {
            var canRestart = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(Context.ROLE_SERVICE) as? RoleManager
                roleManager?.isRoleHeld(RoleManager.ROLE_CALL_REDIRECTION) == true
            } else {
                true
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