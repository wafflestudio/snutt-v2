-- v1에서 isEmailVerified 필드가 없던(null) 계정을 false와 구분해 보존한다.
-- v1은 null 계정의 강의평 접근을 막지 않았다(== false만 막음). active_email은 NULL을 미인증으로 계산한다.
ALTER TABLE `user` MODIFY is_email_verified BOOLEAN NULL DEFAULT FALSE;
