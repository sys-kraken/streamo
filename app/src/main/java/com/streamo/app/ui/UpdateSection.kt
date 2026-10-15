package com.streamo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.streamo.app.BuildConfig
import com.streamo.app.StreamoApp
import com.streamo.app.data.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Updater.Release) : UpdateState
    data class Downloading(val release: Updater.Release, val progress: Float) : UpdateState
    data class Ready(val release: Updater.Release, val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** "Check for updates": newest release from GitHub, downloaded and installed in-app. */
@Composable
fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updater = remember { Updater(StreamoApp.http) }
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    fun check() = scope.launch {
        state = UpdateState.Checking
        state = try {
            val release = updater.latest()
            if (release != null && updater.isNewer(release)) UpdateState.Available(release) else UpdateState.UpToDate
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateState.Failed("Couldn't reach GitHub. Check your internet and try again.")
        }
    }

    fun download(release: Updater.Release) = scope.launch {
        state = UpdateState.Downloading(release, 0f)
        state = try {
            val apk = updater.download(context, release) { p -> state = UpdateState.Downloading(release, p) }
            Updater.install(context, apk)
            UpdateState.Ready(release, apk)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateState.Failed("The download didn't finish. Try again.")
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SystemUpdate, null, tint = Ember)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Streamo ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (val s = state) {
                        UpdateState.Idle -> "Updates come from github.com/${Updater.REPO}"
                        UpdateState.Checking -> "Checking for updates…"
                        UpdateState.UpToDate -> "You have the latest version."
                        is UpdateState.Available -> "Version ${s.release.version} is available!"
                        is UpdateState.Downloading -> "Downloading ${s.release.version}… ${(s.progress * 100).toInt()}%"
                        is UpdateState.Ready -> "Downloaded. Tap Install to finish."
                        is UpdateState.Failed -> s.message
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is UpdateState.Available) Kelp else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state is UpdateState.Checking) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Ember)
            }
        }

        when (val s = state) {
            is UpdateState.Available -> {
                if (s.release.notes.isNotBlank()) {
                    Text(
                        s.release.notes,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                    )
                }
                Button(
                    onClick = { download(s.release) },
                    colors = ButtonDefaults.buttonColors(containerColor = Ember, contentColor = Ink),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Download and install") }
            }
            is UpdateState.Downloading -> LinearProgressIndicator(
                progress = { s.progress }, color = Ember, modifier = Modifier.fillMaxWidth().height(6.dp),
            )
            is UpdateState.Ready -> Button(
                onClick = { Updater.install(context, s.apk) },
                colors = ButtonDefaults.buttonColors(containerColor = Ember, contentColor = Ink),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Install") }
            UpdateState.Checking -> {}
            else -> OutlinedButton(onClick = { check() }, modifier = Modifier.fillMaxWidth()) {
                Text("Check for updates")
            }
        }
    }
}
