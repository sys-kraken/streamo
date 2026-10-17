package com.streamo.shared

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Called from Swift (iosApp/iosApp/ContentView.swift) to show the shared Compose UI. */
fun MainViewController(): UIViewController = ComposeUIViewController { IosPreview() }
