package com.assistenteseal.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Funções que o Gemini pode chamar durante a conversa. */
class ToolBox(private val ctx: Context, private val http: OkHttpClient) {

    /** Chamado quando o Gemini pede para encerrar a conversa. */
    var onEndRequested: (() -> Unit)? = null

    init {
        CarControl.init(ctx)
    }

    fun notesBlock(): String = try { Notes.promptBlock(ctx) } catch (_: Exception) { "" }

    fun declarations(nonBlocking: Boolean): JSONArray {
        val behavior = if (nonBlocking) "NON_BLOCKING" else "BLOCKING"
        fun str(desc: String, enum: List<String>? = null): JSONObject {
            val o = JSONObject().put("type", "STRING").put("description", desc)
            if (enum != null) o.put("enum", JSONArray(enum))
            return o
        }
        fun int(desc: String) = JSONObject().put("type", "INTEGER").put("description", desc)
        fun fn(name: String, desc: String, props: JSONObject? = null, required: List<String> = emptyList()): JSONObject {
            val o = JSONObject().put("name", name).put("description", desc).put("behavior", behavior)
            if (props != null) {
                val p = JSONObject().put("type", "OBJECT").put("properties", props)
                if (required.isNotEmpty()) p.put("required", JSONArray(required))
                o.put("parameters", p)
            }
            return o
        }
        return JSONArray()
            .put(
                fn(
                    "abrir_rota",
                    "Abre a navegação na tela do carro já indo até o destino (Waze ou Google Maps, o que estiver instalado). Use para 'me leva para', 'traça a rota', 'como chego em'.",
                    JSONObject()
                        .put("destino", str("Destino do jeito que o motorista falou: endereço, cidade, lugar ou nome do estabelecimento."))
                        .put("app", str("App de navegação, se o motorista disser.", listOf("google_maps", "waze", "qualquer"))),
                    listOf("destino")
                )
            )
            .put(
                fn(
                    "tocar_musica",
                    "Toca uma música, artista, álbum ou playlist no app de música da central.",
                    JSONObject()
                        .put("busca", str("O que tocar, por exemplo 'Evidências Chitãozinho e Xororó' ou 'playlist sertanejo'."))
                        .put("app", str("App de música, se o motorista disser. Padrão: o que estiver instalado (Spotify).", listOf("youtube_music", "spotify", "deezer", "qualquer"))),
                    listOf("busca")
                )
            )
            .put(
                fn(
                    "controle_midia",
                    "Controla o que está tocando: tocar, pausar, próxima ou anterior.",
                    JSONObject().put("acao", str("Ação", listOf("tocar", "pausar", "proxima", "anterior"))),
                    listOf("acao")
                )
            )
            .put(
                fn(
                    "ajustar_volume",
                    "Ajusta o volume da música. Use nivel (0 a 100) para um valor exato ou acao para aumentar/diminuir um pouco.",
                    JSONObject()
                        .put("nivel", int("Volume de 0 a 100."))
                        .put("acao", str("Aumentar ou diminuir um pouco.", listOf("aumentar", "diminuir")))
                )
            )
            .put(
                fn(
                    "abrir_aplicativo",
                    "Abre um aplicativo instalado na central pelo nome, por exemplo Spotify, YouTube, Google Maps.",
                    JSONObject().put("nome", str("Nome do aplicativo.")),
                    listOf("nome")
                )
            )
            .put(
                fn(
                    "controlar_ar",
                    "Controla o ar-condicionado do carro: ligar/desligar, temperatura (17 a 33 graus) e ventilação (1 a 7). Envie só o que o motorista pediu.",
                    JSONObject()
                        .put("ligar", JSONObject().put("type", "BOOLEAN").put("description", "true para ligar, false para desligar o ar."))
                        .put("temperatura", JSONObject().put("type", "NUMBER").put("description", "Temperatura em graus Celsius, de 17 a 33."))
                        .put("ventilacao", int("Força do ventilador, de 1 a 7."))
                )
            )
            .put(
                fn(
                    "controlar_vidros",
                    "Abre, fecha ou para os vidros do carro. Se o motorista não disser qual, use todos.",
                    JSONObject()
                        .put("acao", str("O que fazer.", listOf("abrir", "fechar", "parar")))
                        .put(
                            "vidros", JSONObject().put("type", "ARRAY").put(
                                "items", str("Vidro", listOf("todos", "motorista", "passageiro", "traseiro_esquerdo", "traseiro_direito"))
                            ).put("description", "Quais vidros. Traseiros = traseiro_esquerdo e traseiro_direito; dianteiros = motorista e passageiro.")
                        ),
                    listOf("acao")
                )
            )
            .put(
                fn(
                    "controlar_bancos",
                    "Liga, desliga ou ajusta o aquecimento ou a ventilação dos bancos dianteiros.",
                    JSONObject()
                        .put("tipo", str("Aquecimento ou ventilação.", listOf("aquecimento", "ventilacao")))
                        .put("banco", str("Qual banco. Se não disserem, use motorista.", listOf("motorista", "passageiro", "ambos")))
                        .put("nivel", int("0 desliga, 1 baixo, 2 alto. Se pedirem só para ligar, use 1.")),
                    listOf("tipo", "nivel")
                )
            )
            .put(
                fn(
                    "anotar",
                    "Guarda uma anotação para lembrar depois (ex.: 'estacionei no piso 3').",
                    JSONObject().put("texto", str("O que anotar, do jeito que o motorista disse.")),
                    listOf("texto")
                )
            )
            .put(
                fn(
                    "ver_anotacoes",
                    "Lista as anotações guardadas (as mais recentes primeiro). Use busca para filtrar por uma palavra.",
                    JSONObject().put("busca", str("Palavra para filtrar (opcional)."))
                )
            )
            .put(
                fn(
                    "apagar_anotacao",
                    "Apaga as anotações que contêm o trecho informado ('todas' apaga todas).",
                    JSONObject().put("trecho", str("Trecho da anotação a apagar.")),
                    listOf("trecho")
                )
            )
            .put(
                fn(
                    "criar_lembrete",
                    "Marca um lembrete: no horário, se o carro estiver ligado, você avisa o motorista; se estiver desligado, avisa quando ligar. Para 'daqui a X minutos/horas' use daqui_minutos. Para dia e hora use quando (dia da semana = a próxima data dele no calendário do prompt). Se faltar o horário de um dia citado, pergunte. Depois confirme o dia e a hora em voz alta.",
                    JSONObject()
                        .put("texto", str("Do que lembrar."))
                        .put("daqui_minutos", int("Para pedidos relativos: daqui a quantos minutos (ex.: 'daqui a 2 horas' = 120)."))
                        .put("quando", str("Para dia/hora: AAAA-MM-DD HH:MM (horário de Brasília).")),
                    listOf("texto")
                )
            )
            .put(fn("ver_lembretes", "Lista os lembretes marcados que ainda não foram avisados."))
            .put(
                fn(
                    "apagar_lembrete",
                    "Cancela os lembretes que contêm o trecho informado ('todos' cancela todos).",
                    JSONObject().put("trecho", str("Trecho do lembrete a cancelar.")),
                    listOf("trecho")
                )
            )
            .put(
                fn(
                    "onde_estou",
                    "Retorna a localização atual do carro (cidade, bairro, rua e coordenadas). Use antes de responder perguntas que dependem do lugar, como clima ou 'perto daqui'."
                )
            )
            .put(
                fn(
                    "encerrar_conversa",
                    "Encerra a conversa e volta a esperar. Use quando o motorista se despedir, agradecer encerrando ou disser que é só isso."
                )
            )
    }

