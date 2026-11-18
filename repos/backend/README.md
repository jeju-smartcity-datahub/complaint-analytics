# jeju-datahub-back

제주 스마트시티 데이터 허브 백엔드. **전자정부표준프레임워크 5.0**(실행환경 RTE 5.0.0) 기반 **Spring Boot 3 / Java 17** 애플리케이션이다.

## 기술 스택

| 항목 | 버전 |
| --- | --- |
| Java | 17 |
| Spring Boot | 3.5.6 |
| Spring Framework | 6.2.x |
| 내장 Tomcat | 10.1.56 (`ext['tomcat.version']` 고정) |
| 전자정부표준프레임워크 RTE | 5.0.0 (`org.egovframe.rte:egovframe-rte-*`) |
| 빌드 도구 | Gradle (wrapper 8.10.2) |
| 영속성 | MyBatis (mybatis-spring-boot-starter) |
| 로깅 | Log4j2 |
| API 문서 | springdoc-openapi (Swagger UI) |

> RTE 아티팩트는 전자정부 Maven 저장소(`https://maven.egovframe.go.kr/maven/`)에서 내려받는다.
> 사내/폐쇄망이라면 넥서스 미러 등록이 필요할 수 있다.

## 실행

```bash
./gradlew bootRun
```

- 앱: http://localhost:8080
- 샘플 API: http://localhost:8080/api/samples
- Swagger UI: http://localhost:8080/swagger-ui.html
- H2 콘솔: http://localhost:8080/h2-console (JDBC URL: `jdbc:h2:mem:jejudatahub`)
- 헬스체크: http://localhost:8080/actuator/health

## 빌드 / 테스트

```bash
./gradlew build      # 컴파일 + 테스트 + jar
./gradlew test       # 테스트만
```

## 패키지 구조 (전자정부 계층 관례)

```
kr.go.jeju.datahub
├─ JejuDatahubApplication      # 진입점
├─ config                      # 전역 설정 (MyBatis, WebMvc/CORS)
└─ sample                      # 예시 도메인 (실제 도메인 작성 시 참고)
   ├─ web        SampleController        # 표현 계층 (Controller)
   ├─ service    SampleService, SampleVO # 서비스 인터페이스 / VO
   └─ service.impl
                 SampleServiceImpl       # EgovAbstractServiceImpl 상속
                 SampleMapper            # MyBatis 매퍼 인터페이스
```

SQL 매퍼 XML: `src/main/resources/mapper/**/*.xml`

## 운영 DB 전환

`application.yml` 의 `spring.datasource` 를 MySQL 등으로 교체하고,
`build.gradle` 의 `mysql-connector-j` 주석을 해제한다.
