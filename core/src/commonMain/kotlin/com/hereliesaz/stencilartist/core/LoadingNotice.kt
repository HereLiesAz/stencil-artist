package com.hereliesaz.stencilartist.core

/**
 * Lifecycle of a processing notice. Each new or replacement process receives the next tip,
 * regardless of whether its predecessor's overlay is still visible. Completion leaves that
 * process's tip open; only an acknowledgement after completion can hide it.
 */
data class LoadingNotice private constructor(
    val tipIndex: Int,
    val visible: Boolean,
    val processing: Boolean,
) {
    constructor() : this(-1, false, false)

    /** Start or restart work; advance exactly once for every new processing generation. */
    fun begin(tipCount: Int): LoadingNotice {
        require(tipCount > 0)
        return LoadingNotice((tipIndex + 1) % tipCount, true, true)
    }

    /** Current work ended. Keep its tip visible until acknowledged. */
    fun finish(): LoadingNotice = if (processing) copy(processing = false) else this

    /** A tap or Continue may dismiss only a completed notice. */
    fun dismiss(): LoadingNotice = if (visible && !processing) copy(visible = false) else this
}
