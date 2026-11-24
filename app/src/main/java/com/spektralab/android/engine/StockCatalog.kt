package com.spektralab.android.engine

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * 一款胶片或相纸。
 *
 * @param id    引擎用的 stock id（如 `kodak_portra_400`），即 wire 字段 film_stock/print_stock 的值
 * @param name  显示名（如 `Kodak Portra 400`），来自 profile 的 `info.name`
 * @param stage `filming`（胶片）或 `printing`（相纸）
 * @param type  `negative`（负片）或 `positive`（反转片/正片）
 */
data class Stock(
    val id: String,
    val name: String,
    val stage: String,
    val type: String,
    val targetPrint: String?,
    val monochrome: Boolean = false,
    val antihalation: String = "",
    val derivedFrom: String? = null,
)

/**
 * 从 assets 里的 profile JSON 读出胶片/相纸目录。
 * 不依赖引擎，可在创建引擎前调用。
 */
object StockCatalog {
    @Volatile
    private var stocks: Map<String, Stock> = emptyMap()
    @Volatile
    private var catalog: Pair<List<Stock>, List<Stock>>? = null
    val isLoaded: Boolean get() = catalog != null
    fun isMonochrome(id: String): Boolean = stocks[id]?.monochrome == true
    fun isPositive(id: String): Boolean = stocks[id]?.type == "positive"
    fun hasNoAntihalation(id: String): Boolean = stocks[id]?.antihalation == "no"
    fun canPrint(film: String, paper: String): Boolean = isMonochrome(film) == isMonochrome(paper)

    private const val DEFAULT_FILM = "kodak_portra_400"

    /**
     * 旧版本存下的材料 id 可能已随功能一起删除（例如已撤掉的「数字中间片」伪相纸），
     * 直接发给引擎会报 no profile。这里把不存在的 id 换回真实材料：
     * 相纸优先取该胶片的目标相纸，其次取同类型（负片/正片）相纸；胶片缺失时退回默认胶片。
     * 返回 null 表示无需修正。
     */
    fun repair(filmId: String, paperId: String): Pair<String, String>? {
        val (films, papers) = catalog ?: return null
        var film = filmId
        var paper = paperId
        var changed = false
        val filmStock = films.firstOrNull { it.id == film } ?: run {
            val fallback = films.firstOrNull { it.id == DEFAULT_FILM } ?: films.firstOrNull() ?: return null
            film = fallback.id
            changed = true
            fallback
        }
        if (papers.none { it.id == paper && it.monochrome == filmStock.monochrome }) {
            val fallback = papers.firstOrNull { it.id == filmStock.targetPrint && it.monochrome == filmStock.monochrome }
                ?: papers.firstOrNull { it.monochrome == filmStock.monochrome }
                ?: return if (changed) film to paper else null
            paper = fallback.id
            changed = true
        }
        return if (changed) film to paper else null
    }

    /** The lettering exposed by the physical emulsion, resolved like Windows. */
    fun edgeText(id: String, format: String = "135"): String {
        val gauge120 = format.startsWith("120")
        val known = mapOf(
            "kodak_gold_200" to ("KODAK GB 200-7" to "KODAK 200"),
            "kodak_portra_800" to ("KODAK PORTRA 800-3" to "KODAK PORTRA 800"),
            "kodak_portra_800_push1" to ("KODAK PORTRA 800-3" to "KODAK PORTRA 800"),
            "kodak_portra_800_push2" to ("KODAK PORTRA 800-3" to "KODAK PORTRA 800"),
            "kodak_ultramax_400" to ("KODAK GC 400" to "KODAK GC 400"),
            "kodak_ektachrome_100" to ("KODAK E100" to "KODAK E100"),
            "fujifilm_c200" to ("FUJI 200" to "FUJI 200"),
            "fujifilm_xtra_400" to ("S-400" to "FUJI S-400"),
            "fujifilm_pro_400h" to ("PRO400H" to "FUJI PRO400H"),
            "fujifilm_provia_100f" to ("FUJI RDPIII" to "FUJI RDPIII"),
            "fujifilm_velvia_100" to ("RVP100" to "FUJI RVP100"),
            "kodak_vision3_50d" to ("EASTMAN 5203" to "EASTMAN 5203"),
            "kodak_vision3_250d" to ("EASTMAN 5207" to "EASTMAN 5207"),
            "kodak_vision3_200t" to ("EASTMAN 5213" to "EASTMAN 5213"),
            "kodak_vision3_500t" to ("EASTMAN 5219" to "EASTMAN 5219"),
            "cinestill_800t" to ("CINESTILL 800T" to "CINESTILL 800T"),
        )[id]
        if (known != null) return if (gauge120) known.second else known.first
        val name = stocks[id]?.name ?: id
        return name.substringBefore('(').trim().uppercase(Locale.US)
    }

    /** Windows' visible film picker order: Positive first, then Negative. */
    fun filmGroups(films: List<Stock>): List<Pair<String, List<Stock>>> = listOf(
        "正片" to films.filter { it.type == "positive" },
        "负片" to films.filter { it.type != "positive" },
    ).filter { it.second.isNotEmpty() }

    /** Each Android process needs its own catalog; parse it once and publish the complete map. */
    @Synchronized
    fun load(context: Context): Pair<List<Stock>, List<Stock>> {
        catalog?.let { return it }
        val films = mutableListOf<Stock>()
        val papers = mutableListOf<Stock>()
        val root = JSONObject(context.assets.open("engine/material-catalog.json").bufferedReader().use { it.readText() })
        require(root.getInt("schema") == 1) { "Unsupported material catalog" }
        val materials = root.getJSONArray("materials")
        for (index in 0 until materials.length()) {
            val info = materials.getJSONObject(index)
            val stock = Stock(
                id = info.getString("id"),
                name = info.getString("name"),
                stage = info.getString("stage"),
                type = info.getString("type"),
                targetPrint = if (info.isNull("target_print")) null else info.optString("target_print"),
                monochrome = info.getBoolean("monochrome"),
                antihalation = info.optString("antihalation"),
                derivedFrom = if (info.isNull("derived_from")) null else info.getString("derived_from"),
            )
            when (stock.stage) {
                "filming" -> films += stock
                "printing" -> papers += stock
            }
        }
        require(films.isNotEmpty() && papers.isNotEmpty()) { "Material catalog is empty" }
        stocks = (films + papers).associateBy { it.id }
        return (films.sortedBy { it.name } to papers.sortedBy { it.name }).also { catalog = it }
    }
}
