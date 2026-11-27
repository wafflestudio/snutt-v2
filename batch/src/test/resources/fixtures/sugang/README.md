# sugang fixture

실제 수강편람 사이트(sugang.snu.ac.kr)에서 받은 응답이다. 2026-11-27, 2026학년도 겨울학기 기준.

| 파일 | 요청 | 손본 것 |
|---|---|---|
| `coursebook-condition.json` | `GET /sugang/cc/cc100ajax.action` (실제 상태 코드 201) | 없음 |
| `main-page.html` | `GET /sugang/co/co010.action` | `<script>`, `<style>`, `<link>`, 빈 줄 제거 |
| `search-page.html` | `GET /sugang/cc/cc100InterfaceSrch.action?...&pageNo=1` | 위와 같음 |
| `lecture-info.json` | `GET /sugang/cc/cc101ajax.action` (100.100-001) | 없음 |
| `lectures-ko.xls`, `lectures-en.xls` | `POST /sugang/cc/cc100InterfaceExcel.action` | 앞 30행과 모양별 표본만 남김(`SugangXlsxFixtureFiles.trimKorean`) |

- 엑셀은 확장자와 달리 OLE2 `.xls`(HSSF)로 내려온다. 프로그램으로 만드는 `SugangXlsxFixture`는 `.xlsx`(XSSF)다.
- 학생 개인정보는 없다. 교수명·강의실은 공개 정보다. 세션 쿠키는 저장하지 않았다.
- 엑셀은 `./gradlew :batch:liveTest -PrefreshFixtures`로 갱신한다. 나머지는 손으로 받아야 한다. 사이트가 잦은 요청에 IP를 막을 수 있으니 필요할 때만 받는다.
- `fixtures/snumap/`은 실제로 받지 않았고 `LectureBuildingSync`가 읽는 필드 이름대로 만든 것이다.
