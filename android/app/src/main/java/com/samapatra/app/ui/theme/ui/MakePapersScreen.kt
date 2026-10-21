package com.samapatra.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.samapatra.app.data.ApiResult
import com.samapatra.app.data.ClassDetail
import com.samapatra.app.data.PaperSet
import com.samapatra.app.data.SamaPatraApi
import com.samapatra.app.data.SessionStore
import kotlinx.coroutines.launch

private sealed interface Made {
    object Waiting : Made
    object Working : Made
    object Existing : Made
    class Done(val marks: Double, val warnings: List<String>) : Made
    class Failed(val message: String) : Made
}

private fun marksText(marks: Double) =
    if (marks % 1.0 == 0.0) marks.toInt().toString() else marks.toString()

private fun levelLabel(level: Double?) =
    if (level == null) "No level check yet" else "Level ${marksText(level)} of 5"

private fun plural(count: Int, word: String) = if (count == 1) "$count $word" else "$count ${word}s"

@Composable
fun MakePapersScreen(classId: String, onBack: () -> Unit, onSignOut: () -> Unit) {
    val session = SessionStore.session
    if (session == null) {
        LaunchedEffect(Unit) { onSignOut() }
        return
    }

    val scope = rememberCoroutineScope()
    val view = LocalView.current

    var detail by remember { mutableStateOf<ClassDetail?>(null) }
    var sets by remember { mutableStateOf<List<PaperSet>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    var title by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf("") }
    var marksField by rememberSaveable { mutableStateOf("40") }
    var durationField by rememberSaveable { mutableStateOf("") }

    var running by remember { mutableStateOf(false) }
    var stopRequested by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    val statuses = remember { mutableStateListOf<Made>() }
    var confirmLeave by remember { mutableStateOf(false) }
    var shareTarget by remember { mutableStateOf<PaperSet?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var shareMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh) {
        loadError = null
        when (val result = SamaPatraApi.classDetail(session.token, classId)) {
            is ApiResult.Ok -> {
                detail = result.value
                if (subject.isBlank()) subject = result.value.subject
            }
            is ApiResult.Failure -> loadError = result.message
        }
        when (val result = SamaPatraApi.classPaperSets(session.token, classId)) {
            is ApiResult.Ok -> sets = result.value
            is ApiResult.Failure -> if (loadError == null) loadError = result.message
        }
    }

    // Keep the tablet awake while papers are being made
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    BackHandler(enabled = running) { confirmLeave = true }

    fun run(d: ClassDetail) {
        if (running) return
        val marks = marksField.toDoubleOrNull()
        val minutes = durationField.toIntOrNull()
        when {
            title.isBlank() -> formError = "Give the paper a title."
            subject.isBlank() -> formError = "Enter the subject."
            marks == null || marks <= 0 || marks > 200 -> formError = "Total marks must be between 1 and 200."
            durationField.isNotBlank() && (minutes == null || minutes !in 1..600) ->
                formError = "Time must be between 1 and 600 minutes, or left empty."
            d.students.isEmpty() -> formError = "There are no students in this class yet."
            else -> {
                formError = null
                scope.launch {
                    running = true
                    stopRequested = false
                    shareMessage = null
                    statuses.clear()
                    statuses.addAll(d.students.map { Made.Waiting })
                    for ((index, student) in d.students.withIndex()) {
                        if (stopRequested) break
                        statuses[index] = Made.Working
                        statuses[index] = when (
                            val result = SamaPatraApi.generatePaper(
                                token = session.token,
                                classId = classId,
                                studentId = student.id,
                                subject = subject.trim(),
                                grade = d.grade,
                                totalMarks = marks,
                                title = title.trim(),
                                durationMinutes = minutes,
                            )
                        ) {
                            is ApiResult.Ok -> Made.Done(result.value.totalMarks, result.value.warnings)
                            is ApiResult.Failure ->
                                if (result.code == 409) Made.Existing else Made.Failed(result.message)
                        }
                    }
                    running = false
                    refresh++
                }
            }
        }
    }

    fun share(target: PaperSet) {
        scope.launch {
            sharing = true
            shareMessage = null
            when (val result = SamaPatraApi.publishPaperSet(session.token, classId, target.title)) {
                is ApiResult.Ok -> {
                    shareMessage = "Shared ${plural(result.value, "paper")} with students."
                    refresh++
                }
                is ApiResult.Failure -> shareMessage = result.message
            }
            sharing = false
            shareTarget = null
        }
    }

    ScreenFrame(
        title = "Make papers",
        onBack = { if (running) confirmLeave = true else onBack() },
    ) {
        val d = detail
        if (d == null) {
            ErrorText(loadError)
            if (loadError != null) {
                TextButton(onClick = { refresh++ }) { Text("Try again") }
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = "Loading the class" },
                )
            }
        } else {
            Text(
                text = "${d.name} · Grade ${d.grade} · ${plural(d.students.size, "student")}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ErrorText(loadError)

            if (sets.isNotEmpty()) {
                Text(
                    text = "Papers already made",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.semantics { heading() },
                )
                sets.forEach { set -> SetCard(set) { shareTarget = set } }
                shareMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                }
            }

            Text(
                text = "Make a new paper",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "Each student gets their own paper from the question bank, in their language, at their level. The same total marks, and one shared answer key.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Field(
                value = title,
                onValueChange = { title = it },
                label = "Paper title, for example Practice paper 1",
                capitalization = KeyboardCapitalization.Sentences,
            )
            Field(
                value = subject,
                onValueChange = { subject = it },
                label = "Subject (must match the subject of your question bank)",
                capitalization = KeyboardCapitalization.Words,
            )
            Field(
                value = marksField,
                onValueChange = { marksField = it.filter { c -> c.isDigit() || c == '.' }.take(6) },
                label = "Total marks",
                keyboardType = KeyboardType.Number,
            )
            Field(
                value = durationField,
                onValueChange = { durationField = it.filter { c -> c.isDigit() }.take(3) },
                label = "Time in minutes (optional)",
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            )

            if (statuses.isEmpty()) {
                Text(
                    text = "Students and their levels",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.semantics { heading() },
                )
                d.students.forEach { student ->
                    Text(
                        text = "${student.name} · ${levelLabel(student.level)}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                if (d.students.any { it.level == null }) {
                    Text(
                        text = "Students with no level check yet get a paper at a middle level. They can do their level check from their home screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = "Progress",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.semantics { heading() },
                )
                d.students.forEachIndexed { index, student ->
                    val status = statuses.getOrNull(index) ?: Made.Waiting
                    StatusRow(student.name, status)
                }
                if (!running) {
                    val made = statuses.count { it is Made.Done }
                    val existing = statuses.count { it is Made.Existing }
                    val failed = statuses.count { it is Made.Failed }
                    val waiting = statuses.count { it is Made.Waiting }
                    Text(
                        text = "Made $made, already made $existing, failed $failed" +
                                if (waiting > 0) ", not started $waiting." else ".",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    if (failed > 0 || waiting > 0) {
                        Text(
                            text = "Run it again to finish. Papers that are already made are skipped.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            ErrorText(formError)
            PrimaryButton(
                text = "Make ${plural(d.students.size, "paper")}",
                onClick = { run(d) },
                enabled = d.students.isNotEmpty(),
                loading = running,
            )
            if (running) {
                Text(
                    text = "This takes a few minutes. The first papers are slowest, because questions are being written in each student’s language. Keep this screen open.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecondaryButton(
                    text = if (stopRequested) "Stopping after this student…" else "Stop after this student",
                    onClick = { stopRequested = true },
                    enabled = !stopRequested,
                )
            }
        }
    }

    val target = shareTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { if (!sharing) shareTarget = null },
            title = { Text("Share “${target.title}”?") },
            text = {
                Text(
                    "${plural(target.drafts, "student")} will see this paper the next time they open the app. You can’t take it back once it’s shared."
                )
            },
            confirmButton = {
                TextButton(enabled = !sharing, onClick = { share(target) }) {
                    Text(if (sharing) "Sharing…" else "Share")
                }
            },
            dismissButton = {
                TextButton(enabled = !sharing, onClick = { shareTarget = null }) { Text("Not yet") }
            },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave while papers are being made?") },
            text = { Text("Papers that are already made are kept. Run it again later to finish the rest.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onBack()
                }) { Text("Leave") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text("Stay") }
            },
        )
    }
}

@Composable
private fun SetCard(set: PaperSet, onShare: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(colors.surfaceVariant)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(set.title, style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
        Text(
            text = "${set.subject} · ${marksText(set.totalMarks)} marks · ${plural(set.papers, "paper")}",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
        )
        Text(
            text = "${set.shared} shared with students · ${set.drafts} not shared yet",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        if (set.drafts > 0) {
            PrimaryButton(text = "Share ${set.drafts} with students", onClick = onShare)
        }
    }
}

@Composable
private fun StatusRow(name: String, status: Made) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val line = when (status) {
            is Made.Waiting -> "Waiting"
            is Made.Working -> "Making this paper…"
            is Made.Existing -> "Already made. Skipped."
            is Made.Done -> "Paper ready · ${marksText(status.marks)} marks"
            is Made.Failed -> status.message
        }
        Text(name, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
        Text(
            text = line,
            style = MaterialTheme.typography.bodyMedium,
            color = if (status is Made.Failed) colors.error else colors.onSurfaceVariant,
        )
        if (status is Made.Done) {
            status.warnings.forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}