package com.creativem.msm

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
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
    private lateinit var simSelectorSpinner: Spinner
    private lateinit var simSelectorLabel: TextView
    private lateinit var whatsappSelectorSpinner: Spinner
    private lateinit var whatsappSelectorLabel: TextView

    data class SimInfo(
        val subscriptionId: Int,
        val displayName: String,
        val phoneNumber: String?,
        val carrierName: String?
    ) {
        override fun toString(): String {
            return buildString {
                append(displayName)
                if (!phoneNumber.isNullOrBlank()) {
                    append(" - $phoneNumber")
                }
                if (!carrierName.isNullOrBlank() && carrierName != displayName) {
                    append(" ($carrierName)")
                }
            }
        }
    }

    data class WhatsAppAppInfo(val displayName: String, val packageName: String) {
        override fun toString(): String = displayName
    }

    private var simInfoList: List<SimInfo> = emptyList()
    private var whatsAppAppList: List<WhatsAppAppInfo> = emptyList()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.entries.all { it.value }) {
                Toast.makeText(this, "Todos los permisos fueron concedidos.", Toast.LENGTH_SHORT).show()
                populateSimSelector()
                populateWhatsAppSelector()
                requestBatteryOptimizations()
            } else {
                Toast.makeText(this, "Algunos permisos fueron denegados.", Toast.LENGTH_LONG).show()
            }
            updateUiAndServiceStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        messageInput = findViewById(R.id.messageInput)
        btnSaveMessage = findViewById(R.id.btnSaveMessage)
        toggleService = findViewById(R.id.toggleService)
        btnHideApp = findViewById(R.id.btnHideApp)
        simSelectorSpinner = findViewById(R.id.simSelectorSpinner)
        simSelectorLabel = findViewById(R.id.simSelectorLabel)
        whatsappSelectorSpinner = findViewById(R.id.whatsappSelectorSpinner)
        whatsappSelectorLabel = findViewById(R.id.whatsappSelectorLabel)

        setupClickListeners()
        setupSimSelectorListener()
        setupWhatsAppSelectorListener()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        populateSimSelector()
        populateWhatsAppSelector()
        updateUiAndServiceStatus()
    }

    private fun setupClickListeners() {
        toggleService.setOnClickListener {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val isCurrentlyActive = prefs.getBoolean("service_active", true)
            val isNowActive = !isCurrentlyActive
            prefs.edit().putBoolean("service_active", isNowActive).apply()
            Toast.makeText(this, if (isNowActive) "Servicio activado" else "Servicio desactivado", Toast.LENGTH_SHORT).show()
            if (isNowActive) {
                checkAndRequestPermissions()
            } else {
                updateUiAndServiceStatus()
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
            finishAndRemoveTask()
        }
    }

    private fun setupSimSelectorListener() {
        simSelectorSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (simInfoList.isNotEmpty()) {
                    val selectedSim = simInfoList[position]
                    PreferenceManager.getDefaultSharedPreferences(this@MainActivity).edit()
                        .putInt("selected_sim_subscription_id", selectedSim.subscriptionId)
                        .apply()
                    Log.d("SimSelection", "SIM seleccionada: ${selectedSim.displayName} con ID: ${selectedSim.subscriptionId}")
                    updateUiAndServiceStatus()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupWhatsAppSelectorListener() {
        whatsappSelectorSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (whatsAppAppList.isNotEmpty()) {
                    val selectedApp = whatsAppAppList[position]
                    PreferenceManager.getDefaultSharedPreferences(this@MainActivity).edit()
                        .putString("selected_whatsapp_package", selectedApp.packageName)
                        .apply()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun populateSimSelector() {
        val hasPhoneStatePerm = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val hasPhoneNumbersPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED

        if (!hasPhoneStatePerm || !hasPhoneNumbersPerm) {
            simSelectorSpinner.visibility = View.GONE
            simSelectorLabel.visibility = View.GONE
            return
        }

        val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList ?: emptyList()

        if (activeSubscriptions.size <= 1) {
            simSelectorSpinner.visibility = View.GONE
            simSelectorLabel.visibility = View.GONE
            if (activeSubscriptions.isNotEmpty()) {
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putInt("selected_sim_subscription_id", activeSubscriptions[0].subscriptionId)
                    .apply()
            }
            return
        }

        simSelectorSpinner.visibility = View.VISIBLE
        simSelectorLabel.visibility = View.VISIBLE // <-- SIN TÍTULO PARA EL SELECTOR DE SIM
        simInfoList = activeSubscriptions.map { subInfo ->
            SimInfo(
                subscriptionId = subInfo.subscriptionId,
                displayName = subInfo.displayName.toString(),
                phoneNumber = subInfo.number,
                carrierName = subInfo.carrierName?.toString()
            )
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, simInfoList)
        simSelectorSpinner.adapter = adapter
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val savedSubId = prefs.getInt("selected_sim_subscription_id", -1)
        val savedPosition = simInfoList.indexOfFirst { it.subscriptionId == savedSubId }
        if (savedPosition != -1) {
            simSelectorSpinner.setSelection(savedPosition)
        }
    }

    private fun populateWhatsAppSelector() {
        val packageManager = this.packageManager
        val installedApps = mutableListOf<WhatsAppAppInfo>()

        // Esta comprobación ahora funcionará gracias a <queries>
        if (isPackageInstalled("com.whatsapp", packageManager)) {
            installedApps.add(WhatsAppAppInfo("WhatsApp", "com.whatsapp"))
        }
        if (isPackageInstalled("com.whatsapp.w4b", packageManager)) {
            installedApps.add(WhatsAppAppInfo("WhatsApp Business", "com.whatsapp.w4b"))
        }

        this.whatsAppAppList = installedApps
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        if (installedApps.size <= 1) {
            whatsappSelectorSpinner.visibility = View.GONE
            whatsappSelectorLabel.visibility = View.GONE
            val packageName = if (installedApps.isNotEmpty()) installedApps[0].packageName else null
            prefs.edit().putString("selected_whatsapp_package", packageName).apply()
        } else {
            whatsappSelectorSpinner.visibility = View.VISIBLE
            whatsappSelectorLabel.visibility = View.VISIBLE
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, installedApps)
            whatsappSelectorSpinner.adapter = adapter
            val savedPackage = prefs.getString("selected_whatsapp_package", null)
            val savedPosition = installedApps.indexOfFirst { it.packageName == savedPackage }
            if (savedPosition != -1) {
                whatsappSelectorSpinner.setSelection(savedPosition)
            }
        }
    }

    private fun isPackageInstalled(packageName: String, packageManager: PackageManager): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.RECEIVE_BOOT_COMPLETED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissionsToRequest.add(Manifest.permission.MANAGE_OWN_CALLS)
            permissionsToRequest.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissionsToRequest.add(Manifest.permission.FOREGROUND_SERVICE_PHONE_CALL)
        }
        val permissionsNeeded = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.distinct()
        if (permissionsNeeded.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsNeeded.toTypedArray())
        } else {
            populateSimSelector()
            populateWhatsAppSelector()
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
        val hasRequiredPermissions = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
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

    private fun requestBatteryOptimizations() {
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