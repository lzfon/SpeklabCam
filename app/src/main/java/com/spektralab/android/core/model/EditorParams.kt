package com.spektralab.android.core.model

import com.spektralab.android.core.color.ToneCurves
import com.spektralab.android.core.color.ColorAdjustments
import com.spektralab.android.core.color.ChannelMixer
import com.spektralab.android.core.color.ColorWheels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spektralab.android.core.geometry.computeCenteredCropByAspect
import com.spektralab.android.core.geometry.overscanGateAspect
import com.spektralab.android.core.geometry.FinishedCrop
import com.spektralab.android.engine.StockCatalog
import com.spektralab.android.core.color.SceneCurves
import org.json.JSONObject

/**
 * 编辑器参数 = 引擎 wire 字段的集合（带 Compose 状态）。
 * 字段名均来自 engine/src/core/params.cpp 的 kFields。
 */
class EditorParams {
    // Opaque future extensions travel with their own photo, including history and copy.
    // Empty for existing files; never sent to the engine or selected into a template.
    private var preservedFields: Map<String, String> = emptyMap()
    // 胶片 & 相纸
    var filmId by mutableStateOf("kodak_portra_400")
    var paperId by mutableStateOf("kodak_portra_endura")
    var filmOff by mutableStateOf(false)
    var perspectiveVertical by mutableFloatStateOf(0f)
    var perspectiveHorizontal by mutableFloatStateOf(0f)
    var finishedCrop by mutableStateOf(FinishedCrop.FULL)
    var smartDevelopStyle by mutableStateOf("")
    var smartDevelopSummary by mutableStateOf("")
    // 用「我的配方」显影时记下配方名：面板上要显示这张照片用的是哪份配方，
    // 而不是上一次分析出来的内置风格名。
    var smartDevelopRecipe by mutableStateOf("")
    var smartDevelopVersion by mutableStateOf(0)

    // 相机曝光（SHOOT，重建负片）
    var exposureEv by mutableFloatStateOf(0f)
    // 自动测光方法（SHOOT）。引擎默认 auto_exposure=true，会按此方法自动测光；
    // exposureEv 是叠加其上的手动补偿。合法值：balanced/center/protect_highlights/protect_shadows。
    var autoExposureMethod by mutableStateOf("balanced")
    var autoExposure by mutableStateOf(true)
    // 胶片画幅（SHOOT）：帧长边毫米数，引擎用它缩放颗粒/光晕的微米量。默认 135 的 36mm。
    var filmFormatMm by mutableFloatStateOf(36f)
    // 相机白平衡（解码侧，仅 RAW，不进引擎 delta）。Kelvin 色温 + 色调（Adobe DNG 定义）。
    // cameraWbCustom=false 用 as-shot；true 时按 Kelvin/tint 重新解码。
    var cameraWbKelvin by mutableFloatStateOf(5500f)
    var cameraWbTint by mutableFloatStateOf(0f)
    var cameraWbCustom by mutableStateOf(false)
    var cameraWbTemperatureAsShot by mutableStateOf(false)
    var cameraWbTintAsShot by mutableStateOf(false)

    // 场景宽容度 / 置位（RFC-023，SHOOT）。默认值对齐 engine params.hpp 的 SceneLatitudeParams：
    // 两侧 room=0 表示该侧关闭。由「测量宽容度 → 应用」填入求解结果。
    var sceneLatitudeActive by mutableStateOf(false)
    var sceneLatitudeNorm by mutableStateOf("power")   // power | y | max
    var sceneLatitudeHighlightKnee by mutableFloatStateOf(2f)
    var sceneLatitudeHighlightRoom by mutableFloatStateOf(0f)
    var sceneLatitudeShadowKnee by mutableFloatStateOf(-2f)
    var sceneLatitudeShadowRoom by mutableFloatStateOf(0f)
    var sceneLatitudeRolloff by mutableFloatStateOf(2f)
    var sceneLatitudeMaxLift by mutableFloatStateOf(4f)

    // 放大机（PRINT，live 字段，reprint 复用负片）
    var printExposure by mutableFloatStateOf(1f)   // 越小越亮（暗房约定）
    var mFilterShift by mutableFloatStateOf(0f)    // 品红/绿（用户相对中性值的偏移）
    var yFilterShift by mutableFloatStateOf(0f)    // 黄/蓝（用户相对中性值的偏移）
    // 注：中性滤色包（m/y/c_filter_neutral）不在这里——引擎默认
    // neutral_print_filters_from_database=true，open 时自动从数据库解析，前端不持有。

    // 胶片效果（SHOOT）。默认与引擎一致：颗粒/光晕/DIR 都开（这才是完整胶片观感）。
    var grainActive by mutableStateOf(true)
    var grainAmount by mutableFloatStateOf(1f)
    var grainLayered by mutableStateOf(true)
    var halationActive by mutableStateOf(true)
    var halationAmount by mutableFloatStateOf(1f)
    var halationScatterAmount by mutableFloatStateOf(1f)
    var halationBoostEv by mutableFloatStateOf(0f)
    var halationModelVersion by mutableStateOf(HalationModel.CLASSIC.version)
    var halationHeadroomEv by mutableFloatStateOf(4f)
    var antihalationRemoved by mutableStateOf(false)
    var dirCouplersActive by mutableStateOf(true)
    var dirCouplersAmount by mutableFloatStateOf(1f)
    var glareActive by mutableStateOf(true)
    var glareAmount by mutableFloatStateOf(1f)
    var preflashExposure by mutableFloatStateOf(0f)
    var extendedDynamicRange by mutableStateOf(false)

