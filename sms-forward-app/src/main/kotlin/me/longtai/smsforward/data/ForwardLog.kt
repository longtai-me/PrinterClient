package me.longtai.smsforward.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

enum class ForwardStatus(val label: String) {
    SENT("已轉發"),
    FAILED("轉發失敗"),
    SKIPPED("略過"),
}

@Entity(tableName = "forward_log")
data class ForwardLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receivedAt: Long,
    val fromNumber: String,
    val targetNumber: String,
    /** Truncated preview only; the full body is never persisted beyond the forward itself. */
    val preview: String,
    val status: String,
    val reason: String?,
)

@Dao
interface ForwardLogDao {
    @Insert
    suspend fun insert(entity: ForwardLogEntity): Long

    @Query("SELECT * FROM forward_log ORDER BY receivedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<ForwardLogEntity>>

    @Query("SELECT COUNT(*) FROM forward_log WHERE status = 'SENT'")
    fun sentCount(): Flow<Int>

    @Query("DELETE FROM forward_log")
    suspend fun clear()

    @Query("DELETE FROM forward_log WHERE receivedAt < :before")
    suspend fun purgeOlderThan(before: Long)
}

@Database(entities = [ForwardLogEntity::class], version = 1, exportSchema = true)
abstract class ForwardDatabase : RoomDatabase() {
    abstract fun logs(): ForwardLogDao
}

@Module
@InstallIn(SingletonComponent::class)
object ForwardDatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ForwardDatabase =
        Room.databaseBuilder(context, ForwardDatabase::class.java, "sms_forward.db").build()

    @Provides
    fun logDao(db: ForwardDatabase): ForwardLogDao = db.logs()
}

object PhoneNumbers {
    fun normalize(raw: String): String = raw.filter { it.isDigit() }

    /** True when two numbers refer to the same line, comparing the trailing significant digits. */
    fun sameNumber(a: String, b: String): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        val len = minOf(na.length, nb.length, 9)
        return na.takeLast(len) == nb.takeLast(len)
    }
}
