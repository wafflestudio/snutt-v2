package com.wafflestudio.snutt.parity.env

import com.wafflestudio.snutt.core.test.IntegrationContainers
import com.wafflestudio.snutt.core.test.TestImages
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.output.OutputFrame
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.DriverManager
import java.time.Duration

/**
 * parity용 컨테이너. JVM당 한 번 뜬다.
 *
 * - v2: 모듈 공용 [IntegrationContainers](MySQL, Valkey). v2 api는 테스트 JVM 안에서 RANDOM_PORT로 뜬다.
 * - v1: Mongo(snutt), v1용 Valkey(snutt db 0, ev db 1), EV MySQL(공용 MySQL 서버의 `snutt_ev` DB),
 *   그리고 고정 커밋에서 빌드한 v1 snutt·snutt-ev bootJar를 JRE 컨테이너로 띄운다.
 *
 * v1 컨테이너는 기본 bridge 네트워크에서 다른 컨테이너의 bridge IP로 붙는다. 테스트 JVM은 매핑된 포트로 붙는다.
 * EV MySQL을 별도 컨테이너로 두지 않은 것은 메모리 때문이다(서버 버전은 v2와 같은 [TestImages.MYSQL]).
 */
object ParityContainers {
    const val EV_DATABASE = "snutt_ev"
    const val MONGO_DATABASE = "snutt"

    /** v2 테스트 프로파일의 `snutt.auth.legacy-secret-key`. v1 `snutt.secret-key`와 같아야 apikey·토큰이 양쪽에서 통한다. */
    const val LEGACY_SECRET_KEY = "test-legacy-secret-key-0123456789abcdef"

    private val v1JavaImage = "eclipse-temurin:25-jre"
    private val startupTimeout = Duration.ofMinutes(4)

    val mysql get() = IntegrationContainers.mysql

    val mongo: GenericContainer<*> = GenericContainer(TestImages.MONGO).withExposedPorts(27017)

    val v1Redis: GenericContainer<*> = GenericContainer(TestImages.VALKEY).withExposedPorts(6379)

    lateinit var snutt: GenericContainer<*>
        private set

    lateinit var ev: GenericContainer<*>
        private set

    val mongoUri get() = "mongodb://${mongo.host}:${mongo.getMappedPort(27017)}"

    val evJdbcUrl get() = "jdbc:mysql://${mysql.host}:${mysql.getMappedPort(3306)}/$EV_DATABASE"

    const val MYSQL_ROOT_USER = "root"
    val mysqlRootPassword: String get() = mysql.password

    val v1SnuttUrl get() = "http://${snutt.host}:${snutt.getMappedPort(8080)}"

    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        val snuttJar = requiredFile("parity.v1.snutt-jar")
        val evJar = requiredFile("parity.v1.ev-jar")
        val shimJar = requiredFile("parity.v1.shim-jar")
        mysql.isRunning
        listOf(mongo, v1Redis).parallelStream().forEach { it.start() }
        createEvDatabase()

        ev =
            v1Container("v1-ev", evJar)
                .withCopyFileToContainer(MountableFile.forClasspathResource("parity/v1/ev-application.yml"), "/app/parity.yml")
                .withCommand(
                    "java",
                    *JVM_OPTS,
                    "-jar",
                    "/app/app.jar",
                    "--spring.profiles.active=parity",
                    "--spring.config.additional-location=file:/app/parity.yml",
                    "--spring.datasource.url=jdbc:mysql://${bridgeIp(mysql)}:3306/$EV_DATABASE",
                    "--spring.datasource.username=$MYSQL_ROOT_USER",
                    "--spring.datasource.password=$mysqlRootPassword",
                    "--spring.data.redis.host=${bridgeIp(v1Redis)}",
                    "--spring.data.redis.port=6379",
                    "--spring.data.redis.database=1",
                    "--spring.mongodb.uri=mongodb://${bridgeIp(mongo)}:27017/$MONGO_DATABASE",
                ).waitingFor(Wait.forLogMessage(".*Started SnuttevApplication.*\\n", 1).withStartupTimeout(startupTimeout))
        ev.start()

