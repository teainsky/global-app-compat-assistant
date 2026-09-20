package com.example.globalcompat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredGoogleCompatibilityLayerDetectorTest {
    @Test
    fun `does not classify microG from package presence`() {
        val result = DeferredGoogleCompatibilityLayerDetector().detect(
            components = listOf(
                SystemComponent(
                    id = ComponentId.GOOGLE_PLAY_SERVICES,
                    displayName = "Google Play 服务",
                    packageName = "com.google.android.gms",
                    presence = ComponentPresence.PRESENT,
                    enabled = true,
                    versionName = "test",
                    versionCode = 1,
                ),
            ),
        )

        assertEquals(CompatibilityLayerAssessment.NOT_ASSESSED, result.assessment)
        assertTrue(result.note.contains("不判断 microG"))
    }
}
