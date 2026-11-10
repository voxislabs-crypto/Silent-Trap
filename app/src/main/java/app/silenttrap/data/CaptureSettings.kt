package app.silenttrap.data

data class CaptureSettings(
    val vadThreshold: Float = 0.25f,
    val preRollMs: Int = 3_000,
    val postRollMs: Int = 3_000,
    val gain: Float = 12f,
    val highPass: Boolean = true,
    val whisperMakeup: Boolean = true,
    val autoUpload: Boolean = true,
    val keepLocal: Int = 0,
    val acceptedNotice: Boolean = false,
) {
    fun sanitized(): CaptureSettings = copy(
        vadThreshold = vadThreshold.coerceIn(0.10f, 0.90f),
        preRollMs = preRollMs.coerceIn(500, 10_000),
        postRollMs = postRollMs.coerceIn(500, 10_000),
        gain = gain.coerceIn(1f, 32f),
        keepLocal = keepLocal.coerceIn(0, 50),
    )
}