    // 几何（SHOOT）。裁剪按源图归一化（左上原点，0..1），在旋转/翻转之前应用。
    var quarterTurns by mutableStateOf(0)          // 0..3，90° 顺时针
    var flipH by mutableStateOf(false)
    var flipV by mutableStateOf(false)
    var rotationDeg by mutableFloatStateOf(0f)     // -45..45
    var cropX by mutableFloatStateOf(0f)
    var cropY by mutableFloatStateOf(0f)
    var cropW by mutableFloatStateOf(1f)
    var cropH by mutableFloatStateOf(1f)

    // Layer 2 白平衡（应用侧后处理，不进引擎 delta；只影响显示/导出，并持久化）。
    var temperature by mutableFloatStateOf(0f)   // -1 冷(蓝) .. +1 暖(橙)
    var tint by mutableFloatStateOf(0f)          // -1 品红 .. +1 绿

    // Layer 2 调色（应用侧，编码空间，与桌面 adjustments.ts 一致）。-100..100 表示百分比。
    var gradeExposure by mutableFloatStateOf(0f)   // EV
    var contrast by mutableFloatStateOf(0f)
    var brightness by mutableFloatStateOf(0f)
    var saturation by mutableFloatStateOf(0f)
    var highlights by mutableFloatStateOf(0f)
    var shadows by mutableFloatStateOf(0f)
    var blackPoint by mutableFloatStateOf(0f)
    var whitePoint by mutableFloatStateOf(0f)
    var colorAdjustments by mutableStateOf(ColorAdjustments.NEUTRAL)
    var unreadableColorAdjustments by mutableStateOf<String?>(null)
    var channelMixer by mutableStateOf(ChannelMixer.IDENTITY)
    var unreadableChannelMixer by mutableStateOf<String?>(null)
    var colorWheels by mutableStateOf(ColorWheels.NEUTRAL)
    var unreadableColorWheels by mutableStateOf<String?>(null)
    var sceneCurves by mutableStateOf(SceneCurves.IDENTITY)
    var unreadableSceneCurves by mutableStateOf<String?>(null)
    var toneCurves by mutableStateOf(ToneCurves.IDENTITY)
    var unreadableToneCurves by mutableStateOf<String?>(null)
    var vignetteAmount by mutableFloatStateOf(0f) // 0..100, zero preserves existing output
    var vignetteSize by mutableFloatStateOf(50f)
    var vignetteFeather by mutableFloatStateOf(70f)
    // 三区色彩平衡：shadow/mid/high 各 temp（暖↔冷）+ tint（绿↔品红）。
    var cbShadowTemp by mutableFloatStateOf(0f)
    var cbShadowTint by mutableFloatStateOf(0f)
    var cbMidTemp by mutableFloatStateOf(0f)
    var cbMidTint by mutableFloatStateOf(0f)
    var cbHighTemp by mutableFloatStateOf(0f)
    var cbHighTint by mutableFloatStateOf(0f)

    // M5 高级功能（SHOOT）。
    var cameraFilter by mutableStateOf("")              // Wratten 镜头滤镜，"" = 无
    var overscanActive by mutableStateOf(false)         // 片边
    var overscanFormat by mutableStateOf("135")
    var overscanMode by mutableStateOf("strip")         // strip / filed
    var overscanGate by mutableStateOf("auto")          // auto / square / rounded / eared / shouldered / kicked
    var overscanHoles by mutableStateOf("white")        // white / black
    var overscanCarrier by mutableStateOf("none")       // none / black / open
    var overscanEdgeText by mutableStateOf("KODAK PORTRA 400")
    var overscanFrameNumber by mutableStateOf(0)        // 0 = 由帧种子决定
    var overscanCameraSeed by mutableStateOf(1)         // 机身（门形状/毛刺/雾化）
    var overscanFrameSeed by mutableStateOf(1)          // 过片与扫描（漏光/编号）
    var overscanFog by mutableFloatStateOf(1f)          // 0..4 边缘雾化
    var overscanLeaks by mutableFloatStateOf(0f)        // 0..4 漏光
    var overscanFNumber by mutableFloatStateOf(0f)      // 0=未知（按 f/5.6），门边光晕
    var dateImprintActive by mutableStateOf(true)       // 日期后背（默认开）
    var dateImprintText by mutableStateOf("")           // 空 = 自动当前日期
    var dateImprintStyle by mutableStateOf("lcd")       // lcd 七段 / dots 点阵 / data 拍摄数据
    var dateImprintPlacement by mutableStateOf("frame") // frame / rebate
    var dateImprintCorner by mutableStateOf("br")       // br / bl / tr / tl
    var dateImprintSize by mutableFloatStateOf(1f)      // 0.4..3.0
    var dateImprintEv by mutableFloatStateOf(3.5f)      // -2..8
    var dateImprintInsetX by mutableFloatStateOf(3f)    // mm
    var dateImprintInsetY by mutableFloatStateOf(2.4f)  // mm
    var dateImprintDataText by mutableStateOf("")       // 拍摄数据
    var dateImprintShowData by mutableStateOf(false)      // 是否把拍摄参数一起印上

    /** 打开会话的完整参数。[outputColorSpace] 默认 sRGB，导出时可指定 Display P3；
     *  [inputColorSpace] 默认 sRGB，RAW 解码后用 "ProPhoto RGB"。 */
    fun openJson(srcW: Int, srcH: Int, outputColorSpace: String = "sRGB", inputColorSpace: String = "sRGB"): String = baseJson(srcW, srcH)
        .put("input_color_space", inputColorSpace)
        .put("output_color_space", outputColorSpace)
        .toString()

    /** 编辑时的参数 delta。 */
    fun deltaJson(srcW: Int, srcH: Int): String = baseJson(srcW, srcH).toString()

