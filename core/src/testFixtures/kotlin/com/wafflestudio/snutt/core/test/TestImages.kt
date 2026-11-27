package com.wafflestudio.snutt.core.test

/**
 * 테스트 컨테이너 이미지. 모든 모듈(마이그레이션 포함)이 이 값을 쓴다.
 *
 * MySQL: 운영 DB는 공유 OCI HeatWave MySQL(10.0.30.217)이다. waffle-world-oci 레포에는 서버 버전 근거가 없다
 * (mysql-backup CronJob의 클라이언트 이미지 mysql:8.4만 있음). 서버 버전을 확인하면 여기 한 곳만 바꾼다.
 */
object TestImages {
    const val MYSQL = "mysql:26.7"
    const val VALKEY = "valkey/valkey:9-alpine"
    const val MONGO = "mongo:8.2.7"
}
