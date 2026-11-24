package com.spektralab.android.core.model

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** Empty tokens preserve Theia's original timestamp naming. Values never become paths. */
data class ExportNaming(val tokens: List<Token> = emptyList(), val separator: String = "_") {
    enum class Token { ORIGINAL, FILM, PAPER, DATE }

    init {
        require(tokens.size <= 4 && tokens.distinct().size == tokens.size) { "INVALID_EXPORT_NAMING" }
        require(separator in listOf("_", "-", " ")) { "INVALID_EXPORT_SEPARATOR" }
    }

    fun stem(original: String, film: String, paper: String, date: String, legacySuffix: String): String {
        if (tokens.isEmpty()) return safePart("Theia_$legacySuffix")
        val parts = tokens.map { token ->
            safePart(when (token) {
                Token.ORIGINAL -> original.substringBeforeLast('.', original)
                Token.FILM -> film
                Token.PAPER -> paper
                Token.DATE -> date
            })
        }
        return safePart(parts.joinToString(separator))
    }

    fun toJson(): JSONObject = JSONObject().put("version", 1)
        .put("tokens", JSONArray(tokens.map { it.name })).put("separator", separator)

    companion object {
        fun fromJson(json: JSONObject): ExportNaming {
            require(json.getDouble("version") == 1.0) { "UNSUPPORTED_EXPORT_NAMING" }
            val tokens = json.getJSONArray("tokens")
            return ExportNaming((0 until tokens.length()).map { Token.valueOf(tokens.getString(it)) },
                json.getString("separator"))
        }

        fun safePart(value: String): String {
            val clean = Normalizer.normalize(value, Normalizer.Form.NFC)
                .replace(Regex("[\\p{Cc}\\p{Cf}\\\\/:*?\"<>|]"), "_")
                .replace(Regex("\\s+"), " ").trim(' ', '.')
                .ifBlank { "Theia" }
            // Stay below common 255-byte filesystem limits, including suffix and extension.
            val result = StringBuilder()
            var bytes = 0
            val iterator = clean.codePoints().iterator()
            while (iterator.hasNext()) {
                val point = iterator.nextInt()
                val text = String(Character.toChars(point))
                bytes += text.toByteArray(Charsets.UTF_8).size
                if (bytes > 180) break
                result.append(text)
            }
            return result.toString().trimEnd(' ', '.').ifBlank { "Theia" }
        }
    }
}