    /** An unsupported future curve may be inspected/reset, but never silently exported. */
    internal fun previewOpenJson(srcW: Int, srcH: Int, outputColorSpace: String = "sRGB", inputColorSpace: String = "sRGB"): String =
        baseJson(srcW, srcH, preview = true).put("input_color_space", inputColorSpace)
            .put("output_color_space", outputColorSpace).toString()

    internal fun previewDeltaJson(srcW: Int, srcH: Int): String = baseJson(srcW, srcH, preview = true).toString()

    /** 序列化为 sidecar JSON（所有可编辑字段）。 */
    fun toJson(): JSONObject = JSONObject()
        .put("film_stock", filmId)
        .put("print_stock", paperId)
        .put("smart_develop_style", smartDevelopStyle)
        .put("smart_develop_summary", smartDevelopSummary)
        .put("smart_develop_recipe", smartDevelopRecipe)
        .put("smart_develop_version", smartDevelopVersion)
        .put("exposure_compensation_ev", exposureEv.toDouble())
        .put("auto_exposure_method", autoExposureMethod)
        .put("auto_exposure", autoExposure)
        .put("film_format_mm", filmFormatMm.toDouble())
        .put("camera_wb_kelvin", cameraWbKelvin.toDouble())
        .put("camera_wb_tint", cameraWbTint.toDouble())
        .put("camera_wb_custom", cameraWbCustom)
        .put("camera_wb_temperature_as_shot", cameraWbTemperatureAsShot)
        .put("camera_wb_tint_as_shot", cameraWbTintAsShot)
        .put("scene_latitude_active", sceneLatitudeActive)
        .put("scene_latitude_norm", sceneLatitudeNorm)
        .put("scene_latitude_highlight_knee", sceneLatitudeHighlightKnee.toDouble())
        .put("scene_latitude_highlight_room", sceneLatitudeHighlightRoom.toDouble())
        .put("scene_latitude_shadow_knee", sceneLatitudeShadowKnee.toDouble())
        .put("scene_latitude_shadow_room", sceneLatitudeShadowRoom.toDouble())
        .put("scene_latitude_rolloff", sceneLatitudeRolloff.toDouble())
        .put("scene_latitude_max_lift", sceneLatitudeMaxLift.toDouble())
        .put("print_exposure", printExposure.toDouble())
        .put("m_filter_shift", mFilterShift.toDouble())
        .put("y_filter_shift", yFilterShift.toDouble())
        .put("grain_active", grainActive)
        .put("grain_amount", grainAmount.toDouble())
        .put("grain_layered", grainLayered)
        .put("halation_active", halationActive)
        .put("halation_amount", halationAmount.toDouble())
        .put("halation_scatter_amount", halationScatterAmount.toDouble())
        .put("halation_boost_ev", halationBoostEv.toDouble())
        .put("halation_model_version", halationModelVersion)
        .put("halation_headroom_ev", halationHeadroomEv.toDouble())
        .put("antihalation_removed", antihalationRemoved)
        .put("dir_couplers_active", dirCouplersActive)
        .put("dir_couplers_amount", dirCouplersAmount.toDouble())
        .put("glare_active", glareActive)
        .put("glare_amount", glareAmount.toDouble())
        .put("preflash_exposure", preflashExposure.toDouble())
        .put("extended_dynamic_range", extendedDynamicRange)
        .put("quarter_turns", quarterTurns)
        .put("flip_h", flipH)
        .put("flip_v", flipV)
        .put("rotation_deg", rotationDeg.toDouble())
        .put("crop_x", cropX.toDouble())
        .put("crop_y", cropY.toDouble())
        .put("crop_w", cropW.toDouble())
        .put("crop_h", cropH.toDouble())
        .put("temperature", temperature.toDouble())
        .put("tint", tint.toDouble())
        .put("grade_exposure", gradeExposure.toDouble())
        .put("contrast", contrast.toDouble())
        .put("brightness", brightness.toDouble())
        .put("saturation", saturation.toDouble())
        .put("highlights", highlights.toDouble())
        .put("shadows", shadows.toDouble())
        .put("black_point", blackPoint.toDouble())
        .put("white_point", whitePoint.toDouble())
        .put("vignette_amount", vignetteAmount.toDouble())
        .put("vignette_size", vignetteSize.toDouble())
        .put("vignette_feather", vignetteFeather.toDouble())
        .put("cb_shadow_temp", cbShadowTemp.toDouble())
        .put("cb_shadow_tint", cbShadowTint.toDouble())
        .put("cb_mid_temp", cbMidTemp.toDouble())
        .put("cb_mid_tint", cbMidTint.toDouble())
        .put("cb_high_temp", cbHighTemp.toDouble())
        .put("cb_high_tint", cbHighTint.toDouble())
        .put("camera_filter", cameraFilter)
        .put("overscan_active", overscanActive)
        .put("overscan_format", overscanFormat)
        .put("overscan_mode", overscanMode)
        .put("overscan_gate", overscanGate)
        .put("overscan_holes", overscanHoles)
        .put("overscan_carrier", overscanCarrier)
        .put("overscan_edge_text", StockCatalog.edgeText(filmId, overscanFormat))
        .put("overscan_frame_number", overscanFrameNumber)
        .put("overscan_camera_seed", overscanCameraSeed)
        .put("overscan_frame_seed", overscanFrameSeed)
        .put("overscan_fog", overscanFog.toDouble())
        .put("overscan_leaks", overscanLeaks.toDouble())
        .put("overscan_f_number", overscanFNumber.toDouble())
        .put("date_imprint_active", dateImprintActive)
        .put("date_imprint_text", dateImprintText)
        .put("date_imprint_style", dateImprintStyle)
        .put("date_imprint_placement", dateImprintPlacement)
        .put("date_imprint_corner", dateImprintCorner)
        .put("date_imprint_size", dateImprintSize.toDouble())
        .put("date_imprint_ev", dateImprintEv.toDouble())
        .put("date_imprint_inset_x", dateImprintInsetX.toDouble())
        .put("date_imprint_inset_y", dateImprintInsetY.toDouble())
        // 「打印拍摄参数」开关与参数行都要落盘：只写文本、不写开关，
        // 关一次再存就会把开关和用户填的参数一起丢掉（fromJson 是认这个键的）。
        .put("date_imprint_show_data", dateImprintShowData)
        .put("date_imprint_data_text", dateImprintDataText)
        .apply {
            if (unreadableSceneCurves != null) put("scene_curves", org.json.JSONTokener(unreadableSceneCurves!!).nextValue())
            else if (!sceneCurves.isDefault) put("scene_curves", sceneCurves.toJson())
            if (filmOff) put("film_off",true)
            if (perspectiveVertical != 0f) put("perspective_vertical",perspectiveVertical.toDouble())
            if (perspectiveHorizontal != 0f) put("perspective_horizontal",perspectiveHorizontal.toDouble())
            if (!finishedCrop.isFull) put("finished_crop",finishedCrop.toJson())
            preservedFields.forEach { (key, value) -> put(key, org.json.JSONTokener(value).nextValue()) }
            if (unreadableColorWheels != null) put("color_wheels", org.json.JSONTokener(unreadableColorWheels!!).nextValue())
            else if (!colorWheels.isDefault) put("color_wheels", colorWheels.toJson())
            if (unreadableChannelMixer != null) put("channel_mixer", org.json.JSONTokener(unreadableChannelMixer!!).nextValue())
            else if (!channelMixer.isNeutral) put("channel_mixer", channelMixer.toJson())
            if (unreadableColorAdjustments != null) put("color_adjustments", org.json.JSONTokener(unreadableColorAdjustments!!).nextValue())
            else if (!colorAdjustments.isNeutral) put("color_adjustments", colorAdjustments.toJson())
            if (unreadableToneCurves != null) put("tone_curves", JSONObject(unreadableToneCurves!!))
            else if (!toneCurves.isDefault) put("tone_curves", toneCurves.toJson())
        }

