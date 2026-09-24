package me.longtai.pos.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(
    tableName = "products",
    indices = [Index(value = ["sku"], unique = true), Index(value = ["barcode"]), Index(value = ["name"])],
)
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sku: String,
    val barcode: String?,
    val name: String,
    val priceMinor: Long,
    val category: String?,
    val unit: String?,
    val stockQty: Int?,
    val lowStock: Int?,
    val taxable: Boolean,
    val active: Boolean,
    val updatedAt: Long,
)

@Entity(
    tableName = "members",
    indices = [Index(value = ["memberNo"], unique = true), Index(value = ["cardUid"], unique = true), Index(value = ["phone"])],
)
data class MemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val memberNo: String,
    val name: String,
    val phone: String?,
    val cardUid: String?,
    val discountBp: Int,
    val points: Long,
    val active: Boolean,
    val createdAt: Long,
)

@Entity(tableName = "shifts", indices = [Index(value = ["closedAt"])])
data class ShiftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operatorId: Long,
    val operatorName: String,
    val openedAt: Long,
    val openingCashMinor: Long,
    val closedAt: Long? = null,
    val closedByName: String? = null,
    val countedCashMinor: Long? = null,
    val note: String? = null,
)

@Entity(
    tableName = "orders",
    indices = [
        Index(value = ["orderNo"], unique = true),
        Index(value = ["createdAt"]),
        Index(value = ["shiftId"]),
        Index(value = ["refundedAt"]),
        Index(value = ["refundShiftId"]),
    ],
)
data class OrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderNo: String,
    val createdAt: Long,
    val operatorId: Long,
    val operatorName: String,
    val shiftId: Long,
    val memberId: Long?,
    val memberNo: String?,
    val memberName: String?,
    val grossMinor: Long,
    val lineDiscountsMinor: Long,
    val memberDiscountMinor: Long,
    val orderDiscountMinor: Long,
    val taxMode: String,
    val taxRateBp: Int,
    val taxMinor: Long,
    val totalMinor: Long,
    val pointsEarned: Long,
    val status: String,
    val refundedAt: Long? = null,
    val refundShiftId: Long? = null,
    val refundOperatorName: String? = null,
    val refundReason: String? = null,
)

@Entity(
    tableName = "order_lines",
    foreignKeys = [ForeignKey(entity = OrderEntity::class, parentColumns = ["id"], childColumns = ["orderId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["orderId"]), Index(value = ["productId"])],
)
data class OrderLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderId: Long,
    val position: Int,
    val productId: Long?,
    val sku: String,
    val name: String,
    val unitPriceMinor: Long,
    val quantity: Int,
    val discountMinor: Long,
    val netMinor: Long,
    val taxable: Boolean,
)

@Entity(
    tableName = "payments",
    foreignKeys = [ForeignKey(entity = OrderEntity::class, parentColumns = ["id"], childColumns = ["orderId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["orderId"])],
)
data class PaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderId: Long,
    val position: Int,
    val method: String,
    val amountMinor: Long,
    val tenderedMinor: Long,
    val reference: String?,
)

/** Per business day order number sequence. */
@Entity(tableName = "order_sequences")
data class OrderSequenceEntity(
    @PrimaryKey val day: String,
    val last: Int,
)

data class OrderWithDetails(
    @Embedded val order: OrderEntity,
    @Relation(parentColumn = "id", entityColumn = "orderId")
    val lines: List<OrderLineEntity>,
    @Relation(parentColumn = "id", entityColumn = "orderId")
    val payments: List<PaymentEntity>,
)
