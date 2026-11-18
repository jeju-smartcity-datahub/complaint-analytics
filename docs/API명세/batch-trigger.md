# 배치 수동 실행 API

## 1. POST /api/batch/complaint-fetch

### API 개요
D-Hub 민원 원천을 수집해 분류·지오코딩한 뒤 `parking.tb_cvlcpt` 에 upsert 한다. 기본은 저장된 최대 `load_dt` 부터 오늘까지의 증분 수집이며, `load_dt` 가 NULL 인 원천 행은 매 실행 다시 조회한다.

Job 이 끝날 때까지 응답을 기다리는 동기 API 이며, 실행 이력은 `excnSeCd=API` 로 남는다. 연쇄 잡은 실행되지 않고, 같은 잡의 중복 실행을 막지 않는다. `X-API-Key` 인증은 `batch.api.auth.enabled` 로 켠다. local 은 기본 꺼짐, dev 는 기본 켜짐, prod 는 항상 켜짐이다. Docker 구성에서는 배치 포트가 외부에 공개되지 않으므로 batch 컨테이너 안에서 호출한다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `X-API-Key` | string | 인증 활성 시 Y | - | `BATCH_API_KEY` 와 같은 값 |
| query | `mode` | string | N | `dhub.query-mode`(기본 `incremental`) | `incremental`, `daily`, `backfill`. 대소문자를 구분하지 않는다 |
| query | `targetDate` | string | N | 전일(Asia/Seoul) | daily 기준일 `yyyy-MM-dd`. `mode=daily` 와 함께 쓴다. `backfill` 에서는 무시하고, 증분 수집에서 지정하면 Job 이 `FAILED` 로 끝난다 |
| query | `from` | string | N | 증분: 저장된 최대 `load_dt`, backfill: `dhub.backfill-from` | `load_dt` 시작일(포함) `yyyy-MM-dd`. `daily` 에서는 무시한다 |
| query | `to` | string | N | 증분: 오늘 포함, backfill: `dhub.backfill-to`(비면 실행일) | `load_dt` 끝일(미포함) `yyyy-MM-dd`. 증분 수집에서는 `from` 과 함께 지정한다. `daily` 에서는 무시하고, `backfill` 에서 `from` 이 `to` 이상이면 Job 이 `FAILED` 로 끝난다 |

### 호출 예시
```bash
curl -X POST 'http://localhost:8280/api/batch/complaint-fetch?mode=daily&targetDate=2026-11-17' \
  -H 'X-API-Key: {BATCH_API_KEY}'
```

### 응답
`200 OK`, `application/json`. Job 이 실패해도 실행이 시작됐다면 200 으로 응답하므로 `status` 를 확인한다. 건수는 Job 의 모든 Step 합계다.

