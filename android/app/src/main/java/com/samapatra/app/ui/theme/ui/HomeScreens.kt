package com.samapatra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samapatra.app.data.ApiResult
import com.samapatra.app.data.ClassItem
import com.samapatra.app.data.SamaPatraApi
import com.samapatra.app.data.SessionStore
import kotlinx.coroutines.launch

@Composable
fun TeacherHomeScreen(onOpenClass: (String) -> Unit, onImport: () -> Unit, onBank: () -> Unit, onSignOut: () -> Unit) {
    val session = SessionStore.session
    if (session == null) {
        LaunchedEffect(Unit) { onSignOut() }
        return
    }

    var classes by remember { mutableStateOf<List<ClassItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var showNewClass by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        error = null
        when (val result = SamaPatraApi.teacherClasses(session.token, session.user.id)) {
            is ApiResult.Ok -> classes = result.value
            is ApiResult.Failure -> error = result.message
        }
    }

    ScreenFrame(title = "Your classes") {
        Text(
            text = "Signed in as ${session.user.name}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryButton(text = "New class", onClick = { showNewClass = true })
        SecondaryButton(text = "Import a question paper", onClick = onImport)
        SecondaryButton(text = "Question bank", onClick = onBank)
        ErrorText(error)
        if (error != null) {
            TextButton(onClick = { reload++ }) { Text("Try again") }
        }
        val list = classes
        when {
            list == null && error == null -> CircularProgressIndicator(
                modifier = Modifier.semantics { contentDescription = "Loading your classes" },
            )
            list != null && list.isEmpty() -> Text(
                text = "No classes yet. Make your first class and you’ll get a code to share with students.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            list != null -> {
                Text(
                    text = "Tap a class to add students and manage PINs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                list.forEach { item -> ClassCard(item) { onOpenClass(item.id) } }
            }
        }
        TextButton(onClick = onSignOut) { Text("Sign out") }
    }

    if (showNewClass) {
        NewClassDialog(
            token = session.token,
            onDismiss = { showNewClass = false },
            onCreated = {
                showNewClass = false
                reload++
            },
        )
    }
}

@Composable
private fun ClassCard(item: ClassItem, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val students = "${item.studentCount} student${if (item.studentCount == 1) "" else "s"}"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(colors.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(item.name, style = MaterialTheme.typography.titleLarge, color = colors.onBackground)
        Text(
            text = "Grade ${item.grade} · ${item.subject} · $students",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
        )
        Text(
            text = "Class code for students",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Text(
            text = item.joinCode,
            style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 4.sp),
            color = colors.primary,
            modifier = Modifier.semantics {
                contentDescription = "Class code ${item.joinCode.toList().joinToString(" ")}"
            },
        )
    }
}

@Composable
private fun NewClassDialog(token: String, onDismiss: () -> Unit, onCreated: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var gradeIndex by rememberSaveable { mutableStateOf(1) } // 0 = Grade 9, 1 = Grade 10
    var subject by rememberSaveable { mutableStateOf("Mathematics") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun create() {
        if (loading) return
        if (name.isBlank() || subject.isBlank()) {
            error = "Please fill in the class name and subject."
            return
        }
        scope.launch {
            loading = true
            error = null
            val grade = if (gradeIndex == 0) 9 else 10
            when (val result = SamaPatraApi.createClass(token, name.trim(), grade, subject.trim())) {
                is ApiResult.Ok -> onCreated()
                is ApiResult.Failure -> error = result.message
            }
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text("New class") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Field(
                    value = name,
                    onValueChange = { name = it },
                    label = "Class name, for example 10 C",
                    capitalization = KeyboardCapitalization.Words,
                )
                Text("Grade", style = MaterialTheme.typography.labelLarge)
                ChoiceGroup(
                    options = listOf("Grade 9", "Grade 10"),
                    selectedIndex = gradeIndex,
                    onSelect = { gradeIndex = it },
                )
                Field(
                    value = subject,
                    onValueChange = { subject = it },
                    label = "Subject",
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.Words,
                    onDone = { create() },
                )
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(onClick = { create() }, enabled = !loading) {
                Text(if (loading) "Creating…" else "Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) { Text("Cancel") }
        },
    )
}

