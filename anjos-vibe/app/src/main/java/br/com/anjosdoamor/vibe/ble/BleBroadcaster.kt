package br.com.anjosdoamor.vibe.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/**
 * Emite os pacotes de advertising que o vibrador escuta.
 *
 * Trabalha com MODOS (1..9), nao com velocidades. O aparelho tem 9 modos
 * de fabrica e o modo 0 significa parar.
 *
 * O aparelho nao responde nem confirma nada -- e mao unica. Por isso o
 * comando de parar e reenviado algumas vezes.
 */
class BleBroadcaster(private val context: Context) {

    companion object {
        private const val TAG = "AnjosVibe/BLE"
    }

    enum class Status { PRONTO, SEM_BLUETOOTH, BLUETOOTH_DESLIGADO, SEM_PERMISSAO, NAO_SUPORTADO, ERRO }

    private var advertiser: BluetoothLeAdvertiser? = null
    private var currentCallback: AdvertiseCallback? = null

    /** Modo 0..9 que esta no ar agora. -1 = nada sendo transmitido. */
    var currentMode: Int = -1
        private set

    /**
     * De quantos em quantos ms a transmissao e reiniciada, mesmo sem
     * mudanca de modo. 0 desliga o reenvio.
     *
     * POR QUE COMECA DESLIGADO: no scanner, o app oficial aparece com
     * UM endereco estavel, enquanto o nosso aparecia com varios. Cada
     * endereco novo e uma reinicializacao do anuncio, e cada
     * reinicializacao tem um intervalo de silencio -- tempo em que o
     * aparelho nao recebe nada e o motor perde rotacao. Por isso a
     * transmissao continua e estavel e o comportamento correto.
     *
     * O ajuste fica exposto na tela de Ajustes so para teste: se em
     * algum lote o reenvio ajudar, da para ligar sem recompilar.
     */
    var refreshIntervalMs: Long = 0L

    private var lastStartAt: Long = 0

    /**
     * Quanto tempo cada comando fica no ar. 0 = transmissao continua.
     *
     * CAPTURADO em 02/10/2026 (log HCI do S25 Ultra escutando o Love
     * Spouse num S21 FE): cada toque e uma rajada de ~0,9 s -- 6 a 8
     * pacotes, um a cada ~135 ms -- e depois silencio. O vibrador guarda o
     * modo sozinho. Transmitir sem parar era a unica diferenca em relacao
     * ao app oficial, e com ela o modo pulsava em vez de vibrar direto.
     */
    var burstMs: Long = 1000L

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val endBurst = Runnable { encerrarRajada() }

    /** Agenda o fim da transmissao, contando a partir do ultimo comando. */
    private fun agendarFimDaRajada() {
        handler.removeCallbacks(endBurst)
        if (burstMs > 0L) handler.postDelayed(endBurst, burstMs)
    }

    /**
     * Tira a transmissao do ar, mas mantem [currentMode]: o vibrador
     * continua no ultimo modo, e o mesmo comando nao precisa ser reenviado.
     */
    @Synchronized
    private fun encerrarRajada() {
        stopInternal()
        stopSet()
    }

    /**
     * Nunca reiniciar a transmissao mais rapido que isso.
     *
     * O stopAdvertising do Android e assincrono: parar e comecar no mesmo
     * instante faz o comeco falhar. Sem essa trava, uma falha marcava o
     * estado como "nada no ar", o laco tentava de novo 80ms depois, e o
     * ciclo se repetia -- resultado, um endereco novo a cada tentativa e
     * o motor nunca firmando rotacao.
     */
    private val minRestartGapMs = 150L

    var lastError: String? = null
        private set

    // ---- Diagnostico ------------------------------------------------------
    // Contadores mostrados na tela de Ajustes. Com um modo fixo no ar, o
    // numero de religadas nao pode subir: cada religada e um buraco de
    // silencio que o motor sente.

    /** Quantas vezes a transmissao foi (re)iniciada desde que o app abriu. */
    @Volatile var startCount: Int = 0
        private set

    /** Quantas vezes o Android recusou iniciar a transmissao. */
    @Volatile var failCount: Int = 0
        private set