    fun execute(name: String, args: JSONObject): JSONObject {
        return try {
            when (name) {
                "abrir_rota" -> openRoute(args.optString("destino"), args.optString("app", "qualquer"))
                "tocar_musica" -> playMusic(args.optString("busca"), args.optString("app", "qualquer"))
                "controlar_ar" -> climate(args)
                "controlar_vidros" -> {
                    val list = args.optJSONArray("vidros")
                    val which = HashSet<String>()
                    if (list != null) for (i in 0 until list.length()) which.add(list.optString(i))
                    ok(CarControl.windows(args.optString("acao"), which))
                }
                "controlar_bancos" -> ok(CarControl.seat(args.optString("tipo"), args.optString("banco", "motorista"), args.optInt("nivel", 1)))
                "anotar" -> ok(Notes.addNota(ctx, args.optString("texto")))
                "ver_anotacoes" -> JSONObject().put("anotacoes", Notes.listNotas(ctx, args.optString("busca")))
                "apagar_anotacao" -> ok(Notes.removeNota(ctx, args.optString("trecho")))
                "criar_lembrete" -> ok(Notes.addLembrete(ctx, args.optString("texto"), args.optString("quando"), args.optInt("daqui_minutos", 0)))
                "ver_lembretes" -> JSONObject().put("lembretes", Notes.listLembretes(ctx))
                "apagar_lembrete" -> ok(Notes.removeLembrete(ctx, args.optString("trecho")))
                "controle_midia" -> mediaControl(args.optString("acao"))
                "ajustar_volume" -> volume(if (args.has("nivel")) args.optInt("nivel") else null, args.optString("acao", ""))
                "abrir_aplicativo" -> openApp(args.optString("nome"))
                "onde_estou" -> whereAmI()
                "encerrar_conversa" -> {
                    onEndRequested?.invoke()
                    ok("Conversa será encerrada depois desta resposta.")
                }
                else -> err("Função desconhecida: $name")
            }
        } catch (e: Exception) {
            err(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun ok(msg: String) = JSONObject().put("resultado", msg)
    private fun err(msg: String) = JSONObject().put("erro", msg)

    private fun isInstalled(pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Abre o pedido num app de verdade. Nunca manda para ferramentas como o Interceptador. */
    private fun launch(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pm = ctx.packageManager
        val handlers = try { pm.queryIntentActivities(intent, 0) } catch (_: Exception) { emptyList() }
            .filter { !isBlocked(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        if (handlers.isEmpty()) return false
        if (intent.`package` == null && intent.component == null) {
            val ai = handlers.first().activityInfo
            intent.setClassName(ai.packageName, ai.name)
        }
        ctx.startActivity(intent)
        EventLog.add("Abrindo em: ${label(intent.component?.packageName ?: intent.`package` ?: "")}")
        return true
    }

    private fun isBlocked(pkg: String, label: String): Boolean {
        val l = norm(label)
        return pkg in BLOCKED || pkg.contains("intentintercept") || l.contains("intercept") || pkg == ctx.packageName
    }

    /** Acha o pacote de um app pelo nome conhecido ou pelo nome que aparece na central. */
    private fun findApp(knownPkg: String, vararg labelKeys: String): String? {
        if (isInstalled(knownPkg)) return knownPkg
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (ri in pm.queryIntentActivities(launcher, 0)) {
            val pkg = ri.activityInfo.packageName
            val l = norm(ri.loadLabel(pm).toString())
            if (isBlocked(pkg, l)) continue
            if (labelKeys.any { l.contains(it) }) return pkg
        }
        return null
    }

    private fun climate(args: JSONObject): JSONObject {
        val done = ArrayList<String>()
        if (args.has("ligar")) done.add(CarControl.acPower(args.optBoolean("ligar")))
        if (args.has("temperatura")) done.add(CarControl.acTemperature(args.optDouble("temperatura")))
        if (args.has("ventilacao")) done.add(CarControl.acFan(args.optInt("ventilacao")))
        if (done.isEmpty()) return err("Nenhum ajuste do ar informado")
        return ok(done.joinToString("; "))
    }

    private fun openRoute(dest: String, app: String): JSONObject {
        if (dest.isBlank()) return err("Destino vazio")
        val enc = Uri.encode(dest)
        val maps = findApp(MAPS, "google maps", "maps")
        val waze = findApp(WAZE, "waze")
        if ((app == "waze" || maps == null) && waze != null) {
            val w = Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?q=$enc&navigate=yes")).setPackage(waze)
            if (launch(w)) return ok("Waze aberto navegando até $dest")
            if (launch(Intent(Intent.ACTION_VIEW, Uri.parse("waze://?q=$enc&navigate=yes")).setPackage(waze))) {
                return ok("Waze aberto navegando até $dest")
            }
        }
        if (maps != null) {
            val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$enc&mode=d")).setPackage(maps)
            if (launch(nav)) return ok("Google Maps aberto navegando até $dest")
            val dir = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$enc&travelmode=driving")
            ).setPackage(maps)
            if (launch(dir)) return ok("Google Maps aberto com a rota até $dest")
        }
        if (launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$enc")))) {
            return ok("Mapa aberto com o destino $dest")
        }
        return err("Não há app de navegação instalado na central (Google Maps ou Waze). Diga ao motorista que precisa instalar um deles.")
    }

    private fun playMusic(query: String, app: String): JSONObject {
        if (query.isBlank()) return err("Busca vazia")
        val yt = findApp(YT_MUSIC, "youtube music", "yt music")
        val sp = findApp(SPOTIFY, "spotify")
        val dz = findApp(DEEZER, "deezer")
        val candidates = when (app) {
            "spotify" -> listOfNotNull(sp)
            "youtube_music" -> listOfNotNull(yt)
            "deezer" -> listOfNotNull(dz)
            else -> listOfNotNull(yt, sp, dz)
        }
        if (candidates.isEmpty()) return err("O app de música pedido não está instalado na central")
        var lastReason = ""
        for (pkg in candidates) {
            val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(SearchManager.QUERY, query)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .setPackage(pkg)
            if (launch(i)) return ok("Pedido para tocar '$query' enviado ao ${label(pkg)}")
            // Spotify de carro: pede pelo serviço de mídia, como a tela de mídia da central faz
            val r = MediaPlay.playFromSearch(ctx, pkg, query)
            EventLog.add("${label(pkg)} pelo serviço de mídia: $r")
            if (r.startsWith("ok")) return ok("${label(pkg)}: ${r.removePrefix("ok: ")}")
            lastReason = r.removePrefix("falhou: ")
        }
        for (pkg in candidates) {
            if (pkg == sp && launch(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query))).setPackage(sp))) {
                return ok("Spotify aberto com a busca '$query' (talvez precise tocar no resultado)")
            }
            if (pkg == yt && launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(query))).setPackage(yt))) {
                return ok("YouTube Music aberto com a busca '$query' (talvez precise tocar no resultado)")
            }
        }
        return err("Não consegui tocar no app de música da central" + if (lastReason.isNotBlank()) " ($lastReason)" else "")
    }

    private fun mediaControl(action: String): JSONObject {
        val code = when (action) {
            "tocar" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pausar" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "proxima" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "anterior" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return err("Ação inválida")
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        return ok("Comando '$action' enviado")
    }

    private fun volume(level: Int?, action: String): JSONObject {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream)
        if (level != null) {
            val v = (level.coerceIn(0, 100) * max + 50) / 100
            am.setStreamVolume(stream, v, AudioManager.FLAG_SHOW_UI)
            return ok("Volume da música em $level%")
        }
        val dir = when (action) {
            "aumentar" -> AudioManager.ADJUST_RAISE
            "diminuir" -> AudioManager.ADJUST_LOWER
            else -> return err("Informe nivel ou acao")
        }
        repeat(2) { am.adjustStreamVolume(stream, dir, AudioManager.FLAG_SHOW_UI) }
        val now = am.getStreamVolume(stream) * 100 / maxOf(max, 1)
        return ok("Volume agora em $now%")
    }

    private fun openApp(name: String): JSONObject {
        val target = norm(name)
        if (target.isBlank()) return err("Nome vazio")
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        var best: Pair<String, String>? = null
        var bestScore = 0
        for (ri in apps) {
            val label = ri.loadLabel(pm).toString()
            val l = norm(label)
            if (isBlocked(ri.activityInfo.packageName, label)) continue
            val score = when {
                l == target -> 100
                l.startsWith(target) || target.startsWith(l) -> 80
                l.contains(target) || target.contains(l) -> 60
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                best = ri.activityInfo.packageName to label
            }
        }
        val found = best ?: return err("Não achei nenhum app chamado '$name'")
        val i = pm.getLaunchIntentForPackage(found.first) ?: return err("Não consegui abrir ${found.second}")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return ok("${found.second} aberto")
    }

    @SuppressLint("MissingPermission")
    private fun whereAmI(): JSONObject {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return err("Permissão de localização não liberada no app")
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var loc: Location? = null
        for (p in lm.getProviders(true)) {
            val l = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } ?: continue
            if (loc == null || l.time > loc.time) loc = l
        }
        val l = loc ?: return err("Sem localização disponível no momento")
        val lat = l.latitude
        val lon = l.longitude
        val out = JSONObject().put("latitude", lat).put("longitude", lon)
        var address = ""
        try {
            if (Geocoder.isPresent()) {
                @Suppress("DEPRECATION")
                val list = Geocoder(ctx, Locale("pt", "BR")).getFromLocation(lat, lon, 1)
                val a = list?.firstOrNull()
                if (a != null) {
                    address = listOfNotNull(a.thoroughfare, a.subLocality, a.locality ?: a.subAdminArea, a.adminArea)
                        .joinToString(", ")
                }
            }
        } catch (_: Exception) {
        }
        if (address.isBlank()) {
            try {
                val req = Request.Builder()
                    .url("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=17&accept-language=pt-BR&lat=$lat&lon=$lon")
                    .header("User-Agent", "AssistenteSeal/0.1 (uso pessoal)")
                    .build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string()
                    if (resp.isSuccessful && body != null) {
                        val a = JSONObject(body).optJSONObject("address")
                        if (a != null) {
                            address = listOf(
                                a.optString("road"),
                                a.optString("suburb"),
                                a.optString("city").ifBlank { a.optString("town").ifBlank { a.optString("village") } },
                                a.optString("state")
                            ).filter { it.isNotBlank() }.joinToString(", ")
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (address.isNotBlank()) out.put("endereco", address)
        return out
    }

    private fun label(pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    /** Diz quais apps úteis estão instalados na central (para o registro). */
    fun installedSummary(): String {
        val apps = listOf(
            "Google Maps" to findApp(MAPS, "google maps", "maps"),
            "Waze" to findApp(WAZE, "waze"),
            "YouTube Music" to findApp(YT_MUSIC, "youtube music", "yt music"),
            "Spotify" to findApp(SPOTIFY, "spotify"),
            "Deezer" to findApp(DEEZER, "deezer")
        )
        val pm = ctx.packageManager
        fun handlers(i: Intent) = try {
            pm.queryIntentActivities(i, 0).map { "${it.loadLabel(pm)} (${it.activityInfo.packageName})" }.distinct()
        } catch (_: Exception) { emptyList() }
        val music = handlers(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH))
        val maps = handlers(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=teste")))
        return apps.joinToString(", ") { (n, p) -> n + (if (p != null) " ✓ ($p)" else " ✗") } +
            ". Música por busca: " + music.ifEmpty { listOf("nenhum") }.joinToString("; ") +
            ". Mapas: " + maps.ifEmpty { listOf("nenhum") }.joinToString("; ")
    }

    companion object {
        const val WAZE = "com.waze"
        const val MAPS = "com.google.android.apps.maps"
        const val SPOTIFY = "com.spotify.music"
        const val YT_MUSIC = "com.google.android.apps.youtube.music"
        const val DEEZER = "deezer.android.app"
        val BLOCKED = setOf("uk.co.ashtonbrsc.android.intentintercept")

    }
}
