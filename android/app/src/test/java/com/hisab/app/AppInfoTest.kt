package com.hisab.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppInfoTest {
    @Test
    fun applicationId_matchesManifest() {
        assertEquals("com.hisab.app", AppInfo.APPLICATION_ID)
    }
}
