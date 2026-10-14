package com.example

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.example.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.util.UUID

/**
 * A [PreferencesManager] on its **own** DataStore file, so a test starts from defaults and
 * can never see (or leak) another test's writes. The process-wide `Context.dataStore`
 * singleton survives between Robolectric tests in one JVM — isolating it here is what makes
 * the view-model tests order-independent. Call [close] in `@After`.
 */
class TestPreferences(context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val file = File(context.cacheDir, "test-${UUID.randomUUID()}.preferences_pb")

    val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
    val manager: PreferencesManager = PreferencesManager(context, store)

    fun close() {
        scope.cancel()
        file.delete()
    }
}
