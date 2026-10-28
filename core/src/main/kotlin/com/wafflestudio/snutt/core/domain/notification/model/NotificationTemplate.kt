package com.wafflestudio.snutt.core.domain.notification.model

import com.wafflestudio.snutt.core.common.client.LocalizedMessage
import com.wafflestudio.snutt.core.common.push.LocalizedPushMessage

enum class NotificationTemplate(
    val title: LocalizedMessage,
    val body: LocalizedMessage,
) {
    LECTURE_VACANCY(
        LocalizedMessage(ko = "빈자리 알림", en = "Vacancy alert"),
        LocalizedMessage(
            ko = "\"%s (%s)\" 강의에 빈자리가 생겼습니다.\n%s에 수강신청 사이트를 확인해보세요!",
            en = "A seat has opened up in \"%s (%s)\".\nCheck the course registration site at %s!",
        ),
    ),
    CLASS_REMINDER_NOW(
        LocalizedMessage(ko = "📚 강의 리마인더", en = "📚 Class reminder"),
        LocalizedMessage(ko = "%s 강의 시간이에요.", en = "%s is starting now."),
    ),
    CLASS_REMINDER_AFTER(
        LocalizedMessage(ko = "📚 강의 리마인더", en = "📚 Class reminder"),
        LocalizedMessage(ko = "%s 강의 시작 %d분 후예요.", en = "%s started %d minutes ago."),
    ),
    CLASS_REMINDER_BEFORE(
        LocalizedMessage(ko = "📚 강의 리마인더", en = "📚 Class reminder"),
        LocalizedMessage(ko = "%s 강의 시작 %d분 전이에요.", en = "%s starts in %d minutes."),
    ),
    COURSEBOOK_NEW(
        LocalizedMessage(ko = "신규 수강편람", en = "New course book"),
        LocalizedMessage(ko = "%d년도 %s 수강편람이 추가되었습니다.", en = "The %d %s course book has been added."),
    ),
    COURSEBOOK_UPDATE_CHANGED_AND_DELETED(
        LocalizedMessage(ko = "수강편람 업데이트", en = "Course book update"),
        LocalizedMessage(
            ko = "강의 %d개가 변경, %d개가 삭제되었습니다. 알림함에서 자세히 확인하세요.",
            en = "%d lectures were changed and %d were removed. Check your notifications for details.",
        ),
    ),
    COURSEBOOK_UPDATE_CHANGED(
        LocalizedMessage(ko = "수강편람 업데이트", en = "Course book update"),
        LocalizedMessage(
            ko = "강의 %d개가 변경되었습니다. 알림함에서 자세히 확인하세요.",
            en = "%d lectures were changed. Check your notifications for details.",
        ),
    ),
    COURSEBOOK_UPDATE_DELETED(
        LocalizedMessage(ko = "수강편람 업데이트", en = "Course book update"),
        LocalizedMessage(
            ko = "강의 %d개가 삭제되었습니다. 알림함에서 자세히 확인하세요.",
            en = "%d lectures were removed. Check your notifications for details.",
        ),
    ),
    TIMETABLE_LECTURE_UPDATED(
        LocalizedMessage(ko = "수강편람 업데이트", en = "Course book update"),
        LocalizedMessage(
            ko = "%s '%s' 시간표의 '%s' 강의가 업데이트 되었습니다.(항목: %s)",
            en = "%s · \"%s\" timetable: \"%s\" was updated. (changed: %s)",
        ),
    ),
    TIMETABLE_LECTURE_OVERWRITTEN(
        LocalizedMessage(ko = "수강편람 업데이트", en = "Course book update"),
        LocalizedMessage(
            ko = "%s '%s' 시간표의 '%s' 강의가 업데이트되었으나, 시간표의 다른 강의와 겹쳐 삭제되었습니다.",
            en = "%s · \"%s\" timetable: \"%s\" was updated, but it was removed because it overlapped another lecture.",
        ),
    ),
    TIMETABLE_LECTURE_CLOSED(
        LocalizedMessage(ko = "수강편람 업데이트", en = "Course book update"),
        LocalizedMessage(
            ko = "%s '%s' 시간표의 '%s' 강의가 폐강되어 삭제되었습니다.",
            en = "%s · \"%s\" timetable: \"%s\" was cancelled and has been removed.",
        ),
    ),
    BOOKMARK_LECTURE_UPDATED(
        LocalizedMessage(ko = "수강편람 업데이트", en = "Course book update"),
        LocalizedMessage(
            ko = "%s 관심강좌 목록의 '%s' 강의가 업데이트 되었습니다.(항목: %s)",
            en = "%s · Bookmarks: \"%s\" was updated. (changed: %s)",
        ),
    ),
    BOOKMARK_LECTURE_CLOSED(
        LocalizedMessage(ko = "수강편람 업데이트", en = "Course book update"),
        LocalizedMessage(
            ko = "%s 관심강좌 목록의 '%s' 강의가 폐강되어 삭제되었습니다.",
            en = "%s · Bookmarks: \"%s\" was cancelled and has been removed.",
        ),
    ),
    DIARY_WRITE(
        LocalizedMessage(ko = "이번주 강의일기를 작성해보세요.", en = "Write this week's class diary."),
        LocalizedMessage(
            ko = "최근 수강한 <%s> 강의에 대한 강의일기를 작성해보세요.📔",
            en = "Write a class diary for <%s>, one of your recent classes.📔",
        ),
    ),
    FRIEND_REQUEST(
        LocalizedMessage(ko = "친구 요청", en = "Friend request"),
        LocalizedMessage(
            ko = "'%s'님의 친구 요청을 수락하고 서로의 대표 시간표를 확인해보세요!",
            en = "%s sent you a friend request. Accept it to see each other's primary timetable!",
        ),
    ),
    FRIEND_REQUEST_ACCEPTED(
        LocalizedMessage(ko = "친구 요청 수락", en = "Friend request accepted"),
        LocalizedMessage(ko = "'%s'님과 친구가 되었어요.", en = "You and %s are now friends."),
    ),
    ;

    fun render(vararg args: Any): LocalizedPushMessage = LocalizedPushMessage(title = title.format(*args), body = body.format(*args))
}
