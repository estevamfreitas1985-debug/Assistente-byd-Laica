package com.assistenteseal.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Liga o assistente junto com a central, se estiver ativado nos Ajustes. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updated = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (updated) EventLog.add("App atualizado")
        if (intent.action == "com.assistenteseal.app.KEEPALIVE") EventLog.add("Religado pelo vigia")
        if (!updated && !Prefs(context).startOnBoot) return
        try {
            AssistantService.send(context, AssistantService.ACTION_START)
        } catch (e: Exception) {
            EventLog.add("Não consegui ligar junto com o carro: ${e.message}")
        }
    }
}
