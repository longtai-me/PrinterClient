package me.longtai.pos.data

import me.longtai.core.common.money.Money
import me.longtai.pos.data.db.MemberEntity
import me.longtai.pos.data.db.OrderEntity
import me.longtai.pos.data.db.OrderLineEntity
import me.longtai.pos.data.db.OrderWithDetails
import me.longtai.pos.data.db.PaymentEntity
import me.longtai.pos.data.db.ProductEntity
import me.longtai.pos.data.db.ShiftEntity
import me.longtai.pos.domain.model.Member
import me.longtai.pos.domain.model.Order
import me.longtai.pos.domain.model.OrderLine
import me.longtai.pos.domain.model.OrderStatus
import me.longtai.pos.domain.model.Payment
import me.longtai.pos.domain.model.PaymentMethod
import me.longtai.pos.domain.model.Product
import me.longtai.pos.domain.model.Shift
import me.longtai.pos.domain.model.TaxMode

fun ProductEntity.toModel() = Product(
    id = id, sku = sku, barcode = barcode, name = name, price = Money(priceMinor), category = category, unit = unit,
    stockQty = stockQty, lowStockThreshold = lowStock, taxable = taxable, active = active,
)

fun Product.toEntity(now: Long) = ProductEntity(
    id = id, sku = sku, barcode = barcode, name = name, priceMinor = price.minor, category = category, unit = unit,
    stockQty = stockQty, lowStock = lowStockThreshold, taxable = taxable, active = active, updatedAt = now,
)

fun MemberEntity.toModel() = Member(
    id = id, memberNo = memberNo, name = name, phone = phone, cardUid = cardUid, discountBp = discountBp, points = points, active = active,
)

fun Member.toEntity(createdAt: Long) = MemberEntity(
    id = id, memberNo = memberNo, name = name, phone = phone, cardUid = cardUid, discountBp = discountBp, points = points,
    active = active, createdAt = createdAt,
)

fun ShiftEntity.toModel() = Shift(
    id = id, operatorId = operatorId, operatorName = operatorName, openedAt = openedAt, openingCash = Money(openingCashMinor),
    closedAt = closedAt, closedByName = closedByName, countedCash = countedCashMinor?.let { Money(it) }, note = note,
)

fun OrderWithDetails.toModel() = Order(
    id = order.id,
    orderNo = order.orderNo,
    createdAt = order.createdAt,
    operatorId = order.operatorId,
    operatorName = order.operatorName,
    shiftId = order.shiftId,
    memberId = order.memberId,
    memberNo = order.memberNo,
    memberName = order.memberName,
    lines = lines.sortedBy { it.position }.map {
        OrderLine(it.productId, it.sku, it.name, Money(it.unitPriceMinor), it.quantity, Money(it.discountMinor), Money(it.netMinor), it.taxable)
    },
    gross = Money(order.grossMinor),
    lineDiscounts = Money(order.lineDiscountsMinor),
    memberDiscount = Money(order.memberDiscountMinor),
    orderDiscount = Money(order.orderDiscountMinor),
    taxMode = runCatching { TaxMode.valueOf(order.taxMode) }.getOrDefault(TaxMode.INCLUSIVE),
    taxRateBp = order.taxRateBp,
    tax = Money(order.taxMinor),
    total = Money(order.totalMinor),
    payments = payments.sortedBy { it.position }.map {
        Payment(
            runCatching { PaymentMethod.valueOf(it.method) }.getOrDefault(PaymentMethod.OTHER),
            Money(it.amountMinor),
            Money(it.tenderedMinor),
            it.reference,
        )
    },
    pointsEarned = order.pointsEarned,
    status = runCatching { OrderStatus.valueOf(order.status) }.getOrDefault(OrderStatus.COMPLETED),
    refundedAt = order.refundedAt,
    refundShiftId = order.refundShiftId,
    refundOperatorName = order.refundOperatorName,
    refundReason = order.refundReason,
)

fun Order.toEntity() = OrderEntity(
    id = id,
    orderNo = orderNo,
    createdAt = createdAt,
    operatorId = operatorId,
    operatorName = operatorName,
    shiftId = shiftId,
    memberId = memberId,
    memberNo = memberNo,
    memberName = memberName,
    grossMinor = gross.minor,
    lineDiscountsMinor = lineDiscounts.minor,
    memberDiscountMinor = memberDiscount.minor,
    orderDiscountMinor = orderDiscount.minor,
    taxMode = taxMode.name,
    taxRateBp = taxRateBp,
    taxMinor = tax.minor,
    totalMinor = total.minor,
    pointsEarned = pointsEarned,
    status = status.name,
    refundedAt = refundedAt,
    refundShiftId = refundShiftId,
    refundOperatorName = refundOperatorName,
    refundReason = refundReason,
)

fun Order.lineEntities(orderId: Long) = lines.mapIndexed { index, l ->
    OrderLineEntity(
        orderId = orderId, position = index, productId = l.productId, sku = l.sku, name = l.name,
        unitPriceMinor = l.unitPrice.minor, quantity = l.quantity, discountMinor = l.discount.minor, netMinor = l.net.minor,
        taxable = l.taxable,
    )
}

fun Order.paymentEntities(orderId: Long) = payments.mapIndexed { index, p ->
    PaymentEntity(
        orderId = orderId, position = index, method = p.method.name, amountMinor = p.amount.minor,
        tenderedMinor = p.tendered.minor, reference = p.reference,
    )
}