        snutt =
            v1Container("v1-snutt", snuttJar)
                .withCopyFileToContainer(MountableFile.forHostPath(shimJar.toPath()), "/app/shim/shim.jar")
                .withCopyFileToContainer(MountableFile.forClasspathResource("parity/v1/snutt-application.yml"), "/app/application.yml")
                .withCommand(
                    "java",
                    *JVM_OPTS,
                    "-Dloader.path=/app/shim/shim.jar",
                    "-cp",
                    "/app/app.jar",
                    "org.springframework.boot.loader.launch.PropertiesLauncher",
                    // v1은 활성 프로파일로 phase를 정한다(PhaseUtils: dev/prod/local/test 외에는 예외). 운영과 같게 prod로 둔다.
                    // 설정 파일을 통째로 바꿨으므로 prod 프로파일 설정(운영 접속 정보)은 읽히지 않는다. @Profile("prod") 빈은 없다.
                    "--spring.profiles.active=prod",
                    "--spring.config.location=file:/app/application.yml",
                    "--spring.mongodb.uri=mongodb://${bridgeIp(mongo)}:27017/$MONGO_DATABASE",
                    "--spring.data.redis.host=${bridgeIp(v1Redis)}",
                    "--spring.data.redis.port=6379",
                    "--spring.data.redis.database=0",
                    "--snutt.secret-key=$LEGACY_SECRET_KEY",
                    "--api.server.snuttev.base-url=http://${bridgeIp(ev)}:8080",
                ).waitingFor(Wait.forLogMessage(".*Started SnuttApplicationKt.*\\n", 1).withStartupTimeout(startupTimeout))
        snutt.start()
        started = true
    }

    /** 외부 Mongo 덤프를 넣는다. `--drop`이라 덤프의 컬렉션·인덱스(운영 유일 제약 포함)로 바뀐다. */
    fun restoreMongo(
        archive: Path,
        sourceDatabase: String,
    ) {
        mongo.copyFileToContainer(MountableFile.forHostPath(archive), "/tmp/snapshot.archive")
        exec(
            mongo,
            "mongorestore",
            "--archive=/tmp/snapshot.archive",
            "--gzip",
            "--drop",
            "--nsFrom=$sourceDatabase.*",
            "--nsTo=$MONGO_DATABASE.*",
        )
    }

    /** 외부 EV MySQL 덤프를 `snutt_ev`에 넣는다. mysqldump 기본값(DROP TABLE IF EXISTS)이라 Flyway가 만든 표를 덮어쓴다. */
    fun restoreEv(dump: Path) {
        val gzip = dump.toString().endsWith(".gz")
        mysql.copyFileToContainer(MountableFile.forHostPath(dump), "/tmp/ev.sql${if (gzip) ".gz" else ""}")
        val reader = if (gzip) "gunzip -c /tmp/ev.sql.gz" else "cat /tmp/ev.sql"
        exec(mysql, "sh", "-c", "$reader | mysql -u$MYSQL_ROOT_USER -p$mysqlRootPassword $EV_DATABASE")
    }

    /** 지금 v1 저장소 내용을 [SnapshotDataSource]가 읽는 형식으로 내보낸다(시드 덤프 확인용, docs/parity.md). */
    fun dumpTo(dir: Path) {
        Files.createDirectories(dir)
        exec(mongo, "mongodump", "--db=$MONGO_DATABASE", "--archive=/tmp/seed.archive", "--gzip")
        mongo.copyFileFromContainer("/tmp/seed.archive", dir.resolve("mongo.archive.gz").toString())
        exec(mysql, "sh", "-c", "mysqldump --no-tablespaces -u$MYSQL_ROOT_USER -p$mysqlRootPassword $EV_DATABASE > /tmp/ev.sql")
        mysql.copyFileFromContainer("/tmp/ev.sql", dir.resolve("ev.sql").toString())
    }

    private fun exec(
        container: GenericContainer<*>,
        vararg command: String,
    ) {
        val result = container.execInContainer(*command)
        check(result.exitCode == 0) { "${command.first()} 실패(${result.exitCode}): ${result.stderr.takeLast(2_000)}" }
    }

    private fun v1Container(
        name: String,
        jar: File,
    ): GenericContainer<*> {
        val log = ParityReports.dir.resolve("$name.log")
        Files.createDirectories(log.parent)
        Files.deleteIfExists(log)
        return GenericContainer(v1JavaImage)
            .withExposedPorts(8080)
            .withCopyFileToContainer(MountableFile.forHostPath(jar.toPath()), "/app/app.jar")
            .withLogConsumer { frame: OutputFrame -> appendLog(log, frame) }
    }

    private fun appendLog(
        file: Path,
        frame: OutputFrame,
    ) {
        if (frame.type == OutputFrame.OutputType.END) return
        Files.write(file, frame.bytes ?: return, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun createEvDatabase() {
        val url = "jdbc:mysql://${mysql.host}:${mysql.getMappedPort(3306)}/"
        DriverManager.getConnection(url, MYSQL_ROOT_USER, mysqlRootPassword).use { connection ->
            connection.createStatement().use {
                it.execute("CREATE DATABASE IF NOT EXISTS $EV_DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci")
            }
        }
    }

    private fun bridgeIp(container: GenericContainer<*>): String =
        checkNotNull(
            container.containerInfo.networkSettings.networks.values
                .first()
                .ipAddress,
        ) { "${container.dockerImageName} 컨테이너의 bridge IP가 없다" }

    private fun requiredFile(property: String): File {
        val path = System.getProperty(property) ?: error("$property 가 없다. parity 테스트는 ./gradlew parityTest 로 돌린다")
        return File(path).also { check(it.isFile) { "$property=$path 파일이 없다" } }
    }

    /** 컨테이너 메모리를 작게 묶는다. 이 머신과 CI 러너에서 v1 둘과 v2가 함께 떠야 한다. */
    private val JVM_OPTS = arrayOf("-Xmx384m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xss512k")
}
