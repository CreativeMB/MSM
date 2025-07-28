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
import android.preference.PreferenceManager
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


class MainActivity : AppCompatActivity() {

    private lateinit var messageInput: EditText
    private lateinit var btnSaveMessage: TextView
    private lateinit var toggleService: TextView
    private lateinit var btnHideApp: TextView
    private lateinit var simSelectorSpinner: Spinner
    private lateinit var simSelectorLabel: TextView
    private lateinit var whatsappSelectorSpinner: Spinner
    private lateinit var whatsappSelectorLabel: TextView
    private lateinit var manualPhoneNumber: EditText
    private lateinit var sendManualMessage: TextView

    data class SimInfo(
        val subscriptionId: Int,
        val displayName: String,
        val phoneNumber: String?,
        val carrierName: String?
    ) {
        override fun toString(): String {
            return buildString {
                append(displayName)
                if (!phoneNumber.isNullOrBlank()) append(" - $phoneNumber")
                if (!carrierName.isNullOrBlank() && carrierName != displayName) append(" ($carrierName)")
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
        manualPhoneNumber = findViewById(R.id.manualPhoneNumber)
        sendManualMessage = findViewById(R.id.sendManualMessage)

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
            val isNowActive = !prefs.getBoolean("service_active", true)
            prefs.edit().putBoolean("service_active", isNowActive).apply()
            Toast.makeText(this, if (isNowActive) "Servicio activado" else "Servicio desactivado", Toast.LENGTH_SHORT).show()
            if (isNowActive) checkAndRequestPermissions() else updateUiAndServiceStatus()
        }

        sendManualMessage.setOnClickListener {
            val number = manualPhoneNumber.text.toString().trim()
            if (number.isBlank()) {
                Toast.makeText(this, "Ingrese un número válido", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val message = prefs.getString("custom_sms_message", "") ?: ""
            Log.d("DEBUG_MSM", "Mensaje recuperado: '$message'")

            if (message.isBlank()) {
                Toast.makeText(this, "El mensaje no puede estar vacío", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val selectedPackage =
                if (whatsappSelectorSpinner.visibility == View.VISIBLE && whatsappSelectorSpinner.selectedItem is WhatsAppAppInfo) {
                    (whatsappSelectorSpinner.selectedItem as WhatsAppAppInfo).packageName
                } else {
                    "com.whatsapp"
                }

            Log.d("DEBUG_MSM", "Número: $number, Paquete: $selectedPackage")
            sendWhatsAppMessage(number, message, selectedPackage)
            finishAndRemoveTask()
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
                        .putInt("selected_sim_subscription_id", selectedSim.subscriptionId).apply()
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
                        .putString("selected_whatsapp_package", selectedApp.packageName).apply()
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
                    .putInt("selected_sim_subscription_id", activeSubscriptions[0].subscriptionId).apply()
            }
            return
        }

        simSelectorSpinner.visibility = View.VISIBLE
        simSelectorLabel.visibility = View.VISIBLE
        simInfoList = activeSubscriptions.map {
            SimInfo(it.subscriptionId, it.displayName.toString(), it.number, it.carrierName?.toString())
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, simInfoList)
        simSelectorSpinner.adapter = adapter
        val savedId = PreferenceManager.getDefaultSharedPreferences(this).getInt("selected_sim_subscription_id", -1)
        val savedPosition = simInfoList.indexOfFirst { it.subscriptionId == savedId }
        if (savedPosition != -1) simSelectorSpinner.setSelection(savedPosition)
    }

    private fun populateWhatsAppSelector() {
        val packageManager = this.packageManager
        val installedApps = mutableListOf<WhatsAppAppInfo>()
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
            prefs.edit().putString("selected_whatsapp_package", installedApps.firstOrNull()?.packageName).apply()
        } else {
            whatsappSelectorSpinner.visibility = View.VISIBLE
            whatsappSelectorLabel.visibility = View.VISIBLE
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, installedApps)
            whatsappSelectorSpinner.adapter = adapter
            val savedPackage = prefs.getString("selected_whatsapp_package", null)
            val savedPosition = installedApps.indexOfFirst { it.packageName == savedPackage }
            if (savedPosition != -1) whatsappSelectorSpinner.setSelection(savedPosition)
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
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.RECEIVE_BOOT_COMPLETED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.MANAGE_OWN_CALLS)
            permissions.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_PHONE_CALL)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            requestPermissionLauncher.launch(needed.toTypedArray())
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
        📖 Catálogo completo 👉 www.floristerialoslirios.com

        📲 Atendemos por WhatsApp (no llamadas).
        🧑‍💻 Envía referencia, dirección y hora.
        🚘 ¡Entregamos por ti! Servicio 24/7.
    """.trimIndent()

        val storedMessage = prefs.getString("custom_sms_message", null)
        if (storedMessage.isNullOrBlank()) {
            prefs.edit().putString("custom_sms_message", defaultMessage).apply()
            Log.d("DEBUG_MSM", "Mensaje predeterminado guardado automáticamente.")
        }

        messageInput.setText(prefs.getString("custom_sms_message", defaultMessage))

        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

        if (serviceActive && hasPermission) {
            if (!isServiceRunning(this, CallForegroundService::class.java)) {
                Log.d("DEBUG_MSM", "Iniciando servicio porque no estaba corriendo.")
                startForegroundCallService()
            } else {
                Log.d("DEBUG_MSM", "El servicio ya estaba en ejecución. No se reinicia.")
            }
        } else {
            Log.d("DEBUG_MSM", "Deteniendo servicio (desactivado o sin permisos).")
            stopService(Intent(this, CallForegroundService::class.java))
        }
    }


    private fun updateToggleServiceButton(active: Boolean) {
        toggleService.text = if (active) "Desactivar servicio" else "Activar servicio"
    }

    private fun startForegroundCallService() {
        val serviceIntent = Intent(this, CallForegroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun requestBatteryOptimizations() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("BatteryOpt", "No se pudo abrir optimización batería", e)
            }
        }
    }

    private fun sendWhatsAppMessage(phone: String, message: String, packageName: String) {
        val formattedPhone = phone.filter { it.isDigit() }
        if (formattedPhone.length < 10) {
            Toast.makeText(this, "Número no válido para WhatsApp", Toast.LENGTH_SHORT).show()
            return
        }
        if (message.isBlank()) {
            Toast.makeText(this, "El mensaje no puede estar vacío", Toast.LENGTH_SHORT).show()
            return
        }

        val fullPhone = if (formattedPhone.length == 10 && !formattedPhone.startsWith("57")) {
            "57$formattedPhone"
        } else formattedPhone

        try {
            if (packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b") {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                    putExtra("jid", "$fullPhone@s.whatsapp.net")
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } else {
                val encodedMessage = Uri.encode(message)
                val uri = Uri.parse("https://wa.me/$fullPhone?text=$encodedMessage")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error al abrir WhatsApp: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            Log.e("WhatsAppIntent", "Error al enviar mensaje", e)
        }

    }
    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

}