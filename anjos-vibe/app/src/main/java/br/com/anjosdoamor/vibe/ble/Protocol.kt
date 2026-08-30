package br.com.anjosdoamor.vibe.ble

import android.content.Context
import android.os.ParcelUuid

/**
 * Protocolo dos vibradores da Anjos do Amor.
 *
 * O aparelho nao pareia: fica escutando pacotes de advertising BLE e reage
 * ao Manufacturer Data. Qualquer app que emita o pacote certo controla ele.
 *
 * TABELA CAPTURADA do estoque em 28/08/2026, com o nRF Connect, comparando
 * com o app oficial:
 *
 *   Company ID    0x00FF
 *   Service UUID  0xAE8F
 *   Prefixo       6DB643CE97FE427C
 *
 *   Parar         E5157D
 *   9 modos       E0B82A E1313B E2AA09 E32318 E49C6C
 *                 E68E4F E7075E ECD4E0 ED5DF1
 *
 * O aparelho NAO tem 3 velocidades -- tem 9 modos de fabrica. A versao
 * anterior do app usava so 4 bytes, dois deles fracos, e por isso tudo
 * saia fraco. Agora os 9 estao disponiveis.
 *
 * Tudo aqui e editavel pela tela de Ajustes: se a fabrica trocar o firmware
 * de um lote, basta capturar o pacote novo e digitar, sem recompilar.
 */
object Protocol {

    private const val PREFS = "anjos_vibe_protocol"
    private const val BASE_UUID = "0000%s-0000-1000-8000-00805F9B34FB"

    const val TOTAL_MODOS = 9

    // ---- Valores de fabrica ----------------------------------------------

    const val DEFAULT_COMPANY_ID = 0x00FF
    const val DEFAULT_SERVICE_UUID = "AE8F"
    const val DEFAULT_PREFIX = "6DB643CE97FE427C"
    const val DEFAULT_STOP = "E5157D"

    /** Os 9 modos, na ordem em que aparecem no app oficial. */
    val DEFAULT_MODOS = listOf(
        "E0B82A", "E1313B", "E2AA09", "E32318", "E49C6C",
        "E68E4F", "E7075E", "ECD4E0", "ED5DF1"
    )

    /**
     * Quais modos os padroes, o desenho e a musica usam como
     * "fraco / medio / forte".
     *
     * CONFIRMADO no aparelho em 29/08/2026: os modos 1, 2 e 3 sao as
     * velocidades constantes, em ordem crescente de forca. Do 4 ao 9 sao
     * padroes proprios de fabrica -- ja pulsam sozinhos, e usa-los como
     * degrau de intensidade estraga a curva dos padroes e do desenho.
     *
     * Indices baseados em zero: 0, 1, 2 = modos 1, 2 e 3.
     */
    val DEFAULT_ESCALA = listOf(0, 1, 2)

    // ---- Leitura ---------------------------------------------------------

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

    fun stopSuffix(ctx: Context): String =
        prefs(ctx).getString("stop", DEFAULT_STOP)!!

    /** Sufixo do modo 1..9. */
    fun modoSuffix(ctx: Context, modo: Int): String {
        val i = (modo - 1).coerceIn(0, TOTAL_MODOS - 1)
        return prefs(ctx).getString("modo_$i", DEFAULT_MODOS[i])!!
    }

    /** Nome que o cliente deu ao modo, se deu. */
    /** Nomes de fabrica dos modos ja identificados no aparelho. */
    private val NOMES_PADRAO = mapOf(
        1 to "Continuo fraco",
        2 to "Continuo medio",
        3 to "Continuo forte"
    )

    fun modoNome(ctx: Context, modo: Int): String {
        val i = (modo - 1).coerceIn(0, TOTAL_MODOS - 1)
        val padrao = NOMES_PADRAO[modo] ?: "Modo $modo"
        return prefs(ctx).getString("nome_$i", padrao)!!
    }

    fun setModoNome(ctx: Context, modo: Int, nome: String) {
        val i = (modo - 1).coerceIn(0, TOTAL_MODOS - 1)
        prefs(ctx).edit().putString("nome_$i", nome.ifBlank { "Modo $modo" }).apply()
    }

    /**
     * Os 3 modos usados como escala de intensidade pelos padroes, pelo
     * desenho e pela musica. Devolve numeros de modo 1..9.
     */
    fun escala(ctx: Context): List<Int> {
        val raw = prefs(ctx).getString("escala", null)
            ?: return DEFAULT_ESCALA.map { it + 1 }
        return try {
            raw.split(",").map { it.trim().toInt().coerceIn(1, TOTAL_MODOS) }
                .take(3).ifEmpty { DEFAULT_ESCALA.map { it + 1 } }
        } catch (e: Exception) {
            DEFAULT_ESCALA.map { it + 1 }
        }
    }

    fun setEscala(ctx: Context, modos: List<Int>) {
        prefs(ctx).edit()
            .putString("escala", modos.joinToString(",") { it.coerceIn(1, TOTAL_MODOS).toString() })
            .apply()
    }

    /** Pacote de 11 bytes do modo 1..9. Modo 0 = parar. */
    fun payload(ctx: Context, modo: Int): ByteArray {
        val suffix = if (modo <= 0) stopSuffix(ctx) else modoSuffix(ctx, modo)
        return hexToBytes(prefix(ctx) + suffix)
    }

    // ---- Escrita ---------------------------------------------------------

    fun saveBase(ctx: Context, companyId: Int, serviceUuid: String, prefix: String, stop: String) {
        prefs(ctx).edit()
            .putInt("company_id", companyId)
            .putString("service_uuid", serviceUuid.trim().uppercase())
            .putString("prefix", prefix.trim().uppercase())
            .putString("stop", stop.trim().uppercase())
            .apply()
    }

    fun saveModo(ctx: Context, modo: Int, suffix: String) {
        val i = (modo - 1).coerceIn(0, TOTAL_MODOS - 1)
        prefs(ctx).edit().putString("modo_$i", suffix.trim().uppercase()).apply()
    }

    /** Intervalo de reenvio da transmissao, em ms. 0 desliga. */
    fun refreshMs(ctx: Context): Long =
        prefs(ctx).getLong("refresh_ms", 0L)

    fun setRefreshMs(ctx: Context, ms: Long) {
        prefs(ctx).edit().putLong("refresh_ms", ms.coerceIn(0L, 2000L)).apply()
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