    /** Quantas vezes o Android respondeu "ja estava no ar". */
    @Volatile var alreadyStartedCount: Int = 0
        private set

    /** Codigo da ultima recusa, ou 0 se nunca houve. */
    @Volatile var lastFailCode: Int = 0
        private set

    /** Ha quantos ms a transmissao atual esta no ar sem ser religada. */
    fun onAirMs(): Long =
        if (currentMode < 0 || lastStartAt == 0L) 0L
        else System.currentTimeMillis() - lastStartAt

    fun resetCounters() {
        startCount = 0
        failCount = 0
        alreadyStartedCount = 0
        lastFailCode = 0
    }

    fun status(): Status {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return Status.SEM_BLUETOOTH
        val adapter: BluetoothAdapter = manager.adapter ?: return Status.SEM_BLUETOOTH
        if (!adapter.isEnabled) return Status.BLUETOOTH_DESLIGADO
        if (!hasPermission()) return Status.SEM_PERMISSAO
        if (adapter.bluetoothLeAdvertiser == null) return Status.NAO_SUPORTADO
        return Status.PRONTO
    }

    fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    private fun advertiser(): BluetoothLeAdvertiser? {
        if (advertiser == null) {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            advertiser = manager?.adapter?.bluetoothLeAdvertiser
        }
        return advertiser
    }

    /**
     * Coloca no ar o modo informado.
     *
     * Se o modo ja for o atual, reenvia mesmo assim depois de
     * [refreshIntervalMs] -- ver a explicacao no campo.
     */
    @Synchronized
    fun setMode(mode: Int) {
        val target = mode.coerceIn(0, Protocol.TOTAL_MODOS)
        if (target == currentMode) {
            if (refreshIntervalMs <= 0L) return
            if (System.currentTimeMillis() - lastStartAt < refreshIntervalMs) return
            // Reenvio ligado nos Ajustes: derruba para religar do zero
            stopSet()
        }
        forceMode(target)
    }

    private fun buildData(target: Int): AdvertiseData =
        AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(
                Protocol.companyId(context),
                Protocol.payload(context, target)
            )
            .addServiceUuid(Protocol.serviceUuid(context))
            .build()

    /** Reenvia o comando mesmo que ja seja o modo atual. */
    @Synchronized
    @SuppressLint("MissingPermission")
    fun forceMode(mode: Int) {
        val target = mode.coerceIn(0, Protocol.TOTAL_MODOS)
        val adv = advertiser()
        if (adv == null) {
            lastError = "Este aparelho nao consegue transmitir Bluetooth."
            return
        }
        if (!hasPermission()) {
            lastError = "Permissao de Bluetooth nao concedida."
            return
        }

        val data = try {
            buildData(target)
        } catch (e: Exception) {
            lastError = "Comando invalido nos Ajustes: ${e.message}"
            return
        }

        // Android 8+: troca o pacote com a transmissao no ar, sem religar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            forceModeSet(adv, target, data)
            return
        }

        // Trava contra reinicio em cascata
        val agora = System.currentTimeMillis()
        if (agora - lastStartAt < minRestartGapMs && currentMode == target) return

