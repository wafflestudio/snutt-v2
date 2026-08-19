package com.wafflestudio.snutt.migration.step

import com.wafflestudio.snutt.migration.AbstractMigrationStep
import com.wafflestudio.snutt.migration.IdSequence
import com.wafflestudio.snutt.migration.MigrationContext
import com.wafflestudio.snutt.migration.MongoSource
import com.wafflestudio.snutt.migration.bool
import com.wafflestudio.snutt.migration.doc
import com.wafflestudio.snutt.migration.id
import com.wafflestudio.snutt.migration.instant
import com.wafflestudio.snutt.migration.long
import com.wafflestudio.snutt.migration.orNow
import com.wafflestudio.snutt.migration.str
import com.wafflestudio.snutt.migration.toSqlTimestamp
import org.bson.Document
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import kotlin.random.Random

@Component
class UserStep(
    jdbc: JdbcTemplate,
    context: MigrationContext,
    private val mongo: MongoSource,
) : AbstractMigrationStep(jdbc, context) {
    override val name = "user"
    override val tables = listOf("user")

    override fun run() {
        val localIdOwner = HashMap<String, String>()
        val emailOwner = HashMap<String, String>()
        resolveLoginOwners(localIdOwner, emailOwner)

        val ids = IdSequence()
        val takenNicknames = HashSet<String>(256_000)
        writer("user", COLUMNS).use { out ->
            mongo.each("users") { doc ->
                val externalId = doc.id()
                val credential = doc.doc("credential") ?: Document()
                val active = doc.bool("active")
                val id = ids.next()
                context.userIds[externalId] = id

                var localId = credential.str("localId")
                var localPw = credential.str("localPw")
                if (active && localId != null && localIdOwner[localId] != externalId) {
                    localId = null
                    localPw = null
                    context.resolved("같은 아이디를 쓰는 활성 계정이 여럿이라 로컬 로그인 수단을 제거")
                }

                val email = doc.str("email")
                var isEmailVerified = doc.bool("isEmailVerified")
                if (active && isEmailVerified && email != null && emailOwner[email.lowercase()] != externalId) {
                    isEmailVerified = false
                    context.resolved("같은 이메일이 인증된 활성 계정이 여럿이라 인증 상태를 해제")
                }

                val registeredAt = doc.instant("regDate").orNow()
                out.add(
                    id,
                    externalId,
                    email,
                    isEmailVerified,
                    uniqueNickname(doc.str("nickname"), takenNicknames),
                    localId,
                    localPw,
                    credential.str("fbId"),
                    credential.str("fbName"),
                    credential.str("appleSub"),
                    credential.str("appleTransferSub"),
                    credential.str("appleEmail"),
                    credential.str("googleSub"),
                    credential.str("googleEmail"),
                    credential.str("kakaoSub"),
                    credential.str("kakaoEmail"),
                    active,
                    doc.bool("isAdmin"),
                    lastLoginAt(doc, registeredAt).toSqlTimestamp(),
                    doc.instant("notificationCheckedAt").orNow().toSqlTimestamp(),
                    registeredAt.toSqlTimestamp(),
                    registeredAt.toSqlTimestamp(),
                )
            }
        }
        alignAutoIncrement("user", ids.peek())
        log.info("사용자 이관: {}건", context.userIds.size)
    }

    private fun resolveLoginOwners(
        localIdOwner: HashMap<String, String>,
        emailOwner: HashMap<String, String>,
    ) {
        val localIdLastLogin = HashMap<String, Long>()
        val emailLastLogin = HashMap<String, Long>()
        mongo.each("users") { doc ->
            if (!doc.bool("active")) return@each
            val externalId = doc.id()
            val lastLogin = doc.long("lastLoginTimestamp") ?: 0L
            doc.doc("credential")?.str("localId")?.let { localId ->
                if (lastLogin >= (localIdLastLogin[localId] ?: -1L)) {
                    localIdLastLogin[localId] = lastLogin
                    localIdOwner[localId] = externalId
                }
            }
            if (doc.bool("isEmailVerified")) {
                doc.str("email")?.lowercase()?.let { email ->
                    if (lastLogin >= (emailLastLogin[email] ?: -1L)) {
                        emailLastLogin[email] = lastLogin
                        emailOwner[email] = externalId
                    }
                }
            }
        }
    }

    private fun lastLoginAt(
        doc: Document,
        fallback: Instant,
    ): Instant = doc.long("lastLoginTimestamp")?.let(Instant::ofEpochMilli) ?: fallback

    private fun uniqueNickname(
        nickname: String?,
        taken: HashSet<String>,
    ): String {
        val candidate = nickname?.takeIf { it.isNotBlank() } ?: "스누티" + Random.nextInt(TAG_BOUND).toString().padStart(4, '0')
        if (taken.add(candidate)) return candidate
        val base = candidate.substringBeforeLast(TAG_DELIMITER)
        while (true) {
            val retagged = "$base$TAG_DELIMITER%04d".format(Random.nextInt(TAG_BOUND))
            if (taken.add(retagged)) {
                context.resolved("닉네임이 중복되어 태그를 재배정")
                return retagged
            }
        }
    }

    companion object {
        private const val TAG_DELIMITER = "#"
        private const val TAG_BOUND = 10_000
        private val COLUMNS =
            listOf(
                "id",
                "email",
                "is_email_verified",
                "nickname",
                "local_id",
                "local_pw",
                "facebook_sub",
                "facebook_name",
                "apple_sub",
                "apple_transfer_sub",
                "apple_email",
                "google_sub",
                "google_email",
                "kakao_sub",
                "kakao_email",
                "active",
                "is_admin",
                "last_login_at",
                "notification_checked_at",
                "created_at",
                "updated_at",
            )
    }
}
