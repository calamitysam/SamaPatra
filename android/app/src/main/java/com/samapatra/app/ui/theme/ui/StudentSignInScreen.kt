package com.samapatra.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.samapatra.app.data.ApiResult
import com.samapatra.app.data.ClassRoster
import com.samapatra.app.data.SamaPatraApi
import com.samapatra.app.data.SessionStore
import com.samapatra.app.data.SignInName
import kotlinx.coroutines.launch

private enum class Step { Code, Name, Pin }

@Composable
fun StudentSignInScreen(onBack: () -> Unit, onSignedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Step.Code) }
    var code by rememberSaveable { mutableStateOf("") }
    var roster by remember { mutableStateOf<ClassRoster?>(null) }
    var chosen by remember { mutableStateOf<SignInName?>(null) }
    var pin by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun goBack() {
        error = null
        when (step) {
            Step.Code -> onBack()
            Step.Name -> step = Step.Code
            Step.Pin -> {
                pin = ""
                step = Step.Name
            }
        }
    }

    BackHandler(enabled = step != Step.Code) { goBack() }

    fun findClass() {
        if (loading) return
        if (code.length != 6) {
            error = "Class codes have 6 letters and numbers."
            return
        }
        scope.launch {
            loading = true
            error = null
            when (val result = SamaPatraApi.classRoster(code)) {
                is ApiResult.Ok -> {
                    roster = result.value
                    step = Step.Name
                }
                is ApiResult.Failure -> error = result.message
            }
            loading = false
        }
    }

    fun signIn() {
        val student = chosen ?: return
        if (loading || pin.length != 4) return
        scope.launch {
            loading = true
            error = null
            when (val result = SamaPatraApi.studentLogin(code, student.id, pin)) {
                is ApiResult.Ok -> {
                    SessionStore.signIn(result.value)
                    onSignedIn()
                }
                is ApiResult.Failure -> {
                    error = result.message
                    pin = ""
                }
            }
            loading = false
        }
    }

    val title = when (step) {
        Step.Code -> "Student sign-in"
        Step.Name -> "Which one is you?"
        Step.Pin -> "Hi, ${chosen?.name ?: ""}"
    }

    ScreenFrame(title = title, onBack = { goBack() }) {
        when (step) {
            Step.Code -> {
                Text(
                    text = "Type the class code your teacher gave you.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Field(
                    value = code,
                    onValueChange = { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) },
                    label = "Class code",
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.Characters,
                    onDone = { findClass() },
                )
                ErrorText(error)
                PrimaryButton(
                    text = "Find my class",
                    onClick = { findClass() },
                    enabled = code.length == 6,
                    loading = loading,
                )
            }
            Step.Name -> {
                val current = roster
                Text(
                    text = "${current?.className ?: "Your class"}: tap your name.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (current != null && current.students.isEmpty()) {
                    Text(
                        text = "No students have been added to this class yet. Ask your teacher.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                current?.students?.forEach { student ->
                    NameCard(student.name) {
                        chosen = student
                        pin = ""
                        error = null
                        step = Step.Pin
                    }
                }
            }
            Step.Pin -> {
                Text(
                    text = "Enter your 4-digit PIN.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PinPad(pin = pin, onChange = { pin = it })
                ErrorText(error)
                PrimaryButton(
                    text = "Sign in",
                    onClick = { signIn() },
                    enabled = pin.length == 4,
                    loading = loading,
                )
            }
        }
    }
}

@Composable
private fun NameCard(name: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}