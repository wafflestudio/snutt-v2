package com.wafflestudio.snutt.migration.rehearsal

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.wafflestudio.snutt.core.test.TestImages
import com.wafflestudio.snutt.migration.MigrationDataSourceConfig
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import javax.sql.DataSource

/**
 * 리허설용 로컬 컨테이너. JVM당 한 번 뜬다. MySQL 서버 하나에 EV 원본 DB와 v2 대상 DB를 나눠 둔다(메모리 때문).
 *
 * `rehearsal.mysql-buffer-pool`(예: 4G) 시스템 프로퍼티로 InnoDB 버퍼 풀을 키운다. 운영 크기 스냅샷의 소요 시간을 잴 때는 키운다
 * (기본 128MB로는 디스크 I/O가 지배해 운영보다 훨씬 느리게 나온다).
 */
object RehearsalContainers {
    const val ROOT = "root"

    val mysql: MySQLContainer by lazy {
        MySQLContainer(TestImages.MYSQL)
            .withDatabaseName("rehearsal")
            .apply {
                // 바이너리 로그를 끈다. 켜 두면 복원·마스킹 UPDATE의 원본 행 이미지가 컨테이너 안 binlog에 남는다.
                val pool = System.getProperty("rehearsal.mysql-buffer-pool")?.let { "--innodb-buffer-pool-size=$it" }
                withCommand(*listOfNotNull("--disable-log-bin", pool).toTypedArray())
            }.apply { start() }
    }

    val mongo: GenericContainer<*> by lazy {
        GenericContainer(TestImages.MONGO).withExposedPorts(27017).apply { start() }
    }

    val mongoClient: MongoClient by lazy { MongoClients.create("mongodb://${mongo.host}:${mongo.getMappedPort(27017)}") }

    private val pools = HashMap<String, DataSource>()

    fun jdbcUrl(database: String): String =
        MigrationDataSourceConfig.jdbcUrl("jdbc:mysql://${mysql.host}:${mysql.getMappedPort(3306)}/$database")

    val password: String get() = mysql.password

    @Synchronized
    fun jdbc(database: String): JdbcTemplate =
        JdbcTemplate(
            pools.getOrPut(database) {
                DataSourceBuilder
                    .create()
                    .url(jdbcUrl(database))
                    .username(ROOT)
                    .password(password)
                    .build()
            },
        )

    /** 빈 DB를 새로 만든다(있으면 지운다). */
    fun recreateDatabase(database: String) {
        DriverManager.getConnection("jdbc:mysql://${mysql.host}:${mysql.getMappedPort(3306)}/", ROOT, password).use { connection ->
            connection.createStatement().use {
                it.execute("DROP DATABASE IF EXISTS `$database`")
                it.execute("CREATE DATABASE `$database` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci")
            }
        }
    }

    /** `mysqldump` 결과(.sql 또는 .sql.gz)를 [database]에 넣는다. */
    fun restoreMysql(
        dump: Path,
        database: String,
    ) {
        recreateDatabase(database)
        val gzip = dump.toString().endsWith(".gz")
        val remote = "/tmp/restore-$database.sql${if (gzip) ".gz" else ""}"
        mysql.copyFileToContainer(MountableFile.forHostPath(dump), remote)
        val reader = if (gzip) "gunzip -c $remote" else "cat $remote"
        // GTID가 켜진 서버에서 뜬 덤프는 SET @@GLOBAL.GTID_PURGED를 담는다. 로컬 작업 서버라 GTID 기록을 비우고 넣는다.
        exec(mysql, "mysql", "-u$ROOT", "-p$password", "-e", "RESET BINARY LOGS AND GTIDS")
        try {
            exec(mysql, "sh", "-c", "$reader | mysql -u$ROOT -p$password $database")
        } finally {
            mysql.execInContainer("rm", "-f", remote)
        }
    }

    /** [database]를 `mysqldump --no-tablespaces` 형식(.sql.gz)으로 [out]에 쓴다. */
    fun dumpMysql(
        database: String,
        out: Path,
    ) {
        Files.createDirectories(out.toAbsolutePath().parent)
        val remote = "/tmp/dump-$database.sql.gz"
        exec(mysql, "sh", "-c", "mysqldump --no-tablespaces --set-gtid-purged=OFF -u$ROOT -p$password $database | gzip > $remote")
        mysql.copyFileFromContainer(remote, out.toString())
        exec(mysql, "rm", "-f", remote)
    }

    /** `mongodump --archive --gzip` 결과를 [targetDatabase]에 넣는다(`--drop`). 덤프의 인덱스도 함께 들어온다. */
    fun restoreMongo(
        archive: Path,
        sourceDatabase: String,
        targetDatabase: String,
    ) {
        mongoClient.getDatabase(targetDatabase).drop()
        val remote = "/tmp/restore-$targetDatabase.archive"
        mongo.copyFileToContainer(MountableFile.forHostPath(archive), remote)
        exec(
            mongo,
            "mongorestore",
            "--archive=$remote",
            "--gzip",
            "--drop",
            "--nsFrom=$sourceDatabase.*",
            "--nsTo=$targetDatabase.*",
        )
        mongo.execInContainer("rm", "-f", remote)
    }

    /** [database]를 `mongodump --archive --gzip` 형식으로 쓴다. 덤프 안의 DB 이름은 [database]다. */
    fun dumpMongo(
        database: String,
        out: Path,
    ) {
        Files.createDirectories(out.toAbsolutePath().parent)
        val remote = "/tmp/dump-$database.archive"
        exec(mongo, "mongodump", "--db=$database", "--archive=$remote", "--gzip")
        mongo.copyFileFromContainer(remote, out.toString())
        exec(mongo, "rm", "-f", remote)
    }

    private fun exec(
        container: GenericContainer<*>,
        vararg command: String,
    ) {
        val result = container.execInContainer(*command)
        check(result.exitCode == 0) { "${command.first()} 실패(${result.exitCode}): ${result.stderr.takeLast(2_000)}" }
    }
}
