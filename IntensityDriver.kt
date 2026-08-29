package br.com.anjosdoamor.vibe.engine

import br.com.anjosdoamor.vibe.ble.BleBroadcaster
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * O aparelho so entende 4 estados: parado, 1, 2 e 3.
 *
 * IMPORTANTE -- o motor tem inercia. Ele leva de 100 a 200ms para pegar
 * rotacao. Se o app trocar de nivel rapido demais, o motor nunca chega na
 * velocidade e a vibracao sai fraca mesmo com a intensidade alta. Era
 * exatamente o que acontecia nos padroes e no desenho.
 *
 * Correcoes desta versao:
 *   1) A janela de alternancia passou de 4 para 10 ticks (800ms), dando
 *      tempo do motor firmar em cada nivel.
 *   2) Com intensidade acima de zero, o nivel de baixo nunca e 0 -- o
 *      motor nao para por completo no meio de um padrao.
 *   3) Curva de forca ajustavel pelo cliente.
 */
class IntensityDriver(private val broadcaster: BleBroadcaster) {

    /** Alterna entre niveis vizinhos para simular meio-termo. */
    var smoothMode: Boolean = false

    /**
     * Forca. 1.0 = curva original. Acima disso empurra tudo para cima
     * mantendo a forma do padrao. Em 2.0, uma intensidade de 50% vira 71%.
     */
    var gain: Float = 1.6f

    private var tick: Int = 0

    /** Ticks de 80ms. 10 ticks = 800ms por ciclo de alternancia. */
    private val window = 10

    fun apply(intensity: Float) {
        val raw = intensity.coerceIn(0f, 1f)

        if (raw <= 0.02f) {
            broadcaster.setLevel(0)
            tick = 0
            return
        }

        // Curva de forca: preserva o desenho, mas desloca para cima
        val v = raw.pow(1f / gain.coerceIn(0.5f, 3f)).coerceIn(0f, 1f)

        if (!smoothMode) {
            broadcaster.setLevel(quantize(v))
            return
        }

        val scaled = v * 3f
        var lower = scaled.toInt().coerceIn(0, 3)
        val upper = (lower + 1).coerceAtMost(3)
        val frac = scaled - lower

        // Nunca deixar o motor parar no meio de um padrao
        if (lower == 0) lower = 1

        if (lower >= upper) {
            broadcaster.setLevel(upper)
            return
        }

        val upperTicks = (frac * window).roundToInt()
        val useUpper = (tick % window) < upperTicks

        broadcaster.setLevel(if (useUpper) upper else lower)
        tick++
    }

    /**
     * Sem o modo suave, escolhe o nivel mais proximo.
     * Os limites sao deliberadamente baixos: melhor errar para o forte,
     * porque o cliente sempre pode diminuir.
     */
    private fun quantize(v: Float): Int = when {
        v < 0.06f -> 0
        v < 0.30f -> 1
        v < 0.62f -> 2
        else -> 3
    }

    fun stop() {
        tick = 0
        broadcaster.stopAll()
    }
}
