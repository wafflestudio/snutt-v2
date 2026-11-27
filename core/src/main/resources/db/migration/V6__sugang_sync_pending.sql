-- 수강편람 동기화는 강의 반영(1단계)과 시간표 반영(2단계)을 따로 커밋한다. 2단계를 마치기 전에 실패하거나 프로세스가 죽어도 다음 실행이
-- 이어서 하도록, 1단계가 같은 트랜잭션에서 2단계가 할 일을 남긴다. 다음 실행은 엑셀 diff 전에 남은 일부터 처리한다.
--
-- 세 테이블 모두 외래 키를 두지 않는다. FK 검사는 부모 행(강의·시간표·사용자)에 공유 락을 잡는데, 동기화는 강의 행이나 시간표 행을
-- 쥔 채 여기에 쓰므로 사용자 행 → 시간표 → 강의 순으로 잠그는 사용자 요청과 락 사이클이 생긴다. 지워진 강의·시간표·사용자를 가리키는
-- 행은 처리할 때 건너뛴다.

-- 시간표에 반영해야 하는 강의 변경 하나. 1단계 트랜잭션이 넣고, 영향받는 시간표를 모두 반영한 뒤 관심강좌 알림을 남기면서 지운다.
-- expanded는 이 강의를 담은 시간표를 처음 펼쳐 sugang_sync_pending_timetable에 기록했는지다.
CREATE TABLE sugang_sync_pending_lecture
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    lecture_id          BIGINT       NOT NULL,
    class_times_changed BOOLEAN      NOT NULL,
    changed_labels      VARCHAR(512) NOT NULL,
    changed_labels_en   VARCHAR(512) NOT NULL,
    expanded            BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          DATETIME(6)  NOT NULL
);

-- 강의 변경이 닿는 시간표. processed는 2단계 묶음 트랜잭션이 그 시간표를 반영하면서 같은 트랜잭션에서 켠다(같은 시간표를 두 번 반영하지 않는다).
-- notify가 꺼진 행은 처음 펼친 뒤에 나타난 시간표(반영 전 시간표의 복사본)다. 겹침만 바로잡고 변경 알림은 보내지 않는다.
CREATE TABLE sugang_sync_pending_timetable
(
    pending_lecture_id BIGINT  NOT NULL,
    timetable_id       BIGINT  NOT NULL,
    notify             BOOLEAN NOT NULL,
    processed          BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (pending_lecture_id, timetable_id),
    INDEX idx_sugang_sync_pending_timetable_todo (processed, timetable_id)
);

-- 동기화가 남길 알림. 시간표·강의를 잠근 트랜잭션에서는 notification(사용자 FK)에 바로 넣지 않고 여기에 넣은 뒤, 커밋 뒤 따로
-- notification으로 옮기고 같은 트랜잭션에서 지운다. push_kind가 있는 행은 옮긴 뒤 사용자별로 모아 푸시를 보낸다.
CREATE TABLE sugang_sync_outbox
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    title      VARCHAR(255) NOT NULL,
    title_en   VARCHAR(255) NULL,
    message    TEXT         NOT NULL,
    message_en TEXT         NULL,
    type       VARCHAR(32)  NOT NULL,
    deeplink   VARCHAR(512) NULL,
    push_kind  VARCHAR(16)  NULL,
    created_at DATETIME(6)  NOT NULL
);
