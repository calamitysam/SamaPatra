package com.samapatra.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.samapatra.app.data.ApiResult
import com.samapatra.app.data.QuestionView
import com.samapatra.app.data.SamaPatraApi
import com.samapatra.app.data.SessionStore
import com.samapatra.app.data.StudentHome
import com.samapatra.app.data.StudentSettings
import com.samapatra.app.ui.theme.StudentLook
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import com.samapatra.app.data.PaperListItem
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val CHOICE_LETTERS = "ABCDEFGH"

/** The question as it should be read aloud: "Option A: ..." instead of "A) ...". */
private fun spokenText(question: QuestionView): String {
    val options = question.options.joinToString(". ") {
        it.replaceFirst(Regex("^([A-H])\\)\\s*"), "Option $1: ")
    }
    return if (options.isEmpty()) question.text else "${question.text}. $options"
}

private fun languageName(code: String) = when (code) {
    "hi" -> "Hindi"
    "kn" -> "Kannada"
    else -> "English"
}

@Composable
fun StudentHomeScreen(
    onSettings: () -> Unit,
    onLevelCheck: (String) -> Unit,
    onOpenPaper: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    val session = SessionStore.session
    if (session == null) {
        LaunchedEffect(Unit) { onSignOut() }
        return
    }

    var home by remember { mutableStateOf<StudentHome?>(null) }
    var papers by remember { mutableStateOf<List<PaperListItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        error = null
        when (val result = SamaPatraApi.studentHome(session.token)) {
            is ApiResult.Ok -> home = result.value
            is ApiResult.Failure -> error = result.message
        }
        val studentId = session.student?.id
        if (studentId != null) {
            when (val result = SamaPatraApi.studentPapers(session.token, studentId)) {
                is ApiResult.Ok -> papers = result.value
                is ApiResult.Failure -> if (error == null) error = result.message
            }
        }
    }

    StudentLook(SessionStore.settings) {
        ScreenFrame(title = "Hi, ${session.user.name}") {
            val current = home
            ErrorText(error)
            if (error != null) {
                TextButton(onClick = { reload++ }) { Text("Try again") }
            }
            if (current == null && error == null) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = "Loading" },
                )
            }
            if (current != null) {
                Text(
                    text = "Your papers",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (papers.isEmpty()) {
                    Text(
                        text = "No papers yet. When your teacher shares one, it will appear here.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                papers.forEach { paper -> PaperCard(paper) { onOpenPaper(paper.id) } }

                if (current.subjects.isEmpty()) {
                    Text(
                        text = "You’re not in a class yet. Ask your teacher to add you.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                current.subjects.forEach { subject ->
                    val done = current.levels.containsKey(subject)
                    Text(
                        text = "$subject level check",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = if (done) {
                            "Your level check is done. You can do it again any time."
                        } else {
                            "A short set of questions, so your papers start in the right place."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (done) {
                        SecondaryButton(text = "Do the level check again", onClick = { onLevelCheck(subject) })
                    } else {
                        PrimaryButton(text = "Start level check", onClick = { onLevelCheck(subject) })
                    }
                }
            }

            Text(
                text = "Make it work for you",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Change the size of the writing, the colours, the sounds and more.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecondaryButton(text = "How I like to work", onClick = onSettings)
            TextButton(onClick = onSignOut) { Text("Sign out") }
        }
    }
}

@Composable
private fun PaperCard(paper: PaperListItem, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val marks = if (paper.totalMarks % 1.0 == 0.0) paper.totalMarks.toInt().toString() else paper.totalMarks.toString()
    val due = paper.dueAt?.let {
        runCatching {
            LocalDate.parse(it.take(10)).format(DateTimeFormatter.ofPattern("d MMM yyyy"))
        }.getOrNull()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(colors.secondaryContainer)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(paper.title, style = MaterialTheme.typography.titleLarge, color = colors.onSecondaryContainer)
        Text(
            text = "${paper.subject} · $marks marks" + if (due != null) " · Due $due" else "",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSecondaryContainer,
        )
    }
}

@Composable
fun StudentSettingsScreen(onBack: () -> Unit, onSignOut: () -> Unit) {
    val session = SessionStore.session
    if (session == null) {
        LaunchedEffect(Unit) { onSignOut() }
        return
    }

    val settings = SessionStore.settings
    val scope = rememberCoroutineScope()
    // Saves go one after another so a quick run of taps can’t overwrite each other
    val saveLock = remember { Mutex() }
    var message by remember { mutableStateOf<String?>(null) }

    fun change(patch: JSONObject, next: StudentSettings) {
        SessionStore.updateSettings(next)
        message = null
        scope.launch {
            saveLock.withLock {
                if (SamaPatraApi.saveSettings(session.token, patch) is ApiResult.Failure) {
                    message = "Couldn’t save that change. It may go back when you sign in again."
                }
            }
        }
    }

    fun flag(key: String, on: Boolean, next: StudentSettings) =
        change(JSONObject().put(key, on), next)

    val sizes = listOf(1.0, 1.25, 1.5, 2.0)
    val sizeLabels = listOf("Normal", "Large", "Extra large", "Biggest")
    val sizeIndex = sizes.indices.minByOrNull { kotlin.math.abs(sizes[it] - settings.textScale) } ?: 0

    StudentLook(settings) {
        ScreenFrame(title = "How I like to work", onBack = onBack) {
            Text(
                text = "Change anything you like. The app changes straight away, and it stays that way when you sign in again.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "What it looks like",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "A train travels 120 km in 2 hours. What is its average speed?",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "A) 40 km/h     B) 60 km/h",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            Text("Size of the writing", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            ChoiceGroup(
                options = sizeLabels,
                selectedIndex = sizeIndex,
                onSelect = { index ->
                    change(
                        JSONObject().put("textScale", sizes[index]),
                        settings.copy(textScale = sizes[index]),
                    )
                },
            )

            Text("Seeing", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            SwitchRow("Space out the lines", "More room between lines of writing", settings.lineSpacing == "wide") { on ->
                val value = if (on) "wide" else "normal"
                change(JSONObject().put("lineSpacing", value), settings.copy(lineSpacing = value))
            }
            SwitchRow("Easier-to-read lettering", "More room between letters", settings.readableFont) {
                flag("readableFont", it, settings.copy(readableFont = it))
            }
            SwitchRow("Soft yellow background", "Less glare than white", settings.tintedBackground) {
                flag("tintedBackground", it, settings.copy(tintedBackground = it))
            }
            SwitchRow("Strong contrast", "Pure black and white, easy to tell apart", settings.highContrast) {
                flag("highContrast", it, settings.copy(highContrast = it))
            }
            SwitchRow("Less movement on screen", "Stops things sliding and fading", settings.reduceMotion) {
                flag("reduceMotion", it, settings.copy(reduceMotion = it))
            }

            Text("Doing", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            SwitchRow("One question at a time", null, settings.oneQuestionPerScreen) {
                flag("oneQuestionPerScreen", it, settings.copy(oneQuestionPerScreen = it))
            }
            SwitchRow("Bigger buttons", "Easier to tap", settings.largeTouchTargets) {
                flag("largeTouchTargets", it, settings.copy(largeTouchTargets = it))
            }
            SwitchRow("Answer by speaking", "Say your answer instead of typing", settings.voiceAnswers) {
                flag("voiceAnswers", it, settings.copy(voiceAnswers = it))
            }

            Text("Listening", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            SwitchRow("A “Read to me” button", "Tap it to hear a question", settings.readAloudButton) {
                flag("readAloudButton", it, settings.copy(readAloudButton = it))
            }
            SwitchRow("Read everything to me", "Each question is read out as soon as it appears", settings.audioFirst) {
                flag("audioFirst", it, settings.copy(audioFirst = it))
            }

            Text("Words", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(
                text = "These change how new questions are written. They apply to your next level check and your next papers.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow("Simpler words", "Short sentences, everyday words", settings.plainLanguage) {
                flag("plainLanguage", it, settings.copy(plainLanguage = it))
            }
            SwitchRow("Shorter questions", "Only what you need to answer", settings.conciseText) {
                flag("conciseText", it, settings.copy(conciseText = it))
            }
            SwitchRow("Say symbols as words", "For listening, such as “x squared”", settings.listenableWording) {
                flag("listenableWording", it, settings.copy(listenableWording = it))
            }

            ErrorText(message)
            SecondaryButton(
                text = "Go back to what my teacher chose",
                onClick = {
                    scope.launch {
                        saveLock.withLock {
                            when (val result = SamaPatraApi.resetSettings(session.token)) {
                                is ApiResult.Ok -> {
                                    SessionStore.updateSettings(result.value)
                                    message = null
                                }
                                is ApiResult.Failure -> message = result.message
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun LevelCheckScreen(subject: String, onDone: () -> Unit, onBack: () -> Unit, onSignOut: () -> Unit) {
    val session = SessionStore.session
    if (session == null) {
        LaunchedEffect(Unit) { onSignOut() }
        return
    }

    val settings = SessionStore.settings
    val speaker = rememberSpeaker(session.student?.language ?: "en")
    val scope = rememberCoroutineScope()
    val wantsVoice = settings.readAloudButton || settings.audioFirst

    var sessionId by remember { mutableStateOf<String?>(null) }
    var question by remember { mutableStateOf<QuestionView?>(null) }
    var number by remember { mutableIntStateOf(1) }
    var total by remember { mutableIntStateOf(10) }
    var chosen by remember { mutableIntStateOf(-1) }
    var finished by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        starting = true
        error = null
        when (val result = SamaPatraApi.startLevelCheck(session.token, subject)) {
            is ApiResult.Ok -> {
                sessionId = result.value.sessionId
                question = result.value.question
                number = result.value.number
                total = result.value.total
            }
            is ApiResult.Failure -> error = result.message
        }
        starting = false
    }

    // Audio-first students hear each question as soon as it appears
    LaunchedEffect(question?.id, speaker.ready) {
        val current = question
        if (settings.audioFirst && speaker.ready && current != null) {
            speaker.speak(spokenText(current))
        }
    }

    BackHandler(enabled = !finished) { confirmLeave = true }

    fun sendAnswer() {
        val current = question ?: return
        val id = sessionId ?: return
        if (chosen < 0 || sending) return
        speaker.stop()
        scope.launch {
            sending = true
            error = null
            when (val result = SamaPatraApi.answerLevelCheck(
                session.token, id, current.id, CHOICE_LETTERS[chosen].toString()
            )) {
                is ApiResult.Ok -> {
                    val step = result.value
                    if (step.finished || step.next == null) {
                        finished = true
                    } else {
                        question = step.next
                        number = step.number
                        total = step.total
                        chosen = -1
                    }
                }
                is ApiResult.Failure -> error = result.message
            }
            sending = false
        }
    }

    StudentLook(settings) {
        ScreenFrame(
            title = "Level check",
            onBack = { if (finished) onBack() else confirmLeave = true },
        ) {
            val current = question
            when {
                finished -> {
                    Text(
                        text = "All done. Your papers will now start in the right place.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    PrimaryButton(text = "Back to home", onClick = onDone)
                }

                starting -> {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics { contentDescription = "Getting your first question" },
                    )
                    Text(
                        text = "Getting your first question ready. This can take a moment.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }

                current == null -> {
                    ErrorText(error)
                    PrimaryButton(text = "Try again", onClick = { attempt++ })
                }

                else -> {
                    Text(
                        text = "$subject · Question $number of up to $total",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = current.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    if (wantsVoice) {
                        if (speaker.voiceMissing) {
                            Text(
                                text = "This tablet doesn’t have a ${languageName(session.student?.language ?: "en")} voice yet. In the tablet’s Settings, open Text-to-speech output and install the voice.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            SecondaryButton(
                                text = "Read to me",
                                onClick = { speaker.speak(spokenText(current)) },
                                enabled = speaker.ready,
                            )
                        }
                    }
                    ChoiceGroup(
                        options = current.options,
                        selectedIndex = chosen,
                        onSelect = { chosen = it },
                    )
                    ErrorText(error)
                    PrimaryButton(
                        text = "Next",
                        onClick = { sendAnswer() },
                        enabled = chosen >= 0,
                        loading = sending,
                    )
                }
            }
        }

        if (confirmLeave) {
            AlertDialog(
                onDismissRequest = { confirmLeave = false },
                title = { Text("Stop the level check?") },
                text = { Text("Your answers so far won’t be saved.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmLeave = false
                        speaker.stop()
                        onBack()
                    }) { Text("Stop") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmLeave = false }) { Text("Keep going") }
                },
            )
        }
    }
}