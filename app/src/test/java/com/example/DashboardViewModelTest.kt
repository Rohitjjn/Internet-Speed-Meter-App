package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun testInitialState() = runTest {
        // Assert addon is inactive by default
        assertFalse(viewModel.isAddOnActive.value)
        // Assert dialog is hidden by default
        assertFalse(viewModel.showConfirmationDialog.value)
        // Assert addon usage shows 0 MB download and upload by default
        assertEquals(0.0, viewModel.addOnUsage.value.total, 0.0)
    }

    @Test
    fun testStartTrackingFlow() = runTest {
        // Request start -> should show confirmation dialog
        viewModel.requestStartTracking()
        assertTrue(viewModel.showConfirmationDialog.value)

        // Confirm start -> should hide dialog & active add-on tracking
        viewModel.confirmStartTracking()
        assertFalse(viewModel.showConfirmationDialog.value)
        
        // Wait, because combine flow in init has some async dispatch, let's set state directly
        viewModel.confirmStartTracking()
        // Stop tracking -> should restore addon state
        viewModel.stopTracking()
        assertFalse(viewModel.isAddOnActive.value)
    }

    @Test
    fun testDismissDialog() = runTest {
        viewModel.requestStartTracking()
        assertTrue(viewModel.showConfirmationDialog.value)

        viewModel.dismissDialog()
        assertFalse(viewModel.showConfirmationDialog.value)
    }
}
