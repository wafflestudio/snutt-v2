package com.wafflestudio.snutt.migration.rehearsal.check

import com.mongodb.client.MongoDatabase
import com.wafflestudio.snutt.migration.bool
import com.wafflestudio.snutt.migration.doc
import com.wafflestudio.snutt.migration.docs
import com.wafflestudio.snutt.migration.id
import com.wafflestudio.snutt.migration.instant
import com.wafflestudio.snutt.migration.int
import com.wafflestudio.snutt.migration.long
import com.wafflestudio.snutt.migration.str
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

/**
 * v1 원본에서 읽은, 기대값 계산과 표본 대조가 함께 쓰는 색인. 이관 코드(MigrationContext)를 쓰지 않고 원본만으로 만든다.
 * 운영 크기에서도 메모리에 들도록 사용자·강의처럼 수십만 건인 것만 담고, 시간표처럼 큰 것은 그때그때 스트리밍한다.
 */
class SourceIndex(
    val mongo: MongoDatabase,
    val ev: JdbcTemplate,
) {
    class SourceUser(
        val hex: String,
        val active: Boolean,
        val lastLogin: Long,
        val regDate: Instant?,
        val localId: String?,
        val email: String?,
        /** v1 `isEmailVerified` 원값. 필드가 없거나 Boolean이 아니면 null. */
        val verified: Boolean?,
        /** `PROVIDER|sub` */
        val socials: List<String>,
        val credentialHash: String?,
        val hasCredentialKey: Boolean,
    ) {
        val legacyToken: String? get() = credentialHash?.takeIf { active && hasCredentialKey && it.isNotBlank() }
    }

    class SourceOffering(
        val key: String,
        val courseKey: String,
        val classTimes: Int,
    )

    /** 자연 순서(이관이 읽는 순서) 그대로. */
    val users = LinkedHashMap<String, SourceUser>()

    /** 강의 문서 id → 개설 키(연도, 학기, 교과목번호, 분반). 같은 개설의 문서 여럿은 같은 키다. */
    val offeringOf = HashMap<String, String>()

    /** 개설 키 → 처음 나온 문서의 정보(이관이 남기는 행). */
    val offerings = LinkedHashMap<String, SourceOffering>()
    val mongoSemesters = HashSet<Pair<Int?, Int?>>()

    /** 같은 아이디·인증 이메일·소셜 계정을 쓰는 활성 계정 중 최종 로그인이 가장 늦은(같으면 나중에 나온) 계정. */
    val localIdOwner = HashMap<String, String>()
    val emailOwner = HashMap<String, String>()
    val socialOwner = HashMap<String, String>()

    /** EV lecture id → 과목 키, 과목 키 → 가장 작은 EV lecture id(이관이 승계하는 course id). */
    val evCourseKey = HashMap<Long, String>()
    val evCourseIdOfKey = HashMap<String, Long>()

    init {
        loadUsers()
        loadLectures()
        loadEvCourses()
    }

    fun courseKey(
        courseNumber: String?,
        instructor: String?,
    ) = "${courseNumber.orEmpty().trim()}\u0000${instructor.orEmpty().trim()}"

    private fun loadUsers() {
        mongo.getCollection("users").find().forEach { doc ->
            val credential = doc.doc("credential")
            val socials =
                SOCIAL.mapNotNull { (field, provider) -> credential?.str(field)?.let { "$provider|$it" } }
            val user =
                SourceUser(
                    hex = doc.id(),
                    active = doc.bool("active"),
                    lastLogin = doc.long("lastLoginTimestamp") ?: 0L,
                    regDate = doc.instant("regDate"),
                    localId = credential?.str("localId"),
                    email = doc.str("email"),
                    verified = doc["isEmailVerified"] as? Boolean,
                    socials = socials,
                    credentialHash = doc.str("credentialHash"),
                    hasCredentialKey = credential != null && CREDENTIAL_KEYS.any { credential.str(it) != null },
                )
            users[user.hex] = user
        }
        val best = HashMap<String, Long>()

        fun claim(
            owners: HashMap<String, String>,
            key: String,
            user: SourceUser,
        ) {
            if (user.lastLogin >= (best[key] ?: -1L)) {
                best[key] = user.lastLogin
                owners[key] = user.hex
            }
        }
        users.values.filter { it.active }.forEach { user ->
            user.localId?.let { claim(localIdOwner, "local\u0000$it", user) }
            if (user.verified == true) user.email?.let { claim(emailOwner, "email\u0000${it.lowercase()}", user) }
            user.socials.forEach { claim(socialOwner, "social\u0000$it", user) }
        }
    }

    fun ownsLocalId(user: SourceUser) = user.localId != null && localIdOwner["local\u0000${user.localId}"] == user.hex

    fun ownsEmail(user: SourceUser) = user.email != null && emailOwner["email\u0000${user.email.lowercase()}"] == user.hex

    fun ownsSocial(
        user: SourceUser,
        social: String,
    ) = socialOwner["social\u0000$social"] == user.hex

    private fun loadLectures() {
        mongo.getCollection("lectures").find().forEach { doc ->
            val year = doc.int("year")
            val semester = doc.int("semester")
            val key = listOf(year, semester, doc.str("course_number"), doc.str("lecture_number")).joinToString("|")
            offeringOf[doc.id()] = key
            mongoSemesters += year to semester
            offerings.getOrPut(key) {
                SourceOffering(key, courseKey(doc.str("course_number"), doc.str("instructor")), doc.docs("class_time_json").size)
            }
        }
    }

    private fun loadEvCourses() {
        ev.query("SELECT id, course_number, instructor FROM lecture ORDER BY id") { rs ->
            val key = courseKey(rs.getString("course_number"), rs.getString("instructor"))
            val id = rs.getLong("id")
            evCourseKey[id] = key
            evCourseIdOfKey.putIfAbsent(key, id)
        }
    }

    companion object {
        val SOCIAL = listOf("fbId" to "FACEBOOK", "appleSub" to "APPLE", "googleSub" to "GOOGLE", "kakaoSub" to "KAKAO")
        private val CREDENTIAL_KEYS = listOf("localId", "fbId", "appleSub", "googleSub", "kakaoSub")
    }
}
