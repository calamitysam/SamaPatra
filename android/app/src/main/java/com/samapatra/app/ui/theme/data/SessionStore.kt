package com.samapatra.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object SessionStore {
    var session by mutableStateOf<Session?>(null)
        private set

    /** The signed-in student's settings. Changing them changes how the whole app looks. */
    var settings by mutableStateOf(StudentSettings())
        private set

    fun signIn(newSession: Session) {
        session = newSession
        settings = newSession.student?.settings ?: StudentSettings()
    }

    fun updateSettings(newSettings: StudentSettings) {
        settings = newSettings
    }

    fun signOut() {
        session = null
        settings = StudentSettings()
    }
}