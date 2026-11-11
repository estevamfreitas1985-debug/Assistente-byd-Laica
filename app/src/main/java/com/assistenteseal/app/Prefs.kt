package com.assistenteseal.app

import android.content.Context
import android.content.SharedPreferences

class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("config", Context.MODE_PRIVATE)

    private fun str(k: String, d: String) = sp.getString(k, d) ?: d
    private fun putStr(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun int(k: String, d: Int) = sp.getInt(k, d)
    private fun putInt(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun putBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var apiKey: String
        get() = str("apiKey", "")
        set(v) = putStr("apiKey", v.replace(Regex("\\s"), ""))

    /** Chave só de leitura do GitHub, para o app baixar as versões novas sozinho. */
    var githubToken: String
        get() = str("githubToken", "")
        set(v) = putStr("githubToken", v.trim())

    var model: String
        get() = str("model", DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }
        set(v) = putStr("model", v.trim().removePrefix("models/"))

    var apiVersion: String
        get() = str("apiVersion", "v1beta")
        set(v) = putStr("apiVersion", v)

    var voice: String
        get() = str("voice", "Kore")
        set(v) = putStr("voice", v)

    var assistantName: String
        get() = str("assistantName", "Laica").ifBlank { "Laica" }
        set(v) = putStr("assistantName", v.trim())

    var userName: String
        get() = str("userName", "")
        set(v) = putStr("userName", v.trim())

    var style: Int
        get() = int("style", 0)
        set(v) = putInt("style", v)

    var extraInstructions: String
        get() = str("extra", "")
        set(v) = putStr("extra", v.trim())

    /** 0 navegação, 1 mídia, 2 assistente, 3 chamada */
    var audioOutput: Int
        get() = int("audioOutput", 0)
        set(v) = putInt("audioOutput", v)

    /** 0 não mexer na música (não pede foco de áudio), 1 pausar a música, 2 só abaixar */
    var musicMode: Int
        get() = int("musicMode2", 0)
        set(v) = putInt("musicMode2", v)

    /** 0 automático (4 canais do carro), 1 reconhecimento de voz, 2 comunicação, 3 microfone padrão */
    var micSource: Int
        get() = int("micSource", 0)
        set(v) = putInt("micSource", v)

    /** A assistente cumprimenta logo depois do bipe, sem esperar a pessoa falar. */
    var speakFirst: Boolean
        get() = bool("speakFirst", true)
        set(v) = putBool("speakFirst", v)

    /** Fica ouvindo a palavra de chamada (offline) quando não está conversando. */
    var wakeEnabled: Boolean
        get() = bool("wakeEnabled", false)
        set(v) = putBool("wakeEnabled", v)

    var wakePhrase: String
        get() = str("wakePhrase", "Ei Laica").ifBlank { "Ei Laica" }
        set(v) = putStr("wakePhrase", v.trim())

    var allowBargeIn: Boolean
        get() = bool("bargeIn", false)
        set(v) = putBool("bargeIn", v)

    /** 0 curta, 1 normal, 2 longa, 3 bem longa */
    var pauseLevel: Int
        get() = int("pauseLevel", 1)
        set(v) = putInt("pauseLevel", v)

    var listenAfterSec: Int
        get() = int("listenAfter", 12)
        set(v) = putInt("listenAfter", v)

    var startOnBoot: Boolean
        get() = bool("startOnBoot2", false)
        set(v) = putBool("startOnBoot2", v)

    var bubbleSizeDp: Int
        get() = int("bubbleSize", 84)
        set(v) = putInt("bubbleSize", v)

    var bubbleX: Int
        get() = int("bubbleX", -1)
        set(v) = putInt("bubbleX", v)

    var bubbleY: Int
        get() = int("bubbleY", -1)
        set(v) = putInt("bubbleY", v)

    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-live"

        val VOICES = listOf(
            "Kore" to "Kore (feminina, firme)",
            "Aoede" to "Aoede (feminina, leve)",
            "Leda" to "Leda (feminina, jovem)",
            "Zephyr" to "Zephyr (feminina, animada)",
            "Callirrhoe" to "Callirrhoe",
            "Autonoe" to "Autonoe",
            "Despina" to "Despina",
            "Erinome" to "Erinome",
            "Laomedeia" to "Laomedeia",
            "Achernar" to "Achernar",
            "Gacrux" to "Gacrux",
            "Pulcherrima" to "Pulcherrima",
            "Vindemiatrix" to "Vindemiatrix",
            "Sulafat" to "Sulafat",
            "Puck" to "Puck (masculina, animada)",
            "Charon" to "Charon (masculina, informativa)",
            "Fenrir" to "Fenrir (masculina, empolgada)",
            "Orus" to "Orus (masculina, firme)",
            "Enceladus" to "Enceladus",
            "Iapetus" to "Iapetus",
            "Umbriel" to "Umbriel",
            "Algieba" to "Algieba",
            "Algenib" to "Algenib",
            "Rasalgethi" to "Rasalgethi",
            "Alnilam" to "Alnilam",
            "Schedar" to "Schedar",
            "Achird" to "Achird",
            "Zubenelgenubi" to "Zubenelgenubi",
            "Sadachbia" to "Sadachbia",
            "Sadaltager" to "Sadaltager"
        )

        val STYLES = listOf(
            "Padrão" to "Simpática, prestativa e objetiva.",
            "Direto ao ponto" to "Responda em uma frase curta, sem rodeios.",
            "Divertido" to "Bem-humorada, faz piadinhas leves quando cabe, sem perder a objetividade.",
            "Parceiro de estrada" to "Fala como um amigo de viagem, informal, com gírias leves.",
            "Calmo" to "Tom calmo e tranquilo, fala sem pressa.",
            "Formal" to "Educada e formal, trata o motorista por senhor."
        )

        val OUTPUTS = listOf("Navegação", "Mídia", "Assistente de voz", "Chamada")
        val MUSIC_MODES = listOf("Não mexer na música (recomendado)", "Pausar a música", "Só abaixar o volume")
        val MIC_SOURCES = listOf("Automático (microfone do carro, 4 canais)", "Reconhecimento de voz (simples)", "Comunicação (simples, corta eco)", "Microfone padrão (simples)")
        val PAUSES = listOf("Curta (0,6 s)", "Normal (1 s)", "Longa (2 s)", "Bem longa (3,5 s)")
        val LISTEN_AFTER = listOf(6, 10, 12, 15, 20, 30)
    }
}
