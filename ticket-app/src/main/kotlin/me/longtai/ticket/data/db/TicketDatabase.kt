package me.longtai.ticket.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

@Entity(
    tableName = "tickets",
    indices = [
        Index(value = ["code"], unique = true),
        Index(value = ["nfcUid"], unique = true),
        Index(value = ["holderName"]),
        Index(value = ["createdAt"]),
    ],
)
data class TicketEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val nfcUid: String?,
    val type: String,
    val holderName: String?,
    val zone: String?,
    val validFrom: Long?,
    val validUntil: Long?,
    val maxUses: Int?,
    val usedCount: Int,
    val status: String,
    val inside: Boolean,
    val lastEntryAt: Long?,
    val lastExitAt: Long?,
    val createdAt: Long,
    val note: String?,
)

@Entity(
    tableName = "redemptions",
    indices = [Index(value = ["at"]), Index(value = ["ticketId"]), Index(value = ["code"])],
)
data class RedemptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val code: String,
    val ticketId: Long?,
    val holderName: String?,
    val ticketType: String?,
    val direction: String,
    val accepted: Boolean,
    val reason: String?,
    val gateName: String,
    val operatorName: String,
    val source: String,
)

@Dao
interface TicketDao {
    @Query(
        """
        SELECT * FROM tickets
        WHERE (:query = '' OR code LIKE :query || '%' OR holderName LIKE '%' || :query || '%' OR nfcUid = :query)
          AND (:filter = 'ALL'
               OR (:filter = 'ACTIVE' AND status = 'ACTIVE' AND (maxUses IS NULL OR usedCount < maxUses))
               OR (:filter = 'USED' AND maxUses IS NOT NULL AND usedCount >= maxUses)
               OR (:filter = 'VOID' AND status = 'VOID')
               OR (:filter = 'INSIDE' AND inside = 1))
        ORDER BY createdAt DESC
        LIMIT :limit
        """,
    )
    fun search(query: String, filter: String, limit: Int): Flow<List<TicketEntity>>

    @Query("SELECT * FROM tickets WHERE code = :code LIMIT 1")
    suspend fun findByCode(code: String): TicketEntity?

    @Query("SELECT * FROM tickets WHERE nfcUid = :uid LIMIT 1")
    suspend fun findByNfcUid(uid: String): TicketEntity?

    @Query("SELECT * FROM tickets WHERE id = :id")
    suspend fun get(id: Long): TicketEntity?

    @Query("SELECT * FROM tickets WHERE id = :id")
    fun observe(id: Long): Flow<TicketEntity?>

    @Query("SELECT * FROM tickets ORDER BY createdAt")
    suspend fun all(): List<TicketEntity>

    @Query("SELECT COUNT(*) FROM tickets WHERE inside = 1")
    fun observeInsideCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tickets WHERE inside = 1")
    suspend fun insideCount(): Int

    @Insert
    suspend fun insert(entity: TicketEntity): Long

    @Update
    suspend fun update(entity: TicketEntity)
}

data class GateCounts(
    val accepted: Int,
    val rejected: Int,
    val entries: Int,
    val exits: Int,
)

@Dao
interface RedemptionDao {
    @Insert
    suspend fun insert(entity: RedemptionEntity): Long

    @Query(
        """
        SELECT * FROM redemptions
        WHERE at >= :from AND at < :to
          AND (:filter = 'ALL' OR (:filter = 'ACCEPTED' AND accepted = 1) OR (:filter = 'REJECTED' AND accepted = 0))
        ORDER BY at DESC
        LIMIT :limit
        """,
    )
    fun observeBetween(from: Long, to: Long, filter: String, limit: Int): Flow<List<RedemptionEntity>>

    @Query("SELECT * FROM redemptions WHERE at >= :from AND at < :to ORDER BY at")
    suspend fun between(from: Long, to: Long): List<RedemptionEntity>

    @Query("SELECT * FROM redemptions WHERE ticketId = :ticketId ORDER BY at DESC LIMIT 100")
    fun observeForTicket(ticketId: Long): Flow<List<RedemptionEntity>>

    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN accepted = 1 THEN 1 ELSE 0 END), 0) AS accepted,
            COALESCE(SUM(CASE WHEN accepted = 0 THEN 1 ELSE 0 END), 0) AS rejected,
            COALESCE(SUM(CASE WHEN accepted = 1 AND direction = 'ENTRY' THEN 1 ELSE 0 END), 0) AS entries,
            COALESCE(SUM(CASE WHEN accepted = 1 AND direction = 'EXIT' THEN 1 ELSE 0 END), 0) AS exits
        FROM redemptions WHERE at >= :from AND at < :to
        """,
    )
    fun observeCounts(from: Long, to: Long): Flow<GateCounts>

    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN accepted = 1 THEN 1 ELSE 0 END), 0) AS accepted,
            COALESCE(SUM(CASE WHEN accepted = 0 THEN 1 ELSE 0 END), 0) AS rejected,
            COALESCE(SUM(CASE WHEN accepted = 1 AND direction = 'ENTRY' THEN 1 ELSE 0 END), 0) AS entries,
            COALESCE(SUM(CASE WHEN accepted = 1 AND direction = 'EXIT' THEN 1 ELSE 0 END), 0) AS exits
        FROM redemptions WHERE at >= :from AND at < :to
        """,
    )
    suspend fun counts(from: Long, to: Long): GateCounts
}

@Database(entities = [TicketEntity::class, RedemptionEntity::class], version = 1, exportSchema = true)
abstract class TicketDatabase : RoomDatabase() {
    abstract fun tickets(): TicketDao
    abstract fun redemptions(): RedemptionDao
}

@Module
@InstallIn(SingletonComponent::class)
object TicketDatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): TicketDatabase =
        Room.databaseBuilder(context, TicketDatabase::class.java, "tickets.db").build()

    @Provides fun ticketDao(db: TicketDatabase): TicketDao = db.tickets()
    @Provides fun redemptionDao(db: TicketDatabase): RedemptionDao = db.redemptions()
}
