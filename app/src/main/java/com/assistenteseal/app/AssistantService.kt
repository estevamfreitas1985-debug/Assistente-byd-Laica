package com.assistenteseal.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class AssistantService : Service(), LiveSession.Listener {

    companion object {
        const val ACTION_START = "com.assistenteseal.app.START"
        const val ACTION_TOGGLE = "com.assistenteseal.app.TOGGLE"
        const val ACTION_STOP = "com.assistenteseal.app.STOP"
        const val ACTION_BUBBLE = "com.assistenteseal.app.BUBBLE"
        const val ACTION_WAKE = "com.assistenteseal.app.WAKE"
        private const val CHANNEL = "assistente"
        private const val NOTIF_ID = 7

        private const val IDLE = 0
        private const val CONNECTING = 1
        private const val ACTIVE = 2

        @Volatile
        var running = false
            private set

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, AssistantService::class.java).setAction(action)
            ctx.startForegroundService(i)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val httpClient = wsClient.newBuilder()
        .readTimeout(8, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    private var wm: WindowManager? = null
    private var bubble: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var session: LiveSession? = null
    private var mic: MicRecorder? = null
    private var player: AudioPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wake: WakeWord? = null

    @Volatile
    private var conv = IDLE

    @Volatile
    private var lastActivity = 0L

    @Volatile
    private var endRequestedAt = 0L

    @Volatile
    private var endTurnSeen = false

    @Volatile
    private var micLevel = 0f
    private var micPeak = 0f
    private var listeningSince = 0L
    private var mutedWarned = false
    private var bargeIn = false
    private var listenAfterMs = 12000L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
        startForeground(NOTIF_ID, notification())
        running = true
        showBubble()
        EventLog.setStatus(readyStatus())
        EventLog.add("Assistente ligado")
        main.postDelayed({ startWakeIfEnabled() }, 1500)
        main.postDelayed(reminderTick, 8000)
        if (prefs.startOnBoot) {
            // repõe acessibilidade, listas da BYD e vigia (se a central tiver tirado algo)
            Thread {
                try {
                    Thread.sleep(6000)
                    EventLog.add("Ligar junto com o carro: " + Adb.ensureKeepAlive(applicationContext))
                } catch (e: Exception) {
                    EventLog.add("Ligar junto com o carro: não consegui conferir (${e.message})")
                }
            }.start()
        }
    }

    // ---------------- memória da conversa enquanto o carro está ligado ----------------

    private var lastHandle: String? = null
    private var lastHandleAt = 0L
    private val memoryMs = 110 * 60 * 1000L // o Gemini guarda a conversa por até 2 horas

    // ---------------- lembretes ----------------

    private val reminderTick = object : Runnable {
        override fun run() {
            if (!running) return
            try { checkReminders() } catch (e: Exception) { EventLog.add("Lembretes: ${e.message}") }
            main.postDelayed(this, 20000)
        }
    }

    private fun checkReminders() {
        val (id, texto, hora) = Notes.due(this) ?: return
        val msg = "[lembrete] $texto (marcado para $hora)"
        when (conv) {
            IDLE -> {
                Notes.markFired(this, id)
                EventLog.add("Lembrete: $texto")
                startConversation(msg)
            }
            ACTIVE -> {
                val s = session ?: return
                Notes.markFired(this, id)
                EventLog.add("Lembrete: $texto")
                s.sendText(msg)
            }
            else -> Unit // conectando: tenta de novo na próxima volta
        }
    }

    private fun readyStatus(): String =
        if (prefs.wakeEnabled) "Pronto — diga \"${prefs.wakePhrase}\" ou toque na bolinha"
        else "Pronto — toque na bolinha para falar"

    // ---------------- palavra de chamada ----------------

    private fun startWakeIfEnabled() {
        if (!running || !prefs.wakeEnabled || conv != IDLE || wake != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val w = WakeWord(this, prefs.wakePhrase) { heard ->
            main.post {
                if (conv != IDLE) return@post
                EventLog.add("Ouvi a chamada (\"$heard\")")
                startConversation()
            }
        }
        wake = w
        w.start(prefs.micSource)
    }

    private fun stopWake() {
        val w = wake ?: return
        wake = null
        w.stop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> toggle()
            ACTION_STOP -> {
                endConversation(false)
                stopSelf()
            }
            ACTION_BUBBLE -> {
                removeBubble()
                showBubble()
            }
            ACTION_WAKE -> {
                stopWake()
                EventLog.setStatus(readyStatus())
                main.postDelayed({ startWakeIfEnabled() }, 500)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false // impede a escuta de voltar depois que o serviço parar
        main.removeCallbacks(reminderTick)
        stopWake()
        endConversation(false)
        abandonFocus()
        removeBubble()
        EventLog.setStatus("Parado")
        EventLog.add("Assistente desligado")
        super.onDestroy()
    }

    // ---------------- conversa ----------------

    private fun toggle() {
        when (conv) {
            IDLE -> startConversation()
            CONNECTING -> endConversation(false)
            else -> {
                val p = player
                if (p != null && p.isPlaying()) {
                    // toque durante a fala: corta a assistente e continua ouvindo
                    p.interrupt()
                    lastActivity = SystemClock.elapsedRealtime()
                    EventLog.add("Fala cortada pelo toque")
                } else {
                    endConversation(false)
                }
            }
        }
    }

    private fun startConversation(opening: String? = null) {
        if (prefs.apiKey.isBlank()) {
            EventLog.add("Falta a chave do Gemini nos Ajustes")
            flashError()
            openApp()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            EventLog.add("Falta permitir o microfone")
            flashError()
            openApp()
            return
        }
        stopWake() // solta o microfone da escuta antes de conversar
        conv = CONNECTING
        bargeIn = prefs.allowBargeIn
        listenAfterMs = prefs.listenAfterSec * 1000L
        endRequestedAt = 0L
        endTurnSeen = false
        micPeak = 0f
        mutedWarned = false
        setBubble(BubbleView.CONNECTING)
        EventLog.setStatus("Conectando…")
        acquireWakeLock()

        player = AudioPlayer(prefs.audioOutput).also { it.start() }
        requestFocus()

        val tools = ToolBox(this, httpClient)
        tools.onEndRequested = {
            endRequestedAt = SystemClock.elapsedRealtime()
            endTurnSeen = false
        }
        lastActivity = SystemClock.elapsedRealtime()
        val resume = lastHandle?.takeIf { SystemClock.elapsedRealtime() - lastHandleAt < memoryMs }
        session = LiveSession(prefs, tools, wsClient, this).also { it.opening = opening; it.connect(resume) }
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 300)
    }

    private fun endConversation(error: Boolean) {
        if (conv == IDLE) return
        conv = IDLE
        main.removeCallbacks(watchdog)
        mic?.stop()
        mic = null
        session?.let { s ->
            val h = s.currentHandle
            if (!error && h != null) {
                lastHandle = h
                lastHandleAt = SystemClock.elapsedRealtime()
            } else if (error) {
                lastHandle = null
            }
            s.close()
        }
        session = null
        val p = player
        player = null
        // devolve o foco de áudio na hora, antes de qualquer outra coisa
        abandonFocus()
        if (p != null) {
            p.interrupt()
            if (error) p.beep(320.0, 260) else p.beep(560.0, 140)
            main.postDelayed({ p.stop() }, 600)
        }
        releaseWakeLock()
        micLevel = 0f
        if (error) {
            setBubble(BubbleView.ERROR)
            main.postDelayed({ if (conv == IDLE) setBubble(BubbleView.IDLE) }, 3000)
        } else {
            setBubble(BubbleView.IDLE)
        }
        EventLog.setStatus(readyStatus())
        EventLog.add(if (error) "Conversa encerrada com erro" else "Conversa encerrada")
        if (running) main.postDelayed({ startWakeIfEnabled() }, 1200)
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (conv == IDLE) return
            val now = SystemClock.elapsedRealtime()
            val p = player
            val playing = p?.isPlaying() == true

            if (conv == CONNECTING) {
                if (now - lastActivity > 15000) {
                    EventLog.add("Demorou demais para conectar. Confira a internet do carro.")
                    endConversation(true)
                    return
                }
            } else {
                setBubble(if (playing) BubbleView.SPEAKING else BubbleView.LISTENING)
                bubble?.level = if (playing) (p?.level ?: 0f) else micLevel
                if (playing) lastActivity = now

                if (!mutedWarned && listeningSince > 0 && now - listeningSince > 4000 && micPeak < 0.0015f) {
                    mutedWarned = true
                    EventLog.add("Aviso: o microfone chega mudo (nível zero).")
                    logMicDiagnostics()
                }

                if (endRequestedAt > 0 && !playing) {
                    val since = now - endRequestedAt
                    if ((endTurnSeen && now - lastActivity > 400) || since > 9000) {
                        endConversation(false)
                        return
                    }
                }
                if (!playing && now - lastActivity > listenAfterMs) {
                    EventLog.add("Ninguém falou por ${listenAfterMs / 1000} s")
                    endConversation(false)
                    return
                }
            }
            main.postDelayed(this, 250)
        }
    }

    private fun onMic(chunk: ByteArray, level: Float) {
        if (level > micPeak) micPeak = level
        val p = player ?: return
        val s = session ?: return
        if (!bargeIn && p.isPlaying()) {
            micLevel = 0f
            return // não manda a própria voz da assistente de volta
        }
        micLevel = level
        s.sendAudio(chunk)
    }

    // ------------- eventos da sessão (chegam em outra thread) -------------

    override fun onReady() {
        main.post {
            if (conv == IDLE) return@post
            lastActivity = SystemClock.elapsedRealtime()
            if (conv == CONNECTING) {
                conv = ACTIVE
                player?.beep(880.0, 120)
                val m = MicRecorder { chunk, lvl -> onMic(chunk, lvl) }
                if (!m.start(prefs.micSource)) {
                    endConversation(true)
                    return@post
                }
                mic = m
                listeningSince = SystemClock.elapsedRealtime()
                setBubble(BubbleView.LISTENING)
                EventLog.setStatus("Ouvindo…")
                EventLog.add("Conectado. Pode falar.")
            }
        }
    }

    override fun onAudio(pcm: ByteArray) {
        lastActivity = SystemClock.elapsedRealtime()
        player?.enqueue(pcm)
    }

    override fun onInterrupted() {
        lastActivity = SystemClock.elapsedRealtime()
        player?.interrupt()
    }

    override fun onTurnComplete() {
        lastActivity = SystemClock.elapsedRealtime()
        if (endRequestedAt > 0) endTurnSeen = true
    }

    override fun onUserActivity() {
        lastActivity = SystemClock.elapsedRealtime()
    }

    override fun onToolCall(name: String) {
        lastActivity = SystemClock.elapsedRealtime()
    }

    override fun onClosed(reason: String, error: Boolean) {
        main.post {
            if (conv == IDLE) return@post
            EventLog.add("Conexão encerrada: ${friendly(reason)}")
            endConversation(error)
        }
    }

    private fun friendly(reason: String): String {
        val l = reason.lowercase()
        val hint = when {
            "api key" in l || "api_key" in l || "permission" in l || "unauthenticated" in l -> "chave do Gemini inválida ou sem permissão"
            "quota" in l || "resource_exhausted" in l || "429" in l -> "limite de uso da chave atingido"
            "not found" in l || "not supported" in l -> "modelo não encontrado — confira o nome do modelo nos Ajustes"
            "unable to resolve host" in l || "timeout" in l || "failed to connect" in l || "network" in l -> "sem internet"
            else -> null
        }
        return if (hint != null) "$hint ($reason)" else reason
    }

    /** Mostra no registro quem mais está usando o microfone e se o Android silenciou este app. */
    private fun logMicDiagnostics() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val mine = mic?.sessionId ?: -1
            val cfgs = am.activeRecordingConfigurations
            val sb = StringBuilder()
            sb.append("Diagnóstico: Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")")
                .append(", microfone geral mudo=").append(am.isMicrophoneMute)
                .append(", modo de áudio=").append(am.mode)
                .append(", gravações ativas=").append(cfgs.size)
            var others = 0
            var silencedMe = false
            for (c in cfgs) {
                val isMe = c.clientAudioSessionId == mine
                if (!isMe) others++
                sb.append(" | ").append(if (isMe) "ESTE APP" else "outro app")
                    .append(" fonte=").append(sourceName(c.clientAudioSource))
                    .append(" canais=").append(c.clientFormat.channelCount)
                if (Build.VERSION.SDK_INT >= 29) {
                    sb.append(" silenciado=").append(if (c.isClientSilenced) "sim" else "não")
                    if (isMe && c.isClientSilenced) silencedMe = true
                }
                c.audioDevice?.let { d -> sb.append(" entrada=").append(d.productName).append("/").append(d.type) }
            }
            EventLog.add(sb.toString())
            when {
                silencedMe -> EventLog.add("O Android está SILENCIANDO este app porque outro app tem prioridade no microfone. Feche o outro app (Carvox, CarPlay/Siri ou assistente da BYD) e tente de novo.")
                others > 0 -> EventLog.add("Tem outro app usando o microfone ao mesmo tempo ($others). Feche-o e tente de novo.")
                am.isMicrophoneMute -> EventLog.add("O microfone geral da central está no mudo.")
            }
        } catch (e: Exception) {
            EventLog.add("Diagnóstico do microfone falhou: ${e.message}")
        }
    }

    private fun sourceName(src: Int): String = when (src) {
        0 -> "padrão"
        1 -> "microfone"
        5 -> "câmera"
        6 -> "reconhecimento de voz"
        7 -> "chamada/comunicação"
        9 -> "sem processamento"
        1999 -> "palavra de ativação (hotword)"
        else -> src.toString()
    }

    // ---------------- áudio ----------------

    private fun requestFocus() {
        abandonFocus() // nunca acumula pedidos de foco
        if (prefs.musicMode == 0) return // padrão: não mexe no áudio do carro
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val gain = if (prefs.musicMode == 2) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        val req = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(AudioPlayer.audioAttributes(prefs.audioOutput))
            .setOnAudioFocusChangeListener { }
            .build()
        try {
            am.requestAudioFocus(req)
            focusRequest = req
        } catch (_: Exception) {
        }
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try { am.abandonAudioFocusRequest(req) } catch (_: Exception) { }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "assistenteseal:conversa").also {
            it.setReferenceCounted(false)
            it.acquire(30 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
    }

    // ---------------- bolinha ----------------

    private fun showBubble() {
        if (bubble != null) return
        if (!Settings.canDrawOverlays(this)) {
            EventLog.add("Bolinha desligada: falta liberar 'sobrepor a outros apps'")
            return
        }
        val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val size = (prefs.bubbleSizeDp * density).toInt()
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        val dm = resources.displayMetrics
        lp.x = if (prefs.bubbleX >= 0) prefs.bubbleX.coerceAtMost(maxOf(dm.widthPixels - size, 0))
        else maxOf(dm.widthPixels - size - (24 * density).toInt(), 0)
        lp.y = if (prefs.bubbleY >= 0) prefs.bubbleY.coerceAtMost(maxOf(dm.heightPixels - size, 0))
        else dm.heightPixels / 3
        val v = BubbleView(this)
        v.setOnTouchListener(BubbleTouch(lp))
        try {
            w.addView(v, lp)
            wm = w
            bubble = v
            bubbleParams = lp
        } catch (e: Exception) {
            EventLog.add("Não consegui mostrar a bolinha: ${e.message}")
        }
    }

    private fun removeBubble() {
        val v = bubble ?: return
        try { wm?.removeView(v) } catch (_: Exception) { }
        bubble = null
    }

    private fun setBubble(state: Int) {
        val b = bubble ?: return
        if (b.state != state) b.state = state
    }

    private fun flashError() {
        setBubble(BubbleView.ERROR)
        main.postDelayed({ if (conv == IDLE) setBubble(BubbleView.IDLE) }, 2500)
    }

    private inner class BubbleTouch(private val lp: WindowManager.LayoutParams) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private var longPressed = false
        private val slop = ViewConfiguration.get(this@AssistantService).scaledTouchSlop
        private val longPress = Runnable {
            if (!moved) {
                longPressed = true
                openApp()
            }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    longPressed = false
                    main.postDelayed(longPress, 800)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        main.removeCallbacks(longPress)
                    }
                    if (moved) {
                        lp.x = maxOf(0, startX + dx.toInt())
                        lp.y = maxOf(0, startY + dy.toInt())
                        try { wm?.updateViewLayout(v, lp) } catch (_: Exception) { }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (moved) {
                        prefs.bubbleX = lp.x
                        prefs.bubbleY = lp.y
                    } else if (!longPressed) {
                        v.performClick()
                        toggle()
                    }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            return true
        }
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (_: Exception) { }
    }

    // ---------------- notificação ----------------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Assistente de voz", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val talk = PendingIntent.getForegroundService(
            this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_TOGGLE), flags
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Assistente Seal")
            .setContentText("Toque na bolinha para falar")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "Falar", talk).build())
            .build()
    }
}