    fun copy(): EditorParams = fromJson(toJson())

    /** Copy editable capture/print/grade settings while keeping each photo's own geometry. */
    fun copySettingsFrom(source: EditorParams) {
        val copied = source.copy()
        copied.preservedFields = preservedFields
        copied.cropX = cropX; copied.cropY = cropY
        copied.cropW = cropW; copied.cropH = cropH
        copied.quarterTurns = quarterTurns; copied.flipH = flipH; copied.flipV = flipV; copied.rotationDeg = rotationDeg
        copied.perspectiveVertical = perspectiveVertical; copied.perspectiveHorizontal = perspectiveHorizontal
        copied.finishedCrop = finishedCrop
        copyAllFrom(copied)
    }

    private fun copyAllFrom(p: EditorParams) {
        preservedFields = p.preservedFields
        filmId = p.filmId; paperId = p.paperId; exposureEv = p.exposureEv
        filmOff = p.filmOff; perspectiveVertical = p.perspectiveVertical; perspectiveHorizontal = p.perspectiveHorizontal
        finishedCrop = p.finishedCrop
        smartDevelopStyle=p.smartDevelopStyle;smartDevelopSummary=p.smartDevelopSummary;smartDevelopVersion=p.smartDevelopVersion
        smartDevelopRecipe = p.smartDevelopRecipe
        autoExposureMethod = p.autoExposureMethod; autoExposure = p.autoExposure; filmFormatMm = p.filmFormatMm
        cameraWbKelvin = p.cameraWbKelvin; cameraWbTint = p.cameraWbTint; cameraWbCustom = p.cameraWbCustom
        cameraWbTemperatureAsShot = p.cameraWbTemperatureAsShot; cameraWbTintAsShot = p.cameraWbTintAsShot
        sceneLatitudeActive = p.sceneLatitudeActive; sceneLatitudeNorm = p.sceneLatitudeNorm
        sceneLatitudeHighlightKnee = p.sceneLatitudeHighlightKnee; sceneLatitudeHighlightRoom = p.sceneLatitudeHighlightRoom
        sceneLatitudeShadowKnee = p.sceneLatitudeShadowKnee; sceneLatitudeShadowRoom = p.sceneLatitudeShadowRoom
        sceneLatitudeRolloff = p.sceneLatitudeRolloff; sceneLatitudeMaxLift = p.sceneLatitudeMaxLift
        printExposure = p.printExposure; mFilterShift = p.mFilterShift; yFilterShift = p.yFilterShift
        grainActive = p.grainActive; grainAmount = p.grainAmount; grainLayered = p.grainLayered
        halationActive = p.halationActive; halationAmount = p.halationAmount; halationScatterAmount = p.halationScatterAmount
        halationBoostEv = p.halationBoostEv; antihalationRemoved = p.antihalationRemoved
        halationModelVersion = p.halationModelVersion; halationHeadroomEv = p.halationHeadroomEv
        dirCouplersActive = p.dirCouplersActive; dirCouplersAmount = p.dirCouplersAmount
        glareActive = p.glareActive; glareAmount = p.glareAmount; preflashExposure = p.preflashExposure
        extendedDynamicRange = p.extendedDynamicRange
        quarterTurns = p.quarterTurns; flipH = p.flipH; flipV = p.flipV; rotationDeg = p.rotationDeg
        cropX = p.cropX; cropY = p.cropY; cropW = p.cropW; cropH = p.cropH
        temperature = p.temperature; tint = p.tint; gradeExposure = p.gradeExposure
        contrast = p.contrast; brightness = p.brightness; saturation = p.saturation
        highlights = p.highlights; shadows = p.shadows; blackPoint = p.blackPoint; whitePoint = p.whitePoint
        colorAdjustments = p.colorAdjustments; unreadableColorAdjustments = p.unreadableColorAdjustments
        channelMixer = p.channelMixer; unreadableChannelMixer = p.unreadableChannelMixer
        colorWheels = p.colorWheels; unreadableColorWheels = p.unreadableColorWheels
        sceneCurves = p.sceneCurves; unreadableSceneCurves = p.unreadableSceneCurves
        toneCurves = p.toneCurves; unreadableToneCurves = p.unreadableToneCurves
        vignetteAmount = p.vignetteAmount; vignetteSize = p.vignetteSize; vignetteFeather = p.vignetteFeather
        cbShadowTemp = p.cbShadowTemp; cbShadowTint = p.cbShadowTint; cbMidTemp = p.cbMidTemp; cbMidTint = p.cbMidTint
        cbHighTemp = p.cbHighTemp; cbHighTint = p.cbHighTint; cameraFilter = p.cameraFilter
        overscanActive = p.overscanActive; overscanFormat = p.overscanFormat; overscanMode = p.overscanMode
        overscanGate = p.overscanGate; overscanHoles = p.overscanHoles; overscanCarrier = p.overscanCarrier
        overscanEdgeText = p.overscanEdgeText; overscanFrameNumber = p.overscanFrameNumber
        overscanCameraSeed = p.overscanCameraSeed; overscanFrameSeed = p.overscanFrameSeed
        overscanFog = p.overscanFog; overscanLeaks = p.overscanLeaks; overscanFNumber = p.overscanFNumber
        dateImprintActive = p.dateImprintActive; dateImprintText = p.dateImprintText; dateImprintStyle = p.dateImprintStyle
        dateImprintPlacement = p.dateImprintPlacement; dateImprintCorner = p.dateImprintCorner
        dateImprintSize = p.dateImprintSize; dateImprintEv = p.dateImprintEv
        dateImprintInsetX = p.dateImprintInsetX; dateImprintInsetY = p.dateImprintInsetY
        dateImprintDataText = p.dateImprintDataText; dateImprintShowData = p.dateImprintShowData
    }

