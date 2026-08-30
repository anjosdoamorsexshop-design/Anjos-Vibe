package br.com.anjosdoamor.vibe

import android.content.Context
import br.com.anjosdoamor.vibe.audio.BeatDetector
import br.com.anjosdoamor.vibe.ble.BleBroadcaster
import br.com.anjosdoamor.vibe.data.PatternStore
import br.com.anjosdoamor.vibe.engine.IntensityDriver
import br.com.anjosdoamor.vibe.engine.Pattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class Mode { MANUAL, PADRAO, MUSICA, DIRETO }

data class VibeState(
    val running: Boolean = false,
    val mode: Mode = Mode.MANUAL,
    val intensity: Float = 0f,
    val currentMode: Int = 0,
    val patternId: String? = null,
    val smoothMode: Boolean = false,
    val directMode: Int = 0,
    val gain: Float = 1.6f,
    val error: String? = null,
    val timerEndsAt: Long? = null
)

/**
 * Coracao do app. E um singleton porque tanto a interface quanto o servico
 * em primeiro plano precisam falar com o mesmo motor.
 *
 * Regra de seguranca: parar sempre funciona, em qualquer modo, sem depender
 * de rede ou de confirmacao do aparelho.
 */
object VibeController {

    private const val TICK_MS = 80L

    private lateinit var appContext: Context
    private var broadcaster: BleBroadcaster? = null
    private var driver: IntensityDriver? = null
    private val beat = BeatDetector()

    private val scope = CoroutineScope(Dispatchers.Default)
    private var loop: Job? = null

    private val _state = MutableStateFlow(VibeState())
    val state: StateFlow<VibeState> = _state.asStateFlow()

    /** Intensidade alvo no modo manual. */
    private var manualIntensity: Float = 0f
    private var activePattern: Pattern? = null
    private var patternStartedAt: Long = 0

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        broadcaster = BleBroadcaster(appContext).also {
            it.refreshIntervalMs = br.com.anjosdoamor.vibe.ble.Protocol.refreshMs(appContext)
            driver = IntensityDriver(it).apply {
                escala = br.com.anjosdoamor.vibe.ble.Protocol.escala(appContext)
            }
        }
    }

    fun bleStatus(): BleBroadcaster.Status =
        broadcaster?.status() ?: BleBroadcaster.Status.SEM_BLUETOOTH

    // ---- Comandos ---------------------------------------------------------

    fun setManualIntensity(value: Float) {
        manualIntensity = value.coerceIn(0f, 1f)
        if (_state.value.mode == Mode.MANUAL) {
            _state.value = _state.value.copy(intensity = manualIntensity)
            if (manualIntensity > 0f && !_state.value.running) start()
        }
    }

    /**
     * Aciona um dos 9 modos do aparelho, direto, sem curva e sem
     * alternancia. Modo 0 para tudo.
     */
    fun setMode(mode: Int) {
        val target = mode.coerceIn(0, br.com.anjosdoamor.vibe.ble.Protocol.TOTAL_MODOS)
        if (target == 0) {
            stop()
            return
        }
        activePattern = null
        _state.value = _state.value.copy(
            mode = Mode.DIRETO,
            directMode = target,
            patternId = null,
            intensity = 1f
        )
        start()
    }

    fun playPattern(pattern: Pattern) {
        activePattern = pattern
        patternStartedAt = System.currentTimeMillis()
        _state.value = _state.value.copy(mode = Mode.PADRAO, patternId = pattern.id)
        start()
    }

    fun startMusic(): Boolean {
        val ok = beat.start()
        if (!ok) {
            _state.value = _state.value.copy(
                error = "Nao consegui acessar o microfone. Verifique a permissao."
            )
            return false
        }
        activePattern = null
        _state.value = _state.value.copy(mode = Mode.MUSICA, patternId = null, error = null)
        start()
        return true
    }

    fun setSmoothMode(enabled: Boolean) {
        driver?.smoothMode = enabled
        _state.value = _state.value.copy(smoothMode = enabled)
    }

    /**
     * Forca. 1.0 mantem a curva original, valores maiores empurram tudo
     * para cima sem perder a forma do padrao.
     */
    /** Recarrega as preferencias depois de mudar nos Ajustes. */
    fun reloadEscala() {
        driver?.escala = br.com.anjosdoamor.vibe.ble.Protocol.escala(appContext)
        broadcaster?.refreshIntervalMs =
            br.com.anjosdoamor.vibe.ble.Protocol.refreshMs(appContext)
    }

    fun setGain(value: Float) {
        val g = value.coerceIn(1f, 2.6f)
        driver?.gain = g
        _state.value = _state.value.copy(gain = g)
    }

    fun setMusicSensitivity(value: Float) {
        // 0 = menos sensivel, 1 = mais sensivel
        beat.threshold = 2.0f - value.coerceIn(0f, 1f) * 0.9f
    }

    /** Timer de desligamento automatico. Passe null para remover. */
    fun setTimer(minutes: Int?) {
        _state.value = _state.value.copy(
            timerEndsAt = minutes?.let { System.currentTimeMillis() + it * 60_000L }
        )
    }

    fun start() {
        if (loop?.isActive == true) {
            _state.value = _state.value.copy(running = true)
            return
        }
        _state.value = _state.value.copy(running = true, error = null)

        loop = scope.launch {
            while (true) {
                val s = _state.value

                s.timerEndsAt?.let {
                    if (System.currentTimeMillis() >= it) {
                        stop()
                        return@launch
                    }
                }

                if (s.mode == Mode.DIRETO) {
                    // Sem driver: nivel travado, pacote puro
                    broadcaster?.setMode(s.directMode)
                    _state.value = _state.value.copy(
                        currentMode = broadcaster?.currentMode ?: 0,
                        error = broadcaster?.lastError
                    )
                    delay(TICK_MS)
                    continue
                }

                val intensity = when (s.mode) {
                    Mode.MANUAL -> manualIntensity
                    Mode.PADRAO -> activePattern
                        ?.valueAt(System.currentTimeMillis() - patternStartedAt) ?: 0f
                    Mode.MUSICA -> beat.intensity
                    Mode.DIRETO -> 0f
                }

                driver?.apply(intensity)

                _state.value = _state.value.copy(
                    intensity = intensity,
                    currentMode = broadcaster?.currentMode ?: 0,
                    error = broadcaster?.lastError
                )

                delay(TICK_MS)
            }
        }
    }

    /**
     * PARADA. Corta tudo imediatamente.
     * Chamada pelo botao de emergencia, pelo timer e ao fechar o app.
     */
    fun stop() {
        loop?.cancel()
        loop = null
        beat.stop()
        manualIntensity = 0f
        activePattern = null
        driver?.stop()
        // Reenvia a parada: o aparelho nao confirma recebimento
        scope.launch {
            repeat(3) {
                delay(120)
                broadcaster?.forceMode(0)
            }
        }
        _state.value = _state.value.copy(
            directMode = 0,
            running = false,
            intensity = 0f,
            currentMode = 0,
            patternId = null,
            timerEndsAt = null
        )
    }

    fun shutdown() {
        stop()
        broadcaster?.shutdown()
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun patterns(): List<Pattern> = PatternStore.all(appContext)
}
