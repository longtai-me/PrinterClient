package me.longtai.pos.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import me.longtai.core.auth.Operator
import me.longtai.core.auth.ui.OperatorsScreen
import me.longtai.core.ui.device.DeviceSettingsScreen
import me.longtai.pos.ui.members.MemberEditScreen
import me.longtai.pos.ui.members.MembersScreen
import me.longtai.pos.ui.orders.OrderDetailScreen
import me.longtai.pos.ui.orders.OrdersScreen
import me.longtai.pos.ui.payment.PaymentScreen
import me.longtai.pos.ui.products.ProductEditScreen
import me.longtai.pos.ui.products.ProductsScreen
import me.longtai.pos.ui.sale.SaleScreen
import me.longtai.pos.ui.settings.SettingsScreen
import me.longtai.pos.ui.shift.ShiftScreen

object Routes {
    const val SALE = "sale"
    const val PAYMENT = "payment"
    const val PRODUCTS = "products"
    const val PRODUCT_EDIT = "product/{id}?barcode={barcode}"
    const val MEMBERS = "members"
    const val MEMBER_EDIT = "member/{id}?uid={uid}"
    const val ORDERS = "orders"
    const val ORDER_DETAIL = "order/{id}"
    const val SHIFT = "shift"
    const val SETTINGS = "settings"
    const val DEVICE = "device"
    const val OPERATORS = "operators"

    fun productEdit(id: Long, barcode: String? = null) =
        "product/$id" + (barcode?.let { "?barcode=${android.net.Uri.encode(it)}" } ?: "")

    fun memberEdit(id: Long, uid: String? = null) = "member/$id" + (uid?.let { "?uid=${android.net.Uri.encode(it)}" } ?: "")

    fun orderDetail(id: Long) = "order/$id"
}

@Composable
fun PosNavHost(operator: Operator, onLogout: () -> Unit) {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(navController = nav, startDestination = Routes.SALE) {
        composable(Routes.SALE) {
            SaleScreen(
                operator = operator,
                onPay = { nav.navigate(Routes.PAYMENT) },
                onNavigate = { route -> nav.navigate(route) },
                onLogout = onLogout,
            )
        }
        composable(Routes.PAYMENT) {
            PaymentScreen(onBack = back, onDone = { nav.popBackStack(Routes.SALE, inclusive = false) })
        }
        composable(Routes.PRODUCTS) {
            ProductsScreen(
                canEdit = operator.isAdmin,
                onBack = back,
                onOpen = { id -> nav.navigate(Routes.productEdit(id)) },
            )
        }
        composable(
            Routes.PRODUCT_EDIT,
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("barcode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            ProductEditScreen(canEdit = operator.isAdmin, onBack = back)
        }
        composable(Routes.MEMBERS) {
            MembersScreen(onBack = back, onOpen = { id -> nav.navigate(Routes.memberEdit(id)) })
        }
        composable(
            Routes.MEMBER_EDIT,
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("uid") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            MemberEditScreen(onBack = back)
        }
        composable(Routes.ORDERS) {
            OrdersScreen(onBack = back, onOpen = { id -> nav.navigate(Routes.orderDetail(id)) })
        }
        composable(Routes.ORDER_DETAIL, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            OrderDetailScreen(onBack = back)
        }
        composable(Routes.SHIFT) {
            ShiftScreen(onBack = back, onShiftClosed = onLogout)
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
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
