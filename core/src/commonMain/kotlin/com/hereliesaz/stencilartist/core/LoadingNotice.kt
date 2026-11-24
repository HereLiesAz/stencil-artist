package com.hereliesaz.stencilartist.core

/**
 * Lifecycle of one visible processing notice, independent of the processing job's lifetime.
 * A replacement job keeps the current tip while the notice is visible. Completion leaves the
 * notice open; only an acknowledgement after completion can hide it.
 */
data class LoadingNotice private constructor(
    val tipIndex: Int,
    val visible: Boolean,
    val processing: Boolean,
) {
    constructor() : this(-1, false, false)

    /** Start work; advance the tip only when opening a previously hidden notice. */
    fun begin(tipCount: Int): LoadingNotice {
        require(tipCount > 0)
        return if (visible) copy(processing = true)
        else LoadingNotice((tipIndex + 1) % tipCount, true, true)
    }

    /** Current work ended. Keep its tip visible until acknowledged. */
    fun finish(): LoadingNotice = if (processing) copy(processing = false) else this

    /** A tap or Continue may dismiss only a completed notice. */
    fun dismiss(): LoadingNotice = if (visible && !processing) copy(visible = false) else this
}
