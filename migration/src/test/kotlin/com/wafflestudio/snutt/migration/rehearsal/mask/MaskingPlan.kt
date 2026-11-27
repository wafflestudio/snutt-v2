package com.wafflestudio.snutt.migration.rehearsal.mask

/**
 * 마스킹 대상 목록. 문서(docs/migration-rehearsal.md "마스킹 대상")의 표와 같은 내용이고, 마스킹 리포트도 이 목록을 따른다.
 *
 * - [MaskRule.INJECTIVE]: 서로 다른 원문이 같은 가명이 되지 않게 충돌을 피한다. v1·v2 유일 제약이나 이관의 중복 판정에 쓰이는 값이다.
 * - [MaskRule.DETERMINISTIC]: 같은 원문은 같은 가명이 된다(같은 영역 안). 충돌은 피하지 않는다(값이 길거나 중복 판정에 안 쓰임).
 */
enum class MaskRule { INJECTIVE, DETERMINISTIC }

class MaskedField(
    val source: String,
    val path: String,
    val rule: MaskRule,
    val domain: String,
    val why: String,
)

object MaskingPlan {
    /** 개인정보가 들어 있는 Mongo 컬렉션. [MongoMasker]가 손댄다. */
    val MONGO_PII_COLLECTIONS =
        setOf("users", "friend", "userDevice", "timetables", "timetableTheme", "notifications", "diarySubmission")

    /** 검토했고 개인정보가 없는 Mongo 컬렉션. 그대로 둔다. */
    val MONGO_CLEAN_COLLECTIONS =
        setOf(
            "coursebooks",
            "lectures",
            "lectureBuilding",
            "semesterRegistrationPeriod",
            "clientConfig",
            "popup",
            "diaryDailyClassType",
            "diaryQuestion",
            "bookmarks",
            "vacancy_notifications",
            "pushPreference",
            "timetableLectureReminder",
            "taglists",
        )

    /** 마스킹을 거친 덤프라는 표식. 리허설 실행기는 이 컬렉션이 없는 덤프를 거부한다(리포트에 원본 값이 남지 않게). */
    const val MARKER_COLLECTION = "_rehearsal_masked"

    /**
     * users 밖의 개인정보 컬렉션에서 알고 있는 최상위 필드(v1 데이터 클래스 기준). 목록 밖 필드는 지우지 않고 마스킹 리포트의
     * "검토하지 않은 필드"로 알린다(v1 서버·이관이 읽는 필드를 잘못 지우지 않으려고).
     */
    val KNOWN_FIELDS =
        mapOf(
            "friend" to
                setOf(
                    "_id",
                    "_class",
                    "fromUserId",
                    "toUserId",
                    "fromUserDisplayName",
                    "toUserDisplayName",
                    "isAccepted",
                    "createdAt",
                    "updatedAt",
                ),
            "userDevice" to
                setOf(
                    "_id",
                    "_class",
                    "userId",
                    "osType",
                    "osVersion",
                    "deviceId",
                    "deviceModel",
                    "appType",
                    "appVersion",
                    "fcmRegistrationId",
                    "createdAt",
                    "updatedAt",
                    "isDeleted",
                ),
            "timetables" to
                setOf(
                    "_id",
                    "_class",
                    "user_id",
                    "year",
                    "semester",
                    "lecture_list",
                    "title",
                    "theme",
                    "themeId",
                    "is_primary",
                    "updated_at",
                ),
            "timetableTheme" to
                setOf(
                    "_id",
                    "_class",
                    "userId",
                    "name",
                    "colors",
                    "isCustom",
                    "origin",
                    "status",
                    "publishInfo",
                    "createdAt",
                    "updatedAt",
                    "isDefault",
                ),
            "notifications" to setOf("_id", "_class", "user_id", "title", "message", "type", "deeplink", "urlScheme", "created_at"),
            "diarySubmission" to
                setOf(
                    "_id",
                    "_class",
                    "userId",
                    "dailyClassTypeIds",
                    "questionAnswers",
                    "lectureId",
                    "courseTitle",
                    "comment",
                    "createdAt",
                    "year",
                    "semester",
                ),
        )

    /** users 문서에서 알고 있는 최상위 필드. 그 밖의 필드(예전 버전이 남긴 필드)는 무엇이 들었는지 모르므로 지운다. */
    val USER_FIELDS =
        setOf(
            "_id",
            "_class",
            "email",
            "nickname",
            "isEmailVerified",
            "credential",
            "credentialHash",
            "fcmKey",
            "active",
            "isAdmin",
            "regDate",
            "lastLoginTimestamp",
            "notificationCheckedAt",
        )

