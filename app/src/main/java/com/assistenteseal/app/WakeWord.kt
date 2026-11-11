package com.assistenteseal.app

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.text.Normalizer
import java.util.Locale

/** Modelo de fala em português (Vosk), copiado do APK para a central na primeira vez. */
object VoskModel {
    private const val ASSET_DIR = "vosk-pt"
    private const val MARKER = ".copiado-v1"

    @Volatile
    private var model: Model? = null

    @Synchronized
    fun get(ctx: Context): Model {
        model?.let { return it }
        val dir = File(ctx.filesDir, ASSET_DIR)
        if (!File(dir, MARKER).exists()) {
            EventLog.add("Preparando o reconhecimento de voz (só na primeira vez)…")
            dir.deleteRecursively()
            copyAssets(ctx.assets, ASSET_DIR, dir)
            File(dir, MARKER).createNewFile()
        }
        try { LibVosk.setLogLevel(LogLevel.WARNINGS) } catch (_: Throwable) { }
        val m = Model(dir.absolutePath)
        model = m
        return m
    }

    /** true se a palavra existe no vocabulário do modelo. */
    fun hasWord(ctx: Context, word: String): Boolean {
        val f = File(ctx.filesDir, "$ASSET_DIR/graph/words.txt")
        if (!f.exists()) return false
        return try {
            f.useLines { lines -> lines.any { it.substringBefore(' ').equals(word, ignoreCase = true) } }
        } catch (_: Exception) {
            false
        }
    }

    private fun copyAssets(am: AssetManager, path: String, dest: File) {
        val children = am.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            am.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (c in children) copyAssets(am, "$path/$c", File(dest, c))
    }
}

/**
 * Fica ouvindo o microfone e chama [onWake] quando ouvir a palavra de chamada (ex.: "Ei Laica").
 * Tudo roda na central, sem internet.
 */
class WakeWord(
    private val ctx: Context,
    phrase: String,
    private val onWake: (String) -> Unit
) {
    private val words = norm(phrase).split(" ").filter { it.isNotBlank() }
    private val core: String = words.filter { it.length > 2 }.joinToString("").ifBlank { words.joinToString("") }

    @Volatile
    private var running = false

    @Volatile
    private var fired = false
    private var mic: MicRecorder? = null
    private var rec: Recognizer? = null
    private val lock = Object()

    fun start(micSource: Int) {
        if (running) return
        running = true
        fired = false
        Thread({
            try {
                val model = VoskModel.get(ctx)
                val inVocab = words.filter { it.length > 2 }.all { VoskModel.hasWord(ctx, it) }
                val r = if (inVocab) {
                    val phraseNorm = words.joinToString(" ")
                    val longWords = words.filter { it.length > 2 }.joinToString(" ")
                    val grammar = "[\"$phraseNorm\", \"$longWords\", \"[unk]\"]"
                    Recognizer(model, 16000f, grammar)
                } else {
                    Recognizer(model, 16000f)
                }
                synchronized(lock) {
                    if (!running) {
                        r.close()
                        return@Thread
                    }
                    rec = r
                }
                val m = MicRecorder { chunk, _ -> feed(chunk) }
                if (!m.start(micSource)) {
                    EventLog.add("Chamada por voz: não consegui abrir o microfone")
                    running = false
                    return@Thread
                }
                synchronized(lock) {
                    if (!running) {
                        m.stop() // pediram para parar enquanto abria: nunca deixa microfone aberto
                        return@Thread
                    }
                    mic = m
                }
                EventLog.add("Ouvindo a palavra de chamada (" + (if (inVocab) "lista fechada" else "modo livre") + ")")
            } catch (e: Throwable) {
                EventLog.add("Chamada por voz falhou: ${e.javaClass.simpleName}: ${e.message}")
                running = false
            }
        }, "wakeword").start()
    }

    private fun feed(chunk: ByteArray) {
        if (!running || fired) return
        val r = rec ?: return
        val text = try {
            if (r.acceptWaveForm(chunk, chunk.size)) {
                JSONObject(r.getResult()).optString("text")
            } else {
                JSONObject(r.getPartialResult()).optString("partial")
            }
        } catch (_: Exception) {
            ""
        }
        if (text.isNotBlank() && matches(text)) {
            fired = true
            try { r.reset() } catch (_: Throwable) { }
            onWake(text)
        }
    }

    fun stop() {
        val m: MicRecorder?
        val r: Recognizer?
        synchronized(lock) {
            running = false
            m = mic
            mic = null
            r = rec
            rec = null
        }
        m?.stop()
        try { r?.close() } catch (_: Throwable) { }
    }

    /** Aceita pequenas variações de pronúncia ("visentina", "vicente na"). */
    private fun matches(heard: String): Boolean {
        val t = norm(heard).replace(" ", "")
        if (t.isEmpty() || core.isEmpty()) return false
        if (t.contains(core)) return true
        val n = core.length
        val maxD = if (n >= 8) 2 else 1
        for (len in maxOf(3, n - 2)..(n + 2)) {
            var i = 0
            while (i + len <= t.length) {
                if (levenshtein(t.substring(i, i + len), core) <= maxD) return true
                i++
            }
        }
        return false
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    companion object {
        fun norm(s: String): String =
            Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^a-z ]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}
