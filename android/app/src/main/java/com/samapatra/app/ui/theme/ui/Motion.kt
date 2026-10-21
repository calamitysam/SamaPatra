package com.samapatra.app.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.samapatra.app.ui.theme.LocalStudentSettings

/** True when the device's animations are off, or the student asked for less movement. */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    val system = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
    return system || LocalStudentSettings.current.reduceMotion
}