//package com.creativem.msm
//
//
//import android.content.BroadcastReceiver
//import android.content.Context
//import android.content.Intent
//import android.net.Uri
//
//import android.telephony.TelephonyManager
//import android.preference.PreferenceManager
//
//import android.widget.Toast
//
//class CallReceiver : BroadcastReceiver() {
//    override fun onReceive(context: Context, intent: Intent) {
//        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
//        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
//
//        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
//        val serviceActive = prefs.getBoolean("service_active", true)
//        val message = prefs.getString("custom_sms_message", "Hola, te devuelvo la llamada en breve.")
//
//        val shared = context.getSharedPreferences("call_state", Context.MODE_PRIVATE)
//        val lastState = shared.getString("last_state", "")
//
//        when (state) {
//            TelephonyManager.EXTRA_STATE_RINGING -> {
//                shared.edit().putString("last_state", "RINGING").apply()
//                shared.edit().putString("last_number", number ?: "").apply()
//                Toast.makeText(context, "📞 Llamada entrante: $number", Toast.LENGTH_SHORT).show()
//            }
//            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
//                shared.edit().putString("last_state", "OFFHOOK").apply()
//            }
//            TelephonyManager.EXTRA_STATE_IDLE -> {
//                val lastSavedState = shared.getString("last_state", "")
//                val lastSavedNumber = shared.getString("last_number", "")
//
//                if (lastSavedState == "RINGING" && !lastSavedNumber.isNullOrEmpty() && serviceActive) {
//                    val intentSms = Intent(Intent.ACTION_SENDTO).apply {
//                        data = Uri.parse("smsto:$lastSavedNumber")
//                        putExtra("sms_body", message)
//                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
//                    }
//                    context.startActivity(intentSms)
//                    Toast.makeText(context, "✉️ Preparando SMS a $lastSavedNumber", Toast.LENGTH_SHORT).show()
//                }
//
//                shared.edit().clear().apply()
//            }
//        }
//    }
//}