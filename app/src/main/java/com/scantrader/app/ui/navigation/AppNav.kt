package com.scantrader.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
import com.scantrader.app.ui.onboarding.OnboardingScreen
import com.scantrader.app.ui.onboarding.OnboardingViewModel
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
                onTracker = { nav.navigate("tracker") },
                onAbout = { nav.navigate("about") },
                onShowOnboarding = {
                    nav.navigate("onboarding") { launchSingleTop = true }
                },
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
            val confirmVm: ConfirmViewModel = viewModel(factory = ConfirmViewModel.factory(app))
            // Confirm stays in this destination. A separate route would stop the
            // scanner lifecycle, detach PreviewView, and rebind the camera at 1×.
            var showConfirm by rememberSaveable { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                ScanScreen(
                    viewModel = vm,
                    collectionId = collectionId,
                    confirmVisible = showConfirm,
                    onBack = { nav.popBackStack() },
                    onCaptured = { showConfirm = true },
                    onManualSearch = {
                        app.scanSession.resetResults()
                        showConfirm = true
                    },
                )
                if (showConfirm) {
                    BackHandler {
                        showConfirm = false
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .zIndex(1f)
                            .background(MaterialTheme.colorScheme.background)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { },
                    ) {
                        ConfirmScreen(
                            viewModel = confirmVm,
                            onBack = { showConfirm = false },
                            onSaved = {
                                app.scanSession.resetResults()
                                showConfirm = false
                            },
                        )
                    }
                }
            }
        }
        composable("settings") {
            val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))
            SettingsScreen(
                viewModel = vm,
                onBack = { nav.popBackStack() },
                onReviewIntro = { nav.navigate("onboarding") { launchSingleTop = true } },
            )
        }
        composable("onboarding") {
            val vm: OnboardingViewModel = viewModel(factory = OnboardingViewModel.factory(app))
            OnboardingScreen(viewModel = vm, onDone = { nav.popBackStack() })
        }
        composable("tracker") {
            com.scantrader.app.tracker.TrackerScreen(link = app.tracker, onBack = { nav.popBackStack() })
        }
        composable("about") {
            AboutScreen(onBack = { nav.popBackStack() })
        }
    }
}