        stopInternal()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                lastError = null
            }

            override fun onStartFailure(errorCode: Int) {
                // ALREADY_STARTED nao e falha de verdade: alguma coisa ja
                // esta no ar. Zerar o estado aqui era o que criava o ciclo
                // de reinicios que enfraquecia a vibracao.
                if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                    alreadyStartedCount++
                    lastError = null
                    return
                }

                failCount++
                lastFailCode = errorCode

                lastError = when (errorCode) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "Pacote grande demais (max 31 bytes)."
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Sistema ocupado. Desligue e ligue o Bluetooth."
                    ADVERTISE_FAILED_INTERNAL_ERROR -> "Erro interno do Bluetooth."
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Este aparelho nao suporta transmitir."
                    else -> "Falha ao transmitir (codigo $errorCode)."
                }
                Log.w(TAG, lastError ?: "")
                currentMode = -1
            }
        }

        try {
            adv.startAdvertising(settings, data, callback)
            currentCallback = callback
            currentMode = target
            lastStartAt = System.currentTimeMillis()
            startCount++
            agendarFimDaRajada()
        } catch (e: Exception) {
            lastError = e.message
            currentMode = -1
        }
    }

    // ---- Android 8+: uma transmissao so, trocando apenas o pacote ----------
    //
    // Parar e recomecar a transmissao a cada troca de modo deixava um
    // buraco de silencio por troca. Num padrao isso e um tranco a cada
    // degrau. Com o AdvertisingSet a transmissao fica no ar o tempo todo e
    // so os bytes mudam -- mesmo endereco, sem buraco.

    private var advSet: AdvertisingSet? = null
    private var setCallback: AdvertisingSetCallback? = null
    private var setStarting = false

    /** Quantas vezes o pacote foi trocado sem religar a transmissao. */
    @Volatile var dataChangeCount: Int = 0
        private set

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("MissingPermission")
    private fun forceModeSet(adv: BluetoothLeAdvertiser, target: Int, data: AdvertiseData) {
        val set = advSet
        if (set != null) {
            try {
                set.setAdvertisingData(data)
                currentMode = target
                dataChangeCount++
                agendarFimDaRajada()
            } catch (e: Exception) {
                lastError = e.message
                stopSet()
                currentMode = -1
            }
            return
        }

        // Ainda subindo: guarda o modo, o callback aplica quando estiver no ar
        if (setStarting) {
            currentMode = target
            return
        }

        if (System.currentTimeMillis() - lastStartAt < minRestartGapMs) return

        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_MIN)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()

        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(
                advertisingSet: AdvertisingSet?, txPower: Int, status: Int
            ) {
                val eu = this
                synchronized(this@BleBroadcaster) {
                setStarting = false
                if (setCallback !== eu) return@synchronized
                if (status != ADVERTISE_SUCCESS || advertisingSet == null) {
                    failCount++
                    lastFailCode = status
                    lastError = when (status) {
                        ADVERTISE_FAILED_DATA_TOO_LARGE -> "Pacote grande demais (max 31 bytes)."
                        ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Sistema ocupado. Desligue e ligue o Bluetooth."
                        ADVERTISE_FAILED_INTERNAL_ERROR -> "Erro interno do Bluetooth."
                        ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Este aparelho nao suporta transmitir."
                        else -> "Falha ao transmitir (codigo $status)."
                    }
                    Log.w(TAG, lastError ?: "")
                    setCallback = null
                    currentMode = -1
                    return@synchronized
                }
                lastError = null
                advSet = advertisingSet
                // O modo pode ter mudado enquanto a transmissao subia
                if (currentMode != target && currentMode >= 0) {
                    try {
                        advertisingSet.setAdvertisingData(buildData(currentMode))
                        dataChangeCount++
                    } catch (e: Exception) {
                        lastError = e.message
                    }
                }
                }
            }

            override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet?, status: Int) {
                if (status != ADVERTISE_SUCCESS) {
                    failCount++
                    lastFailCode = status
                    lastError = "Falha ao trocar o comando (codigo $status)."
                    Log.w(TAG, lastError ?: "")
                }
            }
        }

        try {
            adv.startAdvertisingSet(params, data, null, null, null, callback)
            setCallback = callback
            setStarting = true
            currentMode = target
            lastStartAt = System.currentTimeMillis()
            startCount++
            agendarFimDaRajada()
        } catch (e: Exception) {
            lastError = e.message
            currentMode = -1
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopSet() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val cb = setCallback ?: return
        try {
            advertiser()?.stopAdvertisingSet(cb)
        } catch (e: Exception) {
            Log.w(TAG, "stopAdvertisingSet: ${e.message}")
        }
        setCallback = null
        advSet = null
        setStarting = false
    }

    fun stopAll() = forceMode(0)

    @SuppressLint("MissingPermission")
    private fun stopInternal() {
        val cb = currentCallback ?: return
        try {
            advertiser()?.stopAdvertising(cb)
        } catch (e: Exception) {
            Log.w(TAG, "stopAdvertising: ${e.message}")
        }
        currentCallback = null
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun shutdown() {
        handler.removeCallbacks(endBurst)
        stopInternal()
        stopSet()
        currentMode = -1
    }
}