```json
{
  "jobExecutionId": 4521,
  "status": "COMPLETED",
  "exitCode": "COMPLETED",
  "read": 1200,
  "written": 1180,
  "skipped": 15,
  "filtered": 5,
  "startTime": "2026-11-18T02:00:00.123456",
  "endTime": "2026-11-18T02:05:00.654321"
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `jobExecutionId` | number | N | 실행 ID. Job 이름은 `complaintFetchJob` |
| `status` | string | N | 실행 상태. `COMPLETED`, `FAILED`, `STOPPED` 등 |
| `exitCode` | string | N | 종료 코드 |
| `read` | number | N | 읽은 행 수 |
| `written` | number | N | `parking.tb_cvlcpt` 에 적재한 행 수 |
| `skipped` | number | N | 실패가 확정되어 건너뛴 행 수 |
| `filtered` | number | N | Processor 에서 제외한 행 수 |
| `startTime` | string | Y | 시작 시각(ISO-8601) |
| `endTime` | string | Y | 종료 시각(ISO-8601) |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 500 | `{"timestamp":"…","status":500,"error":"Internal Server Error","path":"/api/batch/complaint-fetch"}` | `mode` 허용값 밖, 날짜 형식 오류, 증분 수집에서 `to` 만 지정하거나 `from` 이 `to` 이상, Job 시작 실패 |
| 401 | `{"error":"missing or invalid X-API-Key"}` | 인증 활성 상태에서 키 누락 또는 불일치 |
| 503 | `{"error":"batch API key not configured (BATCH_API_KEY missing)"}` | 인증 활성 상태에서 서버 키가 비어 있음 |

## 2. POST /api/batch/risk-alert-judge

### API 개요
읍면동별 민원 증가로 위험단계를 판정해 `parking.tb_risk_stp_noti_hstry` 에 upsert 하고, 알림과 수신자 매핑을 `parking.tb_noti_hstry_rcvr` 에 적재한다.

Job 이 끝날 때까지 응답을 기다리는 동기 API 이며, 실행 이력은 `excnSeCd=API` 로 남는다. 연쇄 잡은 실행되지 않고, 같은 잡의 중복 실행을 막지 않는다. `X-API-Key` 인증은 `batch.api.auth.enabled` 로 켠다. local 은 기본 꺼짐, dev 는 기본 켜짐, prod 는 항상 켜짐이다. Docker 구성에서는 배치 포트가 외부에 공개되지 않으므로 batch 컨테이너 안에서 호출한다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `X-API-Key` | string | 인증 활성 시 Y | - | `BATCH_API_KEY` 와 같은 값 |
| query | `targetDate` | date | N | 어제(Asia/Seoul) | 판정 대상일 `yyyy-MM-dd` |

### 호출 예시
```bash
curl -X POST 'http://localhost:8280/api/batch/risk-alert-judge?targetDate=2026-11-17' \
  -H 'X-API-Key: {BATCH_API_KEY}'
```

### 응답
`200 OK`, `application/json`. Job 이 실패해도 실행이 시작됐다면 200 으로 응답하므로 `status` 를 확인한다. 건수는 Job 의 모든 Step 합계다.

```json
{
  "jobExecutionId": 4521,
  "status": "COMPLETED",
  "exitCode": "COMPLETED",
  "read": 1200,
  "written": 1180,
  "skipped": 15,
  "filtered": 5,
  "startTime": "2026-11-18T02:00:00.123456",
  "endTime": "2026-11-18T02:05:00.654321"
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `jobExecutionId` | number | N | 실행 ID. Job 이름은 `riskAlertJudgeJob` |
| `status` | string | N | 실행 상태. `COMPLETED`, `FAILED`, `STOPPED` 등 |
| `exitCode` | string | N | 종료 코드 |
| `read` | number | N | 읽은 행 수 |
| `written` | number | N | 판정 Step 과 수신자 매핑 Step 의 쓰기 건수 합계 |
| `skipped` | number | N | 실패가 확정되어 건너뛴 행 수 |
| `filtered` | number | N | Processor 에서 제외한 행 수 |
| `startTime` | string | Y | 시작 시각(ISO-8601) |
| `endTime` | string | Y | 종료 시각(ISO-8601) |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"timestamp":"…","status":400,"error":"Bad Request","path":"/api/batch/risk-alert-judge"}` | `targetDate` 형식 오류 |
| 500 | `{"timestamp":"…","status":500,"error":"Internal Server Error","path":"/api/batch/risk-alert-judge"}` | Job 시작 실패 |
| 401 | `{"error":"missing or invalid X-API-Key"}` | 인증 활성 상태에서 키 누락 또는 불일치 |
| 503 | `{"error":"batch API key not configured (BATCH_API_KEY missing)"}` | 인증 활성 상태에서 서버 키가 비어 있음 |

## 3. POST /api/batch/risk-alert-receiver-backfill

### API 개요
현재 수신자 설정으로 과거 알림의 수신자 매핑을 `parking.tb_noti_hstry_rcvr` 에 다시 채운다. 이미 있는 매핑은 건너뛰므로 반복 호출해도 행이 늘지 않는다.

Job 이 끝날 때까지 응답을 기다리는 동기 API 이며, 실행 이력은 `excnSeCd=API` 로 남는다. 연쇄 잡은 실행되지 않고, 같은 잡의 중복 실행을 막지 않는다. `X-API-Key` 인증은 `batch.api.auth.enabled` 로 켠다. local 은 기본 꺼짐, dev 는 기본 켜짐, prod 는 항상 켜짐이다. Docker 구성에서는 배치 포트가 외부에 공개되지 않으므로 batch 컨테이너 안에서 호출한다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `X-API-Key` | string | 인증 활성 시 Y | - | `BATCH_API_KEY` 와 같은 값 |
| query | `fromDt` | date | N | 하한 없음 | 시작일(포함) `yyyy-MM-dd` |
| query | `toDt` | date | N | 상한 없음 | 끝일(미포함) `yyyy-MM-dd` |

### 호출 예시
```bash
curl -X POST 'http://localhost:8280/api/batch/risk-alert-receiver-backfill?fromDt=2026-09-01&toDt=2026-10-01' \
  -H 'X-API-Key: {BATCH_API_KEY}'
