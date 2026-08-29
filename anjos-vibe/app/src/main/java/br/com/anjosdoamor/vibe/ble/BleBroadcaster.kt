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

    /** Coloca no ar o modo informado. Nao faz nada se ja for o modo atual. */
    fun setMode(mode: Int) {
        val target = mode.coerceIn(0, Protocol.TOTAL_MODOS)
        if (target == currentMode) return
        forceMode(target)
    }

    /** Reenvia o comando mesmo que ja seja o modo atual. */
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
                currentMode = -1
            }
        }

        try {
            adv.startAdvertising(settings, data, callback)
            currentCallback = callback
            currentMode = target
        } catch (e: Exception) {
            lastError = e.message
            currentMode = -1
        }
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
    fun shutdown() {
        stopInternal()
        currentMode = -1
    }
}
