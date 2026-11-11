package com.assistenteseal.app

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Procura a versão nova no GitHub, baixa pela internet do carro e instala pela depuração da central. */
object Updater {
    private const val REPO = "estevamfreitas1985-debug/Assistente-byd-Laica"

    data class Release(val tag: String, val code: Int, val url: String, val size: Long)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun currentCode(ctx: Context): Int = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
    } catch (_: Exception) {
        0
    }

    fun latest(token: String): Release {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "AssistenteSeal")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            when (resp.code) {
                200 -> Unit
                401 -> throw IllegalStateException("o token do GitHub está errado ou venceu")
                403, 404 -> throw IllegalStateException("o token não tem acesso ao projeto (ou ele ainda não tem versões publicadas)")
                else -> throw IllegalStateException("o GitHub respondeu ${resp.code}")
            }
            val j = JSONObject(body)
            val tag = j.optString("tag_name")
            val code = tag.substringAfterLast('.').toIntOrNull() ?: 0
            val assets = j.optJSONArray("assets") ?: throw IllegalStateException("a versão não tem arquivo")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return Release(tag, code, a.optString("url"), a.optLong("size"))
                }
            }
            throw IllegalStateException("a versão $tag não tem APK")
        }
    }

    fun download(ctx: Context, token: String, rel: Release, onProgress: (Int) -> Unit): File {
        val out = File(ctx.cacheDir, "atualizacao.apk")
        val req = Request.Builder()
            .url(rel.url)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "AssistenteSeal")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("download falhou (${resp.code})")
            val body = resp.body ?: throw IllegalStateException("download vazio")
            val total = if (body.contentLength() > 0) body.contentLength() else rel.size
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct / 25 != lastPct / 25) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
        }
        return out
    }
}
