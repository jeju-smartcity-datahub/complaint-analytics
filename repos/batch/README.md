# 제주 데이터허브 배치

민원 분석 서비스의 **배치 애플리케이션**이다. D-Hub 원천 데이터(민원, 단속, 공영주차장, 이용패턴, 안전점검, 사고이력)를
수집·가공해 서비스 테이블에 적재하고, 위험단계 판정·보고서 자동 생성·생활권 구역명 채우기를 수행한다.

이 문서는 배치 앱의 빌드, 실행, 설정, 잡 구성을 다룬다. 전체 서비스 실행은 아래 문서를 본다.

| 알고 싶은 것 | 문서 |
| --- | --- |
| DB·백엔드·프론트를 포함한 전체 실행 방법 | [설치 가이드](../../docs/설치가이드.md) |
| 운영 API 요청·응답 스펙 | [배치 운영 API](../../docs/API명세/batch-ops.md) |
| 포함된 오픈소스 고지 | [NOTICE](NOTICE) |

## 스택

| 항목 | 버전/선택 |
| --- | --- |
| Java | 17 (Gradle toolchain 고정) |
| Spring Boot | 3.5.6 |
| Spring Batch | 5.2.4 (`ext['spring-batch.version']` 고정) |
| 내장 Tomcat | 10.1.56 (`ext['tomcat.version']` 고정) |
| 빌드 | Gradle 8.10.2 (wrapper) |
| DB | PostgreSQL 15 + PostGIS 3.4 |
| DB 접근 | Spring JDBC (JPA·MyBatis 미사용) |
| 로깅 | Log4j2 |

백엔드와 달리 eGovFrame RTE를 쓰지 않는다. Spring Batch 5 빌더 API를 그대로 쓰고, 의존성 저장소도 `mavenCentral()` 하나로 충분하다.
대량 적재는 `INSERT ... ON CONFLICT` 기반의 JDBC 쓰기로 처리한다.

## 빠른 시작

DB를 먼저 띄운다. 스키마와 기준데이터는 빈 볼륨으로 처음 기동할 때 자동 적재된다.

```bash
cd database
cp .env.example .env
docker compose up -d
```

### Docker로 실행

저장소 루트의 `docker-compose.yml`이 배치를 `dev` 프로파일로 띄운다. 값은 루트 `.env`에서 읽는다.

```bash
cp .env.example .env    # INTERNAL_API_KEY, BATCH_API_KEY 등을 채운다
docker compose up -d --build
docker compose exec batch curl -s localhost:8280/actuator/health   # {"status":"UP"}
```

배치 포트 8280은 호스트에 공개되지 않는다. 컨테이너 안에서 호출하거나 운영 화면 `/ops/batch`를 쓴다.

> ⚠️ `docker-compose.yml`의 `environment`에 적힌 변수만 컨테이너로 전달된다. 이 README의 다른 변수를 Docker에서 쓰려면 그 목록에 추가해야 한다.
> 특히 `pkltAcdntFetchJob`에 필요한 `DHUB_ACDNT_TABLE_ID`, `DHUB_ACDNT_SOURCE_TABLE`은 현재 전달되지 않아, 추가하지 않으면 이 잡은 시작 시 실패한다.

### 소스에서 실행

`local` 프로파일의 DB 주소는 `localhost:25432`이고 `database/docker-compose.yml`의 기본 포트는 25433이다. 아래처럼 주소를 덮어쓴다.

```bash
cd repos/batch
export SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:25433/jeju_datahub?currentSchema=parking,public'

# 개발 중 스케줄 자동 실행을 끈다 (기본값은 모두 true)
export COMPLAINT_SYNC_SCHEDULED=false PBLPKLT_SYNC_SCHEDULED=false RISKNOTI_SYNC_SCHEDULED=false \
       UTZTN_SYNC_SCHEDULED=false SFTYCHCK_SYNC_SCHEDULED=false ZONENM_SYNC_SCHEDULED=false \
       RPTP_SYNC_SCHEDULED=false ACDNT_SYNC_SCHEDULED=false

./gradlew bootRun
curl -s localhost:8280/actuator/health   # {"status":"UP"}
```

