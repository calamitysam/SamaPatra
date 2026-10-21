package com.samapatra.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.samapatra.app.data.ApiResult
import com.samapatra.app.data.SamaPatraApi
import com.samapatra.app.data.SessionStore
import kotlinx.coroutines.launch

@Composable
fun TeacherAuthScreen(onBack: () -> Unit, onSignedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var registering by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var schoolCode by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (loading) return
        val cleanEmail = email.trim()
        if (cleanEmail.isEmpty() || password.isEmpty() ||
            (registering && (name.isBlank() || schoolCode.isBlank()))
        ) {
            error = "Please fill in every box."
            return
        }
        scope.launch {
            loading = true
            error = null
            val result = if (registering) {
                SamaPatraApi.teacherRegister(name.trim(), cleanEmail, password, schoolCode.trim())
            } else {
                SamaPatraApi.teacherLogin(cleanEmail, password)
            }
            loading = false
            when (result) {
                is ApiResult.Ok -> {
                    SessionStore.signIn(result.value)
                    onSignedIn()
                }
                is ApiResult.Failure -> error = result.message
            }
        }
    }

    ScreenFrame(
        title = if (registering) "Create a teacher account" else "Teacher sign-in",
        onBack = onBack,
    ) {
        if (registering) {
            Field(
                value = name,
                onValueChange = { name = it },
                label = "Your name",
                capitalization = KeyboardCapitalization.Words,
            )
        }
        Field(
            value = email,
            onValueChange = { email = it },
            label = "Email",
            keyboardType = KeyboardType.Email,
        )
        Field(
            value = password,
            onValueChange = { password = it },
            label = "Password",
            isPassword = true,
            imeAction = if (registering) ImeAction.Next else ImeAction.Done,
            onDone = { submit() },
        )
        if (registering) {
            Text(
                text = "Use at least 8 characters.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Field(
                value = schoolCode,
                onValueChange = { schoolCode = it },
                label = "School code",
                imeAction = ImeAction.Done,
                onDone = { submit() },
            )
            Text(
                text = "The code your school gave you for teacher accounts.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ErrorText(error)
        PrimaryButton(
            text = if (registering) "Create account" else "Sign in",
            onClick = { submit() },
            loading = loading,
        )
        TextButton(onClick = {
            registering = !registering
            error = null
        }) {
            Text(if (registering) "I already have an account" else "New here? Create an account")
        }
    }
}