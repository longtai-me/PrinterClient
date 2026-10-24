package me.longtai.core.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.longtai.core.auth.data.OperatorDao
import me.longtai.core.auth.data.OperatorEntity
import me.longtai.core.common.security.PinHasher
import javax.inject.Inject
import javax.inject.Singleton

enum class Role(val label: String) {
    ADMIN("管理員"),
    STAFF("一般人員"),
}

data class Operator(
    val id: Long,
    val name: String,
    val role: Role,
    val active: Boolean,
    val lockedUntil: Long,
    val lastLoginAt: Long?,
) {
    val isAdmin: Boolean get() = role == Role.ADMIN
}

sealed interface AuthResult {
    data class Success(val operator: Operator) : AuthResult
    data class WrongPin(val attemptsLeft: Int) : AuthResult
    data class Locked(val untilMs: Long) : AuthResult
    data object Inactive : AuthResult
    data object NotFound : AuthResult
}

class AuthException(message: String) : IllegalArgumentException(message)

@Singleton
class OperatorRepository @Inject constructor(
    private val dao: OperatorDao,
) {
    val operators: Flow<List<Operator>> = dao.observeAll().map { list -> list.map { it.toModel() } }
    val hasOperators: Flow<Boolean> = dao.observeCount().map { it > 0 }

    suspend fun create(name: String, pin: String, role: Role): Operator {
        val cleanName = name.trim()
        validateName(cleanName)
        validatePin(pin)
        if (dao.findByName(cleanName) != null) throw AuthException("名稱「$cleanName」已存在")
        val hash = withContext(Dispatchers.Default) { PinHasher.hash(pin) }
        val entity = OperatorEntity(name = cleanName, pinHash = hash, role = role.name, createdAt = System.currentTimeMillis())
        val id = dao.insert(entity)
        return entity.copy(id = id).toModel()
    }

    suspend fun rename(id: Long, name: String) {
        val cleanName = name.trim()
        validateName(cleanName)
        val existing = dao.findByName(cleanName)
        if (existing != null && existing.id != id) throw AuthException("名稱「$cleanName」已存在")
        val entity = dao.get(id) ?: throw AuthException("找不到人員")
        dao.update(entity.copy(name = cleanName))
    }

    suspend fun setRole(id: Long, role: Role) {
        val entity = dao.get(id) ?: throw AuthException("找不到人員")
        if (entity.role == Role.ADMIN.name && role != Role.ADMIN) ensureAnotherAdmin()
        dao.update(entity.copy(role = role.name))
    }

    suspend fun setActive(id: Long, active: Boolean) {
        val entity = dao.get(id) ?: throw AuthException("找不到人員")
        if (!active && entity.role == Role.ADMIN.name && entity.active) ensureAnotherAdmin()
        dao.update(entity.copy(active = active, failedAttempts = 0, lockedUntil = 0))
    }

    suspend fun resetPin(id: Long, pin: String) {
        validatePin(pin)
        val entity = dao.get(id) ?: throw AuthException("找不到人員")
        val hash = withContext(Dispatchers.Default) { PinHasher.hash(pin) }
        dao.update(entity.copy(pinHash = hash, failedAttempts = 0, lockedUntil = 0))
    }

    /** Verifies a PIN with lockout after [MAX_ATTEMPTS] consecutive failures. */
    suspend fun authenticate(id: Long, pin: String, now: Long = System.currentTimeMillis()): AuthResult {
        val entity = dao.get(id) ?: return AuthResult.NotFound
        if (!entity.active) return AuthResult.Inactive
        if (entity.lockedUntil > now) return AuthResult.Locked(entity.lockedUntil)
        val ok = withContext(Dispatchers.Default) { PinHasher.verify(pin, entity.pinHash) }
        return if (ok) {
            val updated = entity.copy(failedAttempts = 0, lockedUntil = 0, lastLoginAt = now)
            dao.update(updated)
            AuthResult.Success(updated.toModel())
        } else {
            val attempts = entity.failedAttempts + 1
            if (attempts >= MAX_ATTEMPTS) {
                val until = now + LOCKOUT_MS
                dao.update(entity.copy(failedAttempts = 0, lockedUntil = until))
                AuthResult.Locked(until)
            } else {
                dao.update(entity.copy(failedAttempts = attempts))
                AuthResult.WrongPin(MAX_ATTEMPTS - attempts)
            }
        }
    }

    /** Supervisor override: returns the active admin whose PIN matches, if any. */
    suspend fun authorizeAdmin(pin: String): Operator? = withContext(Dispatchers.Default) {
        dao.activeByRole(Role.ADMIN.name).firstOrNull { PinHasher.verify(pin, it.pinHash) }?.toModel()
    }

    private suspend fun ensureAnotherAdmin() {
        if (dao.countActiveByRole(Role.ADMIN.name) <= 1) throw AuthException("至少需要保留一位啟用中的管理員")
    }

    private fun validateName(name: String) {
        if (name.isEmpty() || name.length > 20) throw AuthException("名稱需為 1–20 個字")
    }

    private fun validatePin(pin: String) {
        if (!PinHasher.isValidPin(pin)) throw AuthException("PIN 需為 4–8 位數字")
    }

    private fun OperatorEntity.toModel() = Operator(
        id = id,
        name = name,
        role = runCatching { Role.valueOf(role) }.getOrDefault(Role.STAFF),
        active = active,
        lockedUntil = lockedUntil,
        lastLoginAt = lastLoginAt,
    )

    companion object {
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MS = 5 * 60_000L
    }
}

/** The operator currently signed in on this device. */
@Singleton
class Session @Inject constructor() {
    private val _current = MutableStateFlow<Operator?>(null)
    val current: StateFlow<Operator?> = _current.asStateFlow()

    fun login(operator: Operator) {
        _current.value = operator
    }

    fun logout() {
        _current.value = null
    }

    fun requireOperator(): Operator = _current.value ?: throw AuthException("尚未登入")
}
