package com.example.globalcompat.data

fun interface GoogleCompatibilityLayerDetector {
    fun detect(components: List<SystemComponent>): GoogleCompatibilityLayerStatus
}
class DeferredGoogleCompatibilityLayerDetector : GoogleCompatibilityLayerDetector {
    override fun detect(components: List<SystemComponent>) = GoogleCompatibilityLayerStatus(
        assessment = CompatibilityLayerAssessment.NOT_ASSESSED,
        note = "首期仅记录已声明组件，不判断 microG 或其他 Google 兼容层。",
    )
}
