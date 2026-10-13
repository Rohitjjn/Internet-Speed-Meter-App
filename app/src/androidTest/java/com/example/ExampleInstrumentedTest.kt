package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * It pins the *installed* package id (the applicationId in `app/build.gradle.kts`, which
 * is deliberately not the same as the Kotlin namespace), so a build-config change that
 * silently renames the app is caught here.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.aistudio.speedtracker.npxqwt", appContext.packageName)
        assertTrue(
            "the namespace (com.example) is only a Kotlin/resource namespace",
            appContext.applicationInfo.packageName.startsWith("com.")
        )
    }
}
