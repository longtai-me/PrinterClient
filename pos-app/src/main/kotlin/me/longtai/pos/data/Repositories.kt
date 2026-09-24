package me.longtai.pos.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.longtai.core.common.barcode.BarcodeCheck
import me.longtai.core.common.codec.Hex
import me.longtai.core.common.money.Money
import me.longtai.core.common.time.TimeFormats
import me.longtai.pos.data.db.MemberDao
import me.longtai.pos.data.db.OrderDao
import me.longtai.pos.data.db.OrderSequenceEntity
import me.longtai.pos.data.db.PosDatabase
import me.longtai.pos.data.db.ProductDao
import me.longtai.pos.data.db.ShiftDao
import me.longtai.pos.data.db.ShiftEntity
import me.longtai.pos.domain.checkout.OrderFactory
import me.longtai.pos.domain.checkout.OrderNumbers
import me.longtai.pos.domain.io.ImportResult
import me.longtai.pos.domain.io.ProductCsv
import me.longtai.pos.domain.model.Member
import me.longtai.pos.domain.model.Order
import me.longtai.pos.domain.model.OrderStatus
import me.longtai.pos.domain.model.Product
import me.longtai.pos.domain.model.Shift
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

class ValidationException(message: String) : IllegalArgumentException(message)

@Singleton
class ProductRepository @Inject constructor(
    private val dao: ProductDao,
    private val settings: PosSettingsRepository,
) {
    fun search(query: String, includeInactive: Boolean): Flow<List<Product>> =
        dao.search(query.trim(), includeInactive, 500).map { list -> list.map { it.toModel() } }

    val lowStockCount: Flow<Int> = dao.observeLowStockCount()

    suspend fun get(id: Long): Product? = dao.get(id)?.toModel()

    /** Resolves a scanned code: active product by barcode first, then by SKU. */
    suspend fun findByCode(code: String): Product? {
        val c = code.trim()
        if (c.isEmpty()) return null
        return (dao.findByBarcode(c) ?: dao.findBySku(c)?.takeIf { it.active })?.toModel()
    }

    suspend fun save(product: Product): Product {
        val sku = product.sku.trim()
        val name = product.name.trim()
        val barcode = product.barcode?.trim()?.ifEmpty { null }
        if (sku.isEmpty() || sku.length > 40) throw ValidationException("SKU 需為 1–40 個字元")
        if (name.isEmpty() || name.length > 60) throw ValidationException("品名需為 1–60 個字")
        if (product.price.isNegative) throw ValidationException("售價不可為負數")
        if (barcode != null && !BarcodeCheck.isPrintableCode128(barcode)) throw ValidationException("條碼只能包含英數與符號")
        dao.findBySku(sku)?.let { if (it.id != product.id) throw ValidationException("SKU「$sku」已被「${it.name}」使用") }
        if (barcode != null) {
            dao.findOtherWithBarcode(barcode, product.id)?.let { throw ValidationException("條碼已被「${it.name}」使用") }
        }
        val clean = product.copy(
            sku = sku, name = name, barcode = barcode,
            category = product.category?.trim()?.ifEmpty { null }, unit = product.unit?.trim()?.ifEmpty { null },
        )
        val now = System.currentTimeMillis()
        return if (clean.id == 0L) {
            clean.copy(id = dao.insert(clean.toEntity(now)))
        } else {
            dao.update(clean.toEntity(now))
            clean
        }
    }

    suspend fun setActive(id: Long, active: Boolean) {
        val entity = dao.get(id) ?: return
        dao.update(entity.copy(active = active, updatedAt = System.currentTimeMillis()))
    }

    suspend fun exportCsv(): String = ProductCsv.export(dao.all().map { it.toModel() }, settings.settings.value.money)

    data class ImportSummary(val inserted: Int, val updated: Int, val errors: List<String>)

    /** Upserts by SKU; rows that fail validation are reported and skipped. */
    suspend fun importCsv(text: String): ImportSummary {
        val parsed: ImportResult<Product> = ProductCsv.import(text, settings.settings.value.money)
        val errors = parsed.errors.map { "第 ${it.line} 行：${it.message}" }.toMutableList()
        var inserted = 0
        var updated = 0
        for (p in parsed.items) {
            val existing = dao.findBySku(p.sku)
            try {
                save(p.copy(id = existing?.id ?: 0))
                if (existing == null) inserted++ else updated++
            } catch (e: ValidationException) {
                errors += "SKU ${p.sku}：${e.message}"
            }
        }
        return ImportSummary(inserted, updated, errors)
    }
}

