package com.assistenteseal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.app.NotificationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.service.notification.NotificationListenerService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Só existe para o app poder ver o que está tocando, se o acesso às notificações for liberado. */
class MediaListener : NotificationListenerService()

/**
 * Pede uma música ao app de música da central pelo serviço de mídia (MediaSession),
 * que é o jeito que o Spotify de carro aceita pedidos.
 */
object MediaPlay {
    private const val SPOTIFY_CAR_SERVICE = "com.spotify.automotive.base.service.AAMMediaBrowserService"

    private var browser: MediaBrowser? = null
    private var browserPkg: String? = null
    private var thread: HandlerThread? = null

    /** true se o usuário liberou o "acesso às notificações" para o app (necessário para o Spotify de fábrica). */
    fun hasAccess(ctx: Context): Boolean = try {
        ctx.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(ctx, MediaListener::class.java))
    } catch (_: Exception) {
        false
    }

    /** Retorna um texto para o registro e para a assistente: começa com "ok:" quando tocou. */
    fun playFromSearch(ctx: Context, pkg: String, query: String): String {
        var session = activeSession(ctx, pkg)
        if (session == null && hasAccess(ctx)) {
            // o app de música ainda não abriu a sessão: abre ele e espera a sessão aparecer
            try {
                ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(it)
                }
            } catch (_: Exception) {
            }
            val until = SystemClock.elapsedRealtime() + 6000
            while (session == null && SystemClock.elapsedRealtime() < until) {
                Thread.sleep(400)
                session = activeSession(ctx, pkg)
            }
        }
        val (controller, how) = session?.let { it to "sessão ativa" }
            ?: browse(ctx, pkg).let { (c, why) -> c to (why ?: "serviço de mídia") }
        if (controller == null) {
            return if (!hasAccess(ctx)) "falhou: $how. Falta liberar o controle da música: no app Assistente Seal, seção Carro, botão Liberar controle da música (não é nos ajustes do carro)"
            else "falhou: $how"
        }
        val extras = Bundle().apply { putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*") }
        return try {
            val before = title(controller)
            controller.transportControls.playFromSearch(query, extras)
            val deadline = SystemClock.elapsedRealtime() + 6000
            while (SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(300)
                val st = controller.playbackState?.state
                val t = title(controller)
                if (st == PlaybackState.STATE_PLAYING && t.isNotBlank() && t != before) return "ok: tocando \"$t\" ($how)"
                if (st == PlaybackState.STATE_ERROR) {
                    return "falhou: o app de música deu erro (${controller.playbackState?.errorMessage ?: "sem detalhe"})"
                }
            }
            val st = controller.playbackState?.state
            if (st == PlaybackState.STATE_PLAYING) "ok: tocando \"${title(controller)}\" ($how)"
            else "falhou: pedido enviado, mas não começou a tocar (estado ${st ?: "desconhecido"})"
        } catch (e: Exception) {
            "falhou: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun title(c: MediaController): String =
        c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""

    /** Funciona só se o acesso às notificações estiver liberado para o app. */
    private fun activeSession(ctx: Context, pkg: String): MediaController? = try {
        val msm = ctx.getSystemService(MediaSessionManager::class.java)
        msm.getActiveSessions(ComponentName(ctx, MediaListener::class.java)).firstOrNull { it.packageName == pkg }
    } catch (_: Exception) {
        null
    }

    /** Conecta no serviço de mídia do app (como a tela de mídia do carro faz). */
    @Synchronized
    private fun browse(ctx: Context, pkg: String): Pair<MediaController?, String?> {
        val existing = browser
        if (existing != null && browserPkg == pkg && existing.isConnected) {
            return MediaController(ctx, existing.sessionToken) to "serviço de mídia"
        }
        try { existing?.disconnect() } catch (_: Exception) { }
        browser = null

        val service = findBrowserService(ctx, pkg) ?: return null to "o app não tem serviço de mídia"
        val ht = thread ?: HandlerThread("media-browse").also { it.start(); thread = it }
        val latch = CountDownLatch(1)
        var token: MediaSession.Token? = null
        var why: String? = null
        var b: MediaBrowser? = null
        Handler(ht.looper).post {
            try {
                val cb = object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        token = b?.sessionToken
                        latch.countDown()
                    }

                    override fun onConnectionFailed() {
                        why = "o app de música recusou a conexão"
                        latch.countDown()
                    }

                    override fun onConnectionSuspended() {
                        why = "conexão com o app de música caiu"
                        latch.countDown()
                    }
                }
                b = MediaBrowser(ctx, service, cb, null)
                b?.connect()
            } catch (e: Exception) {
                why = "erro ao conectar: ${e.message}"
                latch.countDown()
            }
        }
        if (!latch.await(6, TimeUnit.SECONDS)) return null to "o app de música não respondeu"
        val t = token ?: return null to (why ?: "sem sessão")
        browser = b
        browserPkg = pkg
        return MediaController(ctx, t) to "serviço de mídia"
    }

    private fun findBrowserService(ctx: Context, pkg: String): ComponentName? {
        val list = try {
            ctx.packageManager.queryIntentServices(Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0)
        } catch (_: Exception) {
            emptyList()
        }
        list.firstOrNull { it.serviceInfo.name == SPOTIFY_CAR_SERVICE }?.let {
            return ComponentName(pkg, it.serviceInfo.name)
        }
        list.firstOrNull()?.let { return ComponentName(pkg, it.serviceInfo.name) }
        return if (pkg == "com.spotify.music") ComponentName(pkg, SPOTIFY_CAR_SERVICE) else null
    }
}
