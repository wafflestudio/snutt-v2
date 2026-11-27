package com.wafflestudio.snutt.migration.rehearsal.mask

import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.Updates
import com.wafflestudio.snutt.migration.doc
import com.wafflestudio.snutt.migration.oid
import org.bson.BsonString
import org.bson.BsonValue
import org.bson.Document
import org.bson.conversions.Bson

/** 마스킹 결과 집계. 리포트와 테스트가 본다. */
class MaskReport {
    val maskedValues = LinkedHashMap<String, Long>()
    val removedFields = LinkedHashMap<String, Long>()
    val droppedCollections = mutableListOf<String>()
    val unreviewedFields = LinkedHashMap<String, Long>()

    fun masked(
        path: String,
        count: Long = 1,
    ) {
        maskedValues.merge(path, count, Long::plus)
    }

    fun removed(path: String) {
        removedFields.merge(path, 1, Long::plus)
    }

    fun toMarkdown(): String =
        buildString {
            appendLine("| 대상 | 바꾼 값 수 |")
            appendLine("|---|---:|")
            maskedValues.forEach { (path, count) -> appendLine("| `$path` | $count |") }
            if (removedFields.isNotEmpty()) {
                appendLine()
                appendLine("지운 필드(목록 밖):")
                removedFields.forEach { (path, count) -> appendLine("- `$path`: ${count}건") }
            }
            if (unreviewedFields.isNotEmpty()) {
                appendLine()
                appendLine("검토하지 않은 필드(그대로 남김. 개인정보가 있으면 MaskingPlan에 규칙을 넣고 다시 마스킹한다):")
                unreviewedFields.forEach { (path, count) -> appendLine("- `$path`: ${count}건") }
            }
            if (droppedCollections.isNotEmpty()) {
                appendLine()
                appendLine("지운 컬렉션(검토 목록 밖): ${droppedCollections.joinToString { "`$it`" }}")
            }
        }
}

/**
 * v1 Mongo DB를 제자리에서 마스킹한다. 대상과 이유는 [MaskingPlan].
 *
 * id(ObjectId)와 참조, 날짜, 숫자, 불리언, enum은 건드리지 않는다. 문자열 값만 바꾸고 null·빈 값·필드 유무는 그대로라 null 비율이 같다.
 * 검토 목록에 없는 컬렉션이 있으면 멈춘다([dropUnknownCollections]면 지운다).
 */
