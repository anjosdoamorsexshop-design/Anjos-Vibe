package br.com.anjosdoamor.vibe.ble

import android.content.Context
import android.os.ParcelUuid

/**
 * Protocolo de broadcast dos vibradores tipo Love Spouse.
 *
 * O aparelho nao pareia. Ele fica escutando pacotes de advertising BLE e
 * reage ao conteudo do campo Manufacturer Data. Qualquer app que emita o
 * pacote correto controla o aparelho.
 *
 * Valores confirmados por captura no estoque da Anjos do Amor (nRF Connect):
 *   Company ID    0x00FF
 *   Service UUID  0xAE8F
 *   Prefixo       6DB643CE97FE427C
 *   Sufixo E7075E  -> velocidade 2   (CONFIRMADO no aparelho)
 *
 * Os demais sufixos vem da documentacao publica do protocolo e ainda
 * precisam de confirmacao no aparelho. Se algum nao responder, corrija
 * pela tela de Ajustes do app -- os valores ficam em SharedPreferences e
 * NAO exigem recompilar.
 */
object Protocol {

    private const val PREFS = "anjos_vibe_protocol"

    /** Base UUID de 16 bits do Bluetooth SIG. */
    private const val BASE_UUID = "0000%s-0000-1000-8000-00805F9B34FB"

    // ---- Valores de fabrica ----------------------------------------------

    const val DEFAULT_COMPANY_ID = 0x00FF
    const val DEFAULT_SERVICE_UUID = "AE8F"
    const val DEFAULT_PREFIX = "6DB643CE97FE427C"

    /** Sufixos: 3 bytes finais que carregam o comando. */
    const val DEFAULT_STOP = "E5157D"
    const val DEFAULT_SPEED_1 = "E49C6C"
    const val DEFAULT_SPEED_2 = "E7075E" // confirmado
    const val DEFAULT_SPEED_3 = "E68E4F"

    // ---- Leitura / escrita -----------------------------------------------

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun companyId(ctx: Context): Int =
        prefs(ctx).getInt("company_id", DEFAULT_COMPANY_ID)

    fun serviceUuid(ctx: Context): ParcelUuid {
        val short = prefs(ctx).getString("service_uuid", DEFAULT_SERVICE_UUID)!!
        return ParcelUuid.fromString(BASE_UUID.format(short.uppercase()))
    }

    fun prefix(ctx: Context): String =
        prefs(ctx).getString("prefix", DEFAULT_PREFIX)!!

    fun suffix(ctx: Context, level: Int): String {
        val key = "suffix_$level"
        val fallback = when (level) {
            1 -> DEFAULT_SPEED_1
            2 -> DEFAULT_SPEED_2
            3 -> DEFAULT_SPEED_3
            else -> DEFAULT_STOP
        }
        return prefs(ctx).getString(key, fallback)!!
    }

    /** Monta os 11 bytes completos do comando para um nivel 0..3. */
    fun payload(ctx: Context, level: Int): ByteArray =
        hexToBytes(prefix(ctx) + suffix(ctx, level.coerceIn(0, 3)))

    fun save(
        ctx: Context,
        companyId: Int,
        serviceUuid: String,
        prefix: String,
        stop: String,
        speed1: String,
        speed2: String,
        speed3: String
    ) {
        prefs(ctx).edit()
            .putInt("company_id", companyId)
            .putString("service_uuid", serviceUuid.trim().uppercase())
            .putString("prefix", prefix.trim().uppercase())
            .putString("suffix_0", stop.trim().uppercase())
            .putString("suffix_1", speed1.trim().uppercase())
            .putString("suffix_2", speed2.trim().uppercase())
            .putString("suffix_3", speed3.trim().uppercase())
            .apply()
    }

    fun restoreDefaults(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    // ---- Utilitarios ------------------------------------------------------

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").removePrefix("0x").removePrefix("0X")
        require(clean.length % 2 == 0) { "Hex com numero impar de caracteres: $clean" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }

    /** Valida uma string hex antes de salvar. Retorna null se estiver ok. */
    fun validateHex(value: String, expectedBytes: Int?): String? {
        val clean = value.replace(" ", "").removePrefix("0x").removePrefix("0X")
        if (clean.isEmpty()) return "Campo vazio"
        if (!clean.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) {
            return "So aceita 0-9 e A-F"
        }
        if (clean.length % 2 != 0) return "Precisa de um numero par de caracteres"
        if (expectedBytes != null && clean.length / 2 != expectedBytes) {
            return "Precisa de $expectedBytes bytes (${expectedBytes * 2} caracteres)"
        }
        return null
    }
}
