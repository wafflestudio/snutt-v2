# snutt-v2

## 테스트

테스트를 쓰거나 고치기 전에 [docs/testing.md](docs/testing.md)를 읽고 따른다. 핵심만 추리면 다음과 같다.

- `./gradlew ktlintCheck test`가 PR CI다. `parity`·`perf`·`stress` 태그 테스트는 `./gradlew parityTest`/`perfTest`/`stressTest`로 따로 돈다.
- 통합테스트는 모듈 베이스(`AbstractApiIntegrationTest`, `AbstractBatchIntegrationTest`)를 상속한다. 테스트 클래스에 `@MockitoBean`·`@Import`·프로퍼티를 새로 달지 않는다(컨텍스트 캐시가 깨진다).
- 현재 시각은 `Clock` 빈으로만 읽고, 테스트에서는 `MutableClock`으로 고정한다. 쿼리 수는 `assertQueryCount`, 데이터는 `fixtures`(`ScenarioFixtures`)로 만든다.