@Singleton
class MemberRepository @Inject constructor(
    private val dao: MemberDao,
) {
    fun search(query: String): Flow<List<Member>> = dao.search(query.trim()).map { list -> list.map { it.toModel() } }

    suspend fun get(id: Long): Member? = dao.get(id)?.toModel()

    suspend fun findByCard(uid: String): Member? = dao.findByCardUid(Hex.normalize(uid) ?: uid)?.toModel()?.takeIf { it.active }

    /** Member number, phone number or card UID typed/scanned by the cashier. */
    suspend fun findByCode(code: String): Member? {
        val c = code.trim()
        if (c.isEmpty()) return null
        return (dao.findByMemberNo(c) ?: dao.findByPhone(c))?.toModel()?.takeIf { it.active }
    }

    suspend fun save(member: Member): Member {
        val no = member.memberNo.trim()
        val name = member.name.trim()
        val uid = member.cardUid?.trim()?.ifEmpty { null }?.let { Hex.normalize(it) ?: throw ValidationException("卡號格式錯誤") }
        if (no.isEmpty() || no.length > 20) throw ValidationException("會員編號需為 1–20 個字元")
        if (name.isEmpty()) throw ValidationException("請輸入會員姓名")
        if (member.discountBp !in 0..10_000) throw ValidationException("折扣需介於 0–100%")
        dao.findByMemberNo(no)?.let { if (it.id != member.id) throw ValidationException("會員編號「$no」已存在") }
        if (uid != null) dao.findByCardUid(uid)?.let { if (it.id != member.id) throw ValidationException("此卡已綁定會員「${it.name}」") }
        val clean = member.copy(memberNo = no, name = name, cardUid = uid, phone = member.phone?.trim()?.ifEmpty { null })
        return if (clean.id == 0L) {
            clean.copy(id = dao.insert(clean.toEntity(System.currentTimeMillis())))
        } else {
            val existing = dao.get(clean.id) ?: throw ValidationException("找不到會員")
            dao.update(clean.toEntity(existing.createdAt).copy(points = existing.points))
            clean.copy(points = existing.points)
        }
    }

    suspend fun exportCsv(): String = me.longtai.pos.domain.io.MemberCsv.export(dao.all().map { it.toModel() })
}

