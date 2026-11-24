package com.spektralab.android.data.local

import com.spektralab.android.core.model.EditTemplate

/**
 * 出厂内置配方：现在一个都不内置。
 *
 * 2026-11-22 第五版：按用户要求把「伟大的长征」整套彻底撤掉，不留内置条目。
 * retiredNames 里留着历史用过的名字，启动时会把这些旧条目从「我的配方」里清掉。
 * 以后要恢复内置，只需要往 seeds() 里加一条，并把 VERSION 加一。
 */
internal object BuiltinPresets {
    const val VERSION = 5

    data class Seed(val name: String, val note: String, val template: EditTemplate)

    fun seeds(): List<Seed> = emptyList()

    fun retiredNames(): List<String> = listOf(
        "伟大长征·暗绿",
        "伟大长征·暗绿（500T 灯光片版）",
    )
}
