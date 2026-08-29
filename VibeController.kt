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
    val currentLevel: Int = 0,
    val patternId: String? = null,
    val smoothMode: Boolean = false,
    val directLevel: Int = 0,
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
            driver = IntensityDriver(it)
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
     * Botao direto de nivel: 0, 1, 2 ou 3.
     *
     * Manda o nivel exato, sem curva de forca e sem alternancia. Apertar
     * "3" tem que produzir exatamente o mesmo pacote que o app oficial
     * manda na velocidade 3 -- nada no meio do caminho.
     */
    fun setLevel(level: Int) {
        val target = level.coerceIn(0, 3)
        if (target == 0) {
            stop()
            return
        }
        activePattern = null
        _state.value = _state.value.copy(
            mode = Mode.DIRETO,
            directLevel = target,
            patternId = null,
            intensity = target / 3f
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
                    broadcaster?.setLevel(s.directLevel)
                    _state.value = _state.value.copy(
                        currentLevel = broadcaster?.currentLevel ?: 0,
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
                    currentLevel = broadcaster?.currentLevel ?: 0,
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
                broadcaster?.forceLevel(0)
            }
        }
        _state.value = _state.value.copy(
            directLevel = 0,
            running = false,
            intensity = 0f,
            currentLevel = 0,
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
