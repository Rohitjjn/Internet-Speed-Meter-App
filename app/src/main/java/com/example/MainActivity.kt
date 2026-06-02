package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import com.example.data.PreferencesManager
import com.example.services.SpeedMeterService
import com.example.ui.screens.IntroScreen
import com.example.ui.screens.MainScreen
import com.example.ui.screens.SplashScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

enum class AppStage {
    SPLASH,
    INTRO,
    MAIN
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enable true edge-to-edge drawing
        enableEdgeToEdge()

        setContent {
            val preferencesManager = remember { PreferencesManager(applicationContext) }
            val customDarkMode by preferencesManager.isDarkModeFlow.collectAsState(initial = null)
            val isSystemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val darkThemeActive = customDarkMode ?: isSystemDark
            
            val isOnboardingCompleted by preferencesManager.isOnboardingCompletedFlow.collectAsState(initial = false)
            val scope = rememberCoroutineScope()

            MyApplicationTheme(darkTheme = darkThemeActive) {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    var currentStage by remember { mutableStateOf(AppStage.SPLASH) }

                    // Start service safely on entering Intro or Main stage (ensures window is active and in foreground)
                    LaunchedEffect(currentStage) {
                        if (currentStage != AppStage.SPLASH) {
                            try {
                                SpeedMeterService.startService(applicationContext)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }

                    // Beautiful high-end crossfade transition of App Stages
                    Crossfade(
                        targetState = currentStage,
                        animationSpec = tween(durationMillis = 600),
                        label = "app_portal_crossfade"
                    ) { stage ->
                        when (stage) {
                            AppStage.SPLASH -> {
                                SplashScreen(
                                    onSplashComplete = {
                                        if (isOnboardingCompleted) {
                                            currentStage = AppStage.MAIN
                                        } else {
                                            currentStage = AppStage.INTRO
                                        }
                                    }
                                )
                            }
                            AppStage.INTRO -> {
                                IntroScreen(
                                    onComplete = {
                                        scope.launch {
                                            preferencesManager.setOnboardingCompleted(true)
                                            currentStage = AppStage.MAIN
                                        }
                                    }
                                )
                            }
                            AppStage.MAIN -> {
                                MainScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}
