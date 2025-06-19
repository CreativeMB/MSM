package com.creativem.msm

import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)

            // Solo continuamos si el servicio estaba marcado como activo
            if (prefs.getBoolean("service_active", true)) {

                var canStart = false

                // Verificamos si tenemos el rol necesario, igual que en la MainActivity
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                    if (roleManager != null && roleManager.isRoleHeld(RoleManager.ROLE_CALL_REDIRECTION)) {
                        canStart = true
                        Log.d("BootReceiver", "Permiso de rol de llamadas concedido. Iniciando servicio.")
                    } else {
                        Log.e("BootReceiver", "El servicio no se puede iniciar en el arranque, falta el rol de gestión de llamadas.")
                    }
                } else {
                    // En versiones antiguas no se necesita el rol
                    canStart = true
                }

                if (canStart) {
                    val serviceIntent = Intent(context, CallForegroundService::class.java)
                    ContextCompat.startForegroundService(context, serviceIntent)
                }
            } else {
                Log.d("BootReceiver", "El servicio está desactivado en las preferencias, no se inicia.")
            }
        }
    }
}