package com.wafflestudio.snutt.core.common.error

import com.wafflestudio.snutt.core.common.client.LocalizedMessage
import com.wafflestudio.snutt.core.common.client.en
import org.springframework.http.HttpStatus

enum class ErrorType(
    val httpStatus: HttpStatus,
    val displayMessage: LocalizedMessage,
    val title: LocalizedMessage,
) {
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "파라미터 값 오류" en "Invalid parameter value", "잘못된 요청" en "Invalid request"),
    INVALID_REQUEST_BODY(HttpStatus.BAD_REQUEST, "요청 바디 값 오류" en "Invalid request body value", "잘못된 요청" en "Invalid request"),
    INVALID_REGISTRATION_FOR_PREVIOUS_SEMESTER_COURSE(
        HttpStatus.BAD_REQUEST,
        "이전 학기에는 빈자리 알림을 등록할 수 없습니다" en "You can't enable vacancy notifications for past semesters",
        "알림 등록 불가" en "Can't set notification",
    ),
    INVALID_NICKNAME(
        HttpStatus.BAD_REQUEST,
        "사용할 수 없는 닉네임입니다" en "That nickname can't be used",
        "올바르지 않은 닉네임" en "Invalid nickname",
    ),
    INVALID_DISPLAY_NAME(
        HttpStatus.BAD_REQUEST,
        "조건에 맞지 않는 이름입니다" en "That name doesn't meet the requirements",
        "올바르지 않은 이름" en "Invalid name",
    ),
    CANNOT_DELETE_LAST_TIMETABLE(
        HttpStatus.BAD_REQUEST,
        "하나 남은 시간표는 삭제할 수 없습니다" en "You can't delete your only timetable",
        "시간표 삭제 불가" en "Can't delete timetable",
    ),
    INVALID_THEME_COLOR_COUNT(
        HttpStatus.BAD_REQUEST,
        "테마의 색상 개수가 적절하지 않습니다" en "This theme doesn't have a valid number of colors",
        "올바르지 않은 테마 색상" en "Invalid theme colors",
    ),
    NOT_DEFAULT_THEME(HttpStatus.BAD_REQUEST, "default 테마가 아닙니다" en "This isn't a default theme", "기본 테마 아님" en "Not a default theme"),
    TOO_MANY_FILES(
        HttpStatus.BAD_REQUEST,
        "파일 개수가 너무 많습니다" en "You've attached too many files",
        "파일 개수 초과" en "Too many files",
    ),
    EMAIL_ALREADY_VERIFIED(
        HttpStatus.BAD_REQUEST,
        "이미 메일 인증이 완료된 유저입니다" en "Your email is already verified",
        "이미 인증된 이메일" en "Email already verified",
    ),
    TOO_MANY_VERIFICATION_CODE_REQUESTS(
        HttpStatus.TOO_MANY_REQUESTS,
        "인증 코드는 1분에 1회, 1시간에 최대 5회까지 요청할 수 있습니다" en
            "You can request a verification code once a minute, up to 5 times an hour",
        "인증 요청 실패" en "Verification request failed",
    ),
    INVALID_VERIFICATION_CODE(
        HttpStatus.BAD_REQUEST,
        "인증 코드가 유효하지 않습니다" en "That verification code isn't valid",
        "올바르지 않은 인증 코드" en "Invalid verification code",
    ),
    INVALID_PASSWORD_RESET_TOKEN(
        HttpStatus.BAD_REQUEST,
        "비밀번호 재설정 인증이 만료되었습니다. 인증 코드를 다시 받아 주세요" en "Your password reset verification has expired. Please request a new code",
        "재설정 인증 만료" en "Reset verification expired",
    ),
    ALREADY_LOCAL_ACCOUNT(
        HttpStatus.BAD_REQUEST,
        "이미 로컬 계정이 존재합니다" en "You already have an account with an ID and password",
        "로컬 계정 중복" en "Account already exists",
    ),
    ALREADY_SOCIAL_ACCOUNT(
        HttpStatus.BAD_REQUEST,
        "이미 소셜 계정이 존재합니다" en "You already have a social account",
        "소셜 계정 중복" en "Duplicate social account",
    ),
    NOT_PUBLISHED_THEME(
        HttpStatus.BAD_REQUEST,
        "공유한 테마가 아닙니다" en "This theme isn't shared",
        "공유되지 않은 테마" en "Theme not shared",
    ),
    CANNOT_DELETE_PUBLISHED_THEME(
        HttpStatus.BAD_REQUEST,
        "테마마켓에서 테마를 내린 뒤 다시 시도해주세요" en "Please unpublish the theme from Theme Market, then try again",
        "테마 삭제 불가" en "Can't delete theme",
    ),
    TIMETABLE_LECTURE_REMINDER_INVALID_TIME(
        HttpStatus.BAD_REQUEST,
        "리마인더는 시간이 설정된 강의에만 등록할 수 있어요" en "Reminders can only be set for lectures with a scheduled time",
        "리마인더 등록 불가" en "Can't set reminder",
    ),
    INVALID_DIARY_QUESTION(
        HttpStatus.BAD_REQUEST,
        "강의 일기장 질문이 유효하지 않습니다" en "That course diary question isn't valid",
        "올바르지 않은 강의 일기장 질문" en "Invalid course diary question",
    ),
    DIARY_COMMENT_TOO_LONG(
        HttpStatus.BAD_REQUEST,
        "더 남기고 싶은 말은 최대 1,000자 입력할 수 있습니다" en "Additional comments can be up to 1,000 characters",
        "입력 길이 초과" en "Too long",
    ),
    EVALUATION_CONTENT_BLANK(
        HttpStatus.BAD_REQUEST,
        "강의평 내용을 입력해 주세요" en "Please write your course review",
        "강의평 내용 없음" en "No review content",
    ),
    EVALUATION_RATING_OUT_OF_RANGE(
        HttpStatus.BAD_REQUEST,
        "평점은 1점부터 5점까지입니다" en "Ratings must be between 1 and 5",
        "올바르지 않은 평점" en "Invalid rating",
    ),
    EVALUATION_REPORT_CONTENT_BLANK(
        HttpStatus.BAD_REQUEST,
        "신고 내용을 입력해 주세요" en "Please describe your report",
        "신고 내용 없음" en "No report content",
    ),
    INVALID_TIMETABLE_TITLE(
        HttpStatus.BAD_REQUEST,
        "시간표 이름을 입력해 주세요" en "Please enter a timetable name",
        "올바르지 않은 시간표 이름" en "Invalid timetable name",
    ),
    INVALID_CLASS_TIME(
        HttpStatus.BAD_REQUEST,
        "강좌의 시간이 올바르게 설정되었는지 확인해 주시기 바랍니다" en "Please check that the lecture's time is set correctly",
        "시간대 겹침" en "Time conflict",
    ),
    INVALID_EVALUATION_SORT(
        HttpStatus.BAD_REQUEST,
        "sort는 latest 또는 recommended 여야 합니다" en "sort must be either latest or recommended",
        "잘못된 요청" en "Invalid request",
    ),
    INVALID_CURSOR(
        HttpStatus.BAD_REQUEST,
        "cursor 형식이 올바르지 않습니다" en "The cursor format is invalid",
        "잘못된 요청" en "Invalid request",
    ),
    DIARY_SUBMISSION_TOO_FREQUENT(
        HttpStatus.BAD_REQUEST,
        "오늘 이미 강의 일기장을 작성했습니다" en "You've already written a course diary entry today",
        "강의 일기장 작성 불가" en "Can't write course diary",
    ),

    SOCIAL_LOGIN_FAILED(
        HttpStatus.UNAUTHORIZED,
        "소셜 로그인에 실패했습니다" en "Social sign-in failed",
        "로그인 실패" en "Sign-in failed",
    ),
    INVALID_APPLE_LOGIN_TOKEN(
        HttpStatus.UNAUTHORIZED,
        "소셜 로그인에 실패했습니다" en "Social sign-in failed",
        "로그인 실패" en "Sign-in failed",
    ),
    EXPIRED_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요" en "Please sign in again", "로그인 만료" en "Session expired"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요" en "Please sign in again", "로그인 만료" en "Session expired"),
    MISSING_ACCESS_TOKEN(
        HttpStatus.UNAUTHORIZED,
        "앱을 완전히 종료한 뒤, 로그인을 다시 시도해 주시기 바랍니다" en "Please close the app completely and sign in again",
        "로그인 실패" en "Sign-in failed",
    ),

    NOT_MY_EVALUATION(HttpStatus.FORBIDDEN, "내 강의평이 아닙니다" en "This isn't your course review", "권한 없음" en "No permission"),
    WRONG_API_KEY(HttpStatus.FORBIDDEN, "API 키가 잘못되었습니다" en "The API key is invalid", "로그인 실패" en "Sign-in failed"),
    INVALID_ACCESS_TOKEN(
        HttpStatus.UNAUTHORIZED,
        "앱을 완전히 종료한 뒤, 로그인을 다시 시도해 주시기 바랍니다" en "Please close the app completely and sign in again",
        "로그인 실패" en "Sign-in failed",
    ),
    USER_NOT_ADMIN(
        HttpStatus.FORBIDDEN,
        "Admin 사용자가 아닙니다. 운영진에게 문의 주시기 바랍니다" en "You aren't an admin user. Please contact the SNUTT team",
        "잘못된 요청" en "Invalid request",
    ),
    UNREGISTERED_LOCAL_ID(
        HttpStatus.FORBIDDEN,
        "등록되지 않은 아이디입니다. 다시 입력해 주세요" en "That ID isn't registered. Please try again",
        "로그인 실패" en "Sign-in failed",
    ),
    PASSWORD_MISMATCH(
        HttpStatus.FORBIDDEN,
        "잘못된 비밀번호입니다. 다시 입력해 주세요" en "Incorrect password. Please try again",
        "로그인 실패" en "Sign-in failed",
    ),
    INVALID_LOCAL_ID(
        HttpStatus.BAD_REQUEST,
        "아이디는 영문 혹은 숫자로 구성된 4~32자여야 합니다" en "Your ID must be 4–32 characters long and contain only letters and numbers",
        "올바르지 않은 아이디" en "Invalid ID",
    ),
    INVALID_PASSWORD(
        HttpStatus.BAD_REQUEST,
        "비밀번호는 영문과 숫자 모두를 포함하는 6~20자여야 합니다" en
            "Your password must be 6–20 characters long and include both letters and numbers",
        "올바르지 않은 비밀번호" en "Invalid password",
    ),
    DUPLICATE_LOCAL_ID(
        HttpStatus.CONFLICT,
        "이미 존재하는 아이디입니다" en "That ID is already taken",
        "아이디 등록 실패" en "ID registration failed",
    ),
    DUPLICATE_TIMETABLE_TITLE(
        HttpStatus.CONFLICT,
        "같은 학기에 동일한 이름의 시간표가 있습니다" en "You already have a timetable with this name in the same semester",
        "시간표 이름 중복" en "Duplicate timetable name",
    ),
    DUPLICATE_LECTURE(
        HttpStatus.CONFLICT,
        "이미 추가된 강좌입니다" en "This lecture is already in your timetable",
        "강좌 중복" en "Duplicate lecture",
    ),
    LECTURE_SEMESTER_MISMATCH(
        HttpStatus.FORBIDDEN,
        "현재 선택한 학기에서 개설된 강좌가 아닙니다" en "This lecture isn't offered in the selected semester",
        "학기 불일치" en "Semester mismatch",
    ),
    INVALID_TIMETABLE_SEMESTER(
        HttpStatus.BAD_REQUEST,
        "시간표를 생성할 수 없는 학기입니다" en "You can't create a timetable for this semester",
        "시간표 생성 불가" en "Can't create timetable",
    ),
    LECTURE_TIME_OVERLAP(
        HttpStatus.CONFLICT,
        "%s 강의와 시간이 겹칩니다. 강의를 덮어씌우겠습니까?" en "This overlaps with %s. Overwrite?",
        "시간대 겹침" en "Time conflict",
    ),
    CANNOT_RESET_CUSTOM_LECTURE(
        HttpStatus.FORBIDDEN,
        "직접 만든 강좌는 초기화할 수 없습니다" en "Lectures you created yourself can't be reset",
        "강좌 초기화 불가" en "Can't reset lecture",
    ),
    INVALID_EMAIL(
        HttpStatus.BAD_REQUEST,
        "이메일 형식이 올바르지 않습니다. 다시 입력해 주세요" en "That email address isn't valid. Please try again",
        "올바르지 않은 이메일" en "Invalid email",
    ),
    EMAIL_NOT_REGISTERED(
        HttpStatus.BAD_REQUEST,
        "이 계정에는 등록된 이메일이 없어 비밀번호를 재설정할 수 없습니다" en "This account has no registered email, so its password can't be reset",
        "이메일 없음" en "No registered email",
    ),
    EMAIL_NOT_VERIFIED(
        HttpStatus.FORBIDDEN,
        "이메일 인증이 완료된 후 강의평을 확인할 수 있습니다" en "You can view course reviews once your email is verified",
        "이메일 인증 필요" en "Email verification required",
    ),

    TIMETABLE_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "존재하지 않는 시간표입니다" en "That timetable doesn't exist",
        "시간표 없음" en "Timetable not found",
    ),
    PRIMARY_TIMETABLE_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "대표 시간표가 존재하지 않습니다" en "You don't have a primary timetable",
        "대표 시간표 없음" en "No primary timetable",
    ),
    CONFIG_NOT_FOUND(HttpStatus.NOT_FOUND, "config가 존재하지 않습니다" en "That config doesn't exist", "설정 없음" en "Config not found"),
    FRIEND_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "친구 관계가 존재하지 않습니다" en "We couldn't find that friend request or friendship",
        "친구 관계 없음" en "Friend not found",
    ),
    USER_NOT_FOUND_BY_NICKNAME(
        HttpStatus.NOT_FOUND,
        "해당 닉네임의 유저를 찾을 수 없습니다" en "No user found with that nickname",
        "사용자 없음" en "User not found",
    ),
    THEME_NOT_FOUND(HttpStatus.NOT_FOUND, "테마를 찾을 수 없습니다" en "That theme couldn't be found", "테마 없음" en "Theme not found"),
    EVALUATION_TARGET_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "강의평 데이터를 찾을 수 없습니다" en "Course review data couldn't be found",
        "강의평 데이터 없음" en "Review data not found",
    ),
    FRIEND_LINK_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "친구 링크가 유효하지 않습니다" en "That friend link isn't valid",
        "올바르지 않은 친구 링크" en "Invalid friend link",
    ),
    SOCIAL_PROVIDER_NOT_ATTACHED(
        HttpStatus.NOT_FOUND,
        "소셜 계정이 연동되지 않았습니다" en "No social account is connected",
        "연동되지 않은 소셜 계정" en "Social account not connected",
    ),
    DIARY_QUESTION_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "강의 일기장 질문이 유효하지 않습니다" en "That course diary question isn't valid",
        "강의 일기장 질문 없음" en "Course diary question not found",
    ),
    DIARY_DAILY_CLASS_TYPE_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "강의 일기장 오늘 한 일이 유효하지 않습니다" en "That course diary activity isn't valid",
        "강의 일기장 항목 없음" en "Course diary item not found",
    ),
    DIARY_TARGET_LECTURE_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "강의 일기장을 작성할 강의가 없습니다" en "We couldn't find the lecture for this course diary entry",
        "강의 일기장 대상 강의 없음" en "Course diary lecture not found",
    ),
    DIARY_SUBMISSION_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "강의 일기장 기록이 유효하지 않습니다" en "That course diary entry isn't valid",
        "강의 일기장 기록 없음" en "Course diary entry not found",
    ),
    EVALUATION_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "강의평을 찾을 수 없습니다" en "That course review couldn't be found",
        "강의평 없음" en "Review not found",
    ),
    COURSE_NOT_FOUND(HttpStatus.NOT_FOUND, "과목을 찾을 수 없습니다" en "That course couldn't be found", "과목 없음" en "Course not found"),
    LECTURE_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "수강편람에서 찾을 수 없는 강좌입니다" en "This lecture isn't in the course catalog",
        "강좌 없음" en "Lecture not found",
    ),
    USER_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "해당 정보로 가입된 사용자가 없습니다" en "No account matches that information",
        "사용자 없음" en "User not found",
    ),
    TIMETABLE_LECTURE_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "시간표에 존재하지 않는 강좌입니다" en "This lecture isn't in your timetable",
        "강좌 없음" en "Lecture not found",
    ),
    COURSEBOOK_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "수강편람이 아직 등록되지 않았습니다" en "The course catalog hasn't been published yet",
        "수강편람 없음" en "Course catalog not found",
    ),

    DUPLICATE_VACANCY_NOTIFICATION(
        HttpStatus.CONFLICT,
        "이미 빈자리 알림을 받고 있는 강좌입니다" en "You've already enabled vacancy notifications for this lecture",
        "빈자리 알림 중복" en "Duplicate vacancy notification",
    ),
    DUPLICATE_EMAIL(
        HttpStatus.CONFLICT,
        "이미 사용 중인 이메일입니다" en "That email is already in use",
        "회원가입 실패" en "Sign-up failed",
    ),
    DUPLICATE_FRIEND(
        HttpStatus.CONFLICT,
        "이미 친구 관계이거나 친구 요청을 보냈습니다" en "You're already friends or have already sent a request",
        "친구 요청 중복" en "Duplicate friend request",
    ),
    SELF_FRIEND_REQUEST(
        HttpStatus.CONFLICT,
        "친구 요청을 보낼 수 없는 유저입니다" en "You can't send a friend request to this user",
        "친구 요청 불가" en "Can't send friend request",
    ),
    NOT_CUSTOM_THEME(
        HttpStatus.CONFLICT,
        "적절하지 않은 유형의 테마입니다" en "This theme type is invalid",
        "올바르지 않은 테마 유형" en "Invalid theme type",
    ),
    DUPLICATE_POPUP_KEY(
        HttpStatus.CONFLICT,
        "중복된 팝업 키입니다" en "That popup key is already in use",
        "팝업 키 중복" en "Duplicate popup key",
    ),
    ALREADY_DOWNLOADED_THEME(
        HttpStatus.CONFLICT,
        "이미 다운로드한 테마입니다" en "You've already downloaded this theme",
        "테마 중복" en "Duplicate theme",
    ),
    DUPLICATE_SOCIAL_ACCOUNT(
        HttpStatus.CONFLICT,
        "이미 연결된 소셜 계정입니다" en "That social account is already connected",
        "소셜 계정 중복" en "Duplicate social account",
    ),
    CANNOT_REMOVE_LAST_AUTH_PROVIDER(
        HttpStatus.CONFLICT,
        "최소 한 개의 로그인 수단은 유지해야 합니다" en "You must keep at least one sign-in method",
        "로그인 수단 삭제 불가" en "Can't remove sign-in method",
    ),
    DUPLICATE_EVALUATION(
        HttpStatus.CONFLICT,
        "이미 강의평을 작성한 강의입니다" en "You've already written a review for this lecture",
        "강의평 중복" en "Duplicate review",
    ),
    DUPLICATE_EVALUATION_REPORT(
        HttpStatus.CONFLICT,
        "이미 신고한 강의평입니다" en "You've already reported this course review",
        "신고 중복" en "Duplicate report",
    ),
    DUPLICATE_EVALUATION_LIKE(
        HttpStatus.CONFLICT,
        "이미 공감한 강의평입니다" en "You've already liked this course review",
        "공감 중복" en "Duplicate like",
    ),
    EVALUATION_NOT_LIKED(
        HttpStatus.CONFLICT,
        "공감하지 않은 강의평입니다" en "You haven't liked this course review",
        "공감 취소 불가" en "Can't remove like",
    ),
    MY_EVALUATION_REPORT(
        HttpStatus.CONFLICT,
        "내 강의평은 신고할 수 없습니다" en "You can't report your own course review",
        "강의평 신고 불가" en "Can't report review",
    ),
    EVALUATION_LECTURE_MISMATCH(
        HttpStatus.CONFLICT,
        "같은 과목의 다른 학기로만 옮길 수 있습니다" en "You can only move it to another semester of the same course",
        "강의평 이동 불가" en "Can't move review",
    ),
    DUPLICATE_NICKNAME(
        HttpStatus.CONFLICT,
        "사용할 수 없는 닉네임입니다" en "That nickname can't be used",
        "닉네임 변경 실패" en "Nickname change failed",
    ),

    SOCIAL_PROVIDER_UNAVAILABLE(
        HttpStatus.BAD_GATEWAY,
        "소셜 로그인 서버에 문제가 있습니다. 잠시 후 다시 시도해 주세요" en
            "The social sign-in server is having trouble. Please try again in a moment",
        "소셜 로그인 서버 오류" en "Social sign-in server error",
    ),
    FEEDBACK_UPSTREAM_UNAVAILABLE(
        HttpStatus.BAD_GATEWAY,
        "피드백을 전송하지 못했습니다. 잠시 후 다시 시도해 주세요" en "We couldn't send your feedback. Please try again in a moment",
        "피드백 전송 실패" en "Feedback not sent",
    ),
}
