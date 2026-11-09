package com.gemini.summarizer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.gemini.summarizer.core.navigation.AppNavHost
import com.gemini.summarizer.core.theme.GeminiSummarizerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GeminiSummarizerTheme(isRtl = false) {
                val navController = rememberNavController()
                AppNavHost(navController = navController)
            }
        }
    }
}
