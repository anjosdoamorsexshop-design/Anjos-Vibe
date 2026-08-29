package br.com.anjosdoamor.vibe.engine

import br.com.anjosdoamor.vibe.ble.BleBroadcaster
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Traduz uma intensidade continua (0.0 a 1.0) nos modos do aparelho.
 *
 * Os padroes, o desenho e a musica precisam de uma escala. Como o aparelho
 * tem 9 modos e nem todos sao velocidades constantes -- alguns sao padroes
 * proprios de fabrica -- a escala e escolhida pelo cliente na tela de
 * Ajustes: tres modos, do mais fraco ao mais forte.
 *
 * Sobre a alternancia: o motor leva de 100 a 200ms para pegar rotacao.
 * Trocar de modo rapido demais deixa a vibracao fraca. Por isso a janela
 * e de 800ms, e o modo de baixo nunca e "parado" no meio de um padrao.
 */
class IntensityDriver(private val broadcaster: BleBroadcaster) {

    /** Modos usados como fraco / medio / forte. Vem dos Ajustes. */
    var escala: List<Int> = listOf(1, 5, 7)

    /** Alterna entre modos vizinhos para simular meio-termo. */
    var smoothMode: Boolean = false

    /** Forca. 1.0 = curva original. Acima disso empurra tudo para cima. */
    var gain: Float = 1.6f

    private var tick: Int = 0
    private val window = 10

    fun apply(intensity: Float) {
        val raw = intensity.coerceIn(0f, 1f)

        if (raw <= 0.02f) {
            broadcaster.setMode(0)
            tick = 0
            return
        }

        val e = if (escala.size >= 3) escala else listOf(1, 5, 7)
        val v = raw.pow(1f / gain.coerceIn(0.5f, 3f)).coerceIn(0f, 1f)

        if (!smoothMode) {
            broadcaster.setMode(quantize(v, e))
            return
        }

        // Posicao continua entre os 3 degraus da escala
        val scaled = v * 3f
        var lowerIdx = (scaled.toInt() - 1).coerceIn(-1, 2)
        val upperIdx = (lowerIdx + 1).coerceIn(0, 2)
        val frac = scaled - (lowerIdx + 1)

        // Nunca parar o motor no meio de um padrao
        if (lowerIdx < 0) lowerIdx = 0

        if (lowerIdx >= upperIdx) {
            broadcaster.setMode(e[upperIdx])
            return
        }

        val upperTicks = (frac * window).roundToInt()
        val useUpper = (tick % window) < upperTicks

        broadcaster.setMode(if (useUpper) e[upperIdx] else e[lowerIdx])
        tick++
    }

    /**
     * Escolhe o degrau mais proximo. Os limites erram para o forte de
     * proposito -- o cliente sempre pode diminuir.
     */
    private fun quantize(v: Float, e: List<Int>): Int = when {
        v < 0.06f -> 0
        v < 0.30f -> e[0]
        v < 0.62f -> e[1]
        else -> e[2]
    }

    fun stop() {
        tick = 0
        broadcaster.stopAll()
    }
}
