package com.samapatra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samapatra.app.data.ApiResult
import com.samapatra.app.data.ClassDetail
import com.samapatra.app.data.ClassItem
import com.samapatra.app.data.DeletePreview
import com.samapatra.app.data.NewStudent
import com.samapatra.app.data.SamaPatraApi
import com.samapatra.app.data.SessionStore
import com.samapatra.app.data.StudentItem
import kotlinx.coroutines.launch

private val LANGUAGES = listOf(
    "en" to "English",
    "hi" to "Hindi (हिन्दी)",
    "kn" to "Kannada (ಕನ್ನಡ)",
)

private val NEEDS = listOf(
    "blind" to "Needs everything read aloud",
    "low_vision" to "Needs larger or clearer text",
    "dyslexia" to "Finds reading letters and words hard",
    "adhd" to "Finds it hard to stay focused",
    "motor_difficulty" to "Finds tapping or writing hard",
)

private fun languageName(code: String): String = when (code) {
    "hi" -> "Hindi"
    "kn" -> "Kannada"
    else -> "English"
}

private fun plural(count: Int, word: String) = if (count == 1) "$count $word" else "$count ${word}s"

// ---------------------------------------------------------------------------
// The class page
// ---------------------------------------------------------------------------

@Composable
fun ClassDetailScreen(classId: String, onBack: () -> Unit, onMakePapers: () -> Unit, onSignOut: () -> Unit) {
    val session = SessionStore.session
    if (session == null) {
        LaunchedEffect(Unit) { onSignOut() }
        return
    }

    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<ClassDetail?>(null) }
    // All of this teacher's classes for the same grade, including this one
    var gradeClasses by remember { mutableStateOf<List<ClassItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    var showAdd by rememberSaveable { mutableStateOf(false) }
    // Saved, so the one-time PIN survives turning the tablet sideways.
    var revealName by rememberSaveable { mutableStateOf<String?>(null) }
    var revealPin by rememberSaveable { mutableStateOf<String?>(null) }

    var resetTarget by remember { mutableStateOf<StudentItem?>(null) }
    var resetting by remember { mutableStateOf(false) }
    var resetError by remember { mutableStateOf<String?>(null) }

    var classesTarget by remember { mutableStateOf<StudentItem?>(null) }

    var deleteTarget by remember { mutableStateOf<StudentItem?>(null) }
    var deletingStudent by remember { mutableStateOf(false) }
    var deleteStudentError by remember { mutableStateOf<String?>(null) }

    var preview by remember { mutableStateOf<DeletePreview?>(null) }
    var previewing by remember { mutableStateOf(false) }
    var alsoDeleteStudents by remember { mutableStateOf(false) }
    var deletingClass by remember { mutableStateOf(false) }
    var classDeleteError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        error = null
        when (val result = SamaPatraApi.classDetail(session.token, classId)) {
            is ApiResult.Ok -> {
                detail = result.value
                when (val all = SamaPatraApi.teacherClasses(session.token, session.user.id)) {
                    is ApiResult.Ok -> gradeClasses = all.value.filter { it.grade == result.value.grade }
                    is ApiResult.Failure -> error = all.message
                }
            }
            is ApiResult.Failure -> error = result.message
        }
    }

    fun openDeleteClass() {
        if (previewing) return
        scope.launch {
            previewing = true
            classDeleteError = null
            when (val result = SamaPatraApi.classDeletePreview(session.token, classId)) {
                is ApiResult.Ok -> {
                    alsoDeleteStudents = false
                    preview = result.value
                }
                is ApiResult.Failure -> classDeleteError = result.message
            }
            previewing = false
        }
    }

    fun deleteClass() {
        if (deletingClass) return
        scope.launch {
            deletingClass = true
            classDeleteError = null
            when (val result = SamaPatraApi.deleteClass(session.token, classId, alsoDeleteStudents)) {
                is ApiResult.Ok -> onBack()
                is ApiResult.Failure -> {
                    classDeleteError = result.message
                    preview = null
                }
            }
            deletingClass = false
        }
    }

    val current = detail
    ScreenFrame(title = current?.name ?: "Class", onBack = onBack) {
        if (current != null) {
            Text(
                text = "Grade ${current.grade} · ${current.subject}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CodeCard(current.joinCode)
            PrimaryButton(text = "Add student", onClick = { showAdd = true })
            SecondaryButton(text = "Make papers", onClick = onMakePapers)
            Text(
                text = "Students (${current.students.size})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            if (current.students.isEmpty()) {
                Text(
                    text = "No students yet. Tap Add student to make the first one.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            current.students.forEach { student ->
                StudentRow(
                    student = student,
                    classNames = student.classIds.mapNotNull { id ->
                        gradeClasses.firstOrNull { it.id == id }?.name
                    },
                    onClasses = { classesTarget = student },
                    onNewPin = {
                        resetTarget = student
                        resetError = null
                    },
                    onDelete = {
                        deleteTarget = student
                        deleteStudentError = null
                    },
                )
            }
            ErrorText(classDeleteError)
            SecondaryButton(
                text = "Delete this class",
                onClick = { openDeleteClass() },
                enabled = !previewing,
            )
        }
        ErrorText(error)
        if (error != null) {
            TextButton(onClick = { reload++ }) { Text("Try again") }
        }
        if (current == null && error == null) {
            CircularProgressIndicator(
                modifier = Modifier.semantics { contentDescription = "Loading the class" },
            )
        }
    }

    // Add a student, to this class and optionally to more
    if (showAdd && current != null) {
        AddStudentDialog(
            token = session.token,
            classId = current.id,
            grade = current.grade,
            otherClasses = gradeClasses.filter { it.id != current.id },
            onDismiss = { showAdd = false },
            onAdded = { added ->
                showAdd = false
                revealName = added.name
                revealPin = added.pin
                reload++
            },
        )
    }

    // Choose which classes a student is in
    val classesStudent = classesTarget
    if (classesStudent != null) {
        StudentClassesDialog(
            token = session.token,
            student = classesStudent,
            options = gradeClasses,
            onDismiss = { classesTarget = null },
            onSaved = {
                classesTarget = null
                reload++
            },
        )
    }

    // New PIN
    val pinStudent = resetTarget
    if (pinStudent != null) {
        AlertDialog(
            onDismissRequest = { if (!resetting) resetTarget = null },
            title = { Text("New PIN for ${pinStudent.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The old PIN will stop working straight away.")
                    ErrorText(resetError)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !resetting,
                    onClick = {
                        scope.launch {
                            resetting = true
                            resetError = null
                            when (val result = SamaPatraApi.resetPin(session.token, classId, pinStudent.id)) {
                                is ApiResult.Ok -> {
                                    revealName = pinStudent.name
                                    revealPin = result.value
                                    resetTarget = null
                                }
                                is ApiResult.Failure -> resetError = result.message
                            }
                            resetting = false
                        }
                    },
                ) { Text(if (resetting) "Working…" else "Make new PIN") }
            },
            dismissButton = {
                TextButton(enabled = !resetting, onClick = { resetTarget = null }) { Text("Cancel") }
            },
        )
    }

    // Delete a student
    val studentToDelete = deleteTarget
    if (studentToDelete != null) {
        AlertDialog(
            onDismissRequest = { if (!deletingStudent) deleteTarget = null },
            title = { Text("Delete ${studentToDelete.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("They are removed from every class, and their papers and level are deleted. You can’t undo this.")
                    ErrorText(deleteStudentError)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deletingStudent,
                    onClick = {
                        scope.launch {
                            deletingStudent = true
                            deleteStudentError = null
                            when (val result = SamaPatraApi.deleteStudent(session.token, studentToDelete.id)) {
                                is ApiResult.Ok -> {
                                    deleteTarget = null
                                    reload++
                                }
                                is ApiResult.Failure -> deleteStudentError = result.message
                            }
                            deletingStudent = false
                        }
                    },
                ) { Text(if (deletingStudent) "Deleting…" else "Delete student") }
            },
            dismissButton = {
                TextButton(enabled = !deletingStudent, onClick = { deleteTarget = null }) { Text("Keep them") }
            },
        )
    }

    // Delete this class
    val toDelete = preview
    if (toDelete != null) {
        val canDelete = toDelete.onlyHere == 0 || alsoDeleteStudents
        AlertDialog(
            onDismissRequest = { if (!deletingClass) preview = null },
            title = { Text("Delete “${current?.name ?: "this class"}”?") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("The class and its class code are removed. Students who are also in other classes stay in those.")
                    if (toDelete.draftPapers > 0) {
                        Text("${plural(toDelete.draftPapers, "paper")} made for this class that haven’t been shared yet will be deleted.")
                    }
                    if (toDelete.sharedPapers > 0) {
                        Text("${plural(toDelete.sharedPapers, "paper")} already shared with students stay with them.")
                    }
                    if (toDelete.onlyHere > 0) {
                        val who = if (toDelete.onlyHere == 1) "is" else "are"
                        Text("${plural(toDelete.onlyHere, "student")} $who only in this class. Add them to another class first, or delete them with the class.")
                        CheckRow(
                            checked = alsoDeleteStudents,
                            label = "Also delete ${if (toDelete.onlyHere == 1) "that student" else "those students"}, with their papers and levels",
                            onChange = { alsoDeleteStudents = it },
                        )
                    }
                    Text("You can’t undo this.")
                }
            },
            confirmButton = {
                TextButton(enabled = canDelete && !deletingClass, onClick = { deleteClass() }) {
                    Text(if (deletingClass) "Deleting…" else "Delete class")
                }
            },
            dismissButton = {
                TextButton(enabled = !deletingClass, onClick = { preview = null }) { Text("Keep it") }
            },
        )
    }

    // A PIN is shown once
    val pinToShow = revealPin
    if (pinToShow != null) {
        PinDialog(
            name = revealName.orEmpty(),
            pin = pinToShow,
            onClose = {
                revealPin = null
                revealName = null
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Pieces of the class page
// ---------------------------------------------------------------------------

@Composable
private fun CodeCard(code: String) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(colors.primaryContainer)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Class code for students",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onPrimaryContainer,
        )
        Text(
            text = code,
            style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 4.sp),
            color = colors.onPrimaryContainer,
            modifier = Modifier.semantics {
                contentDescription = "Class code ${code.toList().joinToString(" ")}"
            },
        )
    }
}

