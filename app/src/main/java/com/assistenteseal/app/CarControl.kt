package com.assistenteseal.app

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.math.roundToInt

/**
 * Comandos básicos do carro pelas funções internas da central BYD (android.hardware.bydauto).
 * Só itens de conforto: ar-condicionado e vidros. Nada de portas, travas, carregamento ou direção.
 */
object CarControl {
    private const val AC = "android.hardware.bydauto.ac.BYDAutoAcDevice"
    private const val BODY = "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice"
    private const val SETTING = "android.hardware.bydauto.setting.BYDAutoSettingDevice"

    private var ctx: Context? = null
    private val devices = HashMap<String, Any>()
    private var exempted = false

    @Synchronized
    fun init(base: Context) {
        if (ctx == null) ctx = PermissiveContext(base.applicationContext)
        if (!exempted) {
            exempted = true
            exemptHiddenApi()
        }
    }

    /** Libera o acesso às classes internas da central (funciona no Android 10). */
    private fun exemptHiddenApi() {
        try {
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java
            )
            val vmRuntime = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getDeclaredMethod.invoke(vmRuntime, "getRuntime", null) as Method
            val setExemptions = getDeclaredMethod.invoke(
                vmRuntime, "setHiddenApiExemptions", arrayOf<Class<*>>(Array<String>::class.java)
            ) as Method
            setExemptions.invoke(getRuntime.invoke(null), arrayOf("L"))
        } catch (e: Throwable) {
            EventLog.add("Carro: liberação de acesso interno falhou (${root(e)})")
        }
    }

    @Synchronized
    private fun device(name: String): Any {
        devices[name]?.let { return it }
        val c = ctx ?: throw IllegalStateException("controle do carro não iniciado")
        val cls = Class.forName(name)
        val dev = cls.getMethod("getInstance", Context::class.java).invoke(null, c)
            ?: throw IllegalStateException("${cls.simpleName} indisponível")
        devices[name] = dev
        return dev
    }

    /** Chama um método pelo nome e quantidade de parâmetros, convertendo os números. */
    private fun call(devName: String, method: String, vararg args: Any): Any? {
        val dev = device(devName)
        var lastError: Throwable? = null
        for (m in dev.javaClass.methods) {
            if (m.name != method || m.parameterTypes.size != args.size) continue
            val conv = convert(m.parameterTypes, args) ?: continue
            try {
                return m.invoke(dev, *conv)
            } catch (e: Throwable) {
                lastError = e
            }
        }
        if (lastError != null) throw lastError
        throw NoSuchMethodException("$method(${args.size}) não existe nesta central")
    }

    private fun convert(types: Array<Class<*>>, args: Array<out Any>): Array<Any?>? {
        val out = arrayOfNulls<Any>(args.size)
        for (i in args.indices) {
            val t = types[i]
            val a = args[i]
            out[i] = when {
                (t == Int::class.javaPrimitiveType || t == Integer::class.java) && a is Number -> a.toInt()
                (t == Double::class.javaPrimitiveType || t == java.lang.Double::class.java) && a is Number -> a.toDouble()
                (t == Float::class.javaPrimitiveType || t == java.lang.Float::class.java) && a is Number -> a.toFloat()
                (t == Long::class.javaPrimitiveType || t == java.lang.Long::class.java) && a is Number -> a.toLong()
                (t == Boolean::class.javaPrimitiveType || t == java.lang.Boolean::class.java) && a is Boolean -> a
                else -> return null
            }
        }
        return out
    }

    // ---------------- ar-condicionado ----------------

    fun acPower(on: Boolean): String {
        val name = if (on) "start" else "stop"
        var r = call(AC, name, 0)
        if ((r as? Number)?.toInt() == 3) {
            Thread.sleep(500)
            r = call(AC, name, 0)
        }
        return "ar ${if (on) "ligado" else "desligado"} (retorno $r)"
    }

    fun acTemperature(celsius: Double): String {
        if (celsius.isNaN() || celsius < 17 || celsius > 33) return "temperatura precisa estar entre 17 e 33 graus"
        val unit = (try { call(AC, "getTemperatureUnit") } catch (_: Throwable) { null } as? Number)?.toInt() ?: 1
        val value = if (unit == 0) (celsius * 9.0 / 5.0 + 32.0).roundToInt() else celsius.roundToInt()
        val r = call(AC, "setAcTemperature", 0, value, 0, unit)
        return "temperatura em ${celsius.roundToInt()} graus (retorno $r)"
    }

    fun acFan(level: Int): String {
        if (level < 1 || level > 7) return "ventilação vai de 1 a 7"
        val r = call(AC, "setAcWindLevel", 0, level)
        return "ventilação no nível $level (retorno $r)"
    }

    // ---------------- vidros ----------------

    /**
     * acao: abrir | fechar | parar
     * vidros: lista com motorista, passageiro, traseiro_esquerdo, traseiro_direito (vazia = todos)
     */
    fun windows(action: String, which: Set<String>): String {
        val code = when (action) {
            "abrir" -> 1
            "fechar" -> 2
            "parar" -> 3
            else -> return "ação do vidro precisa ser abrir, fechar ou parar"
        }
        val all = which.isEmpty() || "todos" in which
        val order = listOf("motorista", "passageiro", "traseiro_esquerdo", "traseiro_direito")
        val args = order.map { if (all || it in which) code else 0 }
        val r = call(BODY, "setAllWindowState", args[0], args[1], args[2], args[3])
        val alvo = if (all) "todos os vidros" else which.joinToString(", ")
        return "comando '$action' enviado para $alvo (retorno $r)"
    }

    // ---------------- bancos ----------------

    /**
     * tipo: aquecimento | ventilacao
     * banco: motorista | passageiro | ambos
     * nivel: 0 desliga, 1 baixo, 2 alto
     */
    fun seat(tipo: String, banco: String, nivel: Int): String {
        val method = when (tipo) {
            "aquecimento" -> "setSeatHeatingState"
            "ventilacao" -> "setSeatVentilatingState"
            else -> return "tipo precisa ser aquecimento ou ventilacao"
        }
        val level = nivel.coerceIn(0, 2)
        val seats = when (banco) {
            "passageiro" -> listOf(2)
            "ambos" -> listOf(1, 2)
            else -> listOf(1)
        }
        val results = seats.map { call(SETTING, method, it, level + 1) }
        val nome = if (tipo == "aquecimento") "aquecimento" else "ventilação"
        val alvo = when (banco) { "passageiro" -> "banco do passageiro"; "ambos" -> "os dois bancos"; else -> "banco do motorista" }
        val estado = when (level) { 0 -> "desligado"; 1 -> "no nível baixo"; else -> "no nível alto" }
        return "$nome $estado no $alvo (retorno ${results.joinToString("/")})"
    }

    /** Teste sem mexer em nada: só lê informações. */
    fun probe(): String {
        val sb = StringBuilder()
        for ((label, cls) in listOf("Ar" to AC, "Carroceria" to BODY, "Bancos" to SETTING)) {
            try {
                Class.forName(cls)
                device(cls)
                sb.append("$label: acesso ok. ")
            } catch (e: Throwable) {
                sb.append("$label: sem acesso (${root(e)}). ")
            }
        }
        try {
            sb.append("Unidade de temperatura=").append(call(AC, "getTemperatureUnit")).append(". ")
        } catch (e: Throwable) {
            sb.append("Leitura do ar falhou (${root(e)}). ")
        }
        try {
            val p = (1..4).map { i -> call(BODY, "getWindowOpenPercent", i) }
            sb.append("Vidros abertos (%)=").append(p.joinToString("/")).append(".")
        } catch (e: Throwable) {
            sb.append("Leitura dos vidros falhou (${root(e)}).")
        }
        return sb.toString()
    }

    fun root(e: Throwable): String {
        var t: Throwable = e
        while (true) {
            val next = (t as? InvocationTargetException)?.targetException ?: t.cause ?: break
            if (next === t) break
            t = next
        }
        return "${t.javaClass.simpleName}: ${t.message ?: ""}".trim()
    }

    /** Contexto que responde "permitido" às checagens de permissão feitas dentro do app. */
    private class PermissiveContext(base: Context) : ContextWrapper(base) {
        override fun checkCallingOrSelfPermission(permission: String): Int = PackageManager.PERMISSION_GRANTED
        override fun checkSelfPermission(permission: String): Int = PackageManager.PERMISSION_GRANTED
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int = PackageManager.PERMISSION_GRANTED
        override fun checkCallingPermission(permission: String): Int = PackageManager.PERMISSION_GRANTED
        override fun enforceCallingOrSelfPermission(permission: String, message: String?) {}
        override fun enforceCallingPermission(permission: String, message: String?) {}
        override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {}
        override fun getApplicationContext(): Context = this
    }
}
