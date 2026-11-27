package com.wafflestudio.snutt.core.test.perf

import com.wafflestudio.snutt.core.test.IntegrationContainers
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager

/** 생성기가 만든 데이터에서 테스트가 파라미터로 쓸 대표 id들. 극단 사용자는 운영 최댓값을 따른다. */
data class PerfDataset(
    val generationMillis: Long,
    val rowCounts: Map<String, Int>,
    val current: PerfSemester,
    val previous: PerfSemester,
    val beforePrevious: PerfSemester,
    /** snuev에서 이관한 강의평 94%를 가진 계정. */
    val snuevUserId: Long,
    /** 이번 학기 시간표 773개. */
    val heavyTimetableUserId: Long,
    val heavyTimetableId: Long,
    /** 친구 115명과 대기 중 요청 10개. */
    val heavyFriendUserId: Long,
    /** [heavyFriendUserId]와 친구인 사용자. */
    val heavyFriendPartnerId: Long,
    /** 개인 알림 2,000개. */
    val heavyNotificationUserId: Long,
    /** 일기 300개. */
    val heavyDiaryUserId: Long,
    /** 이번 학기 시간표 하나에 강의 3~5개인 평범한 사용자. */
    val typicalUserId: Long,
    val typicalTimetableId: Long,
    /** 강의평이 가장 많은 과목(212개, 운영 최댓값). */
    val popularCourseId: Long,
    /** 이번 학기에 가장 많은 시간표에 담긴 강의. */
    val popularLectureId: Long,
)

/**
 * perf 테스트 전용 스키마 `snutt_perf`. 일반 테스트가 쓰는 싱글톤 MySQL 컨테이너 안의 별도 스키마라
 * `AbstractIntegrationTest`의 매 테스트 초기화(기본 스키마 `test`만 비운다)와 부딪히지 않는다.
 *
 * JVM에서 처음 [dataset]을 읽을 때 한 번 만든다: 스키마 생성 → Flyway migration(운영과 같은 V1~) → [PerfDataGenerator] → ANALYZE.
 * 모든 perf 테스트가 이 데이터를 읽기만 하고 공유한다. 쓰면 안 된다.
 */
object PerfDatabase {
    const val SCHEMA = "snutt_perf"

    private val log = LoggerFactory.getLogger(javaClass)

    val dataset: PerfDataset by lazy { create() }

    /** EXPLAIN을 실행할 연결. 읽기 전용으로 쓴다. */
    val connection: Connection by lazy {
        dataset
        open()
    }

    private fun jdbcUrl(): String {
        val mysql = IntegrationContainers.mysql
        return "jdbc:mysql://${mysql.host}:${mysql.getMappedPort(3306)}/$SCHEMA?rewriteBatchedStatements=true&connectionTimeZone=UTC"
    }

    private fun open(): Connection = DriverManager.getConnection(jdbcUrl(), "root", IntegrationContainers.mysql.password)

    private fun create(): PerfDataset {
        val mysql = IntegrationContainers.mysql
        DriverManager.getConnection(mysql.jdbcUrl, "root", mysql.password).use { root ->
            root.createStatement().use {
                it.execute("DROP DATABASE IF EXISTS $SCHEMA")
                it.execute("CREATE DATABASE $SCHEMA CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci")
            }
        }
        val migrateStarted = System.nanoTime()
        Flyway
            .configure()
            .dataSource(jdbcUrl(), "root", mysql.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        val migrateMillis = (System.nanoTime() - migrateStarted) / 1_000_000
        val dataset = open().use { PerfDataGenerator(it).generate() }
        log.info(
            "perf 데이터셋 생성: migration {}ms, 데이터 {}ms, 행 수 {}",
            migrateMillis,
            dataset.generationMillis,
            dataset.rowCounts,
        )
        return dataset
    }
}
