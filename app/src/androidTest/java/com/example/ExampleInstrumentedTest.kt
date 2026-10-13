package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented sanity check: the test runs in the app's own process, so the package name
 * must be the **applicationId** from `app/build.gradle.kts` (the namespace `com.example`
 * is only the Kotlin/R class package — asserting it here failed on real devices).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun appRunsInItsOwnPackage() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.aistudio.speedtracker.npxqwt", appContext.packageName)
    }
}
