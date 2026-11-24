package com.spektralab.android.data.local

import com.spektralab.android.core.model.EditTemplate

/**
 * 出厂内置配方：不内置任何配方。
 *
 * 应用包里永远不带预设的色彩配方，用户的「我的配方」只装他自己存或他自己导入的东西。
 * 官方配方以文件形式放在仓库 presets/ 目录，由用户自行下载导入。
 * 以后如果要恢复内置，往 seeds() 里加一条并把 VERSION 加一即可。
 */
internal object BuiltinPresets {
    const val VERSION = 5

    data class Seed(val name: String, val note: String, val template: EditTemplate)

    fun seeds(): List<Seed> = emptyList()

    /** 历史内置条目的清理名单：已全部清理完毕，现为空。 */
    fun retiredNames(): List<String> = emptyList()
}
