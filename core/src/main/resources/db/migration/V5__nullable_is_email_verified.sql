-- v1에서 isEmailVerified 필드가 없던(null) 계정을 false와 구분해 보존한다.
-- v1은 null 계정의 강의평 접근을 막지 않았다(== false만 막음). active_email은 NULL을 미인증으로 계산한다.
-- NULL 허용 변경은 online DDL(INPLACE, 동시 DML 허용)로 된다. 운영 중 user 테이블을 잠그는 COPY로 몰래 바뀌지 않도록
-- ALGORITHM/LOCK을 명시해 지원되지 않으면 즉시 실패하게 한다.
ALTER TABLE `user` MODIFY is_email_verified BOOLEAN NULL DEFAULT FALSE, ALGORITHM=INPLACE, LOCK=NONE;
