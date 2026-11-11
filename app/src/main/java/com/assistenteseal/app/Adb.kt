package com.assistenteseal.app

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Executa comandos com permissão de "depuração" na própria central (ADB sem fio, porta 5555),
 * o mesmo caminho que o Carvox e o App Manager usam. Na primeira vez a central pergunta se permite.
 */
object Adb {
    private const val HOST = "127.0.0.1"
    private const val PORT = 5555

    fun portOpen(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(HOST, PORT), 1500) }
        true
    } catch (_: Exception) {
        false
    }

    /** Retorna a saída do comando. Lança exceção com mensagem em português se não der. */
    fun run(ctx: Context, cmd: String, timeoutSec: Long = 45): String {
        if (!portOpen()) throw IllegalStateException("a depuração sem fio (ADB) da central está desligada")
        val dir = File(ctx.filesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        val keys = AdbKeyPair.read(priv, pub)
        val ex = Executors.newSingleThreadExecutor()
        try {
            val f = ex.submit<String> {
                Dadb.create(HOST, PORT, keys).use { d ->
                    val r = d.shell(cmd)
                    (r.output + r.errorOutput).trim()
                }
            }
            return f.get(timeoutSec, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw IllegalStateException("a central não respondeu. Aceite o pedido de depuração que apareceu na tela (marque Sempre permitir) e toque de novo")
        } finally {
            ex.shutdownNow()
        }
    }

    /** Instala um APK pela depuração da central (o app fecha e reabre sozinho). */
    fun install(ctx: Context, apk: File) {
        if (!portOpen()) throw IllegalStateException("a depuração sem fio (ADB) da central está desligada")
        val dir = File(ctx.filesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        val keys = AdbKeyPair.read(priv, pub)
        Dadb.create(HOST, PORT, keys).use { d -> d.install(apk, "-r") }
    }

    /** Ativa (ou desativa) o serviço de acessibilidade que liga o assistente junto com o carro. */
    fun setAutostart(ctx: Context, enable: Boolean): String {
        val comp = AutostartService.component(ctx)
        val current = run(ctx, "settings get secure enabled_accessibility_services")
            .lines().firstOrNull()?.trim().orEmpty()
        val list = current.split(':').map { it.trim() }.filter { it.isNotEmpty() && it != "null" }.toMutableList()
        val has = list.any { it.equals(comp, ignoreCase = true) }
        if (enable && has && AutostartService.isEnabled(ctx)) return "já estava ativo"
        if (enable && !has) list.add(comp)
        if (!enable) list.removeAll { it.equals(comp, ignoreCase = true) }
        run(ctx, "settings put secure enabled_accessibility_services '${list.joinToString(":")}'")
        if (enable) run(ctx, "settings put secure accessibility_enabled 1")
        Thread.sleep(800)
        return if (AutostartService.isEnabled(ctx) == enable) (if (enable) "ativado" else "desativado")
        else "o comando rodou mas a central não confirmou"
    }

    /**
     * Libera o app nas listas da BYD que deixam ele iniciar sozinho e não ser fechado quando o carro desliga
     * (mesmos comandos que o Carvox usa). Comandos que a central não conhece são ignorados.
     */
    fun allowBackgroundStart(ctx: Context): String {
        val p = ctx.packageName
        val script = listOf(
            "cmd appops set $p AUTO_START allow 2>/dev/null",
            "cmd appops set $p BOOT_COMPLETED allow 2>/dev/null",
            "cmd appops set $p RUN_IN_BACKGROUND allow 2>/dev/null",
            "cmd appops set $p RUN_ANY_IN_BACKGROUND allow 2>/dev/null",
            "dumpsys deviceidle whitelist +$p >/dev/null 2>&1",
            "CUR=\$(settings get global ssc_whitelist 2>/dev/null); [ \"\$CUR\" = null ] && CUR=; " +
                "case \",\$CUR,\" in *,$p,*) ;; *) settings put global ssc_whitelist \"\${CUR:+\$CUR,}$p\";; esac",
            "echo \"inicio=\$(cmd appops get $p AUTO_START 2>/dev/null | head -1) | lista_ssc=\$(settings get global ssc_whitelist 2>/dev/null | grep -c $p)\""
        ).joinToString("; ")
        return run(ctx, script).lines().lastOrNull { it.isNotBlank() }.orEmpty()
    }

    private const val DAEMON_RX = "com.assistenteseal.app.Daemo[n]" // colchete: o próprio comando não casa com o padrão

    /** Liga o vigia (processo pela depuração que religa o assistente). Não duplica se já estiver rodando. */
    fun startDaemon(ctx: Context, restart: Boolean = false): String {
        val apk = ctx.applicationInfo.sourceDir
        val launch = "export CLASSPATH=$apk; " +
            "if command -v setsid >/dev/null 2>&1; then " +
            "setsid nohup app_process /system/bin com.assistenteseal.app.Daemon >/dev/null 2>&1 </dev/null & " +
            "else nohup app_process /system/bin com.assistenteseal.app.Daemon >/dev/null 2>&1 </dev/null & fi"
        val cmd = "settings put global seal_vigia 1; " +
            (if (restart) "pkill -f '$DAEMON_RX'; sleep 1; " else "") +
            "pgrep -f '$DAEMON_RX' >/dev/null || ($launch); sleep 2; " +
            "pgrep -f '$DAEMON_RX' >/dev/null && echo vigia=rodando || echo vigia=falhou"
        return run(ctx, cmd).lines().lastOrNull { it.isNotBlank() }.orEmpty()
    }

    /** Pausa o vigia (o assistente desligado pelo usuário fica desligado). */
    fun pauseDaemon(ctx: Context, kill: Boolean = false): String =
        run(ctx, "settings put global seal_vigia 0" + if (kill) "; pkill -f '$DAEMON_RX'; echo vigia=parado" else "; echo vigia=pausado")

    fun daemonStatus(ctx: Context): String =
        run(ctx, "pgrep -f '$DAEMON_RX' >/dev/null && echo rodando || echo parado; settings get global seal_vigia")
            .lines().filter { it.isNotBlank() }.joinToString(" / ")

    /** Garante tudo que faz o assistente voltar sozinho: acessibilidade, listas da BYD e vigia. */
    fun ensureKeepAlive(ctx: Context): String {
        val a = setAutostart(ctx, true)
        val b = allowBackgroundStart(ctx)
        val c = startDaemon(ctx)
        return "$a | lista da BYD: $b | $c"
    }

    /** Libera o "acesso às notificações" do app (necessário para mandar música no Spotify de fábrica). */
    fun grantMediaControl(ctx: Context): String {
        val comp = "${ctx.packageName}/${MediaListener::class.java.name}"
        if (MediaPlay.hasAccess(ctx)) return "já estava liberado"
        val out1 = run(ctx, "cmd notification allow_listener $comp")
        Thread.sleep(800)
        if (MediaPlay.hasAccess(ctx)) return "liberado"
        // plano de reserva: grava direto na configuração do sistema
        val current = run(ctx, "settings get secure enabled_notification_listeners")
            .lines().firstOrNull()?.trim().orEmpty()
        val list = current.split(':').map { it.trim() }.filter { it.isNotEmpty() && it != "null" }.toMutableList()
        if (comp !in list) list.add(comp)
        run(ctx, "settings put secure enabled_notification_listeners '${list.joinToString(":")}'")
        Thread.sleep(800)
        return if (MediaPlay.hasAccess(ctx)) "liberado" else "o comando rodou mas o acesso não apareceu (resposta: ${out1.take(160)})"
    }
}