앱은 `.env` 파일을 직접 읽지 않는다. 셸에서 `export`하거나 IDE 실행 설정에 환경변수를 넣는다.

> 🔴 `currentSchema`에서 `public`을 빼면 안 된다. PostGIS 확장이 `public`에 있어 행정동 공간매칭이 실패한다.

### 빌드와 테스트

```bash
./gradlew build    # 컴파일 + 테스트 + jar
./gradlew test     # 테스트만
```

`@ActiveProfiles("local")`이 붙은 통합 테스트는 `local` 프로파일의 DB가 필요하다. 외부 API나 전용 테스트 DB를 쓰는 일부 테스트는 해당 환경변수(`VWORLD_API_KEY`, `DHUB_USER_EMAIL`, `*_TEST_DB_URL`)가 있을 때만 실행된다.

## 프로파일

공통 설정은 `application.yml`, 환경별 설정은 `application-{local,dev,prod}.yml`에 있다. 기본 프로파일은 `local`이고 `SPRING_PROFILES_ACTIVE`로 바꾼다.

| 프로파일 | 포트 | DB | API 키 인증 | 백엔드 주소 기본값 |
| --- | --- | --- | --- | --- |
| `local` | 8280 | `localhost:25432`, `jeju/jeju1234` 고정 | 꺼짐 (`BATCH_API_AUTH_ENABLED`로 변경) | `http://localhost:8180` |
| `dev` | `SERVER_PORT` (기본 8280) | `DB_*` (기본 `localhost:5432`) | 켜짐 (`BATCH_API_AUTH_ENABLED`로 변경) | `http://backend:8070` |
| `prod` | `SERVER_PORT` (기본 8280) | `DB_*` (`DB_HOST`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` 필수) | 항상 켜짐 (`BATCH_API_KEY` 필수) | 없음 (`BACKEND_BASE_URL` 필수) |

## 환경변수

### 접속과 인증

| 변수 | 필요한 때 | 용도 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | 선택 | 실행 프로파일 (기본 `local`) |
| `SERVER_PORT` | 선택 | `dev`, `prod` 포트 |
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_SCHEMA` `DB_USERNAME` `DB_PASSWORD` | `dev`, `prod` | DB 접속. `DB_SCHEMA` 기본값은 `parking` |
| `SPRING_DATASOURCE_URL` | 선택 | `local` 프로파일의 고정 DB 주소를 덮어쓴다 |
| `BATCH_API_KEY` | `dev`, `prod` | `/api/batch/**` 호출 키 (`X-API-Key` 헤더). 인증이 켜졌는데 비어 있으면 503 |
| `BATCH_API_AUTH_ENABLED` | 선택 | `local`, `dev`의 API 키 인증 on/off |
| `INTERNAL_API_KEY` | 보고서, 구역명 잡 | 배치가 백엔드 내부 API를 호출할 때 쓰는 키. 백엔드와 같은 값이어야 한다 |
| `BACKEND_BASE_URL` | 보고서, 구역명 잡 | 백엔드 주소. 끝에 `/`를 붙이지 않는다 |
| `LOG_DIR` | 선택 | 로그 디렉터리 (기본 `logs`) |
| `TLS_INSECURE_HOSTS` | 선택 | 인증서 검증 실패를 허용할 호스트 목록. 한시적 우회 용도이며 와일드카드는 쓸 수 없다 |

### 외부 원천

| 변수 | 필요한 때 | 용도 |
| --- | --- | --- |
| `DHUB_USER_EMAIL` `DHUB_USER_PW` | D-Hub 수집 잡 | D-Hub 계정 |
| `DHUB_BASE_URL` | 선택 | D-Hub 주소 |
| `VWORLD_API_KEY` | 민원, 단속, 공영주차장 잡 | VWorld 지오코딩 키 |
| `VWORLD_BASE_URL` | 선택 | VWorld 주소. 끝에 `/`를 붙이지 않는다 |
| `COMPLAINT_API_MODE` | 선택 | `real`(기본) 또는 `mock`. `mock`은 샘플 JSON을 재생하는 개발 전용 모드다 |
| `BATCH_TODAY` | 선택 | 기준일 덮어쓰기(`yyyy-MM-dd`). 평소에는 비워 둔다 |

키가 없어도 앱은 기동한다. D-Hub 계정은 잡 시작 시, VWorld 키는 실제 지오코딩 호출 시 검사하며 없으면 명확한 예외로 실패한다.

### D-Hub 데이터셋

데이터셋 ID와 `FROM` 절 테이블은 **같은 데이터셋의 짝**이다. 어긋나면 D-Hub가 422로 거절하므로 함께 바꾼다.
값을 바꾸지 않을 변수는 `.env`에 `KEY=`로 비워 두지 말고 아예 적지 않는다. 빈 문자열이 기본값을 덮어쓴다.

| 원천 | 데이터셋 ID | 원천 테이블 | 페이지 크기 |
| --- | --- | --- | --- |
| 민원 | `DHUB_CVLCPT_TABLE_ID` | `DHUB_CVLCPT_SOURCE_TABLE` | `dhub.page-size` (1000) |
| 단속 | `DHUB_CRACKDOWN_TABLE_ID` | `DHUB_CRACKDOWN_SOURCE_TABLE` | `dhub.crackdown-page-size` (1000) |
| 공영주차장 | `DHUB_PBLPKLT_TABLE_ID` | `DHUB_PBLPKLT_SOURCE_TABLE` | `DHUB_PBLPKLT_PAGE_SIZE` (1000) |
| 이용패턴 | `DHUB_UTZTN_TABLE_ID` | `DHUB_UTZTN_SOURCE_TABLE` | `DHUB_UTZTN_MAX_ROWS` (1000, 한 달 질의 상한) |
| 안전점검 | `DHUB_SFTYCHCK_TABLE_ID` | `DHUB_SFTYCHCK_SOURCE_TABLE` | `DHUB_SFTYCHCK_PAGE_SIZE` (1000) |
| 사고이력 | `DHUB_ACDNT_TABLE_ID` (**기본값 없음**) | `DHUB_ACDNT_SOURCE_TABLE` (**기본값 없음**) | `DHUB_ACDNT_PAGE_SIZE` (1000) |

- 페이지 크기의 허용 범위는 1~1000이다. 범위를 벗어나면 잡이 시작할 때 실패한다.
- 공영주차장 키가 숫자 컬럼인 원천은 `DHUB_PBLPKLT_KEYSET_ORDER=NUMERIC`으로 지정한다 (기본 `LEXICOGRAPHIC`).
- 이용패턴과 안전점검은 API 대신 로컬 CSV를 읽을 수 있다. `DHUB_UTZTN_CSV_PATH`, `DHUB_SFTYCHCK_CSV_PATH`에 경로를 지정하고, 비우면 D-Hub API를 쓴다. `DHUB_SFTYCHCK_CSV_INCLUDE_IDS`를 지정하면 해당 `sfty_chck_id` 행만 읽는다.

## 잡

잡은 10개이며, 명세의 정본은 [`BatchJobCatalog`](src/main/java/jeju/datahub/batch/ops/BatchJobCatalog.java)다.
앱을 띄우는 것만으로는 잡이 실행되지 않는다(`spring.batch.job.enabled=false`). 스케줄, HTTP 트리거, 운영 화면으로 실행한다.

| 잡 | 내용 | 트리거 (`POST`) | 파라미터 | 초기 스케줄 |
| --- | --- | --- | --- | --- |
| `complaintFetchJob` | 민원 수집·분류·지오코딩·적재 | `/api/batch/complaint-fetch` | `mode` `targetDate` `from` `to` | 매월 1일 02:00 |
| `riskAlertJudgeJob` | 읍면동별 위험단계 판정, 수신자 매핑 | `/api/batch/risk-alert-judge` | `targetDate` | 민원 수집 후 연쇄 |
| `riskAlertReceiverBackfillJob` | 과거 알림의 수신자 매핑 백필 | `/api/batch/risk-alert-receiver-backfill` | `fromDt` `toDt` | 수동 전용 |
| `pblpkltFetchJob` | 공영주차장 수집·지오코딩·소멸 처리 | `/api/batch/pblpklt-fetch` | 없음 | 1·4·7·10월 1일 02:00 |
| `pkltUtztnFetchJob` | 주차장 이용패턴 수집 | `/api/batch/pklt-utztn-fetch` | 없음 | 매월 1일 03:00, 주차장 수집 후 연쇄 |
| `crackdownFetchJob` | 단속 수집·좌표 전파·지오코딩 | `/api/batch/crackdown-fetch` | `from` `to` | 매월 1일 03:00 (**사용 안 함**) |
| `reportAutoCrtJob` | 배포일이 도래한 보고서 자동 생성 | `/api/batch/report-auto-crt` | `baseYmd` | 매일 02:30 |
| `pkltSftyChckFetchJob` | 주차장 안전점검 수집 | `/api/batch/pklt-sfty-chck-fetch` | 없음 | 1·7월 1일 04:00 |
| `zoneNmPrefillJob` | 생활권 격자 구역명 캐시 채우기 | `/api/batch/zone-nm-prefill` | `gridSz` | 매일 03:10 |
| `pkltAcdntFetchJob` | 주차장 사고이력 수집 | `/api/batch/pklt-acdnt-fetch` | `from` `to` | 매월 1일 04:30 (**사용 안 함**) |

날짜 파라미터 형식은 `yyyy-MM-dd`이고, `to`와 `toDt`는 해당 일을 포함하지 않는다. `to`는 `from`과 함께 지정한다.

```bash
# local (인증 꺼짐)
curl -X POST 'localhost:8280/api/batch/complaint-fetch?mode=daily&targetDate=2026-11-01'

# Docker (컨테이너 안에서 호출)
docker compose exec batch sh -c 'curl -X POST -H "X-API-Key: $BATCH_API_KEY" localhost:8280/api/batch/complaint-fetch'
```

HTTP 트리거는 잡이 끝날 때까지 응답을 기다린다. 오래 걸리는 잡은 비동기로 실행되는 운영 API나 운영 화면을 쓴다.

### 수집 방식

| 대상 | 적재 테이블 | 수집 구간 | 재수집 처리 |
| --- | --- | --- | --- |
| 민원 | `parking.tb_cvlcpt` | 저장된 최대 원천 `load_dt`부터 오늘까지. `load_dt`가 NULL인 행은 매번 다시 조회 | 내용 해시 `src_hash`로 병합 |
| 단속 | `parking.tb_crdn_dsctn` | 민원과 같음 | 내용 해시 `src_hash`로 병합 |
| 공영주차장 | `parking.tb_pblpklt` | 전량 | 주소 원문이 같은 기존 행을 모두 갱신하고, 없으면 새 행 생성 |
| 이용패턴 | `parking.tb_pklt_utztn_pttn` | 전량 | 실행마다 새 관측을 추가 |
| 안전점검 | `parking.tb_pklt_sfty_chck` | 전량 | 실행마다 새 관측을 추가 |
| 사고이력 | `parking.tb_pklt_acdnt_hstry` | 마지막 사고일시부터 오늘까지 | 내용 해시 `src_hash`로 병합 |

- **공영주차장**: 주소가 없는 행이 있으면 잡이 실패한다. 전량 수집이 끝나면 이번에 받지 못한 주소의 주차장을 소멸 처리한다. 수신 주소가 없거나 주소 누락 행이 있으면 소멸 처리를 건너뛴다.
- **이용패턴·안전점검**: 같은 실행 안의 재시도만 `(btch_excn_id, src_hash)`로 중복을 막는다. 분석에서는 기간별 최신 관측 뷰 `parking.v_pklt_utztn_pttn_latest`, `parking.v_pklt_sfty_chck_latest`를 써서 이중 합산을 피한다.
- **안전점검 등급**: 원천의 D등급은 그대로 보존한다. 최신 관측 뷰에는 A·B·C 등급만 포함된다.
- **사고이력**: 실자료가 1건 이상 적재되면 임의 시드(`SEED-*`)를 삭제한다.

### 스케줄

스케줄의 정본은 DB 표 `batch.tb_btch_schdl`이다. 초기값은 [`11-batch.tb_btch_schdl.csv`](../../database/ddl/data/11-batch.tb_btch_schdl.csv)에 있고, 운영 화면 `/ops/batch`에서 바꾸면 재기동 없이 반영된다.

잡이 자동 실행되려면 아래 조건을 모두 만족해야 한다.

1. 환경변수 스위치가 `true`다. `false`면 표의 값과 무관하게 등록하지 않는다.
2. 표의 `use_yn`이 `Y`이고 `cron_expr`이 유효하다.

| 환경변수 | 기본값 | 대상 |
| --- | --- | --- |
| `COMPLAINT_SYNC_SCHEDULED` | `true` | 민원 수집. 끄면 뒤에 이어지는 위험단계 판정도 돌지 않는다 |
| `RISKNOTI_SYNC_SCHEDULED` | `true` | 민원 수집 뒤의 위험단계 판정만 끈다 |
| `PBLPKLT_SYNC_SCHEDULED` | `true` | 공영주차장 수집 |
| `UTZTN_SYNC_SCHEDULED` | `true` | 이용패턴의 자체 스케줄과 주차장 수집 뒤 연쇄를 함께 끈다 |
| `SFTYCHCK_SYNC_SCHEDULED` | `true` | 안전점검 수집 |
| `RPTP_SYNC_SCHEDULED` | `true` | 보고서 자동 생성 |
| `ZONENM_SYNC_SCHEDULED` | `true` | 구역명 채우기 |
| `ACDNT_SYNC_SCHEDULED` | `true` | 사고이력 수집 |

- Docker에서는 루트 `.env`의 `BATCH_SCHEDULED` 하나가 위 8개를 함께 제어하며 기본값은 `false`다.
- `crackdownFetchJob`은 환경변수 스위치가 없고 표의 값만 따른다.
- 연쇄 실행은 앞 잡이 스케줄로 실행되어 `COMPLETED`로 끝났을 때만 이어진다. HTTP 트리거나 운영 화면으로 실행하면 연쇄하지 않는다.
- 스위치는 자동 실행에만 적용된다. HTTP 트리거와 운영 화면 실행은 스위치를 꺼도 동작한다.
- 스케줄러 스레드는 1개다. 잡 사이의 선후 관계가 직렬 실행에 기대므로 늘리지 않는다.

기동 로그에서 등록 상태를 확인한다.

```text
[schedule] registered job=...   자동 실행 등록됨
[schedule] disabled job=...     표의 use_yn이 N
[schedule] blocked job=...      환경변수 스위치로 꺼짐
```

### 청크 크기

청크 크기는 한 트랜잭션에 커밋하는 행 수이자, 중간 실패 시 되돌아가는 범위다.

| 설정 키 | 기본값 |
| --- | --- |
| `batch.complaint.chunk-size` | 500 |
| `batch.crackdown.chunk-size` | 500 |
| `batch.crackdown.geocode.chunk-size` | 50 |
| `batch.pblpklt.chunk-size` | 100 |
| `batch.utztn.chunk-size` | 500 |
| `batch.sftychck.chunk-size` (`SFTYCHCK_CHUNK_SIZE`) | 500 |
| `batch.acdnt.chunk-size` (`ACDNT_CHUNK_SIZE`) | 100 |
| `batch.risknoti.chunk-size` | 200 |
| `batch.rptp.chunk-size` | 50 |

## 운영 API

`/api/batch/ops/**`는 백엔드의 `/api/ops/batch/**`가 프록시로 호출하는 내부 API다. 인증은 다른 트리거와 같은 `X-API-Key`를 쓴다.

| Method | 경로 | 기능 |
| --- | --- | --- |
| `GET` | `/api/batch/ops/jobs` | 잡 카탈로그 조회 |
| `POST` | `/api/batch/ops/jobs/{jobNm}/executions` | 비동기 실행 (202, 이미 실행 중이면 409) |
| `PUT` | `/api/batch/ops/schedules/{jobNm}` | 스케줄 변경 |
| `POST` | `/api/batch/ops/executions/{id}/stop` | 실행 중지 |
| `POST` | `/api/batch/ops/executions/{id}/abandon` | 멈춘 실행 실패 처리 |
| `GET` | `/api/batch/ops/executions/{id}/log` | 실행별 로그 조회 |
| `GET` | `/api/batch/ops/executions/{id}/live` | 실행 중 단계별 수치 조회 |

실행 이력은 `batch.tb_btch_excn_hstry`에 남는다. 실행 구분 `excn_se_cd`는 `SCHDL`(스케줄), `MANUAL`(운영 화면), `CHAIN`(연쇄), `API`(HTTP 트리거) 중 하나다.

## 로그

실행별 로그는 `{LOG_DIR}/batch-jobs/{jobNm}/{jobExecutionId}.log`에 INFO 이상으로 기록되며, 비밀값은 마스킹된다. Docker에서는 `batch-logs` 볼륨의 `/app/logs`에 저장된다.

| 설정 키 | 기본값 | 뜻 |
| --- | --- | --- |
| `batch.ops.log-dir` (`LOG_DIR`) | `logs` | 로그 디렉터리 |
| `batch.ops.log-retention-days` | `90` | 보존 일수(파일 수정 시각 기준). 1 미만이면 정리하지 않는다 |
| `batch.ops.log-cleanup-cron` | `0 30 5 * * *` | 일 1회 정리 시각. 기동 시에도 1회 정리한다 |

## DB 스키마

서비스 테이블은 `parking` 스키마, Spring Batch 메타 테이블과 실행 이력·스케줄 표는 `batch` 스키마에 있다. 앱은 DDL을 실행하지 않는다(`initialize-schema: never`). 스키마는 [`jeju-datahub-schema.sql`](../../database/ddl/jeju-datahub-schema.sql)로 적용한다.

## 디렉터리

```text
src/main/java/jeju/datahub/batch/
├─ JejuDatahubBatchApplication.java
├─ cvlcpt/      민원 수집 (api/: D-Hub 공통 클라이언트, 설정, mock)
├─ crackdown/   단속 수집 (geocode/: 주소 단위 지오코딩)
├─ pblpklt/     공영주차장 수집, 주소 특이 패턴 기록, 소멸 처리
├─ utztn/       주차장 이용패턴 수집
├─ sftychck/    주차장 안전점검 수집
├─ acdnt/       주차장 사고이력 수집
├─ risknoti/    위험단계 판정, 알림 수신자 매핑
├─ rptp/        보고서 자동 생성 (백엔드 내부 API 호출)
├─ zonenm/      생활권 구역명 채우기 (백엔드 내부 API 호출)
├─ collect/     수집 공통 (증분 구간, 내용 해시, CSV 입력)
├─ geocode/     VWorld 클라이언트, 폴백 체인, 주소 캐시
├─ admdst/      좌표 → 행정동 공간매칭
├─ schedule/    DB 기반 동적 스케줄러
├─ ops/         운영 API, 잡 카탈로그, 비동기 실행, 실행별 로그
├─ history/     실행 이력 기록, 비밀값 마스킹
├─ tls/         지정 호스트 TLS 검증 우회
└─ web/         API 키 필터, 트리거 공통 응답
src/main/resources/
├─ application*.yml
├─ log4j2-spring.xml
└─ mock/        민원 mock 샘플
```
