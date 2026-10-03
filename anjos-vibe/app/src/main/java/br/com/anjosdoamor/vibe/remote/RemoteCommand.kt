package br.com.anjosdoamor.vibe.remote

/**
 * Um comando vindo do parceiro, pela internet, ja validado.
 *
 * As regras do banco tambem validam o formato, mas o app nao confia so
 * nelas: qualquer coisa fora do esperado vira null e e ignorada.
 */
sealed class RemoteCommand {

    /** Um dos 9 modos do aparelho. */
    data class Mode(val mode: Int) : RemoteCommand()

    /** Desenho ao vivo: 0 = dedo fora da tela, 1 fraco, 2 medio, 3 forte. */
    data class Level(val level: Int) : RemoteCommand()

    data class PlayPattern(val id: String) : RemoteCommand()

    object Stop : RemoteCommand()

    companion object {
        private const val MAX_ID = 64

        fun parse(t: String?, v: Any?): RemoteCommand? = when (t) {
            "mode" -> inteiro(v)?.takeIf { it in 1..9 }?.let { Mode(it) }
            "level" -> inteiro(v)?.takeIf { it in 0..3 }?.let { Level(it) }
            "pattern" -> (v as? String)
                ?.takeIf { it.isNotEmpty() && it.length <= MAX_ID }
                ?.let { PlayPattern(it) }
            "stop" -> Stop
            else -> null
        }

        /** O Firebase entrega numeros como Long ou Double. */
        private fun inteiro(v: Any?): Int? = when (v) {
            is Long -> v.toInt()
            is Int -> v
            is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toInt() else null
            else -> null
        }
    }
}

/**
 * Decide se um comando recebido deve ser aplicado.
 *
 * Rejeita comando repetido ou fora de ordem (seq menor ou igual ao
 * ultimo) e o que passar de [maxPerSecond] dentro de um segundo, para
 * ninguem travar o Bluetooth com uma enxurrada.
 */
class RemoteGate(private val maxPerSecond: Int = 20) {

    private var lastSeq = Long.MIN_VALUE
    private val recentes = ArrayDeque<Long>()

    @Synchronized
    fun accept(seq: Long, nowMs: Long): Boolean {
        if (seq <= lastSeq) return false
        // O seq avanca mesmo quando o limite recusa: o comando foi visto
        lastSeq = seq

        while (recentes.isNotEmpty() && nowMs - recentes.first() >= 1000L) {
            recentes.removeFirst()
        }
        if (recentes.size >= maxPerSecond) return false
        recentes.addLast(nowMs)
        return true
    }

    @Synchronized
    fun reset() {
        lastSeq = Long.MIN_VALUE
        recentes.clear()
    }
}
