package com.spektralab.android

import android.app.Instrumentation
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.engine.EngineClient
import com.spektralab.android.engine.SpkException

/** Fault-state injection exercises cleanup/recreation, not a claim of real driver-reset reproduction. */
internal class WindowsExportRecoveryAudit(private val test: Instrumentation) {
    fun run(): String {
        val recorder = EngineClient::class.java.methods.firstOrNull {
            it.name.startsWith("recordFailure") && it.parameterTypes.contentEquals(arrayOf(Throwable::class.java))
        } ?: error("Missing device-loss latch/recovery API")
        val lost = EngineClient::class.java.methods.first { it.name.startsWith("isDeviceLost") && it.parameterCount == 0 }
        val generation = EngineClient::class.java.methods.first { it.name.startsWith("getDeviceGeneration") && it.parameterCount == 0 }
        val width = 96; val height = 64
        val pixels = FloatArray(width * height * 3) { .02f + (it % 31) * .01f }
        val params = EditorParams().apply { grainActive = false; glareActive = false }
            .openJson(width, height)
        EngineClient.create(test.targetContext).use { engine ->
            engine.open(pixels, width, height, 3, params)
            val expected = engine.render("full").rgba16
            val firstGeneration = generation.invoke(engine)
            for (message in listOf("render cancelled", "Vulkan allocation failed (VkResult -2)", "VkResult -40")) {
                recorder.invoke(engine, SpkException(message))
                check(lost.invoke(engine) == false) { "Non-device-loss error invalidated a healthy renderer: $message" }
            }
            check(engine.render("full").rgba16.contentEquals(expected))
            for (message in listOf("Vulkan compute dispatch failed (VkResult-4)",
                "Vulkan compute dispatch failed (VkResult -4)", "VK_ERROR_DEVICE_LOST")) {
                recorder.invoke(engine, SpkException(message))
                check(lost.invoke(engine) == true)
                check(runCatching { engine.render("full") }.exceptionOrNull() is SpkException) {
                    "Lost renderer accepted another render instead of failing closed"
                }
                engine.cancel() // cancellation must not access an invalid device/session
                check(engine.memoryReport().contains("device_lost"))
                engine.open(pixels, width, height, 3, params)
                check(lost.invoke(engine) == false)
                check(engine.render("full").rgba16.contentEquals(expected)) { "Recreated renderer changed pixels" }
            }
            check((generation.invoke(engine) as Number).toLong() == (firstGeneration as Number).toLong() + 3)
        }
        return "Injected three device-loss spellings: failed handles rejected, cancel safe, explicit reopen recreates generation and exact pixels; cancellation/resource/other codes preserve healthy device. Physical GPU reset is not simulated."
    }
}
