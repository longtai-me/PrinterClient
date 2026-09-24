package me.longtai.pos.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

@Dao
interface ProductDao {
    @Query(
        """
        SELECT * FROM products
        WHERE (:includeInactive OR active = 1)
          AND (:query = '' OR name LIKE '%' || :query || '%' OR sku LIKE :query || '%' OR barcode = :query OR category LIKE '%' || :query || '%')
        ORDER BY active DESC, name COLLATE NOCASE
        LIMIT :limit
        """,
    )
    fun search(query: String, includeInactive: Boolean, limit: Int): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE barcode = :code AND active = 1 LIMIT 1")
    suspend fun findByBarcode(code: String): ProductEntity?

    @Query("SELECT * FROM products WHERE sku = :sku LIMIT 1")
    suspend fun findBySku(sku: String): ProductEntity?

    @Query("SELECT * FROM products WHERE barcode = :barcode AND id != :excludeId LIMIT 1")
    suspend fun findOtherWithBarcode(barcode: String, excludeId: Long): ProductEntity?

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun get(id: Long): ProductEntity?

    @Query("SELECT * FROM products ORDER BY sku")
    suspend fun all(): List<ProductEntity>

    @Query("SELECT COUNT(*) FROM products WHERE active = 1 AND stockQty IS NOT NULL AND lowStock IS NOT NULL AND stockQty <= lowStock")
    fun observeLowStockCount(): Flow<Int>

    @Insert
    suspend fun insert(entity: ProductEntity): Long

    @Update
    suspend fun update(entity: ProductEntity)

    @Query("UPDATE products SET stockQty = stockQty + :delta, updatedAt = :now WHERE id = :id AND stockQty IS NOT NULL")
    suspend fun adjustStock(id: Long, delta: Int, now: Long)
}

@Dao
interface MemberDao {
    @Query(
        """
        SELECT * FROM members
        WHERE (:query = '' OR name LIKE '%' || :query || '%' OR memberNo LIKE :query || '%' OR phone LIKE '%' || :query || '%')
        ORDER BY active DESC, memberNo
        LIMIT 500
        """,
    )
    fun search(query: String): Flow<List<MemberEntity>>

    @Query("SELECT * FROM members WHERE cardUid = :uid LIMIT 1")
    suspend fun findByCardUid(uid: String): MemberEntity?

    @Query("SELECT * FROM members WHERE memberNo = :memberNo LIMIT 1")
    suspend fun findByMemberNo(memberNo: String): MemberEntity?

    @Query("SELECT * FROM members WHERE phone = :phone AND active = 1 LIMIT 1")
    suspend fun findByPhone(phone: String): MemberEntity?

    @Query("SELECT * FROM members WHERE id = :id")
    suspend fun get(id: Long): MemberEntity?

    @Query("SELECT * FROM members ORDER BY memberNo")
    suspend fun all(): List<MemberEntity>

    @Insert
    suspend fun insert(entity: MemberEntity): Long

    @Update
    suspend fun update(entity: MemberEntity)

    @Query("UPDATE members SET points = MAX(0, points + :delta) WHERE id = :id")
    suspend fun addPoints(id: Long, delta: Long)
}

@Dao
interface ShiftDao {
    @Query("SELECT * FROM shifts WHERE closedAt IS NULL ORDER BY openedAt DESC LIMIT 1")
    fun observeOpen(): Flow<ShiftEntity?>

    @Query("SELECT * FROM shifts WHERE closedAt IS NULL ORDER BY openedAt DESC LIMIT 1")
    suspend fun currentOpen(): ShiftEntity?

    @Query("SELECT * FROM shifts WHERE id = :id")
    suspend fun get(id: Long): ShiftEntity?

    @Query("SELECT * FROM shifts ORDER BY openedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ShiftEntity>>

    @Insert
    suspend fun insert(entity: ShiftEntity): Long

    @Update
    suspend fun update(entity: ShiftEntity)
}

@Dao
interface OrderDao {
    @Insert
    suspend fun insert(order: OrderEntity): Long

    @Insert
    suspend fun insertLines(lines: List<OrderLineEntity>)

    @Insert
    suspend fun insertPayments(payments: List<PaymentEntity>)

    @Transaction
    @Query("SELECT * FROM orders WHERE id = :id")
    fun observe(id: Long): Flow<OrderWithDetails?>

    @Transaction
    @Query("SELECT * FROM orders WHERE id = :id")
    suspend fun get(id: Long): OrderWithDetails?

    @Transaction
    @Query("SELECT * FROM orders WHERE orderNo = :orderNo LIMIT 1")
    suspend fun findByOrderNo(orderNo: String): OrderWithDetails?

    @Transaction
    @Query("SELECT * FROM orders WHERE createdAt >= :from AND createdAt < :to ORDER BY createdAt DESC")
    fun observeCreatedBetween(from: Long, to: Long): Flow<List<OrderWithDetails>>

    @Transaction
    @Query("SELECT * FROM orders WHERE createdAt >= :from AND createdAt < :to ORDER BY createdAt")
    suspend fun createdBetween(from: Long, to: Long): List<OrderWithDetails>

    @Transaction
    @Query("SELECT * FROM orders WHERE refundedAt >= :from AND refundedAt < :to ORDER BY refundedAt")
    suspend fun refundedBetween(from: Long, to: Long): List<OrderWithDetails>

    @Transaction
    @Query("SELECT * FROM orders WHERE shiftId = :shiftId ORDER BY createdAt")
    suspend fun byShift(shiftId: Long): List<OrderWithDetails>

    @Transaction
    @Query("SELECT * FROM orders WHERE refundShiftId = :shiftId ORDER BY refundedAt")
    suspend fun refundedInShift(shiftId: Long): List<OrderWithDetails>

    @Query("SELECT COUNT(*) FROM orders WHERE shiftId = :shiftId")
    fun observeCountInShift(shiftId: Long): Flow<Int>

    @Query(
        """
        UPDATE orders SET status = :status, refundedAt = :at, refundShiftId = :shiftId,
            refundOperatorName = :operatorName, refundReason = :reason
        WHERE id = :id AND status = 'COMPLETED'
        """,
    )
    suspend fun markRefunded(id: Long, status: String, at: Long, shiftId: Long, operatorName: String, reason: String?): Int

    @Query("SELECT last FROM order_sequences WHERE day = :day")
    suspend fun lastSequence(day: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSequence(sequence: OrderSequenceEntity)
}

@Database(
    entities = [
        ProductEntity::class,
        MemberEntity::class,
        ShiftEntity::class,
        OrderEntity::class,
        OrderLineEntity::class,
        PaymentEntity::class,
        OrderSequenceEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class PosDatabase : RoomDatabase() {
    abstract fun products(): ProductDao
    abstract fun members(): MemberDao
    abstract fun shifts(): ShiftDao
    abstract fun orders(): OrderDao
}

@Module
@InstallIn(SingletonComponent::class)
object PosDatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): PosDatabase =
        Room.databaseBuilder(context, PosDatabase::class.java, "pos.db").build()

    @Provides fun productDao(db: PosDatabase): ProductDao = db.products()
    @Provides fun memberDao(db: PosDatabase): MemberDao = db.members()
    @Provides fun shiftDao(db: PosDatabase): ShiftDao = db.shifts()
    @Provides fun orderDao(db: PosDatabase): OrderDao = db.orders()
}
