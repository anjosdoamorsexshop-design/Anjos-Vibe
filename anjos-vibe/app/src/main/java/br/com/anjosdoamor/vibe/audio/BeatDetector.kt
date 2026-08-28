package br.com.anjosdoamor.vibe.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Escuta o som ambiente pelo microfone e devolve uma intensidade 0..1 que
 * acompanha a musica.
 *
 * Funciona com qualquer fonte de som -- Spotify, YouTube, som do carro --
 * porque le o ambiente, nao o aplicativo. Nao precisa integrar com nada.
 *
 * A deteccao compara a energia atual com a media recente. Quando a energia
 * salta acima da media, isso conta como batida.
 */
class BeatDetector {

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val WINDOW = 1024
    }

    @Volatile
    private var running = false

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    /** Media movel de energia usada como linha de base. */
    private var baseline = 0.0

    /** Intensidade atual, cai suavemente entre as batidas. */
    @Volatile
    var intensity: Float = 0f
        private set

    /** Sensibilidade: quanto menor, mais facil disparar. 1.1 a 2.0. */
    var threshold: Float = 1.35f

    /** Velocidade da queda entre batidas. 0.85 = cai rapido, 0.97 = segura. */
    var decay: Float = 0.90f

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return false

        val bufferSize = maxOf(minBuffer, WINDOW * 2)

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }

        record = rec
        running = true
        baseline = 0.0
        intensity = 0f

        rec.startRecording()

        thread = Thread {
            val buffer = ShortArray(WINDOW)
            while (running) {
                val read = try {
                    rec.read(buffer, 0, WINDOW)
                } catch (e: Exception) {
                    -1
                }
                if (read <= 0) continue

                // Energia RMS da janela
                var sum = 0.0
                for (i in 0 until read) {
                    val s = buffer[i].toDouble() / Short.MAX_VALUE
                    sum += s * s
                }
                val energy = sqrt(sum / read)

                if (baseline == 0.0) {
                    baseline = energy
                } else {
                    baseline = baseline * 0.97 + energy * 0.03
                }

                val isBeat = baseline > 0.0005 && energy > baseline * threshold

                if (isBeat) {
                    // Forca da batida em relacao a linha de base
                    val strength = ((energy / (baseline * threshold)) - 1.0).coerceIn(0.0, 1.0)
                    val target = (0.5 + strength * 0.5).toFloat()
                    intensity = maxOf(intensity, target)
                } else {
                    intensity *= decay
                    if (intensity < 0.02f) intensity = 0f
                }
            }
        }.also { it.isDaemon = true; it.start() }

        return true
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        try {
            record?.stop()
        } catch (e: Exception) {
            // ignorado: pode nao estar gravando
        }
        record?.release()
        record = null
        intensity = 0f
        baseline = 0.0
    }

    fun isRunning(): Boolean = running
}
