package com.streamo.shared

import android.os.Build

actual val platformName: String = "Android ${Build.VERSION.RELEASE}"
