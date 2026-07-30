package com.rpgmaps.tabletop.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rpgmaps.tabletop.ui.display.DisplaySettingsScreen
import com.rpgmaps.tabletop.ui.library.LibraryScreen
import com.rpgmaps.tabletop.ui.map.MapScreen

private object Routes {
    const val LIBRARY = "library"
    const val MAP = "map/{mapId}"
    const val DISPLAY_SETTINGS = "displaySettings"

    fun map(mapId: String) = "map/$mapId"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.LIBRARY) {

        composable(Routes.LIBRARY) {
            LibraryScreen(
                onOpenMap = { navController.navigate(Routes.map(it)) },
                onOpenDisplaySettings = { navController.navigate(Routes.DISPLAY_SETTINGS) },
            )
        }

        composable(
            route = Routes.MAP,
            arguments = listOf(navArgument("mapId") { type = NavType.StringType }),
        ) { entry ->
            val mapId = entry.arguments?.getString("mapId").orEmpty()
            MapScreen(
                mapId = mapId,
                onBack = { navController.popBackStack() },
                onOpenDisplaySettings = { navController.navigate(Routes.DISPLAY_SETTINGS) },
            )
        }

        composable(Routes.DISPLAY_SETTINGS) {
            DisplaySettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
