package br.com.anjosdoamor.vibe.engine

import br.com.anjosdoamor.vibe.ble.BleBroadcaster
import kotlin.math.roundToInt

/**
 * O aparelho so entende 4 estados: parado, 1, 2 e 3.
 *
 * Esta classe recebe uma intensidade continua (0.0 a 1.0) e escolhe qual
 * nivel mandar a cada tick. No modo suave ela alterna entre dois niveis
 * vizinhos para simular intensidades intermediarias -- e um truque, e
 * pode ficar tremido em alguns motores, por isso da para desligar.
 */
class IntensityDriver(private val broadcaster: BleBroadcaster) {

    /** Alterna entre niveis vizinhos para simular meio-termo. */
    var smoothMode: Boolean = true

    private var tick: Int = 0

    /** Quantos ticks formam um ciclo de alternancia. */
    private val window = 4

    fun apply(intensity: Float) {
        val v = intensity.coerceIn(0f, 1f)

        if (v <= 0.02f) {
            broadcaster.setLevel(0)
            tick = 0
            return
        }

        if (!smoothMode) {
            broadcaster.setLevel(quantize(v))
            return
        }

        // Posicao continua na escala 0..3
        val scaled = v * 3f
        val lower = scaled.toInt().coerceIn(0, 3)
        val upper = (lower + 1).coerceAtMost(3)
        val frac = scaled - lower

        // Quantos ticks da janela ficam no nivel de cima
        val upperTicks = (frac * window).roundToInt()
        val useUpper = (tick % window) < upperTicks

        broadcaster.setLevel(if (useUpper) upper else lower)
        tick++
    }

    private fun quantize(v: Float): Int = when {
        v < 0.20f -> 0
        v < 0.50f -> 1
        v < 0.83f -> 2
        else -> 3
    }

    fun stop() {
        tick = 0
        broadcaster.stopAll()
    }
}
