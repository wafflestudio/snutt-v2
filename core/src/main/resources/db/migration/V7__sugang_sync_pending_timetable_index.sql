-- 동기화 1단계가 남은 일을 넣기 전에 이미 기록한 (강의 변경, 시간표)를 시간표 id로 찾는다(findTimetablePairs, processed 조건 없음).
-- V6의 (processed, timetable_id) 인덱스로는 앞 열 조건이 없어 인덱스 전체를 읽는다. V6는 이미 적용된 환경이 있어 고치지 않고 여기서 더한다.
CREATE INDEX idx_sugang_sync_pending_timetable_timetable ON sugang_sync_pending_timetable (timetable_id);
