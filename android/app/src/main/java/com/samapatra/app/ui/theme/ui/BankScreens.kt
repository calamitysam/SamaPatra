package com.samapatra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.samapatra.app.data.ApiResult
import com.samapatra.app.data.BankPaper
import com.samapatra.app.data.BankPaperDetail
import com.samapatra.app.data.BankQuestion
import com.samapatra.app.data.SamaPatraApi
import com.samapatra.app.data.SessionStore
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private fun marksText(marks: Double) =
    if (marks % 1.0 == 0.0) marks.toInt().toString() else marks.toString()

private fun plural(count: Int, word: String) = if (count == 1) "$count $word" else "$count ${word}s"

private fun shortDate(iso: String): String? =
    runCatching {
        LocalDate.parse(iso.take(10)).format(DateTimeFormatter.ofPattern("d MMM yyyy"))
    }.getOrNull()

private fun paperTitle(name: String) = name.ifBlank { "Questions added one by one" }

private fun typeLabel(type: String) = when (type) {
    "mcq" -> "Multiple choice"
    "assertion_reason" -> "Assertion and reason"
    "short_answer" -> "Short answer"
    "long_answer" -> "Long answer"
    "case_study" -> "Case study"
    else -> type
}

@Composable
fun QuestionBankScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onSignOut: () -> Unit) {
    val session = SessionStore.session
    if (session == null) {
        LaunchedEffect(Unit) { onSignOut() }
        return
    }

    var papers by remember { mutableStateOf<List<BankPaper>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        error = null
        when (val result = SamaPatraApi.bankPapers(session.token)) {
            is ApiResult.Ok -> papers = result.value
            is ApiResult.Failure -> error = result.message
        }
    }

    ScreenFrame(title = "Question bank", onBack = onBack) {
        Text(
            text = "The questions that papers and level checks are made from. Tap a paper to see its questions.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ErrorText(error)
        if (error != null) {
            TextButton(onClick = { reload++ }) { Text("Try again") }
        }
        val list = papers
        when {
            list == null && error == null -> CircularProgressIndicator(
                modifier = Modifier.semantics { contentDescription = "Loading the question bank" },
            )
            list != null && list.isEmpty() -> Text(
                text = "Nothing here yet. Import a question paper from the classes screen.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            list != null -> list.forEach { paper -> BankCard(paper) { onOpen(paper.key) } }
        }
    }
}

@Composable
private fun BankCard(paper: BankPaper, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val date = shortDate(paper.createdAt)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(colors.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(paperTitle(paper.name), style = MaterialTheme.typography.titleLarge, color = colors.onBackground)
        Text(
            text = "${paper.subject} · Grade ${paper.grade}",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
        )
        Text(
            text = "${plural(paper.questions, "question")} · ${marksText(paper.totalMarks)} marks",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
        )
        Text(
            text = "${paper.levelCheckReady} ready for level checks",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        if (paper.unchecked > 0) {
            Text(
                text = "${plural(paper.unchecked, "answer")} not checked yet",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.error,
            )
        }
        val note = listOfNotNull(
            date?.let { "Imported $it" },
            if (paper.mine) null else "Imported by another teacher",
        ).joinToString(" · ")
        if (note.isNotBlank()) {
            Text(note, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
fun BankPaperScreen(paperKey: String, onBack: () -> Unit, onSignOut: () -> Unit) {
    val session = SessionStore.session
    if (session == null) {
        LaunchedEffect(Unit) { onSignOut() }
        return
    }

    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<BankPaperDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        error = null
        when (val result = SamaPatraApi.bankPaper(session.token, paperKey)) {
            is ApiResult.Ok -> detail = result.value
            is ApiResult.Failure -> error = result.message
        }
    }

    fun delete() {
        if (deleting) return
        scope.launch {
            deleting = true
            deleteError = null
            when (val result = SamaPatraApi.deleteBankPaper(session.token, paperKey)) {
                is ApiResult.Ok -> onBack()
                is ApiResult.Failure -> {
                    deleteError = result.message
                    confirmDelete = false
                }
            }
            deleting = false
        }
    }

    val current = detail
    ScreenFrame(title = current?.let { paperTitle(it.name) } ?: "Question paper", onBack = onBack) {
        ErrorText(error)
        if (error != null) {
            TextButton(onClick = { reload++ }) { Text("Try again") }
        }
        if (current == null && error == null) {
            CircularProgressIndicator(
                modifier = Modifier.semantics { contentDescription = "Loading the paper" },
            )
        }
        if (current != null) {
            val totalMarks = current.questions.sumOf { it.marks }
            Text(
                text = "${current.subject} · Grade ${current.grade} · ${plural(current.questions.size, "question")} · ${marksText(totalMarks)} marks",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (current.unsharedPapers + current.sharedPapers > 0) {
                Text(
                    text = "Student papers made from these questions: ${current.sharedPapers} shared, ${current.unsharedPapers} not shared yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (current.mine) {
                ErrorText(deleteError)
                SecondaryButton(text = "Delete this paper", onClick = { confirmDelete = true })
            } else {
                Text(
                    text = "Imported by another teacher, so only they can delete it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Questions",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            current.questions.forEach { BankQuestionCard(it) }
        }
    }

    val shown = detail
    if (confirmDelete && shown != null) {
        AlertDialog(
            onDismissRequest = { if (!deleting) confirmDelete = false },
            title = { Text("Delete “${paperTitle(shown.name)}”?") },
            text = {
                val extra = if (shown.unsharedPapers > 0) {
                    " This also deletes ${plural(shown.unsharedPapers, "paper")} made from it that haven’t been shared with students."
                } else {
                    ""
                }
                Text("All ${plural(shown.questions.size, "question")} are removed from the bank.$extra You can’t undo this.")
            },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = { delete() }) {
                    Text(if (deleting) "Deleting…" else "Delete")
                }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { confirmDelete = false }) { Text("Keep it") }
            },
        )
    }
}

@Composable
private fun BankQuestionCard(q: BankQuestion) {
    val colors = MaterialTheme.colorScheme
    val objective = q.type == "mcq" || q.type == "assertion_reason"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(colors.surfaceVariant)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val number = if (q.number.isBlank()) "" else "Question ${q.number} · "
        Text(
            text = "$number${typeLabel(q.type)} · ${marksText(q.marks)} ${if (q.marks == 1.0) "mark" else "marks"}",
            style = MaterialTheme.typography.labelLarge,
            color = colors.primary,
        )
        if (q.chapter.isNotBlank()) {
            Text(
                text = "${q.chapter} · Difficulty ${q.difficulty} of 5",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
        Text(q.text, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
        q.options.forEach {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
        }
        if (objective) {
            if (q.correctAnswer.isNullOrBlank()) {
                Text("No answer saved", style = MaterialTheme.typography.bodyMedium, color = colors.error)
            } else {
                Text(
                    text = "Answer: ${q.correctAnswer}" + if (q.checked) " (checked)" else " (not checked yet)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (q.checked) colors.onSurfaceVariant else colors.error,
                )
            }
        }
        if (q.needsFigure) {
            Text(
                text = "Refers to a figure, so it is left out of papers for students who listen.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}