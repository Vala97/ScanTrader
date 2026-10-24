package com.scantrader.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.scantrader.app.ScanTraderApp
import com.scantrader.app.ui.CollectionViewModel
import com.scantrader.app.ui.ConfirmViewModel
import com.scantrader.app.ui.HomeViewModel
import com.scantrader.app.ui.ScanViewModel
import com.scantrader.app.ui.SettingsViewModel
import com.scantrader.app.ui.about.AboutScreen
import com.scantrader.app.ui.collection.CollectionScreen
import com.scantrader.app.ui.confirm.ConfirmScreen
import com.scantrader.app.ui.home.HomeScreen
import com.scantrader.app.ui.scan.ScanScreen
import com.scantrader.app.ui.settings.SettingsScreen

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val app = LocalContext.current.applicationContext as ScanTraderApp

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(app))
            HomeScreen(
                viewModel = vm,
                onOpenCollection = { nav.navigate("collection/$it") },
                onScan = { nav.navigate("scan?collectionId=") },
                onSettings = { nav.navigate("settings") },
                onAbout = { nav.navigate("about") },
            )
        }
        composable(
            "collection/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            val vm: CollectionViewModel = viewModel(factory = CollectionViewModel.factory(app, id))
            CollectionScreen(
                viewModel = vm,
                onBack = { nav.popBackStack() },
                onScan = {
                    app.scanSession.targetCollectionId = id
                    nav.navigate("scan?collectionId=$id")
                },
            )
        }
        composable(
            "scan?collectionId={collectionId}",
            arguments = listOf(
                navArgument("collectionId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val raw = entry.arguments?.getString("collectionId")
            val collectionId = raw?.toLongOrNull() ?: app.scanSession.targetCollectionId
            val vm: ScanViewModel = viewModel(factory = ScanViewModel.factory(app))
            ScanScreen(
                viewModel = vm,
                collectionId = collectionId,
                onBack = { nav.popBackStack() },
                onCaptured = { nav.navigate("confirm") },
                onManualSearch = {
                    app.scanSession.resetResults()
                    nav.navigate("confirm")
                },
            )
        }
        // Dialog keeps the scan destination STARTED and composed. A normal
        // destination would stop that lifecycle, detach PreviewView, and rebind
        // the camera at 1× — which shifts zoom and the card guide after save.
        dialog(
            "confirm",
            dialogProperties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
            ),
        ) {
            val vm: ConfirmViewModel = viewModel(factory = ConfirmViewModel.factory(app))
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                ConfirmScreen(
                    viewModel = vm,
                    onBack = { nav.popBackStack() },
                    onSaved = {
                        // Pop confirm only. Scan stays underneath with the same
                        // ViewModel and the same camera binding, so the chosen
                        // game, lens, and zoom stay armed.
                        app.scanSession.resetResults()
                        nav.popBackStack()
                    },
                )
            }
        }
        composable("settings") {
            val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))
            SettingsScreen(viewModel = vm, onBack = { nav.popBackStack() })
        }
        composable("about") {
            AboutScreen(onBack = { nav.popBackStack() })
        }
    }
}