```

### 응답
`200 OK`, `application/json`. Job 이 실패해도 실행이 시작됐다면 200 으로 응답하므로 `status` 를 확인한다. 건수는 Job 의 모든 Step 합계다.

```json
{
  "jobExecutionId": 4521,
  "status": "COMPLETED",
  "exitCode": "COMPLETED",
  "read": 1200,
  "written": 1180,
  "skipped": 15,
  "filtered": 5,
  "startTime": "2026-11-18T02:00:00.123456",
  "endTime": "2026-11-18T02:05:00.654321"
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `jobExecutionId` | number | N | 실행 ID. Job 이름은 `riskAlertReceiverBackfillJob` |
| `status` | string | N | 실행 상태. `COMPLETED`, `FAILED`, `STOPPED` 등 |
| `exitCode` | string | N | 종료 코드 |
| `read` | number | N | 읽은 행 수 |
| `written` | number | N | 매핑 Step 의 쓰기 건수 |
| `skipped` | number | N | 실패가 확정되어 건너뛴 행 수 |
| `filtered` | number | N | Processor 에서 제외한 행 수 |
| `startTime` | string | Y | 시작 시각(ISO-8601) |
| `endTime` | string | Y | 종료 시각(ISO-8601) |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"timestamp":"…","status":400,"error":"Bad Request","path":"/api/batch/risk-alert-receiver-backfill"}` | `fromDt`, `toDt` 형식 오류 |
| 500 | `{"timestamp":"…","status":500,"error":"Internal Server Error","path":"/api/batch/risk-alert-receiver-backfill"}` | Job 시작 실패 |
| 401 | `{"error":"missing or invalid X-API-Key"}` | 인증 활성 상태에서 키 누락 또는 불일치 |
| 503 | `{"error":"batch API key not configured (BATCH_API_KEY missing)"}` | 인증 활성 상태에서 서버 키가 비어 있음 |

## 4. POST /api/batch/report-auto-crt

### API 개요
활성화된 보고서 자동 생성 설정 중 기준일에 배포일이 도래한 설정만 골라 백엔드 내부 API `POST /api/internal/reports/auto-crt` 로 보고서를 만들고 `parking.tb_rptp_auto_crt_stng.last_crt_dt` 를 갱신한다. 도래한 설정이 없으면 `written=0` 으로 끝난다.

Job 이 끝날 때까지 응답을 기다리는 동기 API 이며, 실행 이력은 `excnSeCd=API` 로 남는다. 연쇄 잡은 실행되지 않고, 같은 잡의 중복 실행을 막지 않는다. `X-API-Key` 인증은 `batch.api.auth.enabled` 로 켠다. local 은 기본 꺼짐, dev 는 기본 켜짐, prod 는 항상 켜짐이다. Docker 구성에서는 배치 포트가 외부에 공개되지 않으므로 batch 컨테이너 안에서 호출한다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `X-API-Key` | string | 인증 활성 시 Y | - | `BATCH_API_KEY` 와 같은 값 |
| query | `baseYmd` | date | N | 오늘(Asia/Seoul) | 기준일 `yyyy-MM-dd` |

### 호출 예시
```bash
curl -X POST 'http://localhost:8280/api/batch/report-auto-crt?baseYmd=2026-10-01' \
  -H 'X-API-Key: {BATCH_API_KEY}'
