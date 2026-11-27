package com.wafflestudio.snutt.api.perf

import com.wafflestudio.snutt.core.domain.device.repository.UserDeviceRepository
import com.wafflestudio.snutt.core.domain.diary.repository.DiarySubmissionAnswerRepository
import com.wafflestudio.snutt.core.domain.diary.repository.DiarySubmissionRepository
import com.wafflestudio.snutt.core.domain.friend.repository.FriendRepository
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType
import com.wafflestudio.snutt.core.domain.pushpreference.repository.PushPreferenceRepository
import com.wafflestudio.snutt.core.domain.theme.repository.PublishedThemeRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import com.wafflestudio.snutt.v1compat.snutt.LegacyNotificationRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Limit
import java.time.Instant

/** 친구·알림·일기·사용자·푸시 대상·공유 테마 조회의 실행계획. #64·#65·#66에서 통합한 쿼리를 고정한다. */
class SocialQueryPlanTest : AbstractPerfTest() {
    @Autowired lateinit var friendRepository: FriendRepository

    @Autowired lateinit var notificationRepository: NotificationRepository

    @Autowired lateinit var legacyNotificationRepository: LegacyNotificationRepository

    @Autowired lateinit var diarySubmissionRepository: DiarySubmissionRepository

    @Autowired lateinit var diarySubmissionAnswerRepository: DiarySubmissionAnswerRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var userDeviceRepository: UserDeviceRepository

    @Autowired lateinit var pushPreferenceRepository: PushPreferenceRepository

    @Autowired lateinit var publishedThemeRepository: PublishedThemeRepository

    private val registeredAt = Instant.parse("2019-03-01T00:00:00Z")

    @Test
    fun `친구 목록 UNION ALL은 갈래마다 보낸·받은 쪽 복합 인덱스를 쓴다`() {
        // 두 갈래를 합친 뒤 created_at으로 다시 정렬하는 것(<derived>의 filesort)은 피할 수 없다. 갈래 자체에는 정렬·풀 스캔이 없어야 한다.
        val plan = explainOne { friendRepository.findActiveByUserId(perf.heavyFriendUserId) }
        plan
            .assertNoFullScan()
            .assertNoFilesort(
                *plan.rows
                    .filter { it.isTemporaryTable }
                    .map { it.table }
                    .toTypedArray(),
            )
        val keys = plan.rowsOf("friend").map { it.key }.toSet()
        if (keys != setOf("idx_friend_from_user_accepted_created", "idx_friend_to_user_accepted_created")) {
            plan.failWith("갈래가 각각 from/to 복합 인덱스를 써야 한다. 실제: $keys")
        }
    }

    @Test
    fun `대기 중인 친구 요청은 복합 인덱스 순서로 filesort 없이 읽는다`() {
        explainOne { friendRepository.findByFromUserIdAndIsAcceptedFalseOrderByCreatedAtDesc(perf.heavyFriendUserId) }
            .assertKey("friend", "idx_friend_from_user_accepted_created")
            .assertNoFilesort()
        explainOne { friendRepository.findByToUserIdAndIsAcceptedFalseOrderByCreatedAtDesc(perf.heavyFriendUserId) }
            .assertKey("friend", "idx_friend_to_user_accepted_created")
            .assertNoFilesort()
    }

    @Test
    fun `친구 쌍 조회는 uk_friend_pair 한 행이다`() {
        explainOne { friendRepository.findByUserPair(perf.heavyFriendUserId, perf.heavyFriendPartnerId) }
            .assertKey("friend", "uk_friend_pair")
            .assertAccess("friend", "const")
    }

    @Test
    fun `알림 목록은 개인·전체 갈래 모두 idx_notification_user_created 순서로 LIMIT만큼 읽는다`() {
        val plan =
            explainOne {
                notificationRepository.findNotifications(perf.heavyNotificationUserId, registeredAt, null, null, 20)
            }
        plan
            .assertNoFullScan()
            .assertKey("notification", "idx_notification_user_created")
            .assertNoFilesort(
                *plan.rows
                    .filter { it.isTemporaryTable }
                    .map { it.table }
                    .toTypedArray(),
            )
    }

