package com.streamo.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.read
import com.streamo.app.data.VideoItem
import com.streamo.app.player.PlayerConnection
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLParameter

/** What the app's frame remembers: whether the player is open or fullscreen, and pages asked for from outside. */
class AppState {
    var watchExpanded by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)

    /** Which page to open, e.g. "downloads" from the download notification. */
    var openRequest by mutableStateOf<String?>(null)

    fun play(video: VideoItem) {
        PlayerConnection.play(video)
        watchExpanded = true
    }

    /** The back button: leaves fullscreen first, then closes the player. */
    fun back() {
        if (fullscreen) fullscreen = false else watchExpanded = false
    }
}

/**
 * The whole app: pages with the bottom tabs, the mini player, and the watch page sliding over them.
 * [landscape] is whether the phone is sideways; [onPip] opens the pop-up player.
 */
@Composable
fun StreamoRoot(state: AppState, landscape: Boolean, onPip: () -> Unit) {
    val navController = rememberNavController()
    val now by PlayerConnection.nowPlaying.collectAsState()

    // Turning the phone sideways while watching goes fullscreen, like YouTube.
    LaunchedEffect(landscape) {
        if (state.watchExpanded && now != null) state.fullscreen = landscape
    }
    // A search box's cursor and its drag handle would otherwise float over the video.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(state.watchExpanded) { if (state.watchExpanded) focusManager.clearFocus(force = true) }
    LaunchedEffect(now == null) { if (now == null) { state.watchExpanded = false; state.fullscreen = false } }

    val nav = Nav(
        openVideo = { video -> state.play(video) },
        openChannel = { url -> navController.navigate("channel/${url.encodeURLParameter()}") },
        openSearch = { navController.navigate("search") },
        openSettings = { navController.navigate("settings") },
        openPlaylist = { id -> navController.navigate("playlist/$id") },
        openYouTubePlaylist = { url -> navController.navigate("ytplaylist/${url.encodeURLParameter()}") },
        openDownloads = { navController.navigate("downloads") },
        back = { navController.popBackStack() },
    )

    // The download notification opens the Downloads page.
    LaunchedEffect(state.openRequest) {
        if (state.openRequest == "downloads") {
            state.watchExpanded = false
            navController.navigate("downloads") { launchSingleTop = true }
            state.openRequest = null
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).statusBarsPadding()) {
                AppNavHost(navController, nav, state)
            }
            val current = now
            if (current != null && !state.watchExpanded) {
                MiniPlayer(current) { state.watchExpanded = true }
            }
            BottomBar(navController, state)
        }

        val current = now
        AnimatedVisibility(
            visible = current != null && state.watchExpanded,
            enter = slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(180)),
        ) {
            if (current != null) {
                WatchScreen(
                    now = current,
                    fullscreen = state.fullscreen,
                    onToggleFullscreen = { state.fullscreen = !state.fullscreen },
                    onCollapse = { state.watchExpanded = false },
                    onPip = onPip,
                    nav = nav,
                )
            }
        }
    }
}

@Composable
private fun AppNavHost(navController: NavHostController, nav: Nav, state: AppState) {
    val pad = PaddingValues()
    NavHost(
        navController,
        startDestination = "home",
        // A very short fade: smooth, but not slow like the default 0.7 s.
        enterTransition = { fadeIn(tween(150)) },
        exitTransition = { fadeOut(tween(120)) },
        popEnterTransition = { fadeIn(tween(150)) },
        popExitTransition = { fadeOut(tween(120)) },
    ) {
        composable("home") { HomeScreen(nav, pad) }
        composable("subs") { SubscriptionsScreen(nav, pad) }
        composable("library") { LibraryScreen(nav, pad) }
        composable("search") { SearchScreen(nav, pad) }
        composable("settings") { SettingsScreen(nav, pad) }
        composable("downloads") { DownloadsScreen(nav, onPlayed = { state.watchExpanded = true }, pad) }
        composable("playlist/{id}") { entry -> LocalPlaylistScreen(entry.arg("id"), nav, pad) }
        composable("ytplaylist/{url}") { entry ->
            YouTubePlaylistScreen(entry.arg("url").decodeURLPart(), nav, pad)
        }
        composable("channel/{url}", arguments = listOf(navArgument("url") { type = NavType.StringType })) { entry ->
            ChannelScreen(entry.arg("url").decodeURLPart(), nav, pad)
        }
    }
}

private fun NavBackStackEntry.arg(name: String): String = arguments?.read { getStringOrNull(name) }.orEmpty()

@Composable
private fun BottomBar(navController: NavHostController, state: AppState) {
    val route = navController.currentBackStackEntryAsState().value?.destination?.route
    NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
        listOf(
            Triple("home", "Home", Icons.Filled.Home to Icons.Outlined.Home),
            Triple("subs", "Subscriptions", Icons.Filled.Subscriptions to Icons.Outlined.Subscriptions),
            Triple("library", "Library", Icons.Filled.VideoLibrary to Icons.Outlined.VideoLibrary),
        ).forEach { (dest, label, icons) ->
            val selected = route == dest
            NavigationBarItem(
                selected = selected,
                onClick = {
                    state.watchExpanded = false
                    // A tab always opens its own main screen, closing Settings, Search, a channel, etc.
                    if (dest == "home") {
                        navController.popBackStack("home", inclusive = false)
                    } else {
                        navController.navigate(dest) {
                            popUpTo("home")
                            launchSingleTop = true
                        }
                    }
                },
                icon = { Icon(if (selected) icons.first else icons.second, label) },
                label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}
