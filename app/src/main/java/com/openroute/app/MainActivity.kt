package com.openroute.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openroute.app.ui.MainRoute
import com.openroute.app.ui.MainViewModel
import com.openroute.app.ui.OpenRouteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            OpenRouteTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MainRoute(viewModel = viewModel())
                }
            }
        }
    }
}