    val CREDENTIAL_FIELDS =
        setOf(
            "localId",
            "localPw",
            "fbId",
            "fbName",
            "appleSub",
            "appleEmail",
            "appleTransferSub",
            "googleSub",
            "googleEmail",
            "kakaoSub",
            "kakaoEmail",
            "tempDate",
            "tempSeed",
        )

    /** 검토한 EV 테이블과 마스킹할 컬럼. 목록에 없는 테이블이나 컬럼이 있으면 마스킹을 멈춘다. */
    val EV_TABLES =
        mapOf(
            "lecture" to emptySet(),
            "semester_lecture" to emptySet(),
            "lecture_evaluation" to setOf("content"),
            "evaluation_like" to emptySet(),
            "evaluation_report" to setOf("content"),
            "tag_group" to emptySet(),
            "tag" to emptySet(),
            "snutt_lecture_id_map" to emptySet(),
            "flyway_schema_history" to emptySet(),
        )

    val EV_COLUMNS =
        mapOf(
            "lecture" to
                setOf(
                    "id",
                    "created_at",
                    "updated_at",
                    "academic_year",
                    "category",
                    "classification",
                    "course_number",
                    "credit",
                    "department",
                    "instructor",
                    "title",
                ),
            "semester_lecture" to
                setOf(
                    "id",
                    "created_at",
                    "updated_at",
                    "academic_year",
                    "category",
                    "classification",
                    "credit",
                    "extra_info",
                    "semester",
                    "year",
                    "lecture_id",
                ),
            "lecture_evaluation" to
                setOf(
                    "id",
                    "created_at",
                    "updated_at",
                    "content",
                    "gains",
                    "grade_satisfaction",
                    "is_hidden",
                    "is_reported",
                    "life_balance",
                    "like_count",
                    "rating",
                    "teaching_skill",
                    "user_id",
                    "semester_lecture_id",
                    "from_snuev",
                ),
            "evaluation_like" to setOf("id", "created_at", "updated_at", "user_id", "lecture_evaluation_id"),
            "evaluation_report" to setOf("id", "created_at", "updated_at", "content", "is_hidden", "user_id", "lecture_evaluation_id"),
            "tag_group" to setOf("id", "created_at", "updated_at", "color", "name", "ordering", "value_type"),
            "tag" to
                setOf(
                    "id",
                    "created_at",
                    "updated_at",
                    "description",
                    "int_value",
                    "name",
                    "ordering",
                    "string_value",
                    "tag_group_id",
                ),
            "snutt_lecture_id_map" to setOf("id", "created_at", "updated_at", "snutt_id", "semester_lecture_id"),
        )

