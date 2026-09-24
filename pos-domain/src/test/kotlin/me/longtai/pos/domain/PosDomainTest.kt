package me.longtai.pos.domain

import me.longtai.core.common.money.Money
import me.longtai.core.common.money.MoneyFormat
import me.longtai.core.common.print.LabelSize
import me.longtai.core.common.print.PlainTextRenderer
import me.longtai.core.common.text.DisplayWidth
import me.longtai.pos.domain.cart.Cart
import me.longtai.pos.domain.cart.PriceCalculator
import me.longtai.pos.domain.checkout.Cashier
import me.longtai.pos.domain.checkout.CheckoutException
import me.longtai.pos.domain.checkout.OrderFactory
import me.longtai.pos.domain.checkout.OrderNumbers
import me.longtai.pos.domain.checkout.Tender
import me.longtai.pos.domain.checkout.TenderError
import me.longtai.pos.domain.io.ProductCsv
import me.longtai.pos.domain.model.Discount
import me.longtai.pos.domain.model.LoyaltyRule
import me.longtai.pos.domain.model.Member
import me.longtai.pos.domain.model.OrderStatus
import me.longtai.pos.domain.model.PaymentMethod
import me.longtai.pos.domain.model.Product
import me.longtai.pos.domain.model.Shift
import me.longtai.pos.domain.model.StoreProfile
import me.longtai.pos.domain.model.TaxConfig
import me.longtai.pos.domain.model.TaxMode
import me.longtai.pos.domain.print.ReceiptComposer
import me.longtai.pos.domain.report.SalesReport
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PosDomainTest {
    private val apple = Product(id = 1, sku = "A001", barcode = "4710088000019", name = "蘋果", price = Money(30), stockQty = 10)
    private val bag = Product(id = 2, sku = "B001", name = "購物袋", price = Money(2), taxable = false)
    private val milk = Product(id = 3, sku = "M001", name = "鮮乳 936ml", price = Money(95))
    private val tax = TaxConfig(TaxMode.INCLUSIVE, 500)
    private val zone = ZoneId.of("Asia/Taipei")

    @Test
    fun `scanning same product merges lines`() {
        val cart = Cart().add(apple).add(apple).add(milk)
        assertEquals(2, cart.lines.size)
        assertEquals(2, cart.lines[0].quantity)
        assertEquals(3, cart.itemCount)
        val removed = cart.decrement(cart.lines[1].lineId)
        assertEquals(1, removed.lines.size)
    }

    @Test
    fun `discounted line is not merged`() {
        var cart = Cart().add(apple)
        cart = cart.setLineDiscount(cart.lines[0].lineId, Discount.Percent(1000)).add(apple)
        assertEquals(2, cart.lines.size)
    }

    @Test
    fun `inclusive tax is extracted from total`() {
        val totals = PriceCalculator.calculate(Cart().add(milk, 2), tax) // 190
        assertEquals(Money(190), totals.total)
        assertEquals(Money(9), totals.tax) // 190*5/105 = 9.05
    }

    @Test
    fun `exclusive tax is added`() {
        val totals = PriceCalculator.calculate(Cart().add(milk, 2), TaxConfig(TaxMode.EXCLUSIVE, 500))
        assertEquals(Money(10), totals.tax) // 9.5 -> 10
        assertEquals(Money(200), totals.total)
    }

    @Test
    fun `discount order line then member then order`() {
        val member = Member(id = 7, memberNo = "M7", name = "王", discountBp = 1000)
        var cart = Cart().add(apple, 4).add(milk) // 120 + 95
        cart = cart.setLineDiscount(cart.lines[0].lineId, Discount.Amount(Money(20))) // 100 + 95 = 195
            .withMember(member) // -19.5 -> 20 => 175
            .withOrderDiscount(Discount.Amount(Money(15))) // 160
        val t = PriceCalculator.calculate(cart, tax)
        assertEquals(Money(215), t.gross)
        assertEquals(Money(20), t.lineDiscounts)
        assertEquals(Money(195), t.subtotal)
        assertEquals(Money(20), t.memberDiscount)
        assertEquals(Money(15), t.orderDiscount)
        assertEquals(Money(160), t.total)
        assertEquals(Money(55), t.totalDiscount)
    }

    @Test
    fun `tax only applies to taxable share`() {
        val cart = Cart().add(milk).add(bag, 5) // 95 taxable + 10 exempt
        val t = PriceCalculator.calculate(cart, tax)
        assertEquals(Money(105), t.total)
        assertEquals(Money(5), t.tax) // 95*5/105 = 4.52 -> 5
    }

    @Test
    fun `discount never exceeds amount`() {
        val cart = Cart().add(bag).withOrderDiscount(Discount.Amount(Money(999)))
        val t = PriceCalculator.calculate(cart, tax)
        assertEquals(Money.ZERO, t.total)
    }

    @Test
    fun `split tender with cash change`() {
        var tender = Tender(Money(368))
        tender = (tender.add(PaymentMethod.CARD, Money(100), "123456") as Tender.Result.Success).tender
        assertEquals(Money(268), tender.remaining)
        val over = tender.add(PaymentMethod.MOBILE, Money(300))
        assertEquals(TenderError.ExceedsRemaining, (over as Tender.Result.Failure).error)
        tender = (tender.add(PaymentMethod.CASH, Money(500)) as Tender.Result.Success).tender
        assertTrue(tender.isComplete)
        assertEquals(Money(232), tender.change)
        assertEquals(Money(368), tender.paid)
        assertIs<Tender.Result.Failure>(tender.add(PaymentMethod.CASH, Money(1)))
    }

    @Test
    fun `cash suggestions round up to denominations`() {
        val s = Tender.cashSuggestions(Money(368), listOf(Money(100), Money(500), Money(1000)))
        assertEquals(listOf(Money(368), Money(400), Money(500), Money(1000)), s)
        assertEquals(listOf(Money(500), Money(1000)), Tender.cashSuggestions(Money(500), listOf(Money(100), Money(500), Money(1000))))
    }

    @Test
    fun `order factory validates payment and computes points`() {
        val member = Member(id = 3, memberNo = "M3", name = "林", discountBp = 0)
        val cart = Cart().add(milk, 3).withMember(member) // 285
        val totals = PriceCalculator.calculate(cart, tax)
        val cashier = Cashier(1, "小明")
        assertFailsWith<CheckoutException> {
            OrderFactory.create(cart, totals, Tender(totals.total), tax, "X", 0, cashier, 1, LoyaltyRule(Money(100)))
        }
        val tender = (Tender(totals.total).add(PaymentMethod.CASH, Money(300)) as Tender.Result.Success).tender
        val order = OrderFactory.create(cart, totals, tender, tax, "20260924-01-0001", 0, cashier, 1, LoyaltyRule(Money(100)))
        assertEquals(2, order.pointsEarned)
        assertEquals(Money(15), order.change)
        assertEquals(mapOf(3L to -3), OrderFactory.stockDelta(order, refund = false))
        assertEquals(mapOf(3L to 3), OrderFactory.stockDelta(order, refund = true))
    }

    @Test
    fun `order numbers are sortable`() {
        assertEquals("20260924-01-0007", OrderNumbers.format(LocalDate.of(2026, 9, 24), "01", 7))
        assertTrue(OrderNumbers.isValidDeviceCode("A1"))
        assertTrue(!OrderNumbers.isValidDeviceCode("收銀"))
    }

    private fun sampleOrder(no: String, total: Long, method: PaymentMethod, cart: Cart = Cart().add(milk)): me.longtai.pos.domain.model.Order {
        val totals = PriceCalculator.calculate(cart, tax)
        val tender = (Tender(totals.total).add(method, totals.total) as Tender.Result.Success).tender
        val o = OrderFactory.create(cart, totals, tender, tax, no, 0, Cashier(1, "小明"), 1, LoyaltyRule(Money.ZERO))
        assertEquals(Money(total), o.total)
        return o
    }

    @Test
    fun `sales summary nets refunds per method`() {
        val o1 = sampleOrder("1", 95, PaymentMethod.CASH)
        val o2 = sampleOrder("2", 95, PaymentMethod.CARD)
        val o3 = sampleOrder("3", 190, PaymentMethod.CASH, Cart().add(milk, 2))
        val refunded = o1.copy(status = OrderStatus.REFUNDED, refundedAt = 10)
        val summary = SalesReport.summarize(listOf(refunded, o2, o3), listOf(refunded))
        assertEquals(3, summary.orderCount)
        assertEquals(1, summary.refundCount)
        assertEquals(Money(380), summary.grossSales)
        assertEquals(Money(285), summary.netSales)
        assertEquals(Money(190), summary.methodTotal(PaymentMethod.CASH))
        assertEquals(Money(95), summary.methodTotal(PaymentMethod.CARD))
        assertEquals(3, summary.itemCount)
        assertEquals("鮮乳 936ml", summary.topProducts.first().name)

        val shift = Shift(id = 1, operatorId = 1, operatorName = "小明", openedAt = 0, openingCash = Money(1000), countedCash = Money(1180))
        val cash = SalesReport.reconcile(shift, summary)
        assertEquals(Money(1190), cash.expectedCash)
        assertEquals(Money(-10), cash.variance)
    }

    @Test
    fun `receipt renders within paper width`() {
        val member = Member(id = 3, memberNo = "M0003", name = "林小姐", discountBp = 500)
        var cart = Cart().add(apple, 2).add(milk).add(bag).withMember(member)
        cart = cart.setLineDiscount(cart.lines[1].lineId, Discount.Amount(Money(5)))
        val totals = PriceCalculator.calculate(cart, tax)
        val tender = (Tender(totals.total).add(PaymentMethod.CASH, Money(500)) as Tender.Result.Success).tender
        val order = OrderFactory.create(cart, totals, tender, tax, "20260924-01-0001", 1_790_000_000_000, Cashier(1, "小明"), 1, LoyaltyRule(Money(100)))
        val store = StoreProfile("好鄰居商店", "台北市信義區松仁路 100 號", "02-1234-5678", "12345678", "謝謝光臨，歡迎再來！")
        val composer = ReceiptComposer(MoneyFormat(0, "$"), zone)
        for (columns in listOf(32, 48)) {
            val lines = PlainTextRenderer.render(composer.receipt(order, store), columns)
            lines.forEach { assertTrue(DisplayWidth.of(it) <= columns, "too wide at $columns: '$it'") }
            val text = lines.joinToString("\n")
            assertTrue("應收" in text && "找零" in text && "會員折扣" in text && "營業稅(內含 5%)" in text, text)
        }
        val slip = PlainTextRenderer.renderToString(composer.refundSlip(order.copy(refundedAt = 1_790_000_100_000, refundReason = "瑕疵"), store), 32)
        assertTrue("退貨單" in slip && "瑕疵" in slip)
        val label = PlainTextRenderer.renderToString(composer.priceLabel(apple, LabelSize.DEFAULT, "好鄰居"), 32)
        assertTrue("\$30" in label && "EAN13" in label, label)
    }

    @Test
    fun `product csv round trip and error reporting`() {
        val money = MoneyFormat(0)
        val csv = ProductCsv.export(listOf(apple, bag), money)
        val back = ProductCsv.import(csv, money)
        assertEquals(0, back.errors.size, back.errors.toString())
        assertEquals(listOf("A001", "B001"), back.items.map { it.sku })
        assertEquals(false, back.items[1].taxable)
        assertEquals(10, back.items[0].stockQty)

        val bad = "sku,name,price,stock\nX1,好物,abc,1\nX1,好物,10,1.5\n,無 SKU,10,\nX2,好物2,20,\n"
        val result = ProductCsv.import(bad, money)
        assertEquals(listOf("X2"), result.items.map { it.sku })
        assertEquals(listOf(2, 3, 4), result.errors.map { it.line })
        assertTrue(ProductCsv.import("name\nA", money).errors.single().message.contains("sku"))
    }
}
