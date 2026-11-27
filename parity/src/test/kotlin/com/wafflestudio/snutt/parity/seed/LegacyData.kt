package com.wafflestudio.snutt.parity.seed

import com.wafflestudio.snutt.migration.MigrationContext
import org.bson.Document
import org.bson.types.ObjectId
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.Date

/**
 * v1 데이터 한 벌. v1 Mongo 컬렉션 문서와 EV MySQL 행, 그리고 시나리오가 이름으로 부를 수 있는 별칭으로 이뤄진다.
 * v2 데이터는 여기서 만들지 않는다. 이 데이터를 실제 migration 단계로 옮긴 결과가 v2 데이터다.
 */
class LegacyData(
    val mongo: Map<String, List<Document>>,
    val evRows: List<EvRow>,
    val aliases: List<SeedAlias>,
    val users: Map<String, SeedUser>,
)

/** EV MySQL 한 행. 컬럼 순서는 [values] 키 순서다. */
class EvRow(
    val table: String,
    val values: Map<String, Any?>,
)

/**
 * 시나리오가 `@{name}`으로 부르는 값. v1 쪽 값은 시드에서 정해지고, v2 쪽 값은 이관이 끝난 뒤 [v2]로 구한다.
 * 같은 별칭이 양쪽 응답에서 같은 자리에 나오면 id가 대응한다는 뜻이다.
 */
class SeedAlias(
    val name: String,
    val v1: String,
    val v2: (MigrationResult) -> String?,
)

/** 이관 결과. v1 id(ObjectId hex)에서 v2 id로 가는 표는 migration 단계가 만든 [MigrationContext]에 있다. */
class MigrationResult(
    val context: MigrationContext,
    val jdbc: JdbcTemplate,
)

class SeedUser(
    val alias: String,
    val id: ObjectId,
    /** v1 `x-access-token`(= credentialHash). 이관은 이 값의 SHA-256을 legacy_access_token에 넣으므로 양쪽에서 같은 값이 통한다. */
    val token: String,
    val localId: String?,
    val password: String?,
)

/** 결정적인 ObjectId. 타임스탬프 부분이 증가하므로 생성 순서와 _id 순서가 같다(Mongo 자연 순서와도 같게 넣는다). */
class ObjectIds(
    private val baseSeconds: Long,
) {
    private var counter = 0L

    fun next(): ObjectId {
        counter++
        return ObjectId("%08x%016x".format(baseSeconds + counter, counter))
    }
}

fun doc(vararg pairs: Pair<String, Any?>): Document = Document().apply { pairs.forEach { (k, v) -> put(k, v) } }

/** 값이 null인 키는 넣지 않는다. v1(Spring Data Mongo)은 null 프로퍼티를 저장하지 않는다. */
fun compactDoc(vararg pairs: Pair<String, Any?>): Document = Document().apply { pairs.forEach { (k, v) -> if (v != null) put(k, v) } }

fun Instant.toDate(): Date = Date.from(this)
