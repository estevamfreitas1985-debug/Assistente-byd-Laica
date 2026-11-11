package com.assistenteseal.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Anotações e lembretes que a assistente guarda no próprio carro (arquivo do app, sem internet). */
object Notes {
    private const val FILE = "anotacoes.json"
    private const val MAX_NOTAS = 200

    private val fmtCurto = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR"))

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized
    private fun load(ctx: Context): JSONObject {
        val o = try {
            val f = file(ctx)
            if (f.exists()) JSONObject(f.readText()) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
        if (!o.has("notas")) o.put("notas", JSONArray())
        if (!o.has("lembretes")) o.put("lembretes", JSONArray())
        return o
    }

    @Synchronized
    private fun save(ctx: Context, data: JSONObject) {
        val f = file(ctx)
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(data.toString())
        tmp.renameTo(f)
    }

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").trim()

    // ---------------- anotações ----------------

    @Synchronized
    fun addNota(ctx: Context, texto: String): String {
        val t = texto.trim()
        if (t.isEmpty()) return "anotação vazia"
        val d = load(ctx)
        val notas = d.getJSONArray("notas")
        notas.put(JSONObject().put("id", System.currentTimeMillis()).put("texto", t).put("criado", System.currentTimeMillis()))
        while (notas.length() > MAX_NOTAS) notas.remove(0)
        save(ctx, d)
        return "anotado: $t"
    }

    @Synchronized
    fun listNotas(ctx: Context, busca: String = ""): JSONArray {
        val notas = load(ctx).getJSONArray("notas")
        val out = JSONArray()
        val b = norm(busca)
        for (i in notas.length() - 1 downTo 0) {
            val n = notas.getJSONObject(i)
            if (b.isNotEmpty() && !norm(n.optString("texto")).contains(b)) continue
            out.put(JSONObject().put("quando", fmtCurto.format(Date(n.optLong("criado")))).put("texto", n.optString("texto")))
            if (out.length() >= 30) break
        }
        return out
    }

    /** Apaga as anotações que contêm o trecho ("todas" apaga tudo). */
    @Synchronized
    fun removeNota(ctx: Context, trecho: String): String {
        val d = load(ctx)
        val notas = d.getJSONArray("notas")
        val b = norm(trecho)
        if (b.isEmpty()) return "diga qual anotação apagar"
        val keep = JSONArray()
        var removed = 0
        for (i in 0 until notas.length()) {
            val n = notas.getJSONObject(i)
            if (b == "todas" || b == "tudo" || norm(n.optString("texto")).contains(b)) removed++ else keep.put(n)
        }
        d.put("notas", keep)
        save(ctx, d)
        return if (removed == 0) "não achei anotação com \"$trecho\"" else "$removed anotação(ões) apagada(s)"
    }

    // ---------------- lembretes ----------------

    private fun parseQuando(q: String): Long? {
        val s = q.trim().replace('T', ' ')
        for (p in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "dd/MM/yyyy HH:mm")) {
            try {
                val f = SimpleDateFormat(p, Locale.US)
                f.isLenient = false
                return f.parse(s)?.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    @Synchronized
    fun addLembrete(ctx: Context, texto: String, quando: String, daquiMinutos: Int = 0): String {
        val t = texto.trim()
        if (t.isEmpty()) return "lembrete vazio"
        val ms = if (daquiMinutos > 0) System.currentTimeMillis() + daquiMinutos * 60_000L
        else parseQuando(quando) ?: return "não entendi o horário \"$quando\" (use AAAA-MM-DD HH:MM ou daqui_minutos)"
        if (ms < System.currentTimeMillis() - 60_000) return "esse horário já passou (${fmtCurto.format(Date(ms))})"
        val d = load(ctx)
        d.getJSONArray("lembretes").put(
            JSONObject().put("id", System.currentTimeMillis()).put("texto", t).put("quando", ms).put("avisado", false)
        )
        save(ctx, d)
        val dia = SimpleDateFormat("EEEE, dd/MM 'às' HH:mm", Locale("pt", "BR")).format(Date(ms))
        return "lembrete marcado para $dia: $t"
    }

    @Synchronized
    fun listLembretes(ctx: Context): JSONArray {
        val l = load(ctx).getJSONArray("lembretes")
        val itens = (0 until l.length()).map { l.getJSONObject(it) }.filter { !it.optBoolean("avisado") }.sortedBy { it.optLong("quando") }
        val out = JSONArray()
        for (r in itens) out.put(JSONObject().put("quando", fmtCurto.format(Date(r.optLong("quando")))).put("texto", r.optString("texto")))
        return out
    }

    @Synchronized
    fun removeLembrete(ctx: Context, trecho: String): String {
        val d = load(ctx)
        val l = d.getJSONArray("lembretes")
        val b = norm(trecho)
        if (b.isEmpty()) return "diga qual lembrete apagar"
        val keep = JSONArray()
        var removed = 0
        for (i in 0 until l.length()) {
            val r = l.getJSONObject(i)
            val pend = !r.optBoolean("avisado")
            if (pend && (b == "todos" || b == "tudo" || norm(r.optString("texto")).contains(b))) removed++ else keep.put(r)
        }
        d.put("lembretes", keep)
        save(ctx, d)
        return if (removed == 0) "não achei lembrete com \"$trecho\"" else "$removed lembrete(s) cancelado(s)"
    }

    /** Primeiro lembrete vencido ainda não avisado: (texto, horário formatado). */
    @Synchronized
    fun due(ctx: Context): Triple<Long, String, String>? {
        val l = load(ctx).getJSONArray("lembretes")
        val now = System.currentTimeMillis()
        var best: JSONObject? = null
        for (i in 0 until l.length()) {
            val r = l.getJSONObject(i)
            if (r.optBoolean("avisado") || r.optLong("quando") > now) continue
            if (best == null || r.optLong("quando") < best.optLong("quando")) best = r
        }
        val b = best ?: return null
        return Triple(b.optLong("id"), b.optString("texto"), fmtCurto.format(Date(b.optLong("quando"))))
    }

    @Synchronized
    fun markFired(ctx: Context, id: Long) {
        val d = load(ctx)
        val l = d.getJSONArray("lembretes")
        val keep = JSONArray()
        val limite = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        for (i in 0 until l.length()) {
            val r = l.getJSONObject(i)
            if (r.optLong("id") == id) r.put("avisado", true)
            // avisados há mais de 7 dias saem do arquivo
            if (r.optBoolean("avisado") && r.optLong("quando") < limite) continue
            keep.put(r)
        }
        d.put("lembretes", keep)
        save(ctx, d)
    }

    /** Trecho para o prompt da assistente: últimas anotações e lembretes pendentes. */
    fun promptBlock(ctx: Context): String {
        val notas = listNotas(ctx)
        val lembs = listLembretes(ctx)
        if (notas.length() == 0 && lembs.length() == 0) return ""
        val sb = StringBuilder()
        if (notas.length() > 0) {
            sb.appendLine("Anotações guardadas (mais recentes primeiro; use quando ajudar, sem ler todas de uma vez):")
            for (i in 0 until minOf(notas.length(), 15)) {
                val n = notas.getJSONObject(i)
                sb.appendLine("- ${n.optString("quando")}: ${n.optString("texto")}")
            }
        }
        if (lembs.length() > 0) {
            sb.appendLine("Lembretes marcados:")
            for (i in 0 until minOf(lembs.length(), 15)) {
                val r = lembs.getJSONObject(i)
                sb.appendLine("- ${r.optString("quando")}: ${r.optString("texto")}")
            }
        }
        return sb.toString().trimEnd()
    }
}
