package com.assistenteseal.app

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Registro simples em memória, mostrado na tela do app para diagnóstico no carro. */
object EventLog {
    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    var status: String = "Parado"
        private set

    fun add(msg: String) {
        Log.i("AssistenteSeal", msg)
        synchronized(lines) {
            lines.addLast(fmt.format(Date()) + "  " + msg)
            while (lines.size > 300) lines.removeFirst()
        }
        notifyListeners()
    }

    fun setStatus(s: String) {
        status = s
        notifyListeners()
    }

    fun text(): String = synchronized(lines) { lines.reversed().joinToString("\n") }

    fun clear() {
        synchronized(lines) { lines.clear() }
        notifyListeners()
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun notifyListeners() {
        for (l in listeners) {
            try { l() } catch (_: Exception) { }
        }
    }
}
