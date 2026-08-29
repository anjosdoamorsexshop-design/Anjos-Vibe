package br.com.anjosdoamor.vibe.engine

import kotlinx.serialization.Serializable

/**
 * Um ponto da curva do padrao.
 * @param t posicao no tempo, 0.0 = inicio, 1.0 = fim do ciclo
 * @param v intensidade, 0.0 = parado, 1.0 = maximo
 */
@Serializable
data class Point(val t: Float, val v: Float)

/**
 * Um padrao de vibracao. A curva e percorrida em [durationMs] e repete.
 */
@Serializable
data class Pattern(
    val id: String,
    val name: String,
    val durationMs: Int,
    val points: List<Point>,
    val builtIn: Boolean = false
) {
    /** Intensidade 0.0..1.0 no instante informado do ciclo. */
    fun valueAt(elapsedMs: Long): Float {
        if (points.isEmpty()) return 0f
        if (points.size == 1) return points[0].v

        val t = ((elapsedMs % durationMs).toFloat() / durationMs).coerceIn(0f, 1f)

        val sorted = points.sortedBy { it.t }
        if (t <= sorted.first().t) return sorted.first().v
        if (t >= sorted.last().t) return sorted.last().v

        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (t in a.t..b.t) {
                val span = (b.t - a.t)
                if (span <= 0f) return b.v
                val f = (t - a.t) / span
                return a.v + (b.v - a.v) * f
            }
        }
        return sorted.last().v
    }
}

object BuiltInPatterns {

    private fun ramp(from: Float, to: Float, steps: Int, startT: Float, endT: Float): List<Point> {
        val out = mutableListOf<Point>()
        for (i in 0..steps) {
            val f = i.toFloat() / steps
            out.add(Point(startT + (endT - startT) * f, from + (to - from) * f))
        }
        return out
    }

    val ONDA = Pattern(
        id = "onda",
        name = "Onda",
        durationMs = 4000,
        builtIn = true,
        points = ramp(0.45f, 1f, 8, 0f, 0.5f) + ramp(1f, 0.45f, 8, 0.5f, 1f)
    )

    val PULSO = Pattern(
        id = "pulso",
        name = "Pulso",
        durationMs = 1600,
        builtIn = true,
        points = listOf(
            Point(0f, 1f), Point(0.22f, 1f), Point(0.25f, 0f),
            Point(0.48f, 0f), Point(0.5f, 1f), Point(0.72f, 1f),
            Point(0.75f, 0f), Point(1f, 0f)
        )
    )

    val ESCALADA = Pattern(
        id = "escalada",
        name = "Escalada",
        durationMs = 12000,
        builtIn = true,
        points = ramp(0.4f, 1f, 20, 0f, 0.92f) + listOf(Point(0.95f, 0f), Point(1f, 0f))
    )

    val BATIDA = Pattern(
        id = "batida",
        name = "Batida",
        durationMs = 2000,
        builtIn = true,
        points = listOf(
            Point(0f, 1f), Point(0.08f, 1f), Point(0.11f, 0f),
            Point(0.24f, 0f), Point(0.26f, 1f), Point(0.34f, 1f),
            Point(0.37f, 0f), Point(0.62f, 0f), Point(0.64f, 1f),
            Point(0.72f, 1f), Point(0.75f, 0f), Point(1f, 0f)
        )
    )

    val PROVOCACAO = Pattern(
        id = "provocacao",
        name = "Provocacao",
        durationMs = 9000,
        builtIn = true,
        points = listOf(
            Point(0f, 0f), Point(0.05f, 0.7f), Point(0.18f, 0.7f),
            Point(0.2f, 0f), Point(0.38f, 0f), Point(0.4f, 1f),
            Point(0.52f, 1f), Point(0.54f, 0f), Point(0.72f, 0f),
            Point(0.74f, 0.55f), Point(0.86f, 1f), Point(0.95f, 1f),
            Point(0.97f, 0f), Point(1f, 0f)
        )
    )

    val ALL = listOf(ONDA, PULSO, BATIDA, ESCALADA, PROVOCACAO)
}
