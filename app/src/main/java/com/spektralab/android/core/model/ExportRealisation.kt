package com.spektralab.android.core.model

/** Explicit first-render SplitMix64 realisation, matching the existing fresh Windows pipeline. */
internal data class ExportRealisation(val film: Long, val print: Long) {
    init { require(film in 0..0xffffffffL && print in 0..0xffffffffL) }
    val cacheSeed: Long get() = (film shl 32) or print

    companion object {
        // Lower 32 bits of the first two fresh_seed() draws from 0x243F6A8885A308D3.
        // The print side is the first draw when film grain is disabled.
        fun fresh(params: EditorParams) = ExportRealisation(1254007329L, if (params.grainActive) 588548489L else 1254007329L)
    }
}