    @Test
    fun `커서가 있는 알림 목록도 갈래마다 인덱스 범위를 쓴다`() {
        val plan =
            explainOne {
                notificationRepository.findNotifications(
                    perf.heavyNotificationUserId,
                    registeredAt,
                    Instant.parse("2024-01-01T00:00:00Z"),
                    1_000L,
                    20,
                )
            }
        plan
            .assertNoFullScan()
            .assertKey("notification", "idx_notification_user_created")
            .assertNoFilesort(
                *plan.rows
                    .filter { it.isTemporaryTable }
                    .map { it.table }
                    .toTypedArray(),
            )
    }

    @Test
    fun `v1 알림 오프셋 목록도 갈래마다 idx_notification_user_created를 쓴다`() {
        val plan =
            explainOne { legacyNotificationRepository.findNotificationsWithOffset(perf.heavyNotificationUserId, registeredAt, 60, 20, 40) }
        plan
            .assertNoFullScan()
            .assertKey("notification", "idx_notification_user_created")
            .assertNoFilesort(
                *plan.rows
                    .filter { it.isTemporaryTable }
                    .map { it.table }
                    .toTypedArray(),
            )
    }

    @Test
    fun `안 읽은 알림 수는 user_id 인덱스로 개인 알림과 전체 공지만 센다`() {
        explainOne { notificationRepository.countUnread(perf.heavyNotificationUserId, Instant.parse("2026-01-01T00:00:00Z")) }
            .assertNoFullScan()
            .assertKey("notification", "idx_notification_user_created")
    }

    @Test
    fun `내 일기 목록은 idx_diary_submission_user_created 순서로 filesort 없이 읽는다`() {
        explainOne { diarySubmissionRepository.findByUserIdOrderByCreatedAtDesc(perf.heavyDiaryUserId) }
            .assertKey("diary_submission", "idx_diary_submission_user_created")
            .assertNoFilesort()
        explainOne { diarySubmissionRepository.findByUserIdAndCreatedAtAfter(perf.heavyDiaryUserId, Instant.parse("2026-09-01T00:00:00Z")) }
            .assertKey("diary_submission", "idx_diary_submission_user_created")
            .assertAccess("diary_submission", "range")
    }

    @Test
    fun `일기 답변은 제출 id 인덱스로 읽는다`() {
        explainOne { diarySubmissionAnswerRepository.findBySubmissionIdIn((1L..20L).toList()) }
            .assertKey("diary_submission_answer", "idx_diary_submission_answer_submission")
            .assertNoFullScan()
    }

    @Test
    fun `닉네임 태그 조회는 활성 닉네임 유니크 키 앞부분을 쓴다`() {
        explainOne { userRepository.findActiveNicknameTags("닉네임42") }
            .assertKey("user", "uk_user_active_nickname")
            .assertRowsAtMost("user", 20)
    }

    @Test
    fun `로그인 아이디 조회는 활성 로컬 아이디 인덱스를 쓴다`() {
        explainOne { userRepository.findByLocalIdAndActiveTrue("user42") }
            .assertNoFullScan()
            .assertRowsAtMost("user", 5)
    }

    @Test
    fun `푸시 대상과 수신 거부 조회는 사용자 인덱스로 읽는다`() {
        val users = (100L..150L).toList()
        explainOne { userDeviceRepository.findPushTargets(users) }
            .assertKey("user_device", "idx_user_device_user")
            .assertNoFullScan()
        explainOne { pushPreferenceRepository.findDisabledUserIds(users, PushPreferenceType.NORMAL) }
            .assertKey("push_preference", "uk_push_preference_user_type")
            .assertNoFullScan()
    }

    @Test
    fun `공유 테마 목록은 idx_published_theme_download 순서로 filesort 없이 읽는다`() {
        explainOne { publishedThemeRepository.findListed(null, null, null, Limit.of(20)) }
            .assertKey("published_theme", "idx_published_theme_download")
            .assertNoFilesort()
            .assertNoFullScan()
    }
}
