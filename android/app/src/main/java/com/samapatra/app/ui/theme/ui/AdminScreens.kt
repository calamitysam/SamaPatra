package com.samapatra.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.samapatra.app.data.AdminLock
import com.samapatra.app.data.ApiClient
import com.samapatra.app.data.ApiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Asks for the admin password, or makes one the first time. */
@Composable
fun AdminLoginDialog(onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val creating = remember { !AdminLock.isSet(context) }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            if (creating) {
                when {
                    password.length < 6 -> error = "Use at least 6 characters."
                    password != repeat -> error = "The two passwords don’t match."
                    else -> {
                        withContext(Dispatchers.Default) { AdminLock.set(context, password) }
                        onUnlocked()
                    }
                }
            } else {
                val wait = AdminLock.secondsLocked()
                if (wait > 0) {
                    error = "Too many wrong tries. Try again in $wait seconds."
                } else if (withContext(Dispatchers.Default) { AdminLock.check(context, password) }) {
                    onUnlocked()
                } else {
                    val left = AdminLock.secondsLocked()
                    error = if (left > 0) {
                        "Too many wrong tries. Try again in $left seconds."
                    } else {
                        "That isn’t the admin password."
                    }
                }
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (creating) "Create an admin password" else "Admin") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (creating) {
                    Text("This tablet has no admin password yet. Choose one now. You will need it to change the server address.")
                }
                Field(
                    value = password,
                    onValueChange = { password = it },
                    label = if (creating) "New password" else "Admin password",
                    isPassword = true,
                    imeAction = if (creating) ImeAction.Next else ImeAction.Done,
                    onDone = { submit() },
                )
                if (creating) {
                    Field(
                        value = repeat,
                        onValueChange = { repeat = it },
                        label = "Type it again",
                        isPassword = true,
                        imeAction = ImeAction.Done,
                        onDone = { submit() },
                    )
                }
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = !busy) {
                Text(if (creating) "Create" else "Open")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}

/** Where an admin sets the address of the server. */
@Composable
fun AdminScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var address by remember { mutableStateOf(ApiConfig.baseUrl) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }

    var newPassword by remember { mutableStateOf("") }
    var repeatPassword by remember { mutableStateOf("") }
    var passwordMessage by remember { mutableStateOf<String?>(null) }
    var passwordIsError by remember { mutableStateOf(false) }

    fun say(text: String, isError: Boolean) {
        message = text
        messageIsError = isError
    }

    fun test() {
        val cleaned = ApiConfig.clean(address)
        if (cleaned == null) {
            say("That doesn’t look like a server address. Try something like http://192.168.1.23:3000", true)
            return
        }
        if (testing) return
        scope.launch {
            testing = true
            message = null
            if (ApiClient.isReachable(cleaned)) {
                say("Connected. The server answered.", false)
            } else {
                say("Can’t reach a server at $cleaned. Check that it is running and that this tablet can reach it.", true)
            }
            testing = false
        }
    }

    fun save() {
        val cleaned = ApiConfig.clean(address)
        if (cleaned == null) {
            say("That doesn’t look like a server address. Try something like http://192.168.1.23:3000", true)
            return
        }
        ApiConfig.save(context, cleaned)
        address = cleaned
        say("Saved. The app now uses $cleaned", false)
    }

    fun reset() {
        ApiConfig.reset(context)
        address = ApiConfig.baseUrl
        say("Back to the default address.", false)
    }

    fun changePassword() {
        when {
            newPassword.length < 6 -> {
                passwordMessage = "Use at least 6 characters."
                passwordIsError = true
            }
            newPassword != repeatPassword -> {
                passwordMessage = "The two passwords don’t match."
                passwordIsError = true
            }
            else -> scope.launch {
                withContext(Dispatchers.Default) { AdminLock.set(context, newPassword) }
                newPassword = ""
                repeatPassword = ""
                passwordMessage = "Admin password changed."
                passwordIsError = false
            }
        }
    }

    ScreenFrame(title = "Admin", onBack = onBack) {
        Text(
            text = "Server address",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Where this app finds the SamaPatra server. For example http://192.168.1.23:3000 or https://your-server.example.com",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Field(
            value = address,
            onValueChange = { address = it },
            label = "Server address",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
        )
        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
                color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
            )
        }
        PrimaryButton(text = "Save", onClick = { save() }, enabled = !testing)
        SecondaryButton(
            text = if (testing) "Testing…" else "Test this address",
            onClick = { test() },
            enabled = !testing,
        )
        SecondaryButton(text = "Go back to the default address", onClick = { reset() })
        Text(
            text = "The default is ${ApiConfig.DEFAULT_URL}, which only works on the emulator.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = "Admin password",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Field(
            value = newPassword,
            onValueChange = { newPassword = it },
            label = "New password",
            isPassword = true,
        )
        Field(
            value = repeatPassword,
            onValueChange = { repeatPassword = it },
            label = "Type it again",
            isPassword = true,
            imeAction = ImeAction.Done,
            onDone = { changePassword() },
        )
        passwordMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
                color = if (passwordIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
            )
        }
        SecondaryButton(text = "Change password", onClick = { changePassword() })
    }
}