package com.creativem.msm

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

class MainActivity : AppCompatActivity() {

    private lateinit var messageInput: EditText
    private lateinit var btnSaveMessage: TextView
    private lateinit var toggleService: TextView
    private lateinit var btnHideApp: TextView

    // Lanzador único para todos los permisos necesarios.
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // Comprobamos si TODOS los permisos solicitados fueron concedidos.
            if (permissions.entries.all { it.value }) {
                Toast.makeText(this, "Todos los permisos fueron concedidos.", Toast.LENGTH_SHORT).show()
                // Después de obtener permisos, intentamos las optimizaciones de batería.
                requestBatteryOptimizations()
            } else {
                Toast.makeText(this, "Algunos permisos fueron denegados. La app podría no funcionar correctamente.", Toast.LENGTH_LONG).show()
            }
            // En cualquier caso (concedido o denegado), actualizamos la UI y el estado del servicio.
            updateUiAndServiceStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        messageInput = findViewById(R.id.messageInput)
        btnSaveMessage = findViewById(R.id.btnSaveMessage)
        toggleService = findViewById(R.id.toggleService)
        btnHideApp = findViewById(R.id.btnHideApp)

        setupClickListeners()

        // Al iniciar, comprobamos los permisos.
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Cada vez que la app vuelve a primer plano, es bueno refrescar el estado.
        updateUiAndServiceStatus()
    }

    private fun setupClickListeners() {
        toggleService.setOnClickListener {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val isCurrentlyActive = prefs.getBoolean("service_active", true)
            val isNowActive = !isCurrentlyActive
            prefs.edit().putBoolean("service_active", isNowActive).apply()

            Toast.makeText(this, if (isNowActive) "Servicio activado" else "Servicio desactivado", Toast.LENGTH_SHORT).show()

            // Si el usuario acaba de activar el servicio, debemos asegurarnos de tener permisos.
            if (isNowActive) {
                checkAndRequestPermissions()
            } else {
                updateUiAndServiceStatus() // Esto detendrá el servicio.
            }
        }

        btnSaveMessage.setOnClickListener {
            val message = messageInput.text.toString()
            if (message.isNotBlank()) {
                PreferenceManager.getDefaultSharedPreferences(this).edit().putString("custom_sms_message", message).apply()
                Toast.makeText(this, "Mensaje guardado", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "El mensaje no puede estar vacío", Toast.LENGTH_SHORT).show()
            }
        }

        btnHideApp.setOnClickListener {
            moveTaskToBack(true)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.RECEIVE_BOOT_COMPLETED
        )

        // Permiso de Notificaciones para Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Permiso para gestionar llamadas, requerido por el servicio en Android 14+
        // Este es el permiso clave que resuelve el crash.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissionsToRequest.add(Manifest.permission.MANAGE_OWN_CALLS)
        }

        // Permiso para el tipo de servicio en primer plano, requerido en Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissionsToRequest.add(Manifest.permission.FOREGROUND_SERVICE_PHONE_CALL)
        }

        // Filtramos para pedir solo los que no tenemos.
        val permissionsNeeded = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsNeeded.isNotEmpty()) {
            // Si falta algún permiso, los solicitamos todos juntos.
            requestPermissionLauncher.launch(permissionsNeeded.toTypedArray())
        } else {
            // Si ya tenemos todos los permisos, simplemente actualizamos el estado.
            updateUiAndServiceStatus()
        }
    }

    private fun updateUiAndServiceStatus() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val serviceActive = prefs.getBoolean("service_active", true)

        updateToggleServiceButton(serviceActive)

        val defaultMessage = """
        💗Di lo que quieras y cuando quieras con flores♥️
        ✨Bienvenido a 🌹Floristería Los Lirios🌹
        📖 Excelente catálogo con una gran variedad de productos disponibles en 👉 www.floristerialoslirios.com

        📲 Atendemos exclusivamente por WhatsApp, las llamadas no son atendidas por política de la empresa.
        🧑‍💻 Por favor, envía la referencia del arreglo, dirección, fecha y hora de entrega
        🚘 ¡Nosotros lo entregamos por ti!

        🕐 Servicio disponible 24/7 por este medio. ¡Escríbenos y con gusto te ayudamos! 💐
    """.trimIndent()

        messageInput.setText(prefs.getString("custom_sms_message", defaultMessage))

        // Comprobamos si tenemos los permisos MÍNIMOS para INICIAR el servicio.
        val hasRequiredPermissions =
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                            ContextCompat.checkSelfPermission(this, Manifest.permission.MANAGE_OWN_CALLS) == PackageManager.PERMISSION_GRANTED)

        if (serviceActive && hasRequiredPermissions) {
            Log.d("AppStatus", "Permisos OK. Iniciando servicio.")
            startForegroundCallService()
        } else {
            Log.w("AppStatus", "Servicio desactivado o faltan permisos. Deteniendo servicio.")
            stopService(Intent(this, CallForegroundService::class.java))
            if (serviceActive && !hasRequiredPermissions) {
                Toast.makeText(this, "Faltan permisos. El servicio no puede iniciar.", Toast.LENGTH_LONG).show()
            }
        }
    }


    private fun updateToggleServiceButton(isActive: Boolean) {
        toggleService.text = if (isActive) "Desactivar servicio" else "Activar servicio"
    }

    private fun startForegroundCallService() {
        val serviceIntent = Intent(this, CallForegroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    // Esta función combina las dos optimizaciones de batería.
    private fun requestBatteryOptimizations() {
        // 1. Petición estándar de Android
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$packageName") }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.w("BatteryOpt", "No se pudo abrir la pantalla de optimización de batería estándar.", e)
                }
            }
        }

        // 2. Petición específica de Honor/Huawei
        if (Build.MANUFACTURER.equals("huawei", ignoreCase = true) || Build.MANUFACTURER.equals("honor", ignoreCase = true)) {
            try {
                val intent = Intent().apply {
                    component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                }
                Toast.makeText(this, "IMPORTANTE: Busca 'MSM', desactiva el interruptor y activa las 3 opciones manualmente.", Toast.LENGTH_LONG).show()
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("HonorSettings", "No se pudo abrir la pantalla de 'Inicio de aplicaciones'.", e)
            }
        }
    }
}