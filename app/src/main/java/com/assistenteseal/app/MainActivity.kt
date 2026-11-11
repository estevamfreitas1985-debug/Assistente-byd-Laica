package com.assistenteseal.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import okhttp3.OkHttpClient

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var permMic: Button
    private lateinit var permLoc: Button
    private lateinit var permOverlay: Button
    private lateinit var serviceBtn: Button

    private lateinit var keyEdit: EditText
    private lateinit var modelEdit: EditText
    private lateinit var nameEdit: EditText
    private lateinit var userEdit: EditText
    private lateinit var extraEdit: EditText
    private lateinit var voiceSpin: Spinner
    private lateinit var styleSpin: Spinner
    private lateinit var outputSpin: Spinner
    private lateinit var musicSpin: Spinner
    private lateinit var micSpin: Spinner
    private lateinit var pauseSpin: Spinner
    private lateinit var listenSpin: Spinner
    private lateinit var sizeSpin: Spinner
    private lateinit var bargeSwitch: Switch
    private lateinit var bootSwitch: Switch
    private lateinit var speakFirstSwitch: Switch
    private lateinit var wakeSwitch: Switch
    private lateinit var wakeEdit: EditText

    private val sizes = listOf(64, 84, 104, 128)
    private val logListener: () -> Unit = { runOnUiThread { refreshLog() } }

    private val bg = 0xFF0E1420.toInt()
    private val card = 0xFF182235.toInt()
    private val accent = 0xFF1F6FEB.toInt()
    private val textMain = Color.WHITE
    private val textSoft = 0xFFAFC0D8.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.decorView.setBackgroundColor(bg)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        EventLog.addListener(logListener)
        refreshAll()
        if (!AssistantService.running && hasMic()) {
            try { AssistantService.send(this, AssistantService.ACTION_START) } catch (_: Exception) { }
        } else if (AssistantService.running && Settings.canDrawOverlays(this)) {
            AssistantService.send(this, AssistantService.ACTION_BUBBLE)
        }
        refreshAll()
    }

    override fun onPause() {
        save(false)
        EventLog.removeListener(logListener)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshAll()
        if (requestCode == 1 && hasMic() && !AssistantService.running) {
            AssistantService.send(this, AssistantService.ACTION_START)
        }
        if (requestCode == 3 || requestCode == 4 || requestCode == 5) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                when (requestCode) {
                    3 -> readKeyFromUsb()
                    4 -> readInstructionsFromUsb()
                    else -> readTokenFromUsb()
                }
            } else {
                Toast.makeText(this, "Sem permissão para ler o pen drive", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------- chave pelo pen drive ----------------

    private fun readKeyFromUsb() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 3)
            return
        }
        Toast.makeText(this, "Procurando chave.txt no pen drive…", Toast.LENGTH_SHORT).show()
        Thread {
            val found = findFile({ n -> n.contains("chave") || n.contains("gemini") || n.contains("key") }, ::extractKey)
            runOnUiThread {
                if (found == null) {
                    EventLog.add("Não achei o arquivo chave.txt. Confira se o pen drive está conectado e se o arquivo está na pasta principal dele.")
                    Toast.makeText(this, "Não achei o arquivo chave.txt no pen drive", Toast.LENGTH_LONG).show()
                } else {
                    val (key, path) = found
                    keyEdit.setText(key)
                    prefs.apiKey = key
                    EventLog.add("Chave lida de $path: termina em …${key.takeLast(4)} (${key.length} caracteres). Salva.")
                    Toast.makeText(this, "Chave lida e salva (termina em ${key.takeLast(4)})", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun readInstructionsFromUsb() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 4)
            return
        }
        Toast.makeText(this, "Procurando instrucoes.txt no pen drive…", Toast.LENGTH_SHORT).show()
        Thread {
            val found = findFile({ n -> n.contains("instruc") }) { f ->
                try {
                    if (f.length() > 20000) null
                    else f.readText(Charsets.UTF_8).removePrefix("\uFEFF").trim().ifBlank { null }
                } catch (_: Exception) {
                    null
                }
            }
            runOnUiThread {
                if (found == null) {
                    EventLog.add("Não achei o arquivo instrucoes.txt no pen drive.")
                    Toast.makeText(this, "Não achei o arquivo instrucoes.txt no pen drive", Toast.LENGTH_LONG).show()
                } else {
                    val (text, path) = found
                    extraEdit.setText(text)
                    prefs.extraInstructions = text
                    EventLog.add("Instruções lidas de $path (${text.length} caracteres). Salvas.")
                    Toast.makeText(this, "Instruções lidas e salvas. Valem na próxima conversa.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---------------- atualização pela internet ----------------

    private fun readTokenFromUsb() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 5)
            return
        }
        Toast.makeText(this, "Procurando token.txt no pen drive…", Toast.LENGTH_SHORT).show()
        Thread {
            val found = findFile({ n -> n.contains("token") }) { f ->
                try {
                    if (f.length() > 5000) null
                    else f.readText(Charsets.UTF_8).removePrefix("\uFEFF").lines().map { it.trim() }
                        .firstOrNull { it.length >= 20 && !it.contains(' ') }
                } catch (_: Exception) {
                    null
                }
            }
            runOnUiThread {
                if (found == null) {
                    EventLog.add("Não achei o arquivo token.txt no pen drive.")
                    Toast.makeText(this, "Não achei o arquivo token.txt no pen drive", Toast.LENGTH_LONG).show()
                } else {
                    prefs.githubToken = found.first
                    EventLog.add("Token do GitHub lido de ${found.second} e salvo. Pode apagar o token.txt do pen drive.")
                    Toast.makeText(this, "Token salvo. Agora toque em Procurar atualização.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun checkUpdate() {
        val token = prefs.githubToken // só é preciso se o repositório for privado
        Toast.makeText(this, "Procurando atualização…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val cur = Updater.currentCode(applicationContext)
                val rel = Updater.latest(token)
                if (rel.code <= cur) {
                    EventLog.add("Você já está na versão mais nova (0.1.$cur).")
                    runOnUiThread { Toast.makeText(this, "Já está na versão mais nova", Toast.LENGTH_LONG).show() }
                    return@Thread
                }
                EventLog.add("Versão nova ${rel.tag} encontrada. Baixando ${rel.size / 1_000_000} MB pela internet do carro…")
                val f = Updater.download(applicationContext, token, rel) { pct -> EventLog.add("Baixando… $pct%") }
                EventLog.add("Download concluído. Instalando… o app vai fechar e voltar sozinho em alguns segundos.")
                runOnUiThread { Toast.makeText(this, "Instalando a versão nova…", Toast.LENGTH_LONG).show() }
                Adb.install(applicationContext, f)
                EventLog.add("Instalação concluída.")
            } catch (e: Exception) {
                EventLog.add("Atualização falhou: ${e.message}")
                runOnUiThread { Toast.makeText(this, "Atualização falhou: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun testCar() {
        Toast.makeText(this, "Testando o acesso ao carro…", Toast.LENGTH_SHORT).show()
        Thread {
            val apps = try { ToolBox(applicationContext, OkHttpClient()).installedSummary() } catch (e: Exception) { "erro: ${e.message}" }
            EventLog.add("Apps na central: $apps")
            CarControl.init(applicationContext)
            EventLog.add("Teste do carro: " + CarControl.probe())
            EventLog.add("Controle da música (acesso às notificações): " + if (MediaPlay.hasAccess(applicationContext)) "liberado ✓" else "não liberado ✗")
            EventLog.add("Depuração da central (ADB, porta 5555): " + if (Adb.portOpen()) "ligada ✓" else "desligada ✗")
            EventLog.add("Ligar junto com o carro (serviço de acessibilidade): " + if (AutostartService.isEnabled(applicationContext)) "ativo ✓" else "inativo ✗")
            EventLog.add("Vigia de religamento: " + try { Adb.daemonStatus(applicationContext) } catch (e: Exception) { "sem acesso (${e.message})" })
        }.start()
    }

    /** Liga/desliga o serviço que inicia o assistente junto com o carro (pela depuração da central). */
    private fun applyAutostart(enable: Boolean) {
        EventLog.add(if (enable) "Ativando \"ligar junto com o carro\"…" else "Desativando \"ligar junto com o carro\"…")
        Thread {
            val msg = try {
                if (enable) "Ligar junto com o carro: " + Adb.ensureKeepAlive(applicationContext)
                else "Ligar junto com o carro: " + Adb.setAutostart(applicationContext, false) + " | " + Adb.pauseDaemon(applicationContext, kill = true)
            } catch (e: Exception) {
                "Não consegui mudar o \"ligar junto com o carro\": ${e.message}"
            }
            EventLog.add(msg)
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }

    /** Libera o controle da música pelo ADB da central (a BYD esconde a tela normal do Android). */
    private fun grantMusicControl() {
        Toast.makeText(this, "Liberando o controle da música… se a central perguntar sobre depuração, toque em Permitir", Toast.LENGTH_LONG).show()
        EventLog.add("Liberando o controle da música pela depuração da central…")
        Thread {
            val msg = try {
                "Controle da música: " + Adb.grantMediaControl(applicationContext)
            } catch (e: Exception) {
                "Não consegui liberar o controle da música: ${e.message}"
            }
            EventLog.add(msg)
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }

    /** Abre a tela do sistema onde se libera o "acesso às notificações" (controle da música). */
    private fun openNotificationAccess() {
        val candidates = listOf(
            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$NotificationAccessSettingsActivity"),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$NotificationAccessSettingsActivity").setAction(Intent.ACTION_MAIN)
        )
        for (i in candidates) {
            try {
                val list = packageManager.queryIntentActivities(i, 0)
                    .filter { !it.activityInfo.packageName.contains("muntashirakon") && !it.loadLabel(packageManager).toString().lowercase().contains("intercept") }
                if (list.isEmpty()) continue
                val ai = list.first().activityInfo
                startActivity(Intent(i).setClassName(ai.packageName, ai.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Toast.makeText(this, "Ligue a chave do \"Assistente Seal\" nesta lista e volte", Toast.LENGTH_LONG).show()
                return
            } catch (_: Exception) {
            }
        }
        EventLog.add("Esta central não abriu a tela de acesso às notificações.")
        Toast.makeText(this, "Esta central não mostrou a tela de acesso às notificações", Toast.LENGTH_LONG).show()
    }

    /** Procura no pen drive um .txt cujo nome bate com [nameOk] e extrai o conteúdo com [extract]. */
    private fun findFile(nameOk: (String) -> Boolean, extract: (File) -> String?): Pair<String, String>? {
        val roots = listOf(
            "/storage", "/mnt/media_rw", "/mnt/usb", "/mnt/udisk", "/mnt/usbdisk",
            "/mnt/usb_storage", "/udisk", "/storage/emulated/0"
        )
        val seen = HashSet<String>()
        for (r in roots) {
            val hit = search(File(r), 0, seen, nameOk, extract)
            if (hit != null) return hit
        }
        return null
    }

    private fun search(
        dir: File, depth: Int, seen: HashSet<String>,
        nameOk: (String) -> Boolean, extract: (File) -> String?
    ): Pair<String, String>? {
        if (depth > 3) return null
        val canon = try { dir.canonicalPath } catch (_: Exception) { dir.path }
        if (!seen.add(canon)) return null
        val list = try { dir.listFiles() } catch (_: Exception) { null } ?: return null
        for (f in list) {
            val n = f.name.lowercase()
            if (f.isFile && n.endsWith(".txt") && nameOk(n)) {
                val v = extract(f)
                if (v != null) return v to f.path
            }
        }
        for (f in list) {
            if (!f.isDirectory) continue
            val n = f.name.lowercase()
            if (n == "self" || n == "android" || n.startsWith(".")) continue
            val hit = search(f, depth + 1, seen, nameOk, extract)
            if (hit != null) return hit
        }
        return null
    }

    private fun extractKey(f: File): String? {
        val text = try {
            if (f.length() > 20000) return null
            f.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        } catch (_: Exception) {
            return null
        }
        Regex("AIza[0-9A-Za-z_\\-]{35}").find(text)?.let { return it.value }
        val line = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val clean = line.replace(Regex("\\s"), "")
        return if (clean.length in 20..200) clean else null
    }

    private fun keyWarning(key: String): String? {
        if (key.isBlank()) return null
        return if (key.length < 30)
            "Atenção: a chave tem só ${key.length} caracteres. Parece incompleta. Use o botão do pen drive para evitar erro de digitação."
        else null
    }

    // ---------------- interface ----------------

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        scroll.addView(outer)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(40))
        }
        outer.addView(col, LinearLayout.LayoutParams(dp(900).coerceAtMost(resources.displayMetrics.widthPixels), ViewGroup.LayoutParams.WRAP_CONTENT))

        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        col.addView(text("Assistente Seal", 30f, textMain, bold = true))
        col.addView(text("Gemini Live · versão $version", 15f, textSoft))

        statusView = text("", 20f, 0xFF7FD1FF.toInt(), bold = true)
        statusView.setPadding(0, dp(12), 0, dp(8))
        col.addView(statusView)

        val talk = button("Falar agora", big = true) {
            save(false)
            if (!hasMic()) {
                requestMic()
            } else {
                AssistantService.send(this, AssistantService.ACTION_TOGGLE)
            }
        }
        col.addView(talk)
        serviceBtn = button("") {
            if (AssistantService.running) {
                // desligado pela pessoa: o vigia não religa até o assistente ser ligado de novo
                if (prefs.startOnBoot) Thread { try { Adb.pauseDaemon(applicationContext) } catch (_: Exception) { } }.start()
                AssistantService.send(this, AssistantService.ACTION_STOP)
            } else {
                AssistantService.send(this, AssistantService.ACTION_START)
            }
            statusView.postDelayed({ refreshAll() }, 600)
        }
        col.addView(serviceBtn)
        col.addView(text("Toque na bolinha para falar. Tocar enquanto ela fala corta a fala; tocar em silêncio encerra. Segure a bolinha para abrir esta tela. Arraste para mudar de lugar.", 15f, textSoft))

        // permissões
        col.addView(section("Permissões"))
        permMic = button("") { requestMic() }
        permOverlay = button("") { requestOverlay() }
        permLoc = button("") {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 2)
        }
        col.addView(permMic)
        col.addView(permOverlay)
        col.addView(permLoc)

        // gemini
        col.addView(section("Gemini"))
        col.addView(label("Chave da API do Gemini (aistudio.google.com/apikey)"))
        keyEdit = edit(prefs.apiKey, "Cole ou digite a chave aqui", password = true)
        col.addView(keyEdit)
        col.addView(button("Mostrar / esconder chave") {
            val hidden = keyEdit.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD == InputType.TYPE_TEXT_VARIATION_PASSWORD
            keyEdit.inputType = InputType.TYPE_CLASS_TEXT or
                if (hidden) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            keyEdit.setSelection(keyEdit.text.length)
        })
        col.addView(button("Ler chave do pen drive (arquivo chave.txt)") { readKeyFromUsb() })
        col.addView(label("Modelo (troque aqui quando sair um Live mais novo)"))
        modelEdit = edit(prefs.model, Prefs.DEFAULT_MODEL)
        col.addView(modelEdit)

        // assistente
        col.addView(section("Assistente"))
        col.addView(label("Voz"))
        voiceSpin = spinner(Prefs.VOICES.map { it.second }, Prefs.VOICES.indexOfFirst { it.first == prefs.voice }.coerceAtLeast(0))
        col.addView(voiceSpin)
        col.addView(label("Nome da assistente"))
        nameEdit = edit(prefs.assistantName, "Assistente")
        col.addView(nameEdit)
        col.addView(label("Como ela te chama"))
        userEdit = edit(prefs.userName, "Seu nome")
        col.addView(userEdit)
        col.addView(label("Estilo"))
        styleSpin = spinner(Prefs.STYLES.map { it.first }, prefs.style)
        col.addView(styleSpin)
        col.addView(label("Instruções extras (opcional)"))
        extraEdit = edit(prefs.extraInstructions, "Ex.: sempre me chame de chefe; prefiro respostas bem curtas", multiline = true)
        col.addView(extraEdit)
        col.addView(button("Ler instruções do pen drive (arquivo instrucoes.txt)") { readInstructionsFromUsb() })
        speakFirstSwitch = switch("Ela começa a conversa (cumprimenta logo depois do bipe)", prefs.speakFirst)
        col.addView(speakFirstSwitch)

        // chamada por voz
        col.addView(section("Chamar por voz"))
        col.addView(text("Fica ouvindo só a palavra de chamada, dentro da central, sem internet. Se o \"Hi BYD\" parar de funcionar, desligue esta opção.", 15f, textSoft))
        wakeSwitch = switch("Chamar por voz", prefs.wakeEnabled)
        col.addView(wakeSwitch)
        col.addView(label("Palavra de chamada"))
        wakeEdit = edit(prefs.wakePhrase, "Ei Laica")
        col.addView(wakeEdit)

        // som
        col.addView(section("Som e microfone"))
        col.addView(label("Por onde ela fala"))
        outputSpin = spinner(Prefs.OUTPUTS, prefs.audioOutput)
        col.addView(outputSpin)
        col.addView(label("Música durante a conversa"))
        musicSpin = spinner(Prefs.MUSIC_MODES, prefs.musicMode)
        col.addView(musicSpin)
        col.addView(label("Fonte do microfone"))
        micSpin = spinner(Prefs.MIC_SOURCES, prefs.micSource)
        col.addView(micSpin)
        col.addView(label("Pausa para pensar no meio da frase"))
        pauseSpin = spinner(Prefs.PAUSES, prefs.pauseLevel)
        col.addView(pauseSpin)
        col.addView(label("Continuar ouvindo depois da resposta"))
        listenSpin = spinner(Prefs.LISTEN_AFTER.map { "$it segundos" }, Prefs.LISTEN_AFTER.indexOf(prefs.listenAfterSec).coerceAtLeast(2))
        col.addView(listenSpin)
        bargeSwitch = switch("Interromper falando por cima (teste: pode fazer ela se ouvir)", prefs.allowBargeIn)
        col.addView(bargeSwitch)

        // carro
        col.addView(section("Carro"))
        col.addView(text("Ela comanda ar-condicionado, vidros e aquecimento/ventilação dos bancos. Portas, travas e carregamento ficam de fora.", 15f, textSoft))
        col.addView(button("Testar acesso ao carro (não mexe em nada)") { testCar() })
        col.addView(button("Liberar controle da música (acesso às notificações)") { grantMusicControl() })

        // atualização
        col.addView(section("Atualização"))
        col.addView(text("Versão instalada: $version. O app baixa a versão nova pela internet do carro e instala sozinho (precisa da depuração sem fio da central ligada). O token só é necessário se você usar uma cópia privada do projeto.", 15f, textSoft))
        col.addView(button("Ler token do pen drive (opcional, arquivo token.txt)") { readTokenFromUsb() })
        col.addView(button("Procurar e instalar atualização", big = true) { checkUpdate() })

        // geral
        col.addView(section("Geral"))
        col.addView(label("Tamanho da bolinha"))
        sizeSpin = spinner(listOf("Pequena", "Média", "Grande", "Enorme"), sizes.indexOf(prefs.bubbleSizeDp).coerceAtLeast(1))
        col.addView(sizeSpin)
        bootSwitch = switch("Ligar junto com o carro", prefs.startOnBoot)
        col.addView(bootSwitch)
        col.addView(button("Salvar ajustes", big = true) { save(true) })

        // registro
        col.addView(section("Registro"))
        col.addView(button("Limpar registro") { EventLog.clear() })
        logView = text("", 13f, textSoft)
        logView.typeface = Typeface.MONOSPACE
        logView.setTextIsSelectable(true)
        logView.setPadding(dp(12), dp(12), dp(12), dp(12))
        logView.background = rounded(card)
        col.addView(logView)

        return scroll
    }

    private fun save(showToast: Boolean) {
        if (!::keyEdit.isInitialized) return
        val oldSize = prefs.bubbleSizeDp
        prefs.apiKey = keyEdit.text.toString()
        prefs.model = modelEdit.text.toString().ifBlank { Prefs.DEFAULT_MODEL }
        prefs.voice = Prefs.VOICES.getOrNull(voiceSpin.selectedItemPosition)?.first ?: "Kore"
        prefs.assistantName = nameEdit.text.toString()
        prefs.userName = userEdit.text.toString()
        prefs.style = styleSpin.selectedItemPosition.coerceAtLeast(0)
        prefs.extraInstructions = extraEdit.text.toString()
        prefs.audioOutput = outputSpin.selectedItemPosition.coerceAtLeast(0)
        prefs.musicMode = musicSpin.selectedItemPosition.coerceAtLeast(0)
        prefs.micSource = micSpin.selectedItemPosition.coerceAtLeast(0)
        prefs.pauseLevel = pauseSpin.selectedItemPosition.coerceAtLeast(0)
        prefs.listenAfterSec = Prefs.LISTEN_AFTER.getOrNull(listenSpin.selectedItemPosition) ?: 12
        prefs.allowBargeIn = bargeSwitch.isChecked
        val oldBoot = prefs.startOnBoot
        prefs.startOnBoot = bootSwitch.isChecked
        if (showToast && (prefs.startOnBoot || oldBoot != prefs.startOnBoot)) {
            applyAutostart(prefs.startOnBoot)
        }
        prefs.speakFirst = speakFirstSwitch.isChecked
        val oldWake = prefs.wakeEnabled to prefs.wakePhrase
        prefs.wakeEnabled = wakeSwitch.isChecked
        prefs.wakePhrase = wakeEdit.text.toString()
        if (oldWake != (prefs.wakeEnabled to prefs.wakePhrase) && AssistantService.running) {
            AssistantService.send(this, AssistantService.ACTION_WAKE)
        }
        prefs.bubbleSizeDp = sizes.getOrNull(sizeSpin.selectedItemPosition) ?: 84
        if (oldSize != prefs.bubbleSizeDp && AssistantService.running) {
            AssistantService.send(this, AssistantService.ACTION_BUBBLE)
        }
        val warn = keyWarning(prefs.apiKey)
        if (warn != null && showToast) {
            EventLog.add(warn)
            Toast.makeText(this, warn, Toast.LENGTH_LONG).show()
        } else if (showToast) {
            Toast.makeText(this, "Ajustes salvos. Valem na próxima conversa.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshAll() {
        statusView.text = if (AssistantService.running) EventLog.status else "Assistente desligado"
        serviceBtn.text = if (AssistantService.running) "Desligar assistente" else "Ligar assistente"
        permMic.text = (if (hasMic()) "✓ " else "") + "Permitir microfone"
        permOverlay.text = (if (Settings.canDrawOverlays(this)) "✓ " else "") + "Permitir bolinha sobre outros apps"
        permLoc.text = (if (hasLoc()) "✓ " else "") + "Permitir localização (clima e 'onde estou')"
        refreshLog()
    }

    private fun refreshLog() {
        if (!::logView.isInitialized) return
        logView.text = EventLog.text().ifBlank { "Nada registrado ainda." }
        statusView.text = if (AssistantService.running) EventLog.status else "Assistente desligado"
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun hasLoc() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestMic() {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Já está liberado", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (e2: Exception) {
                EventLog.add("A central não abriu a tela de 'sobrepor a outros apps'. Dá para liberar por ADB depois.")
                Toast.makeText(this, "Esta central não tem essa tela. Use o botão 'Falar agora' por enquanto.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------- componentes ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(12).toFloat()
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun section(title: String) = text(title, 22f, textMain, bold = true).apply {
        setPadding(0, dp(28), 0, dp(6))
    }

    private fun label(s: String) = text(s, 16f, textSoft).apply { setPadding(0, dp(12), 0, dp(4)) }

    private fun button(s: String, big: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        textSize = if (big) 22f else 17f
        setTextColor(Color.WHITE)
        background = rounded(if (big) accent else card)
        setPadding(dp(16), dp(if (big) 18 else 12), dp(16), dp(if (big) 18 else 12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        }
        setOnClickListener { onClick() }
    }

    private fun edit(value: String, hint: String, password: Boolean = false, multiline: Boolean = false) = EditText(this).apply {
        setText(value)
        this.hint = hint
        textSize = 18f
        setTextColor(Color.WHITE)
        setHintTextColor(0xFF6B7C96.toInt())
        background = rounded(card)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (multiline) minLines = 2
        isSingleLine = !multiline
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun spinner(items: List<String>, selected: Int) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(selected.coerceIn(0, maxOf(items.size - 1, 0)))
        background = rounded(card)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
    }

    private fun switch(s: String, checked: Boolean) = Switch(this).apply {
        text = s
        textSize = 17f
        setTextColor(Color.WHITE)
        isChecked = checked
        setPadding(0, dp(14), 0, dp(6))
    }
}
