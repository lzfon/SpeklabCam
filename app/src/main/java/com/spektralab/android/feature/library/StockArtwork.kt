package com.spektralab.android.feature.library

import android.content.res.Resources
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.spektralab.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@DrawableRes
internal fun stockArtworkResource(id: String): Int? = when (id) {
    "kodak_portra_400" -> R.drawable.stock_kodak_portra_400
    "kodak_portra_160" -> R.drawable.stock_kodak_portra_160
    "kodak_portra_800", "kodak_portra_800_push1", "kodak_portra_800_push2" -> R.drawable.stock_kodak_portra_800
    "kodak_gold_200" -> R.drawable.stock_kodak_gold_200
    "kodak_ektar_100" -> R.drawable.stock_kodak_ektar_100
    "kodak_ultramax_400" -> R.drawable.stock_kodak_ultramax_400
    "kodak_ektachrome_100" -> R.drawable.stock_kodak_ektachrome_100
    "kodak_kodachrome_64" -> R.drawable.stock_kodak_kodachrome_64
    "fujifilm_c200" -> R.drawable.stock_fujifilm_c200
    "fujifilm_pro_400h" -> R.drawable.stock_fujifilm_pro_400h
    "fujifilm_xtra_400" -> R.drawable.stock_fujifilm_xtra_400
    "fujifilm_provia_100f" -> R.drawable.stock_fujifilm_provia_100f
    "fujifilm_velvia_100" -> R.drawable.stock_fujifilm_velvia_100
    "fujifilm_neopan_acros_100_ii" -> R.drawable.stock_fujifilm_neopan_acros_100_ii
    "ilford_hp5_plus_400" -> R.drawable.stock_ilford_hp5_plus_400
    "kodak_tmax_100" -> R.drawable.stock_kodak_tmax_100
    "kodak_tri_x_400" -> R.drawable.stock_kodak_tri_x_400
    "kodak_vision3_50d" -> R.drawable.stock_kodak_vision3_50d
    "kodak_vision3_200t" -> R.drawable.stock_kodak_vision3_200t
    "kodak_vision3_250d" -> R.drawable.stock_kodak_vision3_250d
    "kodak_vision3_500t" -> R.drawable.stock_kodak_vision3_500t
    "cinestill_800t" -> R.drawable.stock_cinestill_800t
    "kodak_verita_200d" -> R.drawable.stock_kodak_verita_200d
    "ilford_multigrade_iv_rc" -> R.drawable.stock_ilford_multigrade_iv_rc
    "fujifilm_crystal_archive_typeii" -> R.drawable.stock_fujifilm_crystal_archive_typeii
    "kodak_ektacolor_edge" -> R.drawable.stock_kodak_ektacolor_edge
    "kodak_endura_premier" -> R.drawable.stock_kodak_endura_premier
    "kodak_portra_endura" -> R.drawable.stock_kodak_portra_endura
    "kodak_supra_endura" -> R.drawable.stock_kodak_supra_endura
    "kodak_ultra_endura" -> R.drawable.stock_kodak_ultra_endura
    "kodak_2383" -> R.drawable.stock_kodak_2383
    "kodak_2393" -> R.drawable.stock_kodak_2393
    else -> null
}

/** 全部素材图形为自绘矢量，直接交给 Compose 的 painter 渲染，不再解码位图。 */
@Composable
internal fun StockArtwork(id: String, modifier: Modifier = Modifier) {
    val resource = stockArtworkResource(id)
    Box(modifier) {
        if (resource != null) {
            Image(
                painter = painterResource(resource),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
