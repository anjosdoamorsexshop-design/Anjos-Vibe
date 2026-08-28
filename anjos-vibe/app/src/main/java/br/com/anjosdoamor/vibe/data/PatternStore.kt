package br.com.anjosdoamor.vibe.data

import android.content.Context
import br.com.anjosdoamor.vibe.engine.BuiltInPatterns
import br.com.anjosdoamor.vibe.engine.Pattern
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Guarda os padroes que o cliente desenha. Os padroes de fabrica nao sao
 * salvos -- vem do codigo e nao podem ser apagados.
 */
object PatternStore {

    private const val PREFS = "anjos_vibe_patterns"
    private const val KEY = "saved"

    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saved(ctx: Context): List<Pattern> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<Pattern>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun all(ctx: Context): List<Pattern> = BuiltInPatterns.ALL + saved(ctx)

    fun save(ctx: Context, pattern: Pattern) {
        val current = saved(ctx).filterNot { it.id == pattern.id }
        val updated = current + pattern
        prefs(ctx).edit().putString(KEY, json.encodeToString(updated)).apply()
    }

    fun delete(ctx: Context, id: String) {
        val updated = saved(ctx).filterNot { it.id == id }
        prefs(ctx).edit().putString(KEY, json.encodeToString(updated)).apply()
    }

    fun newId(): String = "user_" + System.currentTimeMillis()
}
