package com.raahi.app.core.navigation

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.raahi.app.core.designsystem.MainTab
import com.raahi.app.core.model.FindTarget
import com.raahi.app.core.permissions.openAppSettings
import com.raahi.app.data.AppSettings
import com.raahi.app.feature.diagnostics.DiagnosticsScreen
import com.raahi.app.feature.errors.ConnectionStatusScreen
import com.raahi.app.feature.errors.ErrorScreen
import com.raahi.app.feature.findobject.FindSomethingScreen
import com.raahi.app.feature.findobject.QuickCommandsScreen
import com.raahi.app.feature.findobject.WhatsAheadScreen
import com.raahi.app.feature.guidance.GuidanceScreen
import com.raahi.app.feature.guidance.SessionViewModel
import com.raahi.app.feature.guidance.rememberSessionStarter
import com.raahi.app.feature.help.AboutScreen
import com.raahi.app.feature.help.HelpScreen
import com.raahi.app.feature.help.PrivacyScreen
import com.raahi.app.feature.history.HistoryScreen
import com.raahi.app.feature.history.SessionDetailsScreen
import com.raahi.app.feature.history.SessionSummaryScreen
import com.raahi.app.feature.home.HomeScreen
import com.raahi.app.feature.onboarding.GetStartedScreen
import com.raahi.app.feature.onboarding.OnboardingPage
import com.raahi.app.feature.onboarding.OnboardingScreen
import com.raahi.app.feature.onboarding.PermissionsScreen
import com.raahi.app.feature.onboarding.SplashScreen
import com.raahi.app.feature.settings.AccessibilitySettingsScreen
import com.raahi.app.feature.settings.AppearanceSettingsScreen
import com.raahi.app.feature.settings.ConnectionSettingsScreen
import com.raahi.app.feature.settings.GuidanceSettingsScreen
import com.raahi.app.feature.settings.SettingsOverviewScreen
import com.raahi.app.feature.settings.SettingsViewModel
import com.raahi.app.feature.settings.VoiceAudioSettingsScreen
import com.raahi.app.feature.voice.VoiceCommandScreen
import com.raahi.app.RaahiApp
import com.raahi.app.core.voice.UiRequest
import com.raahi.app.domain.SessionKind

private fun NavController.goHome() {
    navigate(Routes.HOME) {
        popUpTo(Routes.HOME) { inclusive = false }
        launchSingleTop = true
    }
}

private fun NavController.toTab(tab: MainTab) {
    val route = when (tab) {
        MainTab.HOME -> Routes.HOME
        MainTab.HISTORY -> Routes.HISTORY
        MainTab.SETTINGS -> Routes.SETTINGS
    }
    navigate(route) {
        popUpTo(Routes.HOME) { inclusive = false }
        launchSingleTop = true
    }
}

private fun NavController.toError(kind: ErrorKind, target: String? = null) = navigate(Routes.error(kind, target)) { launchSingleTop = true }

/** Routes where voice commands must not navigate (the user hasn't finished setting up yet). */
private val SETUP_ROUTES = setOf(
    Routes.SPLASH, Routes.ONB_WELCOME, Routes.ONB_VOICE, Routes.ONB_SURROUNDINGS, Routes.ONB_GESTURES,
    Routes.GET_STARTED, Routes.PERMISSIONS,
)