    companion object {
        /** 引擎不印日期的全景画幅（overscan.cpp 的日期块只认 135 / 120）。 */
        internal val PANORAMIC_FORMATS = setOf("135_xpan", "120_6x12", "120_6x17")

        private val knownFields: Set<String> by lazy {
            EditorParams().toJson().keys().asSequence().toSet() +
                setOf("tone_curves", "color_adjustments", "channel_mixer", "color_wheels",
                    "film_off", "perspective_vertical", "perspective_horizontal", "finished_crop", "scene_curves")
        }
        /** Same per-film RMS calibration as Windows desktop/shared/params.ts. */
        fun ownGrain(stock: String): Float = when (stock) {
            "kodak_tri_x_400" -> 1.70f
            "ilford_hp5_plus_400" -> 1.49f
            "kodak_tmax_100" -> 0.74f
            "fujifilm_neopan_acros_100_ii" -> 0.64f
            else -> 1f
        }

        /**
         * 颗粒强度在 UI 上的真实上限：发送时按 `grainAmount * ownGrain` 折算，
         * 引擎又封顶 2.0，所以超过 2.0/ownGrain 的滑块位置再推也不会更浓。
         */
        fun grainCeiling(stock: String): Float = (2.0f / ownGrain(stock)).coerceAtLeast(0.05f)

        /** 从 sidecar JSON 恢复参数（缺字段用默认值）。 */
        fun fromJson(json: JSONObject): EditorParams {
            HalationModel.requireReadable(json)
            require(!json.has("film_off") || json.get("film_off") is Boolean)
            for (key in listOf("perspective_vertical","perspective_horizontal"))
                require(!json.has(key) || json.get(key) is Number && json.getDouble(key).isFinite() && json.getDouble(key) in -30.0..30.0)
            require(!json.has("finished_crop") || json.get("finished_crop") is JSONObject)
            val p = EditorParams()
            p.preservedFields = json.keys().asSequence().filterNot { it in knownFields }.associateWith { key ->
                org.json.JSONArray().put(json.get(key)).toString().let { it.substring(1, it.length - 1) }
            }
            p.filmId = json.optString("film_stock", p.filmId)
            p.paperId = json.optString("print_stock", p.paperId)
            p.smartDevelopStyle=json.optString("smart_develop_style", "")
            p.smartDevelopSummary=json.optString("smart_develop_summary", "")
            p.smartDevelopRecipe=json.optString("smart_develop_recipe", "")
            p.smartDevelopVersion=json.optInt("smart_develop_version", 0)
            p.exposureEv = json.optDouble("exposure_compensation_ev", 0.0).toFloat()
            p.autoExposureMethod = json.optString("auto_exposure_method", "balanced")
            p.autoExposure = json.optBoolean("auto_exposure", true)
            p.filmFormatMm = json.optDouble("film_format_mm", 36.0).toFloat()
            p.filmOff = json.optBoolean("film_off",false)
            p.perspectiveVertical = json.optDouble("perspective_vertical",0.0).toFloat()
            p.perspectiveHorizontal = json.optDouble("perspective_horizontal",0.0).toFloat()
            p.finishedCrop = json.optJSONObject("finished_crop")?.let(FinishedCrop::fromJson) ?: FinishedCrop.FULL
            p.cameraWbKelvin = json.optDouble("camera_wb_kelvin", 5500.0).toFloat()
            p.cameraWbTint = json.optDouble("camera_wb_tint", 0.0).toFloat()
            p.cameraWbCustom = json.optBoolean("camera_wb_custom", false)
            p.cameraWbTemperatureAsShot = json.optBoolean("camera_wb_temperature_as_shot", false)
            p.cameraWbTintAsShot = json.optBoolean("camera_wb_tint_as_shot", false)
            p.sceneLatitudeActive = json.optBoolean("scene_latitude_active", false)
            p.sceneLatitudeNorm = json.optString("scene_latitude_norm", "power")
            p.sceneLatitudeHighlightKnee = json.optDouble("scene_latitude_highlight_knee", 2.0).toFloat()
            p.sceneLatitudeHighlightRoom = json.optDouble("scene_latitude_highlight_room", 0.0).toFloat()
            p.sceneLatitudeShadowKnee = json.optDouble("scene_latitude_shadow_knee", -2.0).toFloat()
            p.sceneLatitudeShadowRoom = json.optDouble("scene_latitude_shadow_room", 0.0).toFloat()
            p.sceneLatitudeRolloff = json.optDouble("scene_latitude_rolloff", 2.0).toFloat()
            p.sceneLatitudeMaxLift = json.optDouble("scene_latitude_max_lift", 4.0).toFloat()
            p.printExposure = json.optDouble("print_exposure", 1.0).toFloat()
            p.mFilterShift = json.optDouble("m_filter_shift", 0.0).toFloat()
            p.yFilterShift = json.optDouble("y_filter_shift", 0.0).toFloat()
            p.grainActive = json.optBoolean("grain_active", true)
            p.grainAmount = json.optDouble("grain_amount", 1.0).toFloat().coerceIn(0f, 2f)
            p.grainLayered = json.optBoolean("grain_layered", true)
            p.halationActive = json.optBoolean("halation_active", true)
            p.halationAmount = json.optDouble("halation_amount", 1.0).toFloat()
            p.halationScatterAmount = json.optDouble("halation_scatter_amount", 1.0).toFloat().coerceIn(0f, 1f)
            p.halationBoostEv = json.optDouble("halation_boost_ev", 0.0).toFloat().coerceIn(0f, 8f)
            p.halationModelVersion = json.optInt("halation_model_version", HalationModel.CLASSIC.version)
            p.halationHeadroomEv = json.optDouble("halation_headroom_ev", 4.0).toFloat()
            p.antihalationRemoved = json.optBoolean("antihalation_removed", false)
            p.dirCouplersActive = json.optBoolean("dir_couplers_active", true)
            p.dirCouplersAmount = json.optDouble("dir_couplers_amount", 1.0).toFloat().coerceIn(0f, 1.5f)
            p.glareActive = json.optBoolean("glare_active", true)
            p.glareAmount = json.optDouble("glare_amount", 1.0).toFloat().coerceIn(0f, 30f)
            p.preflashExposure = json.optDouble("preflash_exposure", 0.0).toFloat().coerceIn(0f, 0.03f)
            p.extendedDynamicRange = json.optBoolean("extended_dynamic_range", false)
            p.quarterTurns = json.optInt("quarter_turns", 0)
            p.flipH = json.optBoolean("flip_h", false)
            p.flipV = json.optBoolean("flip_v", false)
            p.rotationDeg = json.optDouble("rotation_deg", 0.0).toFloat()
            p.cropX = json.optDouble("crop_x", 0.0).toFloat()
            p.cropY = json.optDouble("crop_y", 0.0).toFloat()
            p.cropW = json.optDouble("crop_w", 1.0).toFloat()
            p.cropH = json.optDouble("crop_h", 1.0).toFloat()
            p.temperature = json.optDouble("temperature", 0.0).toFloat()
            p.tint = json.optDouble("tint", 0.0).toFloat()
            p.gradeExposure = json.optDouble("grade_exposure", 0.0).toFloat()
            p.contrast = json.optDouble("contrast", 0.0).toFloat()
            p.brightness = json.optDouble("brightness", 0.0).toFloat()
            p.saturation = json.optDouble("saturation", 0.0).toFloat()
            p.highlights = json.optDouble("highlights", 0.0).toFloat()
            p.shadows = json.optDouble("shadows", 0.0).toFloat()
            p.blackPoint = json.optDouble("black_point", 0.0).toFloat()
            p.whitePoint = json.optDouble("white_point", 0.0).toFloat()
            if (json.has("color_wheels")) {
                val raw = json.get("color_wheels")
                p.colorWheels = try { ColorWheels.fromJson(raw as JSONObject) } catch (_: Exception) {
                    p.unreadableColorWheels = org.json.JSONArray().put(raw).toString().let { it.substring(1, it.length - 1) }
                    ColorWheels.NEUTRAL
                }
            }
            if (json.has("channel_mixer")) {
                val raw = json.get("channel_mixer")
                p.channelMixer = try { ChannelMixer.fromJson(raw as JSONObject) } catch (_: Exception) {
                    p.unreadableChannelMixer = org.json.JSONArray().put(raw).toString().let { it.substring(1, it.length - 1) }
                    ChannelMixer.IDENTITY
                }
            }
            if (json.has("color_adjustments")) {
                val raw = json.get("color_adjustments")
                p.colorAdjustments = try { ColorAdjustments.fromJson(raw as JSONObject) } catch (_: Exception) {
                    p.unreadableColorAdjustments = org.json.JSONArray().put(raw).toString().let { it.substring(1, it.length - 1) }
                    ColorAdjustments.NEUTRAL
                }
            }
            if (json.has("scene_curves")) {
                val raw = json.get("scene_curves")
                p.sceneCurves = try { SceneCurves.fromJson(raw as JSONObject) } catch (_: Exception) {
                    p.unreadableSceneCurves = org.json.JSONArray().put(raw).toString().let { it.substring(1, it.length - 1) }
                    SceneCurves.IDENTITY
                }
            }
            val curveData = json.optJSONObject("tone_curves")
            p.toneCurves = try { ToneCurves.fromJson(curveData) } catch (_: Exception) {
                p.unreadableToneCurves = curveData?.toString()
                ToneCurves.IDENTITY
            }
            p.vignetteAmount = json.optDouble("vignette_amount", 0.0).toFloat().coerceIn(0f, 100f)
            p.vignetteSize = json.optDouble("vignette_size", 50.0).toFloat().coerceIn(0f, 100f)
            p.vignetteFeather = json.optDouble("vignette_feather", 70.0).toFloat().coerceIn(0f, 100f)
            p.cbShadowTemp = json.optDouble("cb_shadow_temp", 0.0).toFloat()
            p.cbShadowTint = json.optDouble("cb_shadow_tint", 0.0).toFloat()
            p.cbMidTemp = json.optDouble("cb_mid_temp", 0.0).toFloat()
            p.cbMidTint = json.optDouble("cb_mid_tint", 0.0).toFloat()
            p.cbHighTemp = json.optDouble("cb_high_temp", 0.0).toFloat()
            p.cbHighTint = json.optDouble("cb_high_tint", 0.0).toFloat()
            p.cameraFilter = json.optString("camera_filter", "")
            p.overscanActive = json.optBoolean("overscan_active", false)
            p.overscanFormat = json.optString("overscan_format", "135")
            p.overscanMode = json.optString("overscan_mode", "strip")
            p.overscanGate = json.optString("overscan_gate", "auto")
            p.overscanHoles = json.optString("overscan_holes", "white")
            p.overscanCarrier = json.optString("overscan_carrier", "none")
            p.overscanEdgeText = json.optString("overscan_edge_text", "KODAK PORTRA 400")
            p.overscanFrameNumber = json.optInt("overscan_frame_number", 0)
            p.overscanCameraSeed = json.optInt("overscan_camera_seed", 1)
            p.overscanFrameSeed = json.optInt("overscan_frame_seed", 1)
            p.overscanFog = json.optDouble("overscan_fog", 1.0).toFloat()
            p.overscanLeaks = json.optDouble("overscan_leaks", 0.0).toFloat()
            p.overscanFNumber = json.optDouble("overscan_f_number", 0.0).toFloat()
            p.dateImprintActive = json.optBoolean("date_imprint_active", true)
            p.dateImprintText = json.optString("date_imprint_text", "")
            p.dateImprintStyle = json.optString("date_imprint_style", "lcd")
            p.dateImprintPlacement = json.optString("date_imprint_placement", "frame")
            p.dateImprintCorner = json.optString("date_imprint_corner", "br")
            p.dateImprintSize = json.optDouble("date_imprint_size", 1.0).toFloat()
            p.dateImprintEv = json.optDouble("date_imprint_ev", 3.5).toFloat()
            p.dateImprintInsetX = json.optDouble("date_imprint_inset_x", 3.0).toFloat()
            p.dateImprintInsetY = json.optDouble("date_imprint_inset_y", 2.4).toFloat()
            p.dateImprintDataText = json.optString("date_imprint_data_text", ""); p.dateImprintShowData = json.optBoolean("date_imprint_show_data", false)
            // 旧存档可能引用已删除的材料（如「数字中间片」伪相纸）；换成现有真实材料，否则引擎报 no profile。
            StockCatalog.repair(p.filmId, p.paperId)?.let { (film, paper) -> p.filmId = film; p.paperId = paper }
            return p
        }
    }

