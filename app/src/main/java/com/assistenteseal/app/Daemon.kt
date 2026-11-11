package com.assistenteseal.app

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Vigia" que roda pela depuração da central (como o do Carvox), fora do app:
 * a BYD não fecha esse processo quando desliga o carro. A cada 15 segundos ele confere
 * se o assistente está ligado e, se não estiver, religa (e repõe o serviço de acessibilidade se sumiu).
 * Só age enquanto a configuração global "seal_vigia" for 1 (o app muda isso ao ligar/desligar).
 *
 * Iniciado com: CLASSPATH=<apk> app_process /system/bin com.assistenteseal.app.Daemon
 */
object Daemon {
    private const val PKG = "com.assistenteseal.app"
    private const val ACC = "$PKG/$PKG.AutostartService"
    private val log = File("/data/local/tmp/seal_vigia.log")
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)

    @JvmStatic
    fun main(args: Array<String>) {
        log("vigia iniciado")
        var falhas = 0
        while (true) {
            try {
                if (sh("settings get global seal_vigia").trim() == "1" && !serviceRunning()) {
                    falhas++
                    log("assistente parado: religando (tentativa $falhas)")
                    val acc = sh("settings get secure enabled_accessibility_services").trim()
                    if (!acc.contains(ACC)) {
                        val novo = if (acc.isEmpty() || acc == "null") ACC else "$acc:$ACC"
                        sh("settings put secure enabled_accessibility_services '$novo'")
                        sh("settings put secure accessibility_enabled 1")
                        log("acessibilidade reposta")
                    }
                    sh("am broadcast -f 0x20 -n $PKG/.BootReceiver -a $PKG.KEEPALIVE")
                } else {
                    falhas = 0
                }
            } catch (e: Throwable) {
                log("erro: $e")
            }
            Thread.sleep(if (falhas > 5) 60_000L else 15_000L)
        }
    }

    private fun serviceRunning(): Boolean =
        sh("dumpsys activity services $PKG/.AssistantService").contains("ServiceRecord")

    private fun sh(cmd: String): String {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    private fun log(msg: String) {
        try {
            if (log.length() > 200_000) log.writeText("")
            log.appendText("${fmt.format(Date())} $msg\n")
        } catch (_: Throwable) {
        }
    }
}
