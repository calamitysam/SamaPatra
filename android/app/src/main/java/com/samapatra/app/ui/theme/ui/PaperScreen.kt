package com.samapatra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.samapatra.app.data.ApiResult
import com.samapatra.app.data.PaperQuestion
import com.samapatra.app.data.PaperView
import com.samapatra.app.data.SamaPatraApi
import com.samapatra.app.data.SessionStore
import com.samapatra.app.ui.theme.StudentLook

private class SpokenWords(
    val question: String,
    val mark: String,
    val marks: String,
    val option: String,
)

private fun spokenWords(language: String) = when (language) {
    "hi" -> SpokenWords("प्रश्न", "अंक", "अंक", "विकल्प")
    "kn" -> SpokenWords("ಪ್ರಶ್ನೆ", "ಅಂಕ", "ಅಂಕಗಳು", "ಆಯ್ಕೆ")
    else -> SpokenWords("Question", "mark", "marks", "Option")
}

private fun marksText(marks: Double) =
    if (marks % 1.0 == 0.0) marks.toInt().toString() else marks.toString()

/** What is read aloud for one question: its number, marks, text and options. */
private fun spoken(q: PaperQuestion, language: String): String {
    val w = spokenWords(language)
    val marks = if (q.marks == 1.0) "1 ${w.mark}" else "${marksText(q.marks)} ${w.marks}"
    val options = q.options.joinToString(". ") {
        it.replaceFirst(Regex("^([A-H])\\)\\s*"), "${w.option} $1: ")
    }
    val body = if (options.isEmpty()) q.text else "${q.text}. $options"
    return "${w.question} ${q.sequence}. $marks. $body"
}

private fun languageName(code: String) = when (code) {
    "hi" -> "Hindi"
    "kn" -> "Kannada"
    else -> "English"
}

@Composable
fun PaperScreen(paperId: String, onBack: () -> Unit, onSignOut: () -> Unit) {
    val session = SessionStore.session
    if (session == null) {
        LaunchedEffect(Unit) { onSignOut() }
        return
    }

    val settings = SessionStore.settings
    val language = session.student?.language ?: "en"
    val speaker = rememberSpeaker(language)
    val wantsVoice = settings.readAloudButton || settings.audioFirst
    // Students who listen get one question at a time, with big controls
    val single = settings.oneQuestionPerScreen || settings.audioFirst

    var paper by remember { mutableStateOf<PaperView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var index by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        when (val result = SamaPatraApi.paperView(session.token, paperId)) {
            is ApiResult.Ok -> paper = result.value
            is ApiResult.Failure -> error = result.message
        }
    }

    val current = paper
    val last = (current?.questions?.size ?: 1) - 1
    val position = index.coerceIn(0, maxOf(last, 0))

    // Audio-first students hear each question as soon as it appears
    LaunchedEffect(position, current, speaker.ready) {
        val q = current?.questions?.getOrNull(position)
        if (settings.audioFirst && speaker.ready && q != null) {
            speaker.speak(spoken(q, language))
        }
    }

    StudentLook(settings) {
        key(if (single) position else -1) {
            ScreenFrame(
                title = current?.title ?: "Your paper",
                onBack = {
                    speaker.stop()
                    onBack()
                },
            ) {
                if (current == null) {
                    ErrorText(error)
                    if (error != null) {
                        TextButton(onClick = { attempt++ }) { Text("Try again") }
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics { contentDescription = "Loading your paper" },
                        )
                    }
                } else if (current.questions.isEmpty()) {
                    Text(
                        text = "This paper has no questions. Please tell your teacher.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                } else {
                    val time = current.durationMinutes?.let { " · $it minutes" } ?: ""
                    Text(
                        text = "${current.subject} · ${marksText(current.totalMarks)} marks$time",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Write your answers on your answer sheet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (wantsVoice && speaker.voiceMissing) {
                        Text(
                            text = "This tablet doesn’t have a ${languageName(language)} voice yet. In the tablet’s Settings, open Text-to-speech output and install the voice.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (single) {
                        val q = current.questions[position]
                        Text(
                            text = "Question ${position + 1} of ${current.questions.size}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        QuestionCard(q)
                        if (wantsVoice && !speaker.voiceMissing) {
                            SecondaryButton(
                                text = if (settings.audioFirst) "Read it again" else "Read to me",
                                onClick = { speaker.speak(spoken(q, language)) },
                                enabled = speaker.ready,
                            )
                        }
                        PrimaryButton(
                            text = if (position < last) "Next question" else "That was the last question",
                            onClick = {
                                speaker.stop()
                                index = position + 1
                            },
                            enabled = position < last,
                        )
                        SecondaryButton(
                            text = "Previous question",
                            onClick = {
                                speaker.stop()
                                index = position - 1
                            },
                            enabled = position > 0,
                        )
                    } else {
                        current.questions.forEach { q ->
                            QuestionCard(
                                question = q,
                                onRead = if (settings.readAloudButton && !speaker.voiceMissing) {
                                    { speaker.speak(spoken(q, language)) }
                                } else {
                                    null
                                },
                                readReady = speaker.ready,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionCard(
    question: PaperQuestion,
    onRead: (() -> Unit)? = null,
    readReady: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val marks = if (question.marks == 1.0) "1 mark" else "${marksText(question.marks)} marks"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(colors.surfaceVariant)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Question ${question.sequence} · $marks",
            style = MaterialTheme.typography.labelLarge,
            color = colors.primary,
            modifier = Modifier.semantics { heading() },
        )
        Text(question.text, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
        question.options.forEach {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
        }
        if (onRead != null) {
            SecondaryButton(text = "Read to me", onClick = onRead, enabled = readReady)
        }
    }
}