    private fun baseJson(srcW: Int, srcH: Int, preview: Boolean = false): JSONObject {
        require(unreadableSceneCurves == null || filmOff || preview) { "UNSUPPORTED_SCENE_CURVE" }
        // 片边开启时，把源图居中裁到该画幅的门径（gate）比例，否则用用户的裁剪。
        val c = if (overscanActive && !filmOff) {
            computeCenteredCropByAspect(overscanGateAspect(overscanFormat, srcW, srcH), srcW, srcH)
        } else {
            floatArrayOf(cropX, cropY, cropW, cropH)
        }
        return JSONObject()
            .put("film_stock", filmId)
            .put("print_stock", paperId)
            // 曝光补偿改为在「自动曝光后的成片」上由 App 侧叠加（见 EditorViewModel.grade 的 exposureGain）：
            // 引擎的自动测光会把发进去的 exposure_compensation_ev 一起归一化掉（实测 ±3EV 几乎无变化），
            // 所以这里给引擎发 0，真正的手动补偿在客户端叠加，保证一定生效且方向正确。
            .put("exposure_compensation_ev", 0.0)
            .put("auto_exposure_method", autoExposureMethod)
            .put("auto_exposure", autoExposure)
            .put("film_format_mm", filmFormatMm.toDouble())
            .put("scene_latitude_active", sceneLatitudeActive)
            .put("scene_latitude_norm", sceneLatitudeNorm)
            .put("scene_latitude_highlight_knee", sceneLatitudeHighlightKnee.toDouble())
            .put("scene_latitude_highlight_room", sceneLatitudeHighlightRoom.toDouble())
            .put("scene_latitude_shadow_knee", sceneLatitudeShadowKnee.toDouble())
            .put("scene_latitude_shadow_room", sceneLatitudeShadowRoom.toDouble())
            .put("scene_latitude_rolloff", sceneLatitudeRolloff.toDouble())
            .put("scene_latitude_max_lift", sceneLatitudeMaxLift.toDouble())
            .put("print_exposure", printExposure.toDouble())
            .put("m_filter_shift", mFilterShift.toDouble())
            .put("y_filter_shift", yFilterShift.toDouble())
            .put("filter_shift_scale", 40.0)   // 让 ±1 的 shift 变成 ±40 CC（与原版 App 一致）
            .put("grain_active", grainActive)
            .put("grain_amount", (grainAmount * ownGrain(filmId)).coerceIn(0f, 2f).toDouble())
            .put("grain_sublayers_active", grainActive && grainLayered && !StockCatalog.isMonochrome(filmId))
            .put("scan_film", StockCatalog.isPositive(filmId))
            .put("halation_active", halationActive)
            .put("halation_amount", halationAmount.toDouble())
            .put("halation_scatter_amount", halationScatterAmount.toDouble())
            .put("halation_boost_ev", halationBoostEv.toDouble())
            .put("halation_model_version", halationModelVersion)
            .put("halation_headroom_ev", halationHeadroomEv.toDouble())
            .put("antihalation_removed", antihalationRemoved)
            .put("dir_couplers_active", dirCouplersActive)
            .put("dir_couplers_amount", dirCouplersAmount.toDouble())
            .put("glare_active", glareActive)
            .put("glare_amount", glareAmount.toDouble())
            .put("preflash_exposure", preflashExposure.toDouble())
            .put("extended_dynamic_range", extendedDynamicRange && !StockCatalog.isMonochrome(paperId) && !StockCatalog.isPositive(filmId))
            .put("geometry_quarter_turns", quarterTurns)
            .put("geometry_flip_h", flipH)
            .put("geometry_flip_v", flipV)
            .put("geometry_rotation_deg", rotationDeg.toDouble())
            .put("geometry_crop_x", c[0].toDouble())
            .put("geometry_crop_y", c[1].toDouble())
            .put("geometry_crop_w", c[2].toDouble())
            .put("geometry_crop_h", c[3].toDouble())
            .put("camera_filter", cameraFilter)
            // The curve is a film-exposure operation; bypass keeps its saved data dormant.
            .put("scene_curve", if (filmOff) "" else sceneCurves.wire)
            .put("overscan_active", overscanActive && !filmOff)
            .put("overscan_format", overscanFormat)
            .put("overscan_mode", overscanMode)
            .put("overscan_gate", overscanGate)
            .put("overscan_holes", overscanHoles)
            .put("overscan_carrier", overscanCarrier)
            // Film edge lettering is a property of the selected emulsion and
            // gauge. It is resolved at render time so a sidecar cannot leave
            // stale Portra text after switching stocks.
            .put("overscan_edge_text", StockCatalog.edgeText(filmId, overscanFormat))
            .put("overscan_frame_number", overscanFrameNumber)
            .put("overscan_camera_seed", overscanCameraSeed)
            .put("overscan_frame_seed", overscanFrameSeed)
            .put("overscan_fog", overscanFog.toDouble())
            .put("overscan_leaks", overscanLeaks.toDouble())
            .put("overscan_f_number", overscanFNumber.toDouble())
            // 画面内的字由 App 按像素画（见 DateImprintPaint），引擎只在片边上印，
            // 两边同时开会印出两份，所以这里按路径二选一；数字旁路（filmOff）时片边不上字。
            .put("date_imprint_active", dateImprintActive && !imprintInFrame && !filmOff)
            .put("date_imprint_text", effectiveDateText())
            .put("date_imprint_style", dateImprintStyle)
            .put("date_imprint_placement", dateImprintPlacement)
            .put("date_imprint_corner", dateImprintCorner)
            .put("date_imprint_size", dateImprintSize.toDouble())
            .put("date_imprint_ev", dateImprintEv.toDouble())
            .put("date_imprint_inset_x", dateImprintInsetX.toDouble())
            .put("date_imprint_inset_y", dateImprintInsetY.toDouble())
            .put("date_imprint_data_text", effectiveDataText())
            .apply {
                if (filmOff) put("android_film_off",true)
                if (perspectiveVertical != 0f || perspectiveHorizontal != 0f) {
                    put("android_perspective_vertical",perspectiveVertical.toDouble())
                    put("android_perspective_horizontal",perspectiveHorizontal.toDouble())
                }
                if (StockCatalog.isMonochrome(filmId) && StockCatalog.isMonochrome(paperId)) {
                    put("c_filter_neutral", 0.0); put("m_filter_neutral", 0.0); put("y_filter_neutral", 68.0)
                }
            }
    }

