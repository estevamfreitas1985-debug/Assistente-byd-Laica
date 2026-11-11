package com.assistenteseal.app

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lê o microfone e entrega PCM 16 bits, 16 kHz, mono, em pedaços de 50 ms.
 * Na central da BYD o microfone da cabine é um conjunto de 4 canais
 * (2 microfones + 2 canais de referência do som das caixas). Pedir mono ali
 * devolve silêncio, por isso o modo automático abre os 4 canais e mistura os 2 microfones.
 */
class MicRecorder(private val onChunk: (ByteArray, Float) -> Unit) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    @Volatile
    private var running = false

    @Volatile
    private var arrayMode = false

    /** Sessão de áudio da gravação atual (para o diagnóstico). */
    val sessionId: Int get() = try { record?.audioSessionId ?: -1 } catch (_: Exception) { -1 }

    /** 0 automático (4 canais do carro), 1 reconhecimento de voz mono, 2 comunicação mono, 3 microfone padrão mono */
    fun start(sourcePref: Int): Boolean {
        if (running) return true
        val ok = if (sourcePref == 0) {
            openArray() || run {
                EventLog.add("Microfone de 4 canais não abriu; tentando o modo simples")
                openMono(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            }
        } else {
            openMono(
                when (sourcePref) {
                    2 -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    3 -> MediaRecorder.AudioSource.MIC
                    else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
                }
            )
        }
        if (!ok) {
            EventLog.add("Microfone: não consegui abrir (permissão ou outro app usando)")
            return false
        }
        running = true
        thread = Thread({ loop() }, "mic").also { it.start() }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun openArray(): Boolean {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val buf = maxOf(if (min > 0) min * 4 else 0, 20480)
        var rec: AudioRecord? = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, ARRAY_CHANNEL_MASK, AudioFormat.ENCODING_PCM_16BIT, buf)
        } catch (_: Exception) {
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED || rec.channelCount != 4) {
            try { rec?.release() } catch (_: Exception) { }
            rec = try {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelIndexMask(0xF)
                            .build()
                    )
                    .setBufferSizeInBytes(buf)
                    .build()
            } catch (_: Exception) {
                null
            }
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED || rec.channelCount != 4) {
            try { rec?.release() } catch (_: Exception) { }
            return false
        }
        try {
            rec.startRecording()
        } catch (_: Exception) {
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            try { rec.release() } catch (_: Exception) { }
            return false
        }
        record = rec
        arrayMode = true
        EventLog.add("Microfone do carro ligado (4 canais)")
        return true
    }

    @SuppressLint("MissingPermission")
    private fun openMono(source: Int): Boolean {
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val bufSize = maxOf(minBuf * 2, 16000)
        var rec: AudioRecord? = null
        for (src in listOf(source, MediaRecorder.AudioSource.MIC)) {
            rec = try {
                AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            } catch (_: Exception) {
                null
            }
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) break
            try { rec?.release() } catch (_: Exception) { }
            rec = null
        }
        if (rec == null) return false
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true }
            }
        } catch (_: Exception) {
        }
        try {
            rec.startRecording()
        } catch (_: Exception) {
            try { rec.release() } catch (_: Exception) { }
            return false
        }
        record = rec
        arrayMode = false
        EventLog.add("Microfone ligado (modo simples" + (if (aec != null) ", com cancelamento de eco)" else ")"))
        return true
    }

    private fun loop() {
        val mono = ShortArray(800) // 50 ms a 16 kHz
        val multi = ShortArray(800 * 4)
        var silentChunks = 0
        while (running) {
            val r = record ?: break
            var got: Int
            if (arrayMode) {
                // lê 50 ms dos 4 canais intercalados: mic0, mic1, ref0, ref1
                var off = 0
                while (off < multi.size && running) {
                    val n = try { r.read(multi, off, multi.size - off) } catch (_: Exception) { -1 }
                    if (n <= 0) break
                    off += n
                }
                val frames = off / 4
                var allZero = true
                for (i in 0 until frames) {
                    val a = multi[i * 4].toInt()
                    val b = multi[i * 4 + 1].toInt()
                    if (a != 0 || b != 0) allZero = false
                    mono[i] = ((a + b) / 2).toShort()
                }
                got = frames
                if (frames > 0 && allZero) {
                    silentChunks++
                    if (silentChunks == 40) { // 2 s de silêncio digital
                        EventLog.add("Os 4 canais vieram mudos; trocando para o modo simples")
                        try { r.stop() } catch (_: Exception) { }
                        try { r.release() } catch (_: Exception) { }
                        record = null
                        if (!openMono(MediaRecorder.AudioSource.VOICE_RECOGNITION)) {
                            EventLog.add("Modo simples também não abriu")
                            break
                        }
                        continue
                    }
                } else {
                    silentChunks = 0
                }
            } else {
                got = try { r.read(mono, 0, mono.size) } catch (_: Exception) { -1 }
            }
            if (got > 0) {
                val bytes = ByteArray(got * 2)
                for (i in 0 until got) {
                    val v = mono[i].toInt()
                    bytes[i * 2] = (v and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                onChunk(bytes, rms(bytes))
            } else {
                try { Thread.sleep(20) } catch (_: InterruptedException) { }
            }
        }
    }

    fun stop() {
        running = false
        try { thread?.join(400) } catch (_: InterruptedException) { }
        thread = null
        try { record?.stop() } catch (_: Exception) { }
        try { record?.release() } catch (_: Exception) { }
        record = null
        try { aec?.release() } catch (_: Exception) { }
        try { ns?.release() } catch (_: Exception) { }
        aec = null
        ns = null
    }

    companion object {
        /** LEFT | RIGHT | LEFT_PROCESSED | RIGHT_PROCESSED = 4 canais (mesma máscara usada no Carvox) */
        private const val ARRAY_CHANNEL_MASK = 204

        /** Nível de 0 a 1, para animar a bolinha. */
        fun rms(pcm: ByteArray): Float {
            var sum = 0.0
            var i = 0
            var count = 0
            while (i + 1 < pcm.size) {
                val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toDouble()
                sum += s * s
                count++
                i += 2
            }
            if (count == 0) return 0f
            val v = sqrt(sum / count) / 6000.0
            return v.coerceIn(0.0, 1.0).toFloat()
        }
    }
}

/** Toca o PCM 24 kHz que chega do Gemini, com fila e corte imediato (interrupção). */
class AudioPlayer(private val outputPref: Int) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var generation = 0

    @Volatile
    private var playUntil = 0L

    @Volatile
    var level = 0f
        private set

    fun attributes(): AudioAttributes = audioAttributes(outputPref)

    fun start() {
        if (running) return
        val rate = 24000
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val format = AudioFormat.Builder()
            .setSampleRate(rate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val t = AudioTrack(
            attributes(), format, maxOf(min * 2, 9600),
            AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        t.play()
        track = t
        running = true
        thread = Thread({
            val slice = 1920 // 40 ms
            while (running) {
                val chunk = try { queue.poll(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null }
                if (chunk == null) {
                    level = 0f
                    continue
                }
                val gen = generation
                var off = 0
                while (off < chunk.size && running && gen == generation) {
                    val len = minOf(slice, chunk.size - off)
                    level = MicRecorder.rms(chunk.copyOfRange(off, off + len))
                    val n = try { t.write(chunk, off, len) } catch (e: Exception) { -1 }
                    if (n <= 0) break
                    off += n
                }
            }
        }, "player").also { it.start() }
    }

    fun enqueue(pcm: ByteArray) {
        if (!running || pcm.isEmpty()) return
        val durMs = pcm.size / 48L // 24000 amostras/s * 2 bytes = 48 bytes por ms
        val now = SystemClock.elapsedRealtime()
        playUntil = maxOf(playUntil, now) + durMs
        queue.offer(pcm)
    }

    /** true enquanto a assistente estiver falando (com margem para o fim do som sair da caixa). */
    fun isPlaying(): Boolean =
        queue.isNotEmpty() || SystemClock.elapsedRealtime() < playUntil + 500

    /** Corta a fala na hora (interrupção). */
    fun interrupt() {
        generation++
        queue.clear()
        playUntil = 0
        level = 0f
        val t = track ?: return
        try {
            t.pause()
            t.flush()
            t.play()
        } catch (_: Exception) {
        }
    }

    fun beep(freq: Double, ms: Int) {
        val rate = 24000
        val n = rate * ms / 1000
        val out = ByteArray(n * 2)
        val fade = rate / 100 // 10 ms
        for (i in 0 until n) {
            var amp = 0.25
            if (i < fade) amp *= i.toDouble() / fade
            if (i > n - fade) amp *= (n - i).toDouble() / fade
            val s = (sin(2.0 * PI * freq * i / rate) * amp * 32767).toInt()
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        enqueue(out)
    }

    fun stop() {
        running = false
        queue.clear()
        try { thread?.join(400) } catch (_: InterruptedException) { }
        thread = null
        try { track?.stop() } catch (_: Exception) { }
        try { track?.release() } catch (_: Exception) { }
        track = null
        level = 0f
    }

    companion object {
        fun audioAttributes(outputPref: Int): AudioAttributes {
            val b = AudioAttributes.Builder()
            when (outputPref) {
                1 -> b.setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                2 -> b.setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                3 -> b.setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                else -> b.setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            }
            return b.build()
        }
    }
}
