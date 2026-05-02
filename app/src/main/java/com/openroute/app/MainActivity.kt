package com.openroute.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openroute.app.ui.MainRoute
import com.openroute.app.ui.MainViewModel
import com.openroute.app.ui.OpenRouteSplash
import com.openroute.app.ui.OpenRouteTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            OpenRouteTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    var showSplash by rememberSaveable { mutableStateOf(true) }

                    LaunchedEffect(Unit) {
                        delay(SPLASH_DURATION_MILLIS)
                        showSplash = false
                    }

                    if (showSplash) {
                        OpenRouteSplash()
                    } else {
                        MainRoute(viewModel = viewModel<MainViewModel>())
                    }
                }
            }
        }
    }

    private companion object {
        const val SPLASH_DURATION_MILLIS = 850L
    }
}
