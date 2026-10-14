package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.data.PreferencesManager
import com.example.services.ServiceScheduler
import com.example.services.SpeedMeterService
import com.example.ui.screens.IntroScreen
import com.example.ui.screens.MainScreen
import com.example.ui.screens.SplashScreen
import com.example.ui.theme.AccentTheme
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

enum class AppStage {
    SPLASH,
    INTRO,
    MAIN
}

class MainActivity : ComponentActivity() {

    /**
     * Coming back to the app is the one moment the OS always lets it start its foreground
     * service (Android 12+ background-start rule, Android 15+ dataSync budget). So if the
     * meter was stopped in the meantime — timeout, OEM task killer — it is revived here
     * instead of staying "paused" until the next cold start.
     */
    override fun onResume() {
        super.onResume()
        if (!SpeedMeterService.isServiceRunning.value) {
            SpeedMeterService.startService(applicationContext)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Watchdog: restarts the meter if the OS ever kills it (API 35+ dataSync
        // timeout, background-start blocks, aggressive OEM battery managers).
        ServiceScheduler.schedule(applicationContext)

        setContent {
            val preferencesManager = remember { PreferencesManager(applicationContext) }
            val customDarkMode by preferencesManager.isDarkModeFlow.collectAsState(initial = null)
            val isSystemDark = isSystemInDarkTheme()
            val darkThemeActive = customDarkMode ?: isSystemDark

            val isOnboardingCompleted by preferencesManager.isOnboardingCompletedFlow.collectAsState(initial = false)
            val accentKey by preferencesManager.accentKeyFlow.collectAsState(initial = "INK")
            val scope = rememberCoroutineScope()

            MyApplicationTheme(
                darkTheme = darkThemeActive,
                accent = AccentTheme.fromKey(accentKey)
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var currentStage by remember { mutableStateOf(AppStage.SPLASH) }

                    // Start the meter as soon as the app is interactive.
                    LaunchedEffect(currentStage) {
                        if (currentStage != AppStage.SPLASH) {
                            try {
                                SpeedMeterService.startService(applicationContext)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }

                    Crossfade(
                        targetState = currentStage,
                        animationSpec = tween(durationMillis = 550),
                        label = "app_stage_crossfade"
                    ) { stage ->
                        when (stage) {
                            AppStage.SPLASH -> SplashScreen(
                                onSplashComplete = {
                                    currentStage = if (isOnboardingCompleted) AppStage.MAIN else AppStage.INTRO
                                }
                            )
                            AppStage.INTRO -> IntroScreen(
                                onComplete = {
                                    scope.launch {
                                        preferencesManager.setOnboardingCompleted(true)
                                        currentStage = AppStage.MAIN
                                    }
                                }
                            )
                            AppStage.MAIN -> MainScreen()
                        }
                    }
                }
            }
        }
    }
}
