package com.streamo.app

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.streamo.app.data.LibraryStore
import com.streamo.app.data.SponsorBlock
import com.streamo.app.data.VideoItem
import com.streamo.app.player.PlayerConnection
import com.streamo.app.download.Downloads
import com.streamo.app.ui.ChannelScreen
import com.streamo.app.ui.DownloadsScreen
import com.streamo.app.ui.LocalPlaylistScreen
import com.streamo.app.ui.YouTubePlaylistScreen
import com.streamo.app.ui.HomeScreen
import com.streamo.app.ui.LibraryScreen
import com.streamo.app.ui.MiniPlayer
import com.streamo.app.ui.Nav
import com.streamo.app.ui.PlayerSurface
import com.streamo.app.ui.SearchScreen
import com.streamo.app.ui.SettingsScreen
import com.streamo.app.ui.StreamoTheme
import com.streamo.app.ui.SubscriptionsScreen
import com.streamo.app.ui.WatchScreen

class MainActivity : ComponentActivity() {

    private var watchExpanded by mutableStateOf(false)
    private var fullscreen by mutableStateOf(false)
    private var inPip by mutableStateOf(false)
    private var openRequest by mutableStateOf<String?>(null)

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PlayerConnection.connect(this)
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        handleShare(intent)
        openRequest = intent?.getStringExtra(EXTRA_OPEN)
        Downloads.resumeIfNeeded(this)

        setContent {
            StreamoTheme {
                if (inPip) {
                    PlayerSurface(Modifier.fillMaxSize(), controls = false)
                } else {
                    AppContent()
                }
            }
        }
    }

    @Composable
    private fun AppContent() {
        val navController = rememberNavController()
        val now by PlayerConnection.nowPlaying.collectAsState()
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        // Turning the phone sideways while watching goes fullscreen, like YouTube.
        LaunchedEffect(landscape) {
            if (watchExpanded && now != null) fullscreen = landscape
        }
        LaunchedEffect(fullscreen) { applyFullscreen(fullscreen) }
        LaunchedEffect(now == null) { if (now == null) { watchExpanded = false; fullscreen = false } }

        val nav = Nav(
            openVideo = { video -> play(video) },
            openChannel = { url -> navController.navigate("channel/${Uri.encode(url)}") },
            openSearch = { navController.navigate("search") },
            openSettings = { navController.navigate("settings") },
            openPlaylist = { id -> navController.navigate("playlist/$id") },
            openYouTubePlaylist = { url -> navController.navigate("ytplaylist/${Uri.encode(url)}") },
            openDownloads = { navController.navigate("downloads") },
            back = { navController.popBackStack() },
        )

        // The download notification opens the Downloads page.
        LaunchedEffect(openRequest) {
            if (openRequest == "downloads") {
                watchExpanded = false
                navController.navigate("downloads") { launchSingleTop = true }
                openRequest = null
            }
        }

        BackHandler(enabled = fullscreen || watchExpanded) {
            if (fullscreen) fullscreen = false else watchExpanded = false
        }

        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).statusBarsPadding()) {
                    AppNavHost(navController, nav)
                }
                val current = now
                if (current != null && !watchExpanded) {
                    MiniPlayer(current) { watchExpanded = true }
                }
                BottomBar(navController)
            }

            val current = now
            AnimatedVisibility(
                visible = current != null && watchExpanded,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                if (current != null) {
                    WatchScreen(
                        now = current,
                        fullscreen = fullscreen,
                        onToggleFullscreen = { fullscreen = !fullscreen },
                        onCollapse = { watchExpanded = false },
                        onPip = ::enterPip,
                        nav = nav,
                    )
                }
            }
        }
    }

    @Composable
    private fun AppNavHost(navController: NavHostController, nav: Nav) {
        val pad = PaddingValues()
        // No fade animations between screens: switching should feel instant.
        NavHost(
            navController,
            startDestination = "home",
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable("home") { HomeScreen(nav, pad) }
            composable("subs") { SubscriptionsScreen(nav, pad) }
            composable("library") { LibraryScreen(nav, pad) }
            composable("search") { SearchScreen(nav, pad) }
            composable("settings") { SettingsScreen(nav, pad) }
            composable("downloads") { DownloadsScreen(nav, onPlayed = { watchExpanded = true }, pad) }
            composable("playlist/{id}") { entry -> LocalPlaylistScreen(entry.arguments?.getString("id").orEmpty(), nav, pad) }
            composable("ytplaylist/{url}") { entry ->
                YouTubePlaylistScreen(Uri.decode(entry.arguments?.getString("url").orEmpty()), nav, pad)
            }
            composable("channel/{url}", arguments = listOf(navArgument("url") { type = NavType.StringType })) { entry ->
                ChannelScreen(Uri.decode(entry.arguments?.getString("url").orEmpty()), nav, pad)
            }
        }
    }

    @Composable
    private fun BottomBar(navController: NavHostController) {
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
                        watchExpanded = false
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

    private fun play(video: VideoItem) {
        PlayerConnection.play(video)
        watchExpanded = true
    }

    // ---------- Picture-in-picture & background ----------

    private fun pipParams(): PictureInPictureParams =
        PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()

    private fun enterPip() {
        if (PlayerConnection.nowPlaying.value != null) enterPictureInPictureMode(pipParams())
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Pressing Home while watching keeps the video in a small floating window.
        val now = PlayerConnection.nowPlaying.value
        if (watchExpanded && now?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onStart() {
        super.onStart()
        PlayerConnection.setVideoEnabled(true)
    }

    override fun onStop() {
        super.onStop()
        // App hidden or screen off: keep the sound, stop downloading the picture.
        if (LibraryStore.settings.value.backgroundPlay) PlayerConnection.setVideoEnabled(false)
        else PlayerConnection.pause()
    }

    private fun applyFullscreen(on: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    // ---------- "Share to Streamo" from the YouTube app or browser ----------

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let { openRequest = it }
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val id = SponsorBlock.videoId(text) ?: return
        play(VideoItem("https://www.youtube.com/watch?v=$id", "Loading…", "", null, null, 0, -1, null))
    }

    companion object {
        /** Which page to open, e.g. "downloads" from the download notification. */
        const val EXTRA_OPEN = "open"
    }
}
