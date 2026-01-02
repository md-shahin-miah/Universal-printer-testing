package com.valt.printertestapplication.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.valt.printertestapplication.data.PrinterType
import com.valt.printertestapplication.ui.screens.HomeScreen
import com.valt.printertestapplication.ui.screens.PrintingScreen
import com.valt.printertestapplication.viewmodel.PrinterViewModel

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Printing : Screen("printing/{printerType}") {
        fun createRoute(printerType: PrinterType) = "printing/${printerType.name}"
    }
}

@Composable
fun PrinterNavHost(
    navController: NavHostController,
    viewModel: PrinterViewModel
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                viewModel = viewModel,
                onPrinterTypeSelected = { printerType ->
                    navController.navigate(Screen.Printing.createRoute(printerType))
                }
            )
        }

        composable(
            route = Screen.Printing.route,
            arguments = listOf(
                navArgument("printerType") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val printerTypeName = backStackEntry.arguments?.getString("printerType") ?: ""
            val printerType = PrinterType.valueOf(printerTypeName)
            
            PrintingScreen(
                viewModel = viewModel,
                printerType = printerType,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