    val FIELDS: List<MaskedField> =
        listOf(
            MaskedField(
                "users",
                "email",
                MaskRule.INJECTIVE,
                "email",
                "개인 이메일. 대소문자 무시 유일(v1 인덱스, v2 active_email)과 중복 인증 판정에 쓰여 소문자 기준으로 충돌을 피하고 대소문자 모양은 원문을 따른다. 도메인은 5개 미만 주소가 쓰는 드문 도메인만 바꾼다",
            ),
            MaskedField(
                "users",
                "nickname",
                MaskRule.INJECTIVE,
                "nickname",
                "닉네임. `#` 앞 이름만 바꾸고 태그(4자리)는 둔다. v1 유일 인덱스와 v2 (닉네임, 태그) 유일 제약을 지킨다",
            ),
            MaskedField(
                "users",
                "credential.localId",
                MaskRule.INJECTIVE,
                "local-id",
                "로그인 아이디. 같은 아이디의 활성 계정 정리(LOCAL_ID_DUPLICATE) 판정에 쓰인다",
            ),
            MaskedField(
                "users",
                "credential.localPw",
                MaskRule.DETERMINISTIC,
                "password-hash",
                "비밀번호 bcrypt 해시(오프라인 대입 공격 대상). 머리(`$2a$10$`, 알고리즘·비용)는 두고 솔트·해시 53자만 바꾼다. 형식은 맞지만 어떤 비밀번호와도 맞지 않는다",
            ),
            MaskedField("users", "credential.fbId", MaskRule.INJECTIVE, "sub:fb", "Facebook 사용자 id. (provider, sub) 유일과 중복 정리 판정"),
            MaskedField("users", "credential.fbName", MaskRule.DETERMINISTIC, "person-name", "Facebook 실명"),
            MaskedField("users", "credential.appleSub", MaskRule.INJECTIVE, "sub:apple", "Apple sub"),
            MaskedField(
                "users",
                "credential.appleEmail",
                MaskRule.INJECTIVE,
                "email",
                "Apple 이메일(릴레이 주소 포함). users.email과 같은 영역이라 같은 주소는 같게 바뀐다",
            ),
            MaskedField("users", "credential.appleTransferSub", MaskRule.INJECTIVE, "sub:apple-transfer", "Apple 이전용 sub"),
            MaskedField("users", "credential.googleSub", MaskRule.INJECTIVE, "sub:google", "Google sub"),
            MaskedField("users", "credential.googleEmail", MaskRule.INJECTIVE, "email", "Google 이메일"),
            MaskedField("users", "credential.kakaoSub", MaskRule.INJECTIVE, "sub:kakao", "Kakao 회원번호"),
            MaskedField("users", "credential.kakaoEmail", MaskRule.INJECTIVE, "email", "Kakao 이메일"),
            MaskedField("users", "credential.tempSeed", MaskRule.DETERMINISTIC, "secret", "임시 인증 시드(비밀값)"),
            MaskedField(
                "users",
                "credentialHash",
                MaskRule.INJECTIVE,
                "token",
                "구 앱 액세스 토큰 그 자체(x-access-token). 같은 토큰 공유 판정(LEGACY_TOKEN_AMBIGUOUS)에 쓰인다. 바뀐 값이 v1·v2 양쪽 토큰이 되므로 parity가 그대로 돈다",
            ),
            MaskedField("users", "fcmKey", MaskRule.DETERMINISTIC, "fcm", "구 FCM 기기 그룹 키(푸시 발송 가능)"),
            MaskedField("users", "(목록 밖 최상위 필드)", MaskRule.DETERMINISTIC, "-", "예전 버전이 남긴 필드는 내용을 모르므로 지운다. 이관은 읽지 않는다"),
            MaskedField(
                "userDevice",
                "fcmRegistrationId",
                MaskRule.DETERMINISTIC,
                "fcm",
                "FCM 등록 토큰(푸시 발송 가능). 같은 토큰은 같게 바뀌어 중복 기기 정리(DEVICE_REGISTRATION_DUPLICATE)가 같게 돈다. 길이가 100자를 넘어 충돌 확률은 무시한다",
            ),
            MaskedField("userDevice", "deviceId", MaskRule.DETERMINISTIC, "device-id", "기기 식별자(IDFV/ANDROID_ID)"),
            MaskedField(
                "friend",
                "fromUserDisplayName, toUserDisplayName",
                MaskRule.DETERMINISTIC,
                "display-name",
                "친구에게 붙인 표시 이름(실명이 흔하다)",
            ),
            MaskedField(
                "timetables",
                "title",
                MaskRule.INJECTIVE,
                "timetable-title",
                "사용자가 쓴 시간표 제목. 같은 학기 제목 중복 정리(TIMETABLE_TITLE_DUPLICATE)가 같게 돌도록 같은 제목은 같게, 다른 제목은 다르게 바꾼다",
            ),
            MaskedField(
                "timetables",
                "lecture_list[].course_title, instructor, remark",
                MaskRule.DETERMINISTIC,
                "free-text",
                "직접 만든 강의(lecture_id 없음)의 내용과, 수강편람 강의를 사용자가 고친 값. 수강편람과 같은 값은 두어 override 분포를 지킨다",
            ),
            MaskedField(
                "timetables",
                "lecture_list[].class_time_json[].place (직접 만든 강의)",
                MaskRule.DETERMINISTIC,
                "free-text",
                "직접 만든 강의의 장소는 사용자가 쓴 글이다. 수강편람 강의의 장소는 이관이 수업 시간 비교에 쓰므로 둔다",
            ),
            MaskedField(
                "timetableTheme",
                "name, publishInfo.publishName",
                MaskRule.INJECTIVE,
                "theme-name",
                "사용자가 지은 테마 이름. 다운로드 테마가 원본 게시 이름과 같은지로 게시 스냅샷을 고르므로 같은 영역을 쓴다",
            ),
            MaskedField(
                "notifications",
                "title, message (user_id 있는 문서)",
                MaskRule.DETERMINISTIC,
                "free-text",
                "개인 알림 본문(친구 이름 등). 전체 공지(user_id 없음)는 둔다",
            ),
            MaskedField("diarySubmission", "comment", MaskRule.DETERMINISTIC, "free-text", "강의 일기장 자유 서술"),
            MaskedField("EV lecture_evaluation", "content", MaskRule.DETERMINISTIC, "free-text", "강의평 본문. 줄바꿈·공백·길이를 보존한다"),
            MaskedField("EV evaluation_report", "content", MaskRule.DETERMINISTIC, "free-text", "신고 사유 자유 서술"),
        )
}
