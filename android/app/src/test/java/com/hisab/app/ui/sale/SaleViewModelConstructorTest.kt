package com.hisab.app.ui.sale

import android.app.Application
import com.hisab.app.data.HisabDatabase
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.Clock

/**
 * The New Sale screen asks for its view model with `viewModel()`, and the default
 * factory builds it by looking for a constructor that takes only the `Application`.
 * `SaleViewModel` was given extra, defaulted parameters so a test can hand it an
 * in-memory database; `@JvmOverloads` is what keeps the one-argument constructor
 * alive. Losing it would not fail the build and no unit test would notice, but the
 * New Sale screen would crash the moment it opened — so this pins it down.
 */
class SaleViewModelConstructorTest {
    @Test
    fun `the default view-model factory can still find the one-argument constructor`() {
        assertNotNull(SaleViewModel::class.java.getConstructor(Application::class.java))
    }

    @Test
    fun `a test can still inject its own database and clock`() {
        assertNotNull(SaleViewModel::class.java.getConstructor(Application::class.java, HisabDatabase::class.java, Clock::class.java))
    }
}