@Composable
fun RaahiNavHost(settings: AppSettings) {
    val nav = rememberNavController()

    // ------------------------------------------------------------------ voice commands ("Raahi, settings")
    val container = (LocalContext.current.applicationContext as RaahiApp).container
    val voiceSessionVm: SessionViewModel = viewModel()
    val startGuidanceByVoice by rememberUpdatedState(
        rememberSessionStarter(
            voiceSessionVm,
            { nav.navigate(Routes.GUIDANCE) { launchSingleTop = true } },
            { nav.toError(it) },
        ),
    )
    LaunchedEffect(nav) {
        container.commands.requests.collect { req ->
            val here = nav.currentDestination?.route
            if (here == null || here in SETUP_ROUTES) return@collect
            // a command spoken on the Voice screen replaces it (Back must not reopen the microphone screen)
            if (here == Routes.VOICE && req !is UiRequest.Back) nav.popBackStack()
            when (req) {
                is UiRequest.Navigate -> when (req.route) {
                    Routes.HOME -> nav.goHome()
                    Routes.HISTORY -> nav.toTab(MainTab.HISTORY)
                    Routes.SETTINGS -> nav.toTab(MainTab.SETTINGS)
                    else -> if (nav.currentDestination?.route != req.route) nav.navigate(req.route) { launchSingleTop = true }
                }
                UiRequest.Back -> if (here != Routes.HOME) nav.popBackStack()
                UiRequest.StartGuidance -> if (here != Routes.GUIDANCE) startGuidanceByVoice(SessionKind.GUIDANCE, null)
            }
            Unit
        }
    }
    val enter: () -> EnterTransition = { if (settings.reduceMotion) EnterTransition.None else fadeIn() }
    val exit: () -> ExitTransition = { if (settings.reduceMotion) ExitTransition.None else fadeOut() }

    NavHost(
        navController = nav,
        startDestination = Routes.SPLASH,
        enterTransition = { enter() },
        exitTransition = { exit() },
        popEnterTransition = { enter() },
        popExitTransition = { exit() },
    ) {
        // ------------------------------------------------------------------ onboarding
        composable(Routes.SPLASH) {
            SplashScreen {
                nav.navigate(if (settings.onboardingDone) Routes.HOME else Routes.ONB_WELCOME) { popUpTo(Routes.SPLASH) { inclusive = true } }
            }
        }
        composable(Routes.ONB_WELCOME) {
            OnboardingScreen(OnboardingPage.WELCOME, { nav.navigate(Routes.ONB_VOICE) }, { nav.navigate(Routes.GET_STARTED) })
        }
        composable(Routes.ONB_VOICE) {
            OnboardingScreen(OnboardingPage.VOICE, { nav.navigate(Routes.ONB_SURROUNDINGS) }, { nav.navigate(Routes.GET_STARTED) })
        }
        composable(Routes.ONB_SURROUNDINGS) {
            OnboardingScreen(OnboardingPage.SURROUNDINGS, { nav.navigate(Routes.ONB_GESTURES) }, { nav.navigate(Routes.GET_STARTED) })
        }
        composable(Routes.ONB_GESTURES) {
            OnboardingScreen(OnboardingPage.GESTURES, { nav.navigate(Routes.GET_STARTED) }, { nav.navigate(Routes.GET_STARTED) })
        }
        composable(Routes.GET_STARTED) {
            GetStartedScreen(
                onStart = { nav.navigate(Routes.PERMISSIONS) },
                onReplay = { nav.navigate(Routes.ONB_WELCOME) { popUpTo(Routes.GET_STARTED) { inclusive = true } } },
            )
        }
        composable(Routes.PERMISSIONS) {
            val vm: SettingsViewModel = viewModel()
            PermissionsScreen(onContinue = {
                vm.setOnboardingDone()
                nav.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } }
            })
        }

        // ------------------------------------------------------------------ main tabs
        composable(Routes.HOME) {
            HomeScreen(
                onStarted = { nav.navigate(Routes.GUIDANCE) },
                onError = { nav.toError(it) },
                onFind = { nav.navigate(Routes.FIND) },
                onWhatsAhead = { nav.navigate(Routes.WHATS_AHEAD) },
                onQuickCommands = { nav.navigate(Routes.QUICK) },
                onVoice = { nav.navigate(Routes.VOICE) },
                onConnection = { nav.navigate(Routes.CONNECTION_STATUS) },
                onTab = nav::toTab,
            )
        }
        composable(Routes.HISTORY) {
            val vm: SessionViewModel = viewModel()
            val start = rememberSessionStarter(vm, { nav.navigate(Routes.GUIDANCE) }, { nav.toError(it) })
            HistoryScreen(
                onTab = nav::toTab,
                onOpen = { nav.navigate(Routes.details(it)) },
                onStartGuidance = { start(com.raahi.app.domain.SessionKind.GUIDANCE, null) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsOverviewScreen(
                onTab = nav::toTab,
                onAccessibility = { nav.navigate(Routes.SET_ACCESSIBILITY) },
                onVoice = { nav.navigate(Routes.SET_VOICE) },
                onGuidance = { nav.navigate(Routes.SET_GUIDANCE) },
                onConnection = { nav.navigate(Routes.SET_CONNECTION) },
                onAppearance = { nav.navigate(Routes.SET_APPEARANCE) },
                onDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
                onHelp = { nav.navigate(Routes.HELP) },
                onPrivacy = { nav.navigate(Routes.PRIVACY) },
                onAbout = { nav.navigate(Routes.ABOUT) },
            )
        }

        // ------------------------------------------------------------------ guidance
        composable(Routes.GUIDANCE) {
            GuidanceScreen(
                onFinished = { hasSummary ->
                    if (hasSummary) nav.navigate(Routes.SUMMARY) { popUpTo(Routes.HOME) { inclusive = false } } else nav.goHome()
                },
                onError = { kind, target -> nav.toError(kind, target) },
            )
        }
        composable(Routes.VOICE) {
            VoiceCommandScreen(
                onStarted = { nav.navigate(Routes.GUIDANCE) { popUpTo(Routes.VOICE) { inclusive = true } } },
                onError = { nav.toError(it) },
                onClose = { nav.popBackStack() },
                onMicDenied = { nav.toError(ErrorKind.MIC_PERMISSION) },
            )
        }
        composable(Routes.FIND) {
            FindSomethingScreen(
                onBack = { nav.popBackStack() },
                onVoice = { nav.navigate(Routes.VOICE) },
                onStarted = { nav.navigate(Routes.GUIDANCE) { popUpTo(Routes.FIND) { inclusive = true } } },
                onError = { nav.toError(it) },
            )
        }
        composable(Routes.WHATS_AHEAD) {
            WhatsAheadScreen(onBack = { nav.popBackStack() }, onError = { nav.toError(it) })
        }
        composable(Routes.QUICK) {
            QuickCommandsScreen(
                onBack = { nav.popBackStack() },
                onWhatsAhead = { nav.navigate(Routes.WHATS_AHEAD) },
                onVoice = { nav.navigate(Routes.VOICE) },
                onStarted = { nav.navigate(Routes.GUIDANCE) { popUpTo(Routes.QUICK) { inclusive = true } } },
                onError = { nav.toError(it) },
            )
        }
        composable(Routes.SUMMARY) {
            SessionSummaryScreen(onDone = { nav.goHome() }, onDetails = { nav.navigate(Routes.details(it)) })
        }
        composable(Routes.DETAILS, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            SessionDetailsScreen(id = Uri.decode(entry.arguments?.getString("id").orEmpty()), onBack = { nav.popBackStack() })
        }

        // ------------------------------------------------------------------ settings pages
        composable(Routes.SET_ACCESSIBILITY) { AccessibilitySettingsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SET_VOICE) { VoiceAudioSettingsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SET_GUIDANCE) { GuidanceSettingsScreen(onBack = { nav.popBackStack() }, onHelp = { nav.navigate(Routes.HELP) }) }
        composable(Routes.SET_CONNECTION) { ConnectionSettingsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SET_APPEARANCE) { AppearanceSettingsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.CONNECTION_STATUS) { ConnectionStatusScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.HELP) { HelpScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.PRIVACY) { PrivacyScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }

        // ------------------------------------------------------------------ errors
        composable(
            Routes.ERROR,
            arguments = listOf(
                navArgument("kind") { type = NavType.StringType },
                navArgument("target") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val kind = runCatching { ErrorKind.valueOf(entry.arguments?.getString("kind").orEmpty()) }.getOrDefault(ErrorKind.GENERIC)
            val target = entry.arguments?.getString("target")?.let { Uri.decode(it) }
            val context = LocalContext.current
            val vm: SessionViewModel = viewModel()
            ErrorScreen(
                kind = kind,
                target = target,
                onPrimary = {
                    when (kind) {
                        ErrorKind.CAMERA_PERMISSION, ErrorKind.MIC_PERMISSION -> context.openAppSettings()
                        ErrorKind.SERVER_UNAVAILABLE, ErrorKind.CONNECTION -> { vm.reconnect(); nav.popBackStack() }
                        ErrorKind.CAMERA_FAILED -> nav.popBackStack()
                        ErrorKind.NO_SOUND -> vm.testAudio()
                        ErrorKind.NOT_FOUND -> {
                            FindTarget.entries.firstOrNull { it.label == target }?.let { vm.session.requestFind(it) }
                            nav.popBackStack()
                        }
                        ErrorKind.GENERIC -> {
                            vm.end()
                            nav.navigate(Routes.SPLASH) { popUpTo(0) { inclusive = true } }
                        }
                    }
                },
                onHome = {
                    vm.end()
                    nav.goHome()
                },
            )
        }
    }
}