class MongoMasker(
    private val db: MongoDatabase,
    private val pseudonymizer: Pseudonymizer,
    private val dropUnknownCollections: Boolean = false,
) {
    private val report = MaskReport()

    private val emails = InjectiveMap { key, attempt -> maskEmailKey(key, attempt) }
    private val nicknames =
        InjectiveMap { key, attempt ->
            val name = key.substringBeforeLast(TAG_DELIMITER)
            if (key.contains(TAG_DELIMITER)) {
                pseudonymizer.text("nickname", name, attempt) + TAG_DELIMITER + key.substringAfterLast(TAG_DELIMITER)
            } else {
                pseudonymizer.text("nickname", key, attempt)
            }
        }
    private val localIds = injective("local-id")
    private val tokens = injective("token")
    private val subs = SOCIAL_SUB_FIELDS.associateWith { injective("sub:$it") }
    private val titles = injective("timetable-title")
    private val themeNames = injective("theme-name")
    private val rareEmailDomains = HashSet<String>()

    private fun injective(domain: String) = InjectiveMap { key, attempt -> pseudonymizer.text(domain, key, attempt) }

    fun run(): MaskReport {
        checkCollections()
        reportUnreviewedFields()
        prepare()
        // 가명이 아직 마스킹하지 않은 다른 문서의 원문과 같아질 수 있어, 제자리 갱신 중에는 유일 인덱스를 내려 둔다(끝나면 그대로 다시 만든다).
        val indexes = MaskingPlan.MONGO_PII_COLLECTIONS.associateWith { dropIndexes(it) }
        mask()
        indexes.forEach { (collection, specs) -> recreateIndexes(collection, specs) }
        return report
    }

    private fun dropIndexes(collection: String): List<Document> {
        val target = db.getCollection(collection)
        val specs = target.listIndexes().toList().filter { it.getString("name") != "_id_" }
        specs.forEach { target.dropIndex(it.getString("name")) }
        return specs
    }

    private fun recreateIndexes(
        collection: String,
        specs: List<Document>,
    ) {
        if (specs.isEmpty()) return
        val indexes = specs.map { spec -> Document(spec).apply { remove("v") } }
        db.runCommand(Document("createIndexes", collection).append("indexes", indexes))
    }

    private fun reportUnreviewedFields() {
        MaskingPlan.KNOWN_FIELDS.forEach { (collection, known) ->
            db.getCollection(collection).find().forEach { doc ->
                doc.keys.filterNot { it in known }.forEach { report.unreviewedFields.merge("$collection.$it", 1, Long::plus) }
            }
        }
    }

    private fun mask() {
        maskUsers()
        maskSimple("friend", listOf("fromUserDisplayName", "toUserDisplayName")) { _, value -> pseudonymizer.text("display-name", value) }
        maskSimple("userDevice", listOf("fcmRegistrationId", "deviceId")) { field, value ->
            pseudonymizer.text(if (field == "deviceId") "device-id" else "fcm", value)
        }
        maskTimetables()
        maskThemes()
        maskNotifications()
        maskSimple("diarySubmission", listOf("comment")) { _, value -> pseudonymizer.text("free-text", value) }
    }

    private fun checkCollections() {
        val reviewed = MaskingPlan.MONGO_PII_COLLECTIONS + MaskingPlan.MONGO_CLEAN_COLLECTIONS + MaskingPlan.MARKER_COLLECTION
        val unknown = db.listCollectionNames().filterNot { it in reviewed || it.startsWith("system.") }.sorted()
        if (unknown.isEmpty()) return
        check(dropUnknownCollections) {
            "검토하지 않은 컬렉션이 있다: $unknown. 개인정보가 없는지 확인해 MaskingPlan에 넣거나 --drop-unknown-collections로 지운다"
        }
        unknown.forEach { db.getCollection(it).drop() }
        report.droppedCollections += unknown
    }

    /** 충돌을 피하는 표는 처리 순서와 무관하게 정렬된 키 순서로 먼저 배정한다. */
    private fun prepare() {
        val emailKeys = HashSet<String>()
        val emailDomains = HashMap<String, MutableSet<String>>()
        val nicknameKeys = HashSet<String>()
        val localIdKeys = HashSet<String>()
        val tokenKeys = HashSet<String>()
        val subKeys = SOCIAL_SUB_FIELDS.associateWith { HashSet<String>() }
        db.getCollection("users").find().forEach { user ->
            val credential = user.doc("credential")
            (listOfNotNull(user["email"] as? String) + EMAIL_CREDENTIAL_FIELDS.mapNotNull { credential?.get(it) as? String })
                .forEach { email ->
                    val key = email.lowercase()
                    emailKeys += key
                    emailDomains.getOrPut(key.substringAfterLast('@', "")) { HashSet() } += key
                }
            (user["nickname"] as? String)?.let(nicknameKeys::add)
            (credential?.get("localId") as? String)?.let(localIdKeys::add)
            (user["credentialHash"] as? String)?.let(tokenKeys::add)
            SOCIAL_SUB_FIELDS.forEach { field -> (credential?.get(field) as? String)?.let { subKeys.getValue(field) += it } }
        }
        emailDomains.forEach { (domain, addresses) -> if (addresses.size < RARE_DOMAIN_THRESHOLD) rareEmailDomains += domain }
        emails.prepare(emailKeys)
        nicknames.prepare(nicknameKeys)
        localIds.prepare(localIdKeys)
        tokens.prepare(tokenKeys)
        subKeys.forEach { (field, keys) -> subs.getValue(field).prepare(keys) }

        titles.prepare(db.getCollection("timetables").distinctStrings("title"))
        val themes = db.getCollection("timetableTheme")
        themeNames.prepare(themes.distinctStrings("name") + themes.distinctStrings("publishInfo.publishName"))
    }

    private fun maskEmailKey(
        key: String,
        attempt: Int,
    ): String {
        val at = key.lastIndexOf('@')
        if (at < 0) return pseudonymizer.text("email", key, attempt)
        val domain = key.substring(at + 1)
        val local = pseudonymizer.text("email\u0000$domain", key.substring(0, at), attempt)
        return local + "@" + if (domain in rareEmailDomains) pseudonymizer.text("email-domain", domain) else domain
    }

    /** 소문자 기준 가명에 원문의 대문자 위치를 입힌다. 대소문자 무시 유일 판정이 원문과 같게 나온다. */
    private fun maskEmail(email: String): String {
        val masked = emails.get(email.lowercase())
        if (masked.length != email.length) return masked
        return buildString(masked.length) {
            masked.forEachIndexed { index, char -> append(if (email[index].isUpperCase()) char.uppercaseChar() else char) }
        }
    }

    private fun maskUsers() {
        val users = db.getCollection("users")
        users.bulkUpdate(users.find().sort(Sorts.ascending("_id"))) { doc ->
            val updates = mutableListOf<Bson>()
            doc.keys.filterNot { it in MaskingPlan.USER_FIELDS }.forEach { field ->
                updates += Updates.unset(field)
                report.removed("users.$field")
            }
            (doc["email"] as? String)?.let {
                updates += Updates.set("email", maskEmail(it))
                report.masked("users.email")
            }
            (doc["nickname"] as? String)?.let {
                updates += Updates.set("nickname", nicknames.get(it))
                report.masked("users.nickname")
            }
            (doc["credentialHash"] as? String)?.let {
                updates += Updates.set("credentialHash", tokens.get(it))
                report.masked("users.credentialHash")
            }
            (doc["fcmKey"] as? String)?.let {
                updates += Updates.set("fcmKey", pseudonymizer.text("fcm", it))
                report.masked("users.fcmKey")
            }
            doc.doc("credential")?.let { updates += Updates.set("credential", maskCredential(it)) }
            updates
        }
    }

    private fun maskCredential(credential: Document): Document {
        val out = Document()
        credential.forEach { (field, value) ->
            if (field !in MaskingPlan.CREDENTIAL_FIELDS) {
                report.removed("users.credential.$field")
                return@forEach
            }
            if (value is Number && field in SOCIAL_SUB_FIELDS) {
                // 숫자로 저장된 소셜 id(예전 Kakao 회원번호). 자릿수를 지킨 숫자로 바꾼다.
                out[field] = subs.getValue(field).get(value.toString()).toLongOrNull() ?: value
                report.masked("users.credential.$field")
                return@forEach
            }
            if (value !is String) {
                if (value != null &&
                    field != "tempDate"
                ) {
                    report.unreviewedFields.merge("users.credential.$field(${value.javaClass.simpleName})", 1, Long::plus)
                }
                out[field] = value
                return@forEach
            }
            out[field] =
                when (field) {
                    "localId" -> localIds.get(value)
                    "localPw" -> maskPasswordHash(value)
                    "fbName" -> pseudonymizer.text("person-name", value)
                    "tempSeed" -> pseudonymizer.text("secret", value)
                    "tempDate" -> value
                    in EMAIL_CREDENTIAL_FIELDS -> maskEmail(value)
                    in SOCIAL_SUB_FIELDS -> subs.getValue(field).get(value)
                    else -> error("UNREACHABLE: $field")
                }
            if (field != "tempDate") report.masked("users.credential.$field")
        }
        return out
    }

    /** bcrypt `$2a$10$` 같은 머리(알고리즘·비용)는 두고 솔트·해시 53자만 바꾼다. 비용이 바뀌면 검증이 수 분 걸리거나 형식 오류가 난다. */
    private fun maskPasswordHash(value: String): String {
        val header = BCRYPT_HEADER.find(value)?.value ?: return pseudonymizer.text("password-hash", value)
        return header + pseudonymizer.text("password-hash", value.substring(header.length))
    }

    private fun maskSimple(
        collection: String,
        fields: List<String>,
        mask: (field: String, value: String) -> String,
    ) {
        val target = db.getCollection(collection)
        target.bulkUpdate(target.find().sort(Sorts.ascending("_id"))) { doc ->
            fields.mapNotNull { field ->
                val value = doc[field] as? String ?: return@mapNotNull null
                report.masked("$collection.$field")
                Updates.set(field, mask(field, value))
            }
        }
    }

    private fun maskTimetables() {
        // 이관은 같은 개설의 문서 여럿 중 처음 나온 문서와 비교해 override를 정한다. 같은 기준으로 "사용자가 고친 값"을 고른다.
        val catalog = HashMap<String, Document>()
        val canonical = HashMap<String, Document>()
        db
            .getCollection("lectures")
            .find()
            .projection(Projections.include(LECTURE_TEXT_FIELDS + listOf("year", "semester", "course_number", "lecture_number")))
            .forEach { lecture ->
                val key = listOf("year", "semester", "course_number", "lecture_number").joinToString("|") { lecture[it].toString() }
                catalog[lecture.oid("_id")!!] = canonical.getOrPut(key) { lecture }
            }
        val timetables = db.getCollection("timetables")
        timetables.bulkUpdate(timetables.find().sort(Sorts.ascending("_id"))) { doc ->
            val updates = mutableListOf<Bson>()
            (doc["title"] as? String)?.let {
                updates += Updates.set("title", titles.get(it))
                report.masked("timetables.title")
            }
            val list = doc["lecture_list"]
            if (list is List<*>) {
                // 문서가 아닌 원소(null 등)도 그대로 둔다. ValidateStep이 원소 수($size)로 세기 때문이다.
                val items = list.map { item -> if (item is Document) maskTimetableLecture(item, catalog) else item }
                updates += Updates.set("lecture_list", items)
            }
            updates
        }
    }

    private fun maskTimetableLecture(
        item: Document,
        catalog: Map<String, Document>,
    ): Document {
        val original = item.oid("lecture_id")?.let(catalog::get)
        val copy = Document(item)
        LECTURE_TEXT_FIELDS.forEach { field ->
            val value = item[field] as? String ?: return@forEach
            if (original != null && original[field] == value) return@forEach
            copy[field] = pseudonymizer.text("free-text", value)
            report.masked("timetables.lecture_list.$field")
        }
        // 직접 만든 강의의 장소는 사용자가 쓴 글이다. 수강편람 강의의 장소는 이관이 수업 시간 비교에 쓰므로 둔다.
        if (original == null && item["class_time_json"] is List<*>) {
            copy["class_time_json"] =
                (item["class_time_json"] as List<*>).map { time ->
                    val place = (time as? Document)?.get("place") as? String ?: return@map time
                    report.masked("timetables.lecture_list.class_time_json.place(직접 만든 강의)")
                    Document(time).append("place", pseudonymizer.text("free-text", place))
                }
        }
        return copy
    }

    private fun maskThemes() {
        val themes = db.getCollection("timetableTheme")
        themes.bulkUpdate(themes.find().sort(Sorts.ascending("_id"))) { doc ->
            val updates = mutableListOf<Bson>()
            (doc["name"] as? String)?.let {
                updates += Updates.set("name", themeNames.get(it))
                report.masked("timetableTheme.name")
            }
            (doc.doc("publishInfo")?.get("publishName") as? String)?.let {
                updates += Updates.set("publishInfo.publishName", themeNames.get(it))
                report.masked("timetableTheme.publishInfo.publishName")
            }
            updates
        }
    }

    private fun maskNotifications() {
        val notifications = db.getCollection("notifications")
        val personal = notifications.find(Filters.and(Filters.exists("user_id"), Filters.ne("user_id", null))).sort(Sorts.ascending("_id"))
        notifications.bulkUpdate(personal) { doc ->
            listOf("title", "message").mapNotNull { field ->
                val value = doc[field] as? String ?: return@mapNotNull null
                report.masked("notifications.$field")
                Updates.set(field, pseudonymizer.text("free-text", value))
            }
        }
    }

    private fun MongoCollection<Document>.distinctStrings(field: String): Set<String> =
        distinct(field, BsonValue::class.java).mapNotNullTo(HashSet()) { (it as? BsonString)?.value }

    private fun MongoCollection<Document>.bulkUpdate(
        documents: Iterable<Document>,
        updatesOf: (Document) -> List<Bson>,
    ) {
        val batch = ArrayList<UpdateOneModel<Document>>(BATCH_SIZE)
        documents.forEach { doc ->
            val updates = updatesOf(doc)
            if (updates.isEmpty()) return@forEach
            batch += UpdateOneModel(Filters.eq("_id", doc["_id"]), Updates.combine(updates))
            if (batch.size >= BATCH_SIZE) {
                bulkWrite(batch)
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) bulkWrite(batch)
    }

    companion object {
        private const val TAG_DELIMITER = "#"
        private const val BATCH_SIZE = 1_000
        private const val RARE_DOMAIN_THRESHOLD = 5
        private val SOCIAL_SUB_FIELDS = listOf("fbId", "appleSub", "appleTransferSub", "googleSub", "kakaoSub")
        private val EMAIL_CREDENTIAL_FIELDS = listOf("appleEmail", "googleEmail", "kakaoEmail")
        private val LECTURE_TEXT_FIELDS = listOf("course_title", "instructor", "remark")
        private val BCRYPT_HEADER = Regex("^\\$2[abxy]?\\$\\d{2}\\$")
    }
}
