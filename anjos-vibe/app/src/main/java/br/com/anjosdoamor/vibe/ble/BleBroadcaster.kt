package br.com.anjosdoamor.vibe.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Emite os pacotes de advertising que o vibrador escuta.
 *
 * O aparelho nao responde nem confirma nada -- e mao unica. Por isso o
 * comando de parar e reenviado algumas vezes, para o caso de um pacote
 * se perder no ar.
 */
class BleBroadcaster(private val context: Context) {

    companion object {
        private const val TAG = "AnjosVibe/BLE"
    }

    enum class Status { PRONTO, SEM_BLUETOOTH, BLUETOOTH_DESLIGADO, SEM_PERMISSAO, NAO_SUPORTADO, ERRO }

    private var advertiser: BluetoothLeAdvertiser? = null
    private var currentCallback: AdvertiseCallback? = null

    /** Nivel 0..3 que esta no ar agora. -1 = nada sendo transmitido. */
    var currentLevel: Int = -1
        private set

    var lastError: String? = null
        private set

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
     * Coloca no ar o comando do nivel informado (0 = parar, 1..3 = velocidade).
     * Nao faz nada se o nivel ja for o que esta transmitindo.
     */
    @SuppressLint("MissingPermission")
    fun setLevel(level: Int) {
        val target = level.coerceIn(0, 3)
        if (target == currentLevel) return
        forceLevel(target)
    }

    /** Reenvia o comando mesmo que ja seja o nivel atual. */
    @SuppressLint("MissingPermission")
    fun forceLevel(level: Int) {
        val target = level.coerceIn(0, 3)
        val adv = advertiser()
        if (adv == null) {
            lastError = "Este aparelho nao consegue transmitir Bluetooth."
            return
        }
        if (!hasPermission()) {
            lastError = "Permissao de Bluetooth nao concedida."
            return
        }

        stopInternal()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = try {
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(
                    Protocol.companyId(context),
                    Protocol.payload(context, target)
                )
                .addServiceUuid(Protocol.serviceUuid(context))
                .build()
        } catch (e: Exception) {
            lastError = "Comando invalido nos Ajustes: ${e.message}"
            return
        }

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                lastError = null
            }

            override fun onStartFailure(errorCode: Int) {
                lastError = when (errorCode) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "Pacote grande demais (max 31 bytes)."
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Sistema ocupado. Desligue e ligue o Bluetooth."
                    ADVERTISE_FAILED_ALREADY_STARTED -> "Ja estava transmitindo."
                    ADVERTISE_FAILED_INTERNAL_ERROR -> "Erro interno do Bluetooth."
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Este aparelho nao suporta transmitir."
                    else -> "Falha ao transmitir (codigo $errorCode)."
                }
                Log.w(TAG, lastError ?: "")
                currentLevel = -1
            }
        }

        try {
            adv.startAdvertising(settings, data, callback)
            currentCallback = callback
            currentLevel = target
        } catch (e: Exception) {
            lastError = e.message
            currentLevel = -1
        }
    }

    /** Para tudo. Reenvia o comando de parada algumas vezes por seguranca. */
    @SuppressLint("MissingPermission")
    fun stopAll() {
        forceLevel(0)
    }

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

    /** Desliga o radio por completo. Use so ao encerrar o app. */
    @SuppressLint("MissingPermission")
    fun shutdown() {
        stopInternal()
        currentLevel = -1
    }
}
