package com.assistenteseal.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Serviço de acessibilidade usado só para o assistente ligar junto com o carro.
 * A central da BYD religa os serviços de acessibilidade ativos sempre que o carro liga
 * (o aviso normal de "ligou" não chega aos apps). Não lê nem mexe na tela.
 */
class AutostartService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // espera a central terminar de subir antes de ligar o assistente
        Handler(Looper.getMainLooper()).postDelayed({ startAssistant(this) }, 4000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    companion object {
        fun component(ctx: Context) = ComponentName(ctx, AutostartService::class.java).flattenToString()

        fun isEnabled(ctx: Context): Boolean = try {
            (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                .split(':').any { it.equals(component(ctx), ignoreCase = true) }
        } catch (_: Exception) {
            false
        }

        fun startAssistant(ctx: Context) {
            if (!Prefs(ctx).startOnBoot || AssistantService.running) return
            try {
                AssistantService.send(ctx, AssistantService.ACTION_START)
                EventLog.add("Ligado junto com o carro")
            } catch (e: Exception) {
                EventLog.add("Não consegui ligar junto com o carro: ${e.message}")
            }
        }
    }
}