```

### 응답
`200 OK`, `application/json`. Job 이 실패해도 실행이 시작됐다면 200 으로 응답하므로 `status` 를 확인한다. 건수는 Job 의 모든 Step 합계다.

```json
{
  "jobExecutionId": 4521,
  "status": "COMPLETED",
  "exitCode": "COMPLETED",
  "read": 1200,
  "written": 1180,
  "skipped": 15,
  "filtered": 5,
  "startTime": "2026-11-18T02:00:00.123456",
  "endTime": "2026-11-18T02:05:00.654321"
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `jobExecutionId` | number | N | 실행 ID. Job 이름은 `reportAutoCrtJob` |
| `status` | string | N | 실행 상태. `COMPLETED`, `FAILED`, `STOPPED` 등 |
| `exitCode` | string | N | 종료 코드 |
| `read` | number | N | 읽은 행 수 |
| `written` | number | N | 처리한 보고서 생성 대상 수 |
| `skipped` | number | N | 실패가 확정되어 건너뛴 행 수 |
| `filtered` | number | N | Processor 에서 제외한 행 수 |
| `startTime` | string | Y | 시작 시각(ISO-8601) |
| `endTime` | string | Y | 종료 시각(ISO-8601) |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"timestamp":"…","status":400,"error":"Bad Request","path":"/api/batch/report-auto-crt"}` | `baseYmd` 형식 오류 |
| 500 | `{"timestamp":"…","status":500,"error":"Internal Server Error","path":"/api/batch/report-auto-crt"}` | Job 시작 실패 |
| 401 | `{"error":"missing or invalid X-API-Key"}` | 인증 활성 상태에서 키 누락 또는 불일치 |
| 503 | `{"error":"batch API key not configured (BATCH_API_KEY missing)"}` | 인증 활성 상태에서 서버 키가 비어 있음 |

## 5. POST /api/batch/pblpklt-fetch

### API 개요
D-Hub 공영주차장 원천 전량을 수집·지오코딩해 `parking.tb_pblpklt` 에 적재하고, 수집이 온전하면 이번 수집에 없던 주소의 주차장에 `extsh_dt` 를 기록해 소멸 처리한다.

Job 이 끝날 때까지 응답을 기다리는 동기 API 이며, 실행 이력은 `excnSeCd=API` 로 남는다. 연쇄 잡은 실행되지 않고, 같은 잡의 중복 실행을 막지 않는다. `X-API-Key` 인증은 `batch.api.auth.enabled` 로 켠다. local 은 기본 꺼짐, dev 는 기본 켜짐, prod 는 항상 켜짐이다. Docker 구성에서는 배치 포트가 외부에 공개되지 않으므로 batch 컨테이너 안에서 호출한다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `X-API-Key` | string | 인증 활성 시 Y | - | `BATCH_API_KEY` 와 같은 값 |

### 호출 예시
```bash
curl -X POST 'http://localhost:8280/api/batch/pblpklt-fetch' \
  -H 'X-API-Key: {BATCH_API_KEY}'
