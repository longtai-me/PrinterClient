package me.longtai.ticket.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import me.longtai.core.auth.Operator
import me.longtai.core.auth.ui.OperatorsScreen
import me.longtai.core.ui.device.DeviceSettingsScreen
import me.longtai.ticket.ui.issue.IssueScreen
import me.longtai.ticket.ui.logs.LogsScreen
import me.longtai.ticket.ui.redeem.RedeemScreen
import me.longtai.ticket.ui.settings.TicketSettingsScreen
import me.longtai.ticket.ui.tickets.TicketDetailScreen
import me.longtai.ticket.ui.tickets.TicketsScreen

object Routes {
    const val REDEEM = "redeem"
    const val TICKETS = "tickets"
    const val TICKET_DETAIL = "ticket/{id}"
    const val ISSUE = "issue"
    const val LOGS = "logs"
    const val SETTINGS = "settings"
    const val DEVICE = "device"
    const val OPERATORS = "operators"

    fun ticketDetail(id: Long) = "ticket/$id"
}

@Composable
fun TicketNavHost(operator: Operator, onLogout: () -> Unit) {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(navController = nav, startDestination = Routes.REDEEM) {
        composable(Routes.REDEEM) {
            RedeemScreen(operator = operator, onNavigate = { nav.navigate(it) }, onLogout = onLogout)
        }
        composable(Routes.TICKETS) {
            TicketsScreen(
                isAdmin = operator.isAdmin,
                onBack = back,
                onOpen = { nav.navigate(Routes.ticketDetail(it)) },
                onIssue = { nav.navigate(Routes.ISSUE) },
            )
        }
        composable(Routes.TICKET_DETAIL, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            TicketDetailScreen(onBack = back)
        }
        composable(Routes.ISSUE) {
            IssueScreen(onBack = back, onIssued = { id -> nav.navigate(Routes.ticketDetail(id)) { popUpTo(Routes.TICKETS) } })
        }
        composable(Routes.LOGS) { LogsScreen(onBack = back) }
        composable(Routes.SETTINGS) {
            TicketSettingsScreen(
                isAdmin = operator.isAdmin,
                onBack = back,
                onDevice = { nav.navigate(Routes.DEVICE) },
                onOperators = { nav.navigate(Routes.OPERATORS) },
            )
        }
        composable(Routes.DEVICE) { DeviceSettingsScreen(onBack = back) }
        composable(Routes.OPERATORS) { OperatorsScreen(onBack = back) }
    }
}
