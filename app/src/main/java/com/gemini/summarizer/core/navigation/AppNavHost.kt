package com.gemini.summarizer.core.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.gemini.summarizer.presentation.accounts.AccountsScreen
import com.gemini.summarizer.presentation.history.HistoryScreen
import com.gemini.summarizer.presentation.home.HomeScreen
import com.gemini.summarizer.presentation.models.ModelsScreen
import com.gemini.summarizer.presentation.settings.SettingsScreen

@Composable
fun AppNavHost(navController: NavHostController) {
    Scaffold(
        bottomBar = {
            BottomNavBar(navController = navController)
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen()
            }
            composable(Screen.Accounts.route) {
                AccountsScreen()
            }
            composable(Screen.Models.route) {
                ModelsScreen()
            }
            composable(Screen.History.route) {
                HistoryScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
        }
    }
}

