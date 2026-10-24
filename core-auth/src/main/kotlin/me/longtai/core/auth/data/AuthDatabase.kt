package me.longtai.core.auth.data

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

@Entity(tableName = "operators", indices = [Index(value = ["name"], unique = true)])
data class OperatorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val pinHash: String,
    val role: String,
    val active: Boolean = true,
    val createdAt: Long,
    val failedAttempts: Int = 0,
    val lockedUntil: Long = 0,
    val lastLoginAt: Long? = null,
)

@Dao
interface OperatorDao {
    @Query("SELECT * FROM operators ORDER BY active DESC, role ASC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<OperatorEntity>>

    @Query("SELECT * FROM operators WHERE id = :id")
    suspend fun get(id: Long): OperatorEntity?

    @Query("SELECT * FROM operators WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): OperatorEntity?

    @Query("SELECT * FROM operators WHERE role = :role AND active = 1")
    suspend fun activeByRole(role: String): List<OperatorEntity>

    @Query("SELECT COUNT(*) FROM operators")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM operators WHERE role = :role AND active = 1")
    suspend fun countActiveByRole(role: String): Int

    @Insert
    suspend fun insert(entity: OperatorEntity): Long

    @Update
    suspend fun update(entity: OperatorEntity)
}

@Database(entities = [OperatorEntity::class], version = 1, exportSchema = true)
abstract class AuthDatabase : RoomDatabase() {
    abstract fun operators(): OperatorDao
}

@Module
@InstallIn(SingletonComponent::class)
object AuthDatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AuthDatabase =
        Room.databaseBuilder(context, AuthDatabase::class.java, "auth.db").build()

    @Provides
    fun operatorDao(db: AuthDatabase): OperatorDao = db.operators()
}