    /** 字是不是要印在照片画面内：位置选「照片内」，或者根本没开片边时，都由 App 自己按像素画。 */
    // 有片边时引擎知道片门在哪，画面内的字也交给它（否则 App 只能按外框算，会跑到片边上）；
    // 没片边时下限框就是照片本身，Engine 的胶片毫米坐标对不上，改由 App 按像素画。
    // 全景画幅（135_xpan / 120_6x12 / 120_6x17）引擎不印日期（overscan.cpp 只处理 135 与 120），
    // 所以这三种画幅即使开着片边也要由 App 在画面内画，否则开关开着却什么都不出现。
    val imprintInFrame: Boolean
        get() = dateImprintActive && (!overscanActive || overscanFormat in PANORAMIC_FORMATS)

    /** 日期后背要印的日期字：优先手填/EXIF，空着就退回当天，避免开了却什么都不显示。 */
    fun effectiveDateText(): String {
        if (dateImprintStyle == "data" && dateImprintShowData && dateImprintDataText.isNotBlank()) return dateImprintDataText
        if (dateImprintText.isNotBlank()) return dateImprintText
        val today = java.time.LocalDate.now()
        return "'%02d %d %d".format(today.year % 100, today.monthValue, today.dayOfMonth)
    }

    /** 「打印拍摄参数」打开、或日期样式本身就是“拍摄数据”时，才把拍摄参数送去印。 */
    fun effectiveDataText(): String =
        if ((dateImprintShowData || dateImprintStyle == "data") && dateImprintDataText.isNotBlank())
            dateImprintDataText else ""
}
