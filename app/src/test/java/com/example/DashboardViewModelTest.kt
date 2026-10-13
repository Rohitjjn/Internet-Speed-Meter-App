package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DashboardViewModelTest {

    private lateinit var application: Application
    private lateinit var viewModel: DashboardViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        viewModel = DashboardViewModel(application)
    }

    @Test
    fun testInitialState() {
        // No addon feature anymore: only real mobile / wifi / total states.
        assertEquals(0.0, viewModel.mobileData.value.total, 0.0)
        assertEquals(0.0, viewModel.wifiData.value.total, 0.0)
        assertEquals(0.0, viewModel.totalUsage.value.total, 0.0)
        // No limit configured by default -> no progress.
        assertNull(viewModel.limitProgress.value)
        assertFalse(viewModel.showResetDialog.value)
    }

    @Test
    fun testResetDialogFlow() {
        viewModel.requestResetTodayUsage()
        assertTrue(viewModel.showResetDialog.value)

        viewModel.dismissResetDialog()
        assertFalse(viewModel.showResetDialog.value)
    }

    @Test
    fun testResetDoesNotCrash() = runTest {
        viewModel.confirmResetTodayUsage()
        // Dialog must be closed after confirming.
        assertFalse(viewModel.showResetDialog.value)
    }
}
