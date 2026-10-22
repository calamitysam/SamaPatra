package com.samapatra.app.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.samapatra.app.data.SessionStore

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "welcome") {
        composable("welcome") {
            WelcomeScreen(
                onAdmin = { nav.navigate("admin") },
                onTeacher = { nav.navigate("teacher/auth") },
                onStudent = { nav.navigate("student/signin") },
            )
        }
        composable("admin") {
            AdminScreen(onBack = { nav.popBackStack() })
        }
        composable("teacher/auth") {
            TeacherAuthScreen(
                onBack = { nav.popBackStack() },
                onSignedIn = {
                    nav.navigate("teacher/home") { popUpTo("welcome") { inclusive = true } }
                },
            )
        }
        composable("teacher/home") {
            TeacherHomeScreen(
                onOpenClass = { id -> nav.navigate("teacher/class/$id") },
                onImport = { nav.navigate("teacher/import") },
                onBank = { nav.navigate("teacher/bank") },
                onSignOut = {
                    SessionStore.signOut()
                    nav.navigate("welcome") { popUpTo("teacher/home") { inclusive = true } }
                },
            )
        }
        composable(
            route = "teacher/class/{classId}",
            arguments = listOf(navArgument("classId") { type = NavType.StringType }),
        ) { entry ->
            ClassDetailScreen(
                classId = entry.arguments?.getString("classId").orEmpty(),
                onBack = { nav.popBackStack() },
                onMakePapers = { nav.navigate("teacher/class/${entry.arguments?.getString("classId").orEmpty()}/papers") },
                onSignOut = {
                    SessionStore.signOut()
                    nav.navigate("welcome") { popUpTo("teacher/home") { inclusive = true } }
                },
            )
        }

        composable(
            route = "teacher/class/{classId}/papers",
            arguments = listOf(navArgument("classId") { type = NavType.StringType }),
        ) { entry ->
            MakePapersScreen(
                classId = entry.arguments?.getString("classId").orEmpty(),
                onBack = { nav.popBackStack() },
                onSignOut = {
                    SessionStore.signOut()
                    nav.navigate("welcome") { popUpTo("teacher/home") { inclusive = true } }
                },
            )
        }


        composable("teacher/import") {
            ImportPaperScreen(
                onBack = { nav.popBackStack() },
                onSignOut = {
                    SessionStore.signOut()
                    nav.navigate("welcome") { popUpTo("teacher/home") { inclusive = true } }
                },
            )
        }

        composable("teacher/bank") {
            QuestionBankScreen(
                onBack = { nav.popBackStack() },
                onOpen = { key -> nav.navigate("teacher/bank/${Uri.encode(key)}") },
                onSignOut = {
                    SessionStore.signOut()
                    nav.navigate("welcome") { popUpTo("teacher/home") { inclusive = true } }
                },
            )
        }
        composable(
            route = "teacher/bank/{key}",
            arguments = listOf(navArgument("key") { type = NavType.StringType }),
        ) { entry ->
            BankPaperScreen(
                paperKey = Uri.decode(entry.arguments?.getString("key").orEmpty()),
                onBack = { nav.popBackStack() },
                onSignOut = {
                    SessionStore.signOut()
                    nav.navigate("welcome") { popUpTo("teacher/home") { inclusive = true } }
                },
            )
        }
        composable("student/signin") {
            StudentSignInScreen(
                onBack = { nav.popBackStack() },
                onSignedIn = {
                    nav.navigate("student/home") { popUpTo("welcome") { inclusive = true } }
                },
            )
        }
        composable("student/home") {
            StudentHomeScreen(
                onSettings = { nav.navigate("student/settings") },
                onLevelCheck = { subject -> nav.navigate("student/level/${Uri.encode(subject)}") },
                onOpenPaper = { id -> nav.navigate("student/paper/$id") },
                onSignOut = {
                    SessionStore.signOut()
                    nav.navigate("welcome") { popUpTo("student/home") { inclusive = true } }
                },
            )
        }
        composable("student/settings") {
            StudentSettingsScreen(
                onBack = { nav.popBackStack() },
                onSignOut = {
                    SessionStore.signOut()
                    nav.navigate("welcome") { popUpTo("student/home") { inclusive = true } }
                },
            )
        }
        composable(
            route = "student/level/{subject}",
            arguments = listOf(navArgument("subject") { type = NavType.StringType }),
        ) { entry ->
            LevelCheckScreen(
                subject = Uri.decode(entry.arguments?.getString("subject").orEmpty()),
                onDone = { nav.popBackStack() },
                onBack = { nav.popBackStack() },
                onSignOut = {
                    SessionStore.signOut()
                    nav.navigate("welcome") { popUpTo("student/home") { inclusive = true } }
                },
            )
        }

        composable(
            route = "student/paper/{paperId}",
            arguments = listOf(navArgument("paperId") { type = NavType.StringType }),
        ) { entry ->
            PaperScreen(
                paperId = entry.arguments?.getString("paperId").orEmpty(),
                onBack = { nav.popBackStack() },
                onSignOut = {
                    SessionStore.signOut()
                    nav.navigate("welcome") { popUpTo("student/home") { inclusive = true } }
                },
            )
        }
    }
}
