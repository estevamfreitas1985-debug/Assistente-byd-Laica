package com.assistenteseal.app

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Sessão de voz com o Gemini Live API.
 * Áudio de entrada: PCM 16 bits 16 kHz mono. Áudio de saída: PCM 16 bits 24 kHz mono.
 */
class LiveSession(
    private val prefs: Prefs,
    private val tools: ToolBox,
    private val http: OkHttpClient,
    private val listener: Listener
) {
    interface Listener {
        fun onReady()
        fun onAudio(pcm: ByteArray)
        fun onInterrupted()
        fun onTurnComplete()
        fun onUserActivity()
        fun onToolCall(name: String)
        fun onClosed(reason: String, error: Boolean)
    }

    private val toolExecutor = Executors.newSingleThreadExecutor()
    private var ws: WebSocket? = null

    @Volatile
    var ready = false
        private set

    @Volatile
    private var closedByUs = false
    private var socketId = 0
    private var resumeHandle: String? = null
    private var triedAlternateVersion = false
    private var reconnects = 0
    private var greeted = false

    /** Primeira mensagem da conversa (ex.: um lembrete). Sem ela, usa o cumprimento normal. */
    @Volatile
    var opening: String? = null
    private val userText = StringBuilder()
    private val modelText = StringBuilder()

    private val model: String get() = prefs.model
    private val thinkingModel: Boolean get() = model.contains("thinking")

    /** Último ponto de retomada da conversa (o Gemini guarda o contexto por até 2 horas). */
    val currentHandle: String? get() = resumeHandle

    @Volatile
    private var resumedStart = false
    private var startHandle: String? = null

    fun connect(resume: String? = null) {
        closedByUs = false
        startHandle = resume
        resumedStart = resume != null
        open(prefs.apiVersion, resume)
    }

    private fun open(version: String, handle: String?) {
        val key = prefs.apiKey
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.$version" +
            ".GenerativeService.BidiGenerateContent?key=$key"
        val req = Request.Builder().url(url).build()
        val myId = ++socketId
        ready = false
        EventLog.add("Conectando ao $model ($version)" + if (handle != null) " retomando a conversa" else "")
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (myId != socketId) return
                webSocket.send(buildSetup(handle).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (myId != socketId) return
                handle(text, version)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (myId != socketId) return
                handle(bytes.utf8(), version)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (myId != socketId) return
                closedWith(code, reason, version)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (myId != socketId) return
                val body = try { response?.body?.string()?.take(400) } catch (_: Exception) { null }
                val msg = buildString {
                    append(t.message ?: t.javaClass.simpleName)
                    if (response != null) append(" (HTTP ${response.code})")
                    if (!body.isNullOrBlank()) append(" ").append(body)
                }
                closedWith(-1, msg, version)
            }
        })
    }

    private fun closedWith(code: Int, reason: String, version: String) {
        if (closedByUs) return
        val wasReady = ready
        ready = false
        val r = reason.ifBlank { "código $code" }
        if (!wasReady && startHandle != null) {
            // a conversa anterior expirou no servidor: começa uma nova
            startHandle = null
            resumedStart = false
            EventLog.add("Memória da conversa anterior expirou. Começando uma nova.")
            open(version, null)
            return
        }
        if (!wasReady && !triedAlternateVersion && looksLikeVersionProblem(r)) {
            triedAlternateVersion = true
            val other = if (version == "v1beta") "v1alpha" else "v1beta"
            EventLog.add("Servidor recusou na $version ($r). Tentando $other…")
            open(other, null)
            return
        }
        if (wasReady && resumeHandle != null && reconnects < 2 && code != 1000) {
            reconnects++
            EventLog.add("Conexão caiu ($r). Retomando…")
            open(version, resumeHandle)
            return
        }
        listener.onClosed(r, code != 1000)
    }

    private fun looksLikeVersionProblem(r: String): Boolean {
        val l = r.lowercase(Locale.ROOT)
        return "not found" in l || "not supported" in l || "404" in l || "unknown name" in l || "cannot find field" in l
    }

    fun sendAudio(pcm: ByteArray) {
        if (!ready) return
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        ws?.send("{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=16000\",\"data\":\"$b64\"}}}")
    }

    /** Manda uma mensagem de texto para a assistente no meio da conversa (ex.: lembrete na hora marcada). */
    fun sendText(text: String) {
        if (!ready) return
        val msg = JSONObject().put(
            "clientContent", JSONObject()
                .put("turns", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))))
                .put("turnComplete", true)
        )
        ws?.send(msg.toString())
    }

    fun sendAudioStreamEnd() {
        if (!ready) return
        ws?.send("{\"realtimeInput\":{\"audioStreamEnd\":true}}")
    }

    fun close() {
        closedByUs = true
        ready = false
        socketId++
        try { ws?.close(1000, "fim") } catch (_: Exception) { }
        ws = null
        toolExecutor.shutdownNow()
    }

    private fun buildSetup(handle: String?): JSONObject {
        val gen = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put(
                "speechConfig", JSONObject().put(
                    "voiceConfig", JSONObject().put(
                        "prebuiltVoiceConfig", JSONObject().put("voiceName", prefs.voice)
                    )
                )
            )
        if (thinkingModel) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "LOW"))

        val (silenceMs, endSens) = when (prefs.pauseLevel) {
            0 -> 600 to "END_SENSITIVITY_HIGH"
            2 -> 2000 to "END_SENSITIVITY_LOW"
            3 -> 3500 to "END_SENSITIVITY_LOW"
            else -> 1000 to null
        }
        val vad = JSONObject().put("silenceDurationMs", silenceMs)
        if (endSens != null) vad.put("endOfSpeechSensitivity", endSens)

        val setup = JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", gen)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put(
                "tools", JSONArray()
                    .put(JSONObject().put("googleSearch", JSONObject()))
                    .put(JSONObject().put("functionDeclarations", tools.declarations(thinkingModel)))
            )
            .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", vad))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            .put("sessionResumption", if (handle != null) JSONObject().put("handle", handle) else JSONObject())
        return JSONObject().put("setup", setup)
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy, HH:mm", Locale("pt", "BR")).format(Date())
        val user = prefs.userName.ifBlank { "o motorista" }
        val style = Prefs.STYLES.getOrNull(prefs.style)?.second ?: Prefs.STYLES[0].second
        return buildString {
            appendLine("Você é ${prefs.assistantName}, a assistente de voz dentro de um carro BYD, conversando com $user, que está dirigindo.")
            appendLine("Regras:")
            appendLine("- Fale sempre em português do Brasil, de forma natural, como numa conversa de verdade.")
            appendLine("- Respostas curtas: 1 a 3 frases, a não ser que peçam detalhes. Nada de listas, emojis, links ou formatação: tudo vira fala.")
            appendLine("- Segurança em primeiro lugar: nunca incentive tirar os olhos da estrada ou mexer no celular.")
            appendLine("- Para fatos atuais (notícias, clima, cotações, placares, horários, preços), pesquise no Google antes de responder. Para o que depende do lugar (clima, 'perto daqui'), chame onde_estou primeiro.")
            appendLine("- Rota: abrir_rota (Waze ou Google Maps da central). Música: tocar_musica ou controle_midia. Volume: ajustar_volume. Abrir apps: abrir_aplicativo.")
            appendLine("- Carro: ar-condicionado com controlar_ar, vidros com controlar_vidros e aquecimento/ventilação dos bancos com controlar_bancos. Você NÃO mexe em portas, travas, porta-malas, carregamento, faróis, modos de condução ou direção: se pedirem, diga com bom humor que isso você não faz.")
            appendLine("- Nunca diga que fez uma ação sem antes chamar a função correspondente e receber o resultado.")
            appendLine("- Quando chegar a mensagem [início da conversa], cumprimente em uma frase curta, seguindo as instruções do dono.")
            appendLine("- Quando chegar a mensagem [chamou de novo], é a mesma conversa continuando (o carro não foi desligado): responda bem curto, por exemplo 'Oi, pode falar', sem cumprimentar de novo e sem perguntar com quem está falando. Lembre-se de tudo o que já foi dito.")
            appendLine("- Depois de uma ação, confirme em poucas palavras, por exemplo 'Pronto, rota aberta'. Se der erro, diga o motivo em uma frase.")
            appendLine("- Se não entender, peça para repetir de forma curta. Se ouvir só ruído ou conversa que não é com você, fique em silêncio.")
            appendLine("- Quando $user se despedir ou disser que é só isso, responda curto e chame encerrar_conversa.")
            appendLine("- Anotações: anotar, ver_anotacoes, apagar_anotacao. Lembretes: criar_lembrete (calcule o horário a partir de agora), ver_lembretes, apagar_lembrete.")
            appendLine("- Quando a conversa começar com uma mensagem [lembrete], ela é só para avisar: diga o lembrete de forma natural e curta, sem cumprimentar e sem perguntar com quem está falando (essa regra vale mesmo que as instruções do dono peçam essa pergunta).")
            appendLine("- Agora é $now.")
            appendLine("- Calendário dos próximos dias: ${calendario()}.")
            appendLine("Estilo: $style")
            val notas = tools.notesBlock()
            if (notas.isNotBlank()) appendLine(notas)
            if (prefs.extraInstructions.isNotBlank()) appendLine("Instruções do dono: ${prefs.extraInstructions}")
        }
    }

    private fun calendario(): String {
        val f = SimpleDateFormat("EEEE dd/MM", Locale("pt", "BR"))
        val c = java.util.Calendar.getInstance()
        val dias = ArrayList<String>()
        for (i in 0..7) {
            val nome = when (i) { 0 -> "hoje"; 1 -> "amanhã"; else -> null }
            dias.add((if (nome != null) "$nome " else "") + f.format(c.time))
            c.add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        return dias.joinToString(", ")
    }

    private fun handle(text: String, version: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) {
            EventLog.add("Mensagem inválida do servidor")
            return
        }

        if (msg.has("setupComplete")) {
            ready = true
            reconnects = 0
            if (prefs.apiVersion != version) prefs.apiVersion = version
            listener.onReady()
            val first = opening ?: if (resumedStart) "[chamou de novo]" else if (prefs.speakFirst) "[início da conversa]" else null
            if (resumedStart && !greeted) EventLog.add("Continuando a conversa anterior")
            startHandle = null // daqui em diante, quedas usam a retomada normal
            if (!greeted && first != null) {
                greeted = true
                val hello = JSONObject().put(
                    "clientContent", JSONObject()
                        .put(
                            "turns", JSONArray().put(
                                JSONObject().put("role", "user")
                                    .put("parts", JSONArray().put(JSONObject().put("text", first)))
                            )
                        )
                        .put("turnComplete", true)
                )
                ws?.send(hello.toString())
            }
            greeted = true
            return
        }

        msg.optJSONObject("sessionResumptionUpdate")?.let { u ->
            if (u.optBoolean("resumable") && u.optString("newHandle").isNotBlank()) {
                resumeHandle = u.optString("newHandle")
            }
        }

        msg.optJSONObject("voiceActivity")?.let { v ->
            if (v.optString("type") == "ACTIVITY_START") listener.onUserActivity()
        }

        msg.optJSONObject("goAway")?.let {
            if (resumeHandle != null) {
                EventLog.add("Servidor vai renovar a conexão. Retomando…")
                val h = resumeHandle
                socketId++ // ignora o socket antigo
                try { ws?.close(1000, "renovar") } catch (_: Exception) { }
                open(version, h)
            }
            return
        }

        msg.optJSONObject("toolCall")?.let { tc ->
            val calls = tc.optJSONArray("functionCalls") ?: JSONArray()
            for (i in 0 until calls.length()) {
                val c = calls.optJSONObject(i) ?: continue
                val id = c.optString("id")
                val name = c.optString("name")
                val args = c.optJSONObject("args") ?: JSONObject()
                listener.onToolCall(name)
                EventLog.add("Ação: $name $args")
                toolExecutor.execute {
                    val result = tools.execute(name, args)
                    EventLog.add("Resultado: $result")
                    if (thinkingModel) result.put("scheduling", "WHEN_IDLE")
                    val resp = JSONObject().put(
                        "toolResponse", JSONObject().put(
                            "functionResponses", JSONArray().put(
                                JSONObject().put("id", id).put("name", name).put("response", result)
                            )
                        )
                    )
                    ws?.send(resp.toString())
                }
            }
            return
        }

        msg.optJSONObject("toolCallCancellation")?.let {
            EventLog.add("Ação cancelada pelo servidor")
            return
        }

        val sc = msg.optJSONObject("serverContent") ?: return

        sc.optJSONObject("interimInputTranscription")?.let { listener.onUserActivity() }
        sc.optJSONObject("inputTranscription")?.optString("text")?.let {
            if (it.isNotEmpty()) {
                userText.append(it)
                listener.onUserActivity()
            }
        }
        sc.optJSONObject("outputTranscription")?.optString("text")?.let {
            if (it.isNotEmpty()) modelText.append(it)
        }

        if (sc.optBoolean("interrupted")) {
            listener.onInterrupted()
        }

        sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                val data = p.optJSONObject("inlineData") ?: continue
                if (!data.optString("mimeType").startsWith("audio")) continue
                val b64 = data.optString("data")
                if (b64.isEmpty()) continue
                val pcm = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { null } ?: continue
                flushUserText()
                listener.onAudio(pcm)
            }
        }

        if (sc.optBoolean("turnComplete")) {
            flushUserText()
            if (modelText.isNotBlank()) {
                EventLog.add("${prefs.assistantName}: ${modelText.toString().trim()}")
                modelText.setLength(0)
            }
            val status = sc.optString("interactionStatus", msg.optString("interactionStatus", ""))
            if (status != "IN_PROGRESS") listener.onTurnComplete()
        }
    }

    private fun flushUserText() {
        if (userText.isNotBlank()) {
            EventLog.add("Você: ${userText.toString().trim()}")
            userText.setLength(0)
        }
    }
}