@Composable
private fun StudentRow(
    student: StudentItem,
    classNames: List<String>,
    onClasses: () -> Unit,
    onNewPin: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val needLabels = student.needs
        .filter { it != "none" }
        .mapNotNull { code -> NEEDS.firstOrNull { it.first == code }?.second }
    val levelText = student.level?.let {
        "Level " + (if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()) + " of 5"
    } ?: "No level check yet"
    val details = languageName(student.language) + " · " + levelText + " · " +
            if (needLabels.isEmpty()) "No supports chosen yet" else needLabels.joinToString(", ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(colors.surfaceVariant)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(student.name, style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
        Text(details, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        if (classNames.isNotEmpty()) {
            Text(
                text = "In: ${classNames.joinToString(", ")}",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = onClasses,
                modifier = Modifier.semantics {
                    contentDescription = "Change the classes ${student.name} is in"
                },
            ) { Text("Classes") }
            TextButton(
                onClick = onNewPin,
                modifier = Modifier.semantics {
                    contentDescription = "Make a new PIN for ${student.name}"
                },
            ) { Text("New PIN") }
        }
        TextButton(
            onClick = onDelete,
            colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
            modifier = Modifier.semantics { contentDescription = "Delete ${student.name}" },
        ) { Text("Delete student") }
    }
}

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

@Composable
private fun AddStudentDialog(
    token: String,
    classId: String,
    grade: Int,
    otherClasses: List<ClassItem>,
    onDismiss: () -> Unit,
    onAdded: (NewStudent) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val choices = otherClasses.take(20)
    val allMask = (1 shl choices.size) - 1
    var name by rememberSaveable { mutableStateOf("") }
    var languageIndex by rememberSaveable { mutableStateOf(0) }
    var needsMask by rememberSaveable { mutableStateOf(0) } // one bit per access need
    var classMask by rememberSaveable { mutableStateOf(0) } // one bit per other class
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun add() {
        if (loading) return
        if (name.isBlank()) {
            error = "Please enter the student’s name."
            return
        }
        val needs = NEEDS
            .filterIndexed { index, _ -> (needsMask and (1 shl index)) != 0 }
            .map { it.first }
            .ifEmpty { listOf("none") }
        val extraClassIds = choices
            .filterIndexed { index, _ -> (classMask and (1 shl index)) != 0 }
            .map { it.id }
        scope.launch {
            loading = true
            error = null
            val result = SamaPatraApi.addStudent(
                token = token,
                classId = classId,
                name = name.trim(),
                grade = grade,
                language = LANGUAGES[languageIndex].first,
                needs = needs,
                alsoClassIds = extraClassIds,
            )
            when (result) {
                is ApiResult.Ok -> onAdded(result.value)
                is ApiResult.Failure -> error = result.message
            }
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text("Add student") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Field(
                    value = name,
                    onValueChange = { name = it },
                    label = "Student’s name",
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                    onDone = { add() },
                )
                Text("Language", style = MaterialTheme.typography.labelLarge)
                ChoiceGroup(
                    options = LANGUAGES.map { it.second },
                    selectedIndex = languageIndex,
                    onSelect = { languageIndex = it },
                )
                Text(
                    text = "What might help? Optional. Leave blank if you’re not sure.",
                    style = MaterialTheme.typography.labelLarge,
                )
                CheckGroup(
                    options = NEEDS.map { it.second },
                    mask = needsMask,
                    onToggle = { index -> needsMask = needsMask xor (1 shl index) },
                )
                if (choices.isNotEmpty()) {
                    Text(
                        text = "Also add to these Grade $grade classes. Optional.",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    TextButton(
                        onClick = { classMask = if (classMask == allMask) 0 else allMask },
                    ) { Text(if (classMask == allMask) "Clear all" else "Select all") }
                    CheckGroup(
                        options = choices.map { "${it.name} · ${it.subject}" },
                        mask = classMask,
                        onToggle = { index -> classMask = classMask xor (1 shl index) },
                    )
                }
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(onClick = { add() }, enabled = !loading) {
                Text(if (loading) "Adding…" else "Add student")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) { Text("Cancel") }
        },
    )
}

@Composable
private fun StudentClassesDialog(
    token: String,
    student: StudentItem,
    options: List<ClassItem>,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val choices = options.take(20)
    val startMask = choices.foldIndexed(0) { index, mask, item ->
        if (item.id in student.classIds) mask or (1 shl index) else mask
    }
    var mask by remember(student.id) { mutableStateOf(startMask) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        if (loading) return
        val ids = choices
            .filterIndexed { index, _ -> (mask and (1 shl index)) != 0 }
            .map { it.id }
        if (ids.isEmpty()) {
            error = "Choose at least one class. To remove ${student.name} completely, delete them instead."
            return
        }
        scope.launch {
            loading = true
            error = null
            when (val result = SamaPatraApi.setStudentClasses(token, student.id, ids)) {
                is ApiResult.Ok -> onSaved()
                is ApiResult.Failure -> error = result.message
            }
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text("Classes for ${student.name}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (choices.isEmpty()) {
                    Text("You have no other classes for this grade yet.")
                } else {
                    Text("Tick every class ${student.name} is in. Their PIN, papers and level stay the same.")
                    CheckGroup(
                        options = choices.map { "${it.name} · ${it.subject}" },
                        mask = mask,
                        onToggle = { index -> mask = mask xor (1 shl index) },
                    )
                }
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }, enabled = !loading && choices.isNotEmpty()) {
                Text(if (loading) "Saving…" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) { Text("Cancel") }
        },
    )
}

/** Shows a PIN once. It can’t be dismissed by tapping outside, so it isn’t lost by accident. */
@Composable
private fun PinDialog(name: String, pin: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("PIN for $name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = pin,
                    style = MaterialTheme.typography.displaySmall.copy(letterSpacing = 8.sp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics {
                        contentDescription = "PIN ${pin.toList().joinToString(" ")}"
                    },
                )
                Text("Write this PIN down now. For safety it can’t be shown again. If it’s lost, make a new PIN.")
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("I’ve written it down") }
        },
    )
}

// ---------------------------------------------------------------------------
// Check boxes
// ---------------------------------------------------------------------------

@Composable
private fun CheckGroup(options: List<String>, mask: Int, onToggle: (Int) -> Unit) {
    Column {
        options.forEachIndexed { index, label ->
            CheckRow(
                checked = (mask and (1 shl index)) != 0,
                label = label,
                onChange = { onToggle(index) },
            )
        }
    }
}

@Composable
private fun CheckRow(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, onValueChange = onChange, role = Role.Checkbox)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}