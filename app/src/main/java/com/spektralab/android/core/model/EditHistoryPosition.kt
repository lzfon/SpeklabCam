package com.spektralab.android.core.model

/** A committed cursor. Its generation is independent of the library's edit revision. */
internal data class EditHistoryPosition(
    val editId: String,
    val generation: Long,
    val cursor: Long,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val paramsJson: String,
    val retainedStates: Int,
    val earlierStates: Long,
)