/** Orders, refunds and shifts. All multi-table writes run in a single transaction. */
@Singleton
class SalesRepository @Inject constructor(
    private val db: PosDatabase,
    private val orders: OrderDao,
    private val shifts: ShiftDao,
    private val products: ProductDao,
    private val members: MemberDao,
    private val settings: PosSettingsRepository,
) {
    val openShift: Flow<Shift?> = shifts.observeOpen().map { it?.toModel() }

    fun recentShifts(): Flow<List<Shift>> = shifts.observeRecent(30).map { list -> list.map { it.toModel() } }

    suspend fun currentShift(): Shift? = shifts.currentOpen()?.toModel()

    suspend fun openShift(operatorId: Long, operatorName: String, openingCash: Money): Shift {
        if (openingCash.isNegative) throw ValidationException("零用金不可為負數")
        shifts.currentOpen()?.let { throw ValidationException("已有開啟中的班別（${it.operatorName}）") }
        val entity = ShiftEntity(operatorId = operatorId, operatorName = operatorName, openedAt = System.currentTimeMillis(), openingCashMinor = openingCash.minor)
        return entity.copy(id = shifts.insert(entity)).toModel()
    }

    suspend fun closeShift(shiftId: Long, closedByName: String, countedCash: Money, note: String?): Shift {
        val entity = shifts.get(shiftId) ?: throw ValidationException("找不到班別")
        if (entity.closedAt != null) throw ValidationException("班別已結束")
        val closed = entity.copy(
            closedAt = System.currentTimeMillis(), closedByName = closedByName,
            countedCashMinor = countedCash.minor, note = note?.trim()?.ifEmpty { null },
        )
        shifts.update(closed)
        return closed.toModel()
    }

    suspend fun getShift(id: Long): Shift? = shifts.get(id)?.toModel()

    /**
     * Persists a completed sale: allocates the daily order number, stores order, lines and
     * payments, decrements tracked stock and credits member points — atomically.
     */
    suspend fun saveSale(build: (orderNo: String) -> Order): Order = db.withTransaction {
        val s = settings.settings.value
        val now = System.currentTimeMillis()
        val today = TimeFormats.localDate(now, s.zone)
        val order = build(OrderNumbers.format(today, s.deviceCode, nextSequence(today)))
        val id = orders.insert(order.toEntity())
        orders.insertLines(order.lineEntities(id))
        orders.insertPayments(order.paymentEntities(id))
        OrderFactory.stockDelta(order, refund = false).forEach { (productId, delta) -> products.adjustStock(productId, delta, now) }
        order.memberId?.let { if (order.pointsEarned > 0) members.addPoints(it, order.pointsEarned) }
        order.copy(id = id)
    }

    private suspend fun nextSequence(day: LocalDate): Int {
        val key = TimeFormats.compactDate(day)
        val next = (orders.lastSequence(key) ?: 0) + 1
        orders.setSequence(OrderSequenceEntity(key, next))
        return next
    }

    /** Full refund: restocks items and reverses earned points. */
    suspend fun refund(orderId: Long, shiftId: Long, operatorName: String, reason: String?): Order = db.withTransaction {
        val existing = orders.get(orderId)?.toModel() ?: throw ValidationException("找不到交易")
        if (existing.status != OrderStatus.COMPLETED) throw ValidationException("此交易已退貨")
        val now = System.currentTimeMillis()
        val changed = orders.markRefunded(orderId, OrderStatus.REFUNDED.name, now, shiftId, operatorName, reason?.trim()?.ifEmpty { null })
        if (changed != 1) throw ValidationException("此交易已退貨")
        OrderFactory.stockDelta(existing, refund = true).forEach { (productId, delta) -> products.adjustStock(productId, delta, now) }
        existing.memberId?.let { if (existing.pointsEarned > 0) members.addPoints(it, -existing.pointsEarned) }
        orders.get(orderId)!!.toModel()
    }

    fun observeOrder(id: Long): Flow<Order?> = orders.observe(id).map { it?.toModel() }

    suspend fun getOrder(id: Long): Order? = orders.get(id)?.toModel()

    suspend fun findOrderByNo(orderNo: String): Order? = orders.findByOrderNo(orderNo.trim())?.toModel()

    fun ordersOn(date: LocalDate): Flow<List<Order>> {
        val range = TimeFormats.dayRange(date, settings.settings.value.zone)
        return orders.observeCreatedBetween(range.first, range.last + 1).map { list -> list.map { it.toModel() } }
    }

    suspend fun salesAndRefundsOn(date: LocalDate): Pair<List<Order>, List<Order>> {
        val range = TimeFormats.dayRange(date, settings.settings.value.zone)
        val sales = orders.createdBetween(range.first, range.last + 1).map { it.toModel() }
        val refunds = orders.refundedBetween(range.first, range.last + 1).map { it.toModel() }
        return sales to refunds
    }

    suspend fun salesAndRefundsInShift(shiftId: Long): Pair<List<Order>, List<Order>> =
        orders.byShift(shiftId).map { it.toModel() } to orders.refundedInShift(shiftId).map { it.toModel() }

    suspend fun ordersBetween(fromDate: LocalDate, toDate: LocalDate): List<Order> {
        val zone = settings.settings.value.zone
        val from = TimeFormats.dayRange(fromDate, zone).first
        val to = TimeFormats.dayRange(toDate, zone).last + 1
        return orders.createdBetween(from, to).map { it.toModel() }
    }
}
