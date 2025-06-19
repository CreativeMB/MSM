//package com.creativem.msm
//
//
//import android.content.BroadcastReceiver
//import android.content.Context
//import android.content.Intent
//import android.widget.Toast
//
//class SmsSentReceiver : BroadcastReceiver() {
//    override fun onReceive(context: Context?, intent: Intent?) {
//        val message = when (resultCode) {
//            android.app.Activity.RESULT_OK -> "SMS enviado correctamente"
//            else -> "Error al enviar SMS"
//        }
//        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
//    }
//}