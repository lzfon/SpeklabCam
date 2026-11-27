package com.spektralab.android.core.model

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
     * Empty tokens preserve Theia's original timestamp naming. Values never become paths.
     * [prefix] 是导出页「文件命名」里的自定义前缀：填了才接在文件名最前面，留空 = 不输出前缀。
     */
data class ExportNaming(
    val tokens: List<Token> = emptyList(),
    val separator: String = "_",
    val prefix: String = "",
) {
    enum class Token { ORIGINAL, FILM, PAPER, DATE }

    init {
        require(tokens.size <= 4 && tokens.distinct().size == tokens.size) { "INVALID_EXPORT_NAMING" }
        require(separator in listOf("_", "-", " ")) { "INVALID_EXPORT_SEPARATOR" }
        require(prefix.length <= 40) { "INVALID_EXPORT_PREFIX" }
    }

    fun stem(original: String, film: String, paper: String, date: String, legacySuffix: String): String {
        val body = if (tokens.isEmpty()) safePart("Theia_$legacySuffix") else safePart(
            tokens.map { token ->
                safePart(when (token) {
                    Token.ORIGINAL -> original.substringBeforeLast('.', original)
                    Token.FILM -> film
                    Token.PAPER -> paper
                    Token.DATE -> date
                })
            }.joinToString(separator)
        )
        return withPrefix(body)
    }

    /** 前缀与主体之间用同一个分隔符；前缀先洗掉不能进文件名的字符。 */
    fun withPrefix(body: String): String =
        if (prefix.isBlank()) body else safePart(prefix.trim()) + separator + body

    fun toJson(): JSONObject = JSONObject().put("version", 1)
        .put("tokens", JSONArray(tokens.map { it.name })).put("separator", separator)
        .put("prefix", prefix)

    companion object {
        fun fromJson(json: JSONObject): ExportNaming {
            require(json.getDouble("version") == 1.0) { "UNSUPPORTED_EXPORT_NAMING" }
            val tokens = json.getJSONArray("tokens")
            // 老记录里没有 prefix：按「无前缀」读，不动已有文件名习惯。
            return ExportNaming((0 until tokens.length()).map { Token.valueOf(tokens.getString(it)) },
                json.getString("separator"), json.optString("prefix", ""))
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