```

### 응답
`200 OK`, `application/json`. Job 이 실패해도 실행이 시작됐다면 200 으로 응답하므로 `status` 를 확인한다. 건수는 Job 의 모든 Step 합계다.

```json
{
  "jobExecutionId": 4521,
  "status": "COMPLETED",
  "exitCode": "COMPLETED",
  "read": 1200,
  "written": 1180,
  "skipped": 15,
  "filtered": 5,
  "startTime": "2026-11-18T02:00:00.123456",
  "endTime": "2026-11-18T02:05:00.654321"
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `jobExecutionId` | number | N | 실행 ID. Job 이름은 `pblpkltFetchJob` |
| `status` | string | N | 실행 상태. `COMPLETED`, `FAILED`, `STOPPED` 등 |
| `exitCode` | string | N | 종료 코드 |
| `read` | number | N | 읽은 행 수 |
| `written` | number | N | `parking.tb_pblpklt` 에 적재한 행 수 |
| `skipped` | number | N | 실패가 확정되어 건너뛴 행 수 |
| `filtered` | number | N | Processor 에서 제외한 행 수 |
| `startTime` | string | Y | 시작 시각(ISO-8601) |
| `endTime` | string | Y | 종료 시각(ISO-8601) |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 500 | `{"timestamp":"…","status":500,"error":"Internal Server Error","path":"/api/batch/pblpklt-fetch"}` | Job 시작 실패 |
| 401 | `{"error":"missing or invalid X-API-Key"}` | 인증 활성 상태에서 키 누락 또는 불일치 |
| 503 | `{"error":"batch API key not configured (BATCH_API_KEY missing)"}` | 인증 활성 상태에서 서버 키가 비어 있음 |

## 6. POST /api/batch/zone-nm-prefill

### API 개요
백엔드 내부 API `POST /api/internal/zone-names/prefill` 을 반복 호출해 생활권 격자 구역명 캐시를 미리 채운다. `gridSz` 를 생략하면 1000, 500, 250, 100 순서로 모두 처리한다.

Job 이 끝날 때까지 응답을 기다리는 동기 API 이며, 실행 이력은 `excnSeCd=API` 로 남는다. 연쇄 잡은 실행되지 않고, 같은 잡의 중복 실행을 막지 않는다. `X-API-Key` 인증은 `batch.api.auth.enabled` 로 켠다. local 은 기본 꺼짐, dev 는 기본 켜짐, prod 는 항상 켜짐이다. Docker 구성에서는 배치 포트가 외부에 공개되지 않으므로 batch 컨테이너 안에서 호출한다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `X-API-Key` | string | 인증 활성 시 Y | - | `BATCH_API_KEY` 와 같은 값 |
| query | `gridSz` | long | N | 전 크기 | 격자 크기(m). `1000`, `500`, `250`, `100` |

### 호출 예시
```bash
curl -X POST 'http://localhost:8280/api/batch/zone-nm-prefill?gridSz=1000' \
  -H 'X-API-Key: {BATCH_API_KEY}'
```

### 응답
`200 OK`, `application/json`. Job 이 실패해도 실행이 시작됐다면 200 으로 응답하므로 `status` 를 확인한다. 건수는 Job 의 모든 Step 합계다.

```json
{
  "jobExecutionId": 4521,
  "status": "COMPLETED",
  "exitCode": "COMPLETED",
  "read": 1200,
  "written": 1180,
  "skipped": 15,
  "filtered": 5,
  "startTime": "2026-11-18T02:00:00.123456",
  "endTime": "2026-11-18T02:05:00.654321"
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `jobExecutionId` | number | N | 실행 ID. Job 이름은 `zoneNmPrefillJob` |
| `status` | string | N | 실행 상태. `COMPLETED`, `FAILED`, `STOPPED` 등 |
| `exitCode` | string | N | 종료 코드 |
| `read` | number | N | 시도한 격자 수 |
| `written` | number | N | 저장한 구역명 수 |
| `skipped` | number | N | 실패가 확정되어 건너뛴 행 수 |
| `filtered` | number | N | 일시 실패한 격자 수 |
| `startTime` | string | Y | 시작 시각(ISO-8601) |
| `endTime` | string | Y | 종료 시각(ISO-8601) |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"timestamp":"…","status":400,"error":"Bad Request","path":"/api/batch/zone-nm-prefill"}` | `gridSz` 가 숫자가 아니거나 허용값 밖 |
| 500 | `{"timestamp":"…","status":500,"error":"Internal Server Error","path":"/api/batch/zone-nm-prefill"}` | Job 시작 실패 |
| 401 | `{"error":"missing or invalid X-API-Key"}` | 인증 활성 상태에서 키 누락 또는 불일치 |
| 503 | `{"error":"batch API key not configured (BATCH_API_KEY missing)"}` | 인증 활성 상태에서 서버 키가 비어 있음 |

## 7. POST /api/batch/crackdown-fetch

### API 개요
D-Hub 단속 원천을 수집해 `parking.tb_crdn_dsctn` 에 upsert 한 뒤, 같은 주소의 좌표를 전파하고 남은 주소를 지오코딩한다. 기본은 저장된 최대 `load_dt` 부터 오늘까지의 증분 수집이다.

Job 이 끝날 때까지 응답을 기다리는 동기 API 이며, 실행 이력은 `excnSeCd=API` 로 남는다. 연쇄 잡은 실행되지 않고, 같은 잡의 중복 실행을 막지 않는다. `X-API-Key` 인증은 `batch.api.auth.enabled` 로 켠다. local 은 기본 꺼짐, dev 는 기본 켜짐, prod 는 항상 켜짐이다. Docker 구성에서는 배치 포트가 외부에 공개되지 않으므로 batch 컨테이너 안에서 호출한다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `X-API-Key` | string | 인증 활성 시 Y | - | `BATCH_API_KEY` 와 같은 값 |
| query | `from` | string | N | 저장된 최대 `load_dt` | `load_dt` 시작일(포함) `yyyy-MM-dd` |
| query | `to` | string | N | 오늘 포함 | `load_dt` 끝일(미포함) `yyyy-MM-dd`. `from` 과 함께 지정한다 |

### 호출 예시
```bash
curl -X POST 'http://localhost:8280/api/batch/crackdown-fetch?from=2026-11-01&to=2026-11-18' \
  -H 'X-API-Key: {BATCH_API_KEY}'
```

### 응답
`200 OK`, `application/json`. Job 이 실패해도 실행이 시작됐다면 200 으로 응답하므로 `status` 를 확인한다. 건수는 Job 의 모든 Step 합계다.

```json
{
  "jobExecutionId": 4521,
  "status": "COMPLETED",
  "exitCode": "COMPLETED",
  "read": 1200,
  "written": 1180,
  "skipped": 15,
  "filtered": 5,
  "startTime": "2026-11-18T02:00:00.123456",
  "endTime": "2026-11-18T02:05:00.654321"
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `jobExecutionId` | number | N | 실행 ID. Job 이름은 `crackdownFetchJob` |
| `status` | string | N | 실행 상태. `COMPLETED`, `FAILED`, `STOPPED` 등 |
| `exitCode` | string | N | 종료 코드 |
| `read` | number | N | 읽은 행 수 |
| `written` | number | N | 수집·좌표 전파·지오코딩 Step 의 쓰기 건수 합계 |
| `skipped` | number | N | 실패가 확정되어 건너뛴 행 수 |
| `filtered` | number | N | Processor 에서 제외한 행 수 |
| `startTime` | string | Y | 시작 시각(ISO-8601) |
| `endTime` | string | Y | 종료 시각(ISO-8601) |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 500 | `{"timestamp":"…","status":500,"error":"Internal Server Error","path":"/api/batch/crackdown-fetch"}` | 날짜 형식 오류, `to` 만 지정, `from` 이 `to` 이상, Job 시작 실패 |
| 401 | `{"error":"missing or invalid X-API-Key"}` | 인증 활성 상태에서 키 누락 또는 불일치 |
| 503 | `{"error":"batch API key not configured (BATCH_API_KEY missing)"}` | 인증 활성 상태에서 서버 키가 비어 있음 |

## 8. POST /api/batch/pklt-sfty-chck-fetch

### API 개요
D-Hub 주차장 안전점검 원천을 수집해 `parking.tb_pklt_sfty_chck` 에 실행별 관측 이력으로 추가한다. 주차장 수집과 연쇄되지 않으므로 주차장을 다시 적재했다면 직접 호출한다.

Job 이 끝날 때까지 응답을 기다리는 동기 API 이며, 실행 이력은 `excnSeCd=API` 로 남는다. 연쇄 잡은 실행되지 않고, 같은 잡의 중복 실행을 막지 않는다. `X-API-Key` 인증은 `batch.api.auth.enabled` 로 켠다. local 은 기본 꺼짐, dev 는 기본 켜짐, prod 는 항상 켜짐이다. Docker 구성에서는 배치 포트가 외부에 공개되지 않으므로 batch 컨테이너 안에서 호출한다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `X-API-Key` | string | 인증 활성 시 Y | - | `BATCH_API_KEY` 와 같은 값 |

### 호출 예시
```bash
curl -X POST 'http://localhost:8280/api/batch/pklt-sfty-chck-fetch' \
  -H 'X-API-Key: {BATCH_API_KEY}'
```

### 응답
`200 OK`, `application/json`. Job 이 실패해도 실행이 시작됐다면 200 으로 응답하므로 `status` 를 확인한다. 건수는 Job 의 모든 Step 합계다.

```json
{
  "jobExecutionId": 4521,
  "status": "COMPLETED",
  "exitCode": "COMPLETED",
  "read": 1200,
  "written": 1180,
  "skipped": 15,
  "filtered": 5,
  "startTime": "2026-11-18T02:00:00.123456",
  "endTime": "2026-11-18T02:05:00.654321"
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `jobExecutionId` | number | N | 실행 ID. Job 이름은 `pkltSftyChckFetchJob` |
| `status` | string | N | 실행 상태. `COMPLETED`, `FAILED`, `STOPPED` 등 |
| `exitCode` | string | N | 종료 코드 |
| `read` | number | N | 읽은 행 수 |
| `written` | number | N | `parking.tb_pklt_sfty_chck` 에 추가한 행 수 |
| `skipped` | number | N | 실패가 확정되어 건너뛴 행 수 |
| `filtered` | number | N | Processor 에서 제외한 행 수 |
| `startTime` | string | Y | 시작 시각(ISO-8601) |
| `endTime` | string | Y | 종료 시각(ISO-8601) |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 500 | `{"timestamp":"…","status":500,"error":"Internal Server Error","path":"/api/batch/pklt-sfty-chck-fetch"}` | Job 시작 실패 |
| 401 | `{"error":"missing or invalid X-API-Key"}` | 인증 활성 상태에서 키 누락 또는 불일치 |
| 503 | `{"error":"batch API key not configured (BATCH_API_KEY missing)"}` | 인증 활성 상태에서 서버 키가 비어 있음 |

## 9. POST /api/batch/pklt-acdnt-fetch

### API 개요
D-Hub 주차장 사고이력을 수집해 `parking.tb_pklt_acdnt_hstry` 에 upsert 한다. 적재한 행이 있으면 `SEED-` 로 시작하는 임의 사고 시드를 삭제한다. 기본은 마지막 사고일시부터 오늘까지의 증분 수집이다.

Job 이 끝날 때까지 응답을 기다리는 동기 API 이며, 실행 이력은 `excnSeCd=API` 로 남는다. 연쇄 잡은 실행되지 않고, 같은 잡의 중복 실행을 막지 않는다. `X-API-Key` 인증은 `batch.api.auth.enabled` 로 켠다. local 은 기본 꺼짐, dev 는 기본 켜짐, prod 는 항상 켜짐이다. Docker 구성에서는 배치 포트가 외부에 공개되지 않으므로 batch 컨테이너 안에서 호출한다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `X-API-Key` | string | 인증 활성 시 Y | - | `BATCH_API_KEY` 와 같은 값 |
| query | `from` | string | N | 저장된 마지막 사고일시 | 사고일시 시작일(포함) `yyyy-MM-dd` |
| query | `to` | string | N | 오늘 다음날 00:00 | 사고일시 끝일(미포함) `yyyy-MM-dd`. `from` 과 함께 지정하며 오늘 다음날까지 허용한다 |

### 호출 예시
```bash
curl -X POST 'http://localhost:8280/api/batch/pklt-acdnt-fetch?from=2026-11-01&to=2026-11-18' \
  -H 'X-API-Key: {BATCH_API_KEY}'
```

### 응답
`200 OK`, `application/json`. Job 이 실패해도 실행이 시작됐다면 200 으로 응답하므로 `status` 를 확인한다. 건수는 Job 의 모든 Step 합계다.

```json
{
  "jobExecutionId": 4521,
  "status": "COMPLETED",
  "exitCode": "COMPLETED",
  "read": 1200,
  "written": 1180,
  "skipped": 15,
  "filtered": 5,
  "startTime": "2026-11-18T02:00:00.123456",
  "endTime": "2026-11-18T02:05:00.654321"
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `jobExecutionId` | number | N | 실행 ID. Job 이름은 `pkltAcdntFetchJob` |
| `status` | string | N | 실행 상태. `COMPLETED`, `FAILED`, `STOPPED` 등 |
| `exitCode` | string | N | 종료 코드 |
| `read` | number | N | 읽은 행 수 |
| `written` | number | N | `parking.tb_pklt_acdnt_hstry` 에 적재한 행 수 |
| `skipped` | number | N | 실패가 확정되어 건너뛴 행 수 |
| `filtered` | number | N | Processor 에서 제외한 행 수 |
| `startTime` | string | Y | 시작 시각(ISO-8601) |
| `endTime` | string | Y | 종료 시각(ISO-8601) |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 500 | `{"timestamp":"…","status":500,"error":"Internal Server Error","path":"/api/batch/pklt-acdnt-fetch"}` | 날짜 형식 오류, `to` 만 지정, `from` 이 `to` 이상, `to` 가 오늘 다음날 초과, Job 시작 실패 |
| 401 | `{"error":"missing or invalid X-API-Key"}` | 인증 활성 상태에서 키 누락 또는 불일치 |
| 503 | `{"error":"batch API key not configured (BATCH_API_KEY missing)"}` | 인증 활성 상태에서 서버 키가 비어 있음 |

## 10. POST /api/batch/pklt-utztn-fetch

### API 개요
D-Hub 주차장 이용패턴 원천을 수집해 `parking.tb_pklt_utztn_pttn` 에 실행별 관측으로 추가한다. 이 API 는 `utztn.sync.scheduled` 값과 무관하게 동작한다.

Job 이 끝날 때까지 응답을 기다리는 동기 API 이며, 실행 이력은 `excnSeCd=API` 로 남는다. 연쇄 잡은 실행되지 않고, 같은 잡의 중복 실행을 막지 않는다. `X-API-Key` 인증은 `batch.api.auth.enabled` 로 켠다. local 은 기본 꺼짐, dev 는 기본 켜짐, prod 는 항상 켜짐이다. Docker 구성에서는 배치 포트가 외부에 공개되지 않으므로 batch 컨테이너 안에서 호출한다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `X-API-Key` | string | 인증 활성 시 Y | - | `BATCH_API_KEY` 와 같은 값 |

### 호출 예시
```bash
curl -X POST 'http://localhost:8280/api/batch/pklt-utztn-fetch' \
  -H 'X-API-Key: {BATCH_API_KEY}'
```

### 응답
`200 OK`, `application/json`. Job 이 실패해도 실행이 시작됐다면 200 으로 응답하므로 `status` 를 확인한다. 건수는 Job 의 모든 Step 합계다.

```json
{
  "jobExecutionId": 4521,
  "status": "COMPLETED",
  "exitCode": "COMPLETED",
  "read": 1200,
  "written": 1180,
  "skipped": 15,
  "filtered": 5,
  "startTime": "2026-11-18T02:00:00.123456",
  "endTime": "2026-11-18T02:05:00.654321"
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `jobExecutionId` | number | N | 실행 ID. Job 이름은 `pkltUtztnFetchJob` |
| `status` | string | N | 실행 상태. `COMPLETED`, `FAILED`, `STOPPED` 등 |
| `exitCode` | string | N | 종료 코드 |
| `read` | number | N | 읽은 행 수 |
| `written` | number | N | `parking.tb_pklt_utztn_pttn` 에 추가한 행 수 |
| `skipped` | number | N | 실패가 확정되어 건너뛴 행 수 |
| `filtered` | number | N | Processor 에서 제외한 행 수 |
| `startTime` | string | Y | 시작 시각(ISO-8601) |
| `endTime` | string | Y | 종료 시각(ISO-8601) |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 500 | `{"timestamp":"…","status":500,"error":"Internal Server Error","path":"/api/batch/pklt-utztn-fetch"}` | Job 시작 실패 |
| 401 | `{"error":"missing or invalid X-API-Key"}` | 인증 활성 상태에서 키 누락 또는 불일치 |
| 503 | `{"error":"batch API key not configured (BATCH_API_KEY missing)"}` | 인증 활성 상태에서 서버 키가 비어 있음 |
