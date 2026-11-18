# 대시보드 API

## 1. GET /api/dashboard/ping

### API 개요

민원 테이블(`parking.tb_cvlcpt`)의 원시 행 수를 반환하는 스모크 확인용 API다. 유형·지역 조건 없이 전체 행을 센다. 로그인 세션이 필요하다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 쿠키 |

### 호출 예시

```bash
curl -b 'JSESSIONID={세션ID}' http://localhost:8180/api/dashboard/ping
```

### 응답

`200 OK` · `application/json`

```json
{ "count": 12345 }
```

| 필드 | 타입 | null | 설명 |
| --- | --- | --- | --- |
| `count` | Long | 불가 | 민원 테이블 전체 행 수 |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 401 | 없음 | 로그인 세션이 없음 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | DB 조회 실패 등 서버 내부 오류 |

## 2. GET /api/dashboard/kpi

### API 개요

선택한 기간의 민원 건수와 직전 동일 길이 기간 대비 증감률, 단속 건수, 공영주차장 수, 위험단계 알림 집계를 반환한다. 민원 집계는 유형코드 `99`와 읍면동코드가 없는 행을 제외한다. 로그인 세션이 필요하다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 쿠키 |
| query | `unit` | Enum | 선택 | `TODAY` | 조회 단위. `TODAY`(오늘), `YESTERDAY`(어제), `WEEK`(오늘-7일~어제), `MONTH`(오늘-30일~어제). 기준일은 Asia/Seoul이며 대소문자를 구분한다. `startDt`·`endDt`를 함께 주면 기간 계산에 쓰지 않는다 |
| query | `startDt` | LocalDate | 선택 | 없음 | 시작일(`yyyy-MM-dd`). `endDt`와 함께 줄 때만 적용한다 |
| query | `endDt` | LocalDate | 선택 | 없음 | 종료일(`yyyy-MM-dd`, 당일 포함). `startDt`와 함께 줄 때만 적용한다. 기간은 최대 366일 |
| query | `sggCd` | String | 선택 | 없음 | 시군구코드 5자리(`50110` 제주시, `50130` 서귀포시). 민원·단속·주차장·알림 집계에 모두 적용한다 |
| query | `typeCd` | String | 선택 | 없음 | 민원유형코드(`01`~`05`). `total`·`prevTotal`에만 적용한다 |
| query | `riskLvlCd` | String | 선택 | 없음 | 위험단계코드(`CRIT`, `WARN`, `CAUT`). `riskAlert`에만 적용한다 |
| query | `bucket` | Enum | 선택 | 없음 | `HOUR`, `DAY`. 이 API는 값을 사용하지 않지만 허용값 밖이면 400이다 |

### 호출 예시

```bash
curl -b 'JSESSIONID={세션ID}' \
  'http://localhost:8180/api/dashboard/kpi?unit=WEEK&sggCd=50110'
```

### 응답

`200 OK` · `application/json`

```json
{
  "total": 320,
  "prevTotal": 280,
  "deltaPct": 14,
  "crdnCnt": 512,
  "pkltCnt": 96,
  "riskAlert": {
    "total": 7,
    "breakdown": [
      { "riskLvlCd": "CRIT", "count": 2 },
      { "riskLvlCd": "WARN", "count": 5 }
    ]
  }
}
```

| 필드 | 타입 | null | 설명 |
| --- | --- | --- | --- |
| `total` | Long | 불가 | 현재 기간 민원 건수 |
| `prevTotal` | Long | 불가 | 직전 동일 길이 기간 민원 건수 |
| `deltaPct` | Integer | 불가 | `round((total-prevTotal)/prevTotal*100)`. `prevTotal`이 0이면 0 |
| `crdnCnt` | Long | 불가 | 현재 기간 단속 건수. 읍면동코드가 없는 행은 제외한다 |
| `pkltCnt` | Long | 불가 | 소멸되지 않은 공영주차장 수. 기간과 무관한 현재 시점 값이다 |
| `riskAlert.total` | Long | 불가 | 현재 기간 위험단계 알림 건수 |
| `riskAlert.breakdown[]` | Array | 불가 | 단계별 건수. 0건인 단계는 포함하지 않으며 정렬 순서는 지정되지 않는다 |
| `riskAlert.breakdown[].riskLvlCd` | String | 불가 | 위험단계코드 |
| `riskAlert.breakdown[].count` | Long | 불가 | 해당 단계 알림 건수 |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status: 400, error: "Bad Request", path}` | `unit`·`bucket` 허용값 밖, 날짜 형식 오류, 종료일이 시작일보다 빠름, 기간 366일 초과 |
| 401 | 없음 | 로그인 세션이 없음 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | DB 조회 실패 등 서버 내부 오류 |

## 3. GET /api/dashboard/regions

### API 개요

현재 기간에 민원이 1건 이상인 읍면동 전체를 건수 내림차순으로 반환한다. 유형별 건수와 직전 기간 대비 증감률·순위 변동을 함께 준다. 로그인 세션이 필요하다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 쿠키 |
| query | `unit` | Enum | 선택 | `TODAY` | 조회 단위. `TODAY`(오늘), `YESTERDAY`(어제), `WEEK`(오늘-7일~어제), `MONTH`(오늘-30일~어제). 기준일은 Asia/Seoul이며 대소문자를 구분한다. `startDt`·`endDt`를 함께 주면 기간 계산에 쓰지 않는다 |
| query | `startDt` | LocalDate | 선택 | 없음 | 시작일(`yyyy-MM-dd`). `endDt`와 함께 줄 때만 적용한다 |
| query | `endDt` | LocalDate | 선택 | 없음 | 종료일(`yyyy-MM-dd`, 당일 포함). `startDt`와 함께 줄 때만 적용한다. 기간은 최대 366일 |
| query | `sggCd` | String | 선택 | 없음 | 시군구코드 5자리(`50110` 제주시, `50130` 서귀포시) |
| query | `typeCd` | String | 선택 | 없음 | 민원유형코드(`01`~`05`) |
| query | `riskLvlCd` | String | 선택 | 없음 | 이 API의 집계에는 적용하지 않는다 |
| query | `bucket` | Enum | 선택 | 없음 | `HOUR`, `DAY`. 이 API는 값을 사용하지 않지만 허용값 밖이면 400이다 |

### 호출 예시

```bash
curl -b 'JSESSIONID={세션ID}' \
  'http://localhost:8180/api/dashboard/regions?unit=MONTH&sggCd=50110'
```

### 응답

`200 OK` · `application/json` · 배열. 정렬은 `count` 내림차순, 동률이면 `emdCd` 오름차순이다. 결과가 없으면 `[]`이다.

```json
[
  {
    "emdCd": "50110137",
    "emdNm": "연동",
    "count": 42,
    "deltaPct": 20,
    "rankDelta": 1,
    "breakdown": [
      { "typeCd": "01", "count": 12 },
      { "typeCd": "02", "count": 30 }
    ]
  }
]
```

| 필드 | 타입 | null | 설명 |
| --- | --- | --- | --- |
| `[].emdCd` | String | 불가 | 읍면동코드 8자리 |
| `[].emdNm` | String | 불가 | 읍면동명 |
| `[].count` | Long | 불가 | 현재 기간 민원 건수 |
| `[].deltaPct` | Integer | 불가 | 직전 기간 대비 증감률(%). 직전 건수가 0이면 0 |
| `[].rankDelta` | Integer | 가능 | `직전 순위 - 현재 순위`. 양수는 상승이다. 동률은 같은 순위이고 다음 순위를 건너뛴다. 직전 건수가 0이면 `null` |
| `[].breakdown[]` | Array | 불가 | 유형별 건수. `typeCd` 오름차순 |
| `[].breakdown[].typeCd` | String | 불가 | 민원유형코드 |
| `[].breakdown[].count` | Long | 불가 | 해당 유형 건수 |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status: 400, error: "Bad Request", path}` | `unit`·`bucket` 허용값 밖, 날짜 형식 오류, 종료일이 시작일보다 빠름, 기간 366일 초과 |
| 401 | 없음 | 로그인 세션이 없음 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | DB 조회 실패 등 서버 내부 오류 |

## 4. GET /api/dashboard/trend

### API 개요

선택한 기간의 민원 건수를 시간대(24칸) 또는 일자별로 묶어 유형별 건수와 함께 반환한다. 건수가 없는 칸도 응답에 포함한다. 로그인 세션이 필요하다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 쿠키 |
| query | `unit` | Enum | 선택 | `TODAY` | 조회 단위. `TODAY`(오늘), `YESTERDAY`(어제), `WEEK`(오늘-7일~어제), `MONTH`(오늘-30일~어제). 기준일은 Asia/Seoul이며 대소문자를 구분한다. `startDt`·`endDt`를 함께 주면 기간 계산에 쓰지 않는다 |
| query | `startDt` | LocalDate | 선택 | 없음 | 시작일(`yyyy-MM-dd`). `endDt`와 함께 줄 때만 적용한다 |
| query | `endDt` | LocalDate | 선택 | 없음 | 종료일(`yyyy-MM-dd`, 당일 포함). `startDt`와 함께 줄 때만 적용한다. 기간은 최대 366일 |
| query | `bucket` | Enum | 선택 | `HOUR` | `HOUR`는 기간 내 같은 시각을 합산한 24칸, `DAY`는 날짜별 칸이다. 기간 지정 여부와 관계없이 생략하면 `HOUR`다 |
| query | `sggCd` | String | 선택 | 없음 | 시군구코드 5자리(`50110` 제주시, `50130` 서귀포시) |
| query | `typeCd` | String | 선택 | 없음 | 민원유형코드(`01`~`05`) |
| query | `riskLvlCd` | String | 선택 | 없음 | 이 API의 집계에는 적용하지 않는다 |

### 호출 예시

```bash
curl -b 'JSESSIONID={세션ID}' \
  'http://localhost:8180/api/dashboard/trend?startDt=2026-09-01&endDt=2026-09-07&bucket=DAY'
```

### 응답

`200 OK` · `application/json`

```json
{
  "bucket": "DAY",
  "startDt": "2026-09-01",
  "endDt": "2026-09-07",
  "points": [
    {
      "bucketKey": "2026-09-01",
      "total": 15,
      "breakdown": [
        { "typeCd": "01", "count": 5 },
        { "typeCd": "02", "count": 10 }
      ]
    },
    { "bucketKey": "2026-09-02", "total": 0, "breakdown": [] }
  ]
}
```

| 필드 | 타입 | null | 설명 |
| --- | --- | --- | --- |
| `bucket` | String | 불가 | 적용된 버킷(`HOUR`, `DAY`) |
| `startDt` | LocalDate | 불가 | 집계 시작일(`yyyy-MM-dd`) |
| `endDt` | LocalDate | 불가 | 집계 종료일(`yyyy-MM-dd`, 당일 포함) |
| `points[]` | Array | 불가 | x축 칸 목록. `bucketKey` 오름차순 |
| `points[].bucketKey` | String | 불가 | `HOUR`면 `00`~`23`, `DAY`면 `yyyy-MM-dd` |
| `points[].total` | Long | 불가 | 칸의 총건수 |
| `points[].breakdown[]` | Array | 불가 | 유형별 건수. `typeCd` 오름차순이며 0건인 유형은 포함하지 않는다 |
| `points[].breakdown[].typeCd` | String | 불가 | 민원유형코드 |
| `points[].breakdown[].count` | Long | 불가 | 해당 유형 건수 |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status: 400, error: "Bad Request", path}` | `unit`·`bucket` 허용값 밖, 날짜 형식 오류, 종료일이 시작일보다 빠름, 기간 366일 초과 |
| 401 | 없음 | 로그인 세션이 없음 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | DB 조회 실패 등 서버 내부 오류 |

## 5. GET /api/dashboard/data-status

### API 개요

집계 대상 민원의 최종 발생일시와 총건수를 반환한다. 유형코드 `99`와 읍면동코드가 없는 행은 제외하며 필터를 받지 않는다. 로그인 세션이 필요하다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 쿠키 |

### 호출 예시

```bash
curl -b 'JSESSIONID={세션ID}' http://localhost:8180/api/dashboard/data-status
```

### 응답

`200 OK` · `application/json`

```json
{ "lastOcrnDt": "2026-11-17T18:20:00", "cvlcptCnt": 12000 }
```

| 필드 | 타입 | null | 설명 |
| --- | --- | --- | --- |
| `lastOcrnDt` | LocalDateTime | 가능 | 최종 민원 발생일시(`MAX(ocrn_dt)`). 대상 행이 없으면 `null` |
| `cvlcptCnt` | Long | 불가 | 집계 대상 민원 총건수 |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 401 | 없음 | 로그인 세션이 없음 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | DB 조회 실패 등 서버 내부 오류 |

## 6. GET /api/dashboard/hotspots

### API 개요

최신 민원 발생일을 기준일로 최근 30일(기준일 포함)의 민원·단속 좌표를 실시간 군집 분석해 종합점수 순으로 집중구역을 반환한다. 민원이 없거나 기준일 조회에 실패하면 Asia/Seoul 오늘을 기준일로 쓴다. 로그인 세션이 필요하다.

구역명이 단속주소로 정해지지 않으면 요청당 최대 50건(`analysis.zone-name.max-lookups-per-run` 기본값)까지 VWorld API를 호출하고, 결과를 `parking.tb_zone_nm_cache`에 저장한다. VWorld 호출이 실패해도 `{읍면동명} 일대`로 응답한다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 쿠키 |
| query | `limit` | int | 선택 | `5` | 반환 건수. 1 미만은 1로 보정하며 상한은 없다 |
| query | `sggCd` | String | 선택 | 없음 | 시군구코드 5자리. 생략·빈 값·미등록 코드는 전 지역으로 분석한다 |

### 호출 예시

```bash
curl -b 'JSESSIONID={세션ID}' \
  'http://localhost:8180/api/dashboard/hotspots?limit=5&sggCd=50110'
```

### 응답

`200 OK` · `application/json` · 배열. `rnk` 오름차순이며 군집이 없으면 `[]`이다.

```json
[
  {
    "rnk": 1,
    "zoneNm": "연동 예시사거리",
    "cvlcptCnt": 164,
    "crdnCnt": 402,
    "snthsScr": 100.00,
    "grade": "SEVERE",
    "gradeNm": "심각",
    "mrkLat": 33.4869,
    "mrkLot": 126.4903
  }
]
```

| 필드 | 타입 | null | 설명 |
| --- | --- | --- | --- |
| `[].rnk` | Integer | 불가 | 표시 순번. 1부터 연속한다 |
| `[].zoneNm` | String | 불가 | 구역명 |
| `[].cvlcptCnt` | Integer | 불가 | 군집의 민원 건수 |
| `[].crdnCnt` | Integer | 불가 | 군집의 단속 건수 |
| `[].snthsScr` | BigDecimal | 불가 | 종합점수. 지표별 최댓값으로 나눠 가중합한 뒤 최댓값을 100으로 환산한 값(소수 2자리) |
| `[].grade` | String | 불가 | 등급코드(`SEVERE`, `WARN`, `CAUTION`). 점수 순위 비율로 판정하며 설정 `GRD_BNDRY1~3`의 기본값은 상위 5%·10%·15%다 |
| `[].gradeNm` | String | 불가 | 등급명(`심각`, `경고`, `주의`) |
| `[].mrkLat` | BigDecimal | 불가 | 군집 중심 위도(EPSG:4326) |
| `[].mrkLot` | BigDecimal | 불가 | 군집 중심 경도(EPSG:4326) |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status: 400, error: "Bad Request", path}` | `limit`이 정수가 아님 |
| 401 | 없음 | 로그인 세션이 없음 |
| 409 | `{timestamp, status: 409, error: "Conflict", path}` | 사용 가능한 집중구역 가중치 변수가 없거나 가중치 합계가 0임 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | DB 조회 실패 등 서버 내부 오류 |

## 7. GET /api/dashboard/analyses

### API 개요

로그인 사용자가 실행한 분석 이력을 실행일시 내림차순으로 반환한다. 공통코드 `ANLS_TYPE_CD`의 부가속성이 `ANLS` 또는 `BOTH`인 유형만 대상이고, `POLICY_SIM`은 결과 행이 있는 실행만 포함한다. 로그인 세션이 필요하며 실행자는 세션 사용자로 결정한다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 쿠키 |
| query | `limit` | int | 선택 | `5` | 반환 건수. 0이면 빈 배열이다 |
| query | `anlsTypeCd` | String | 선택 | 없음 | 실행유형코드(`HOTSPOT`, `POLICY_SIM`, `LIFEZONE`, `RECOMMEND`, `INVEST`, `LIFEZONE_SPLY`, `LIFEZONE_CNGST`). 생략·빈 값은 전체이고, 대상이 아닌 코드는 빈 배열이다 |

### 호출 예시

```bash
curl -b 'JSESSIONID={세션ID}' \
  'http://localhost:8180/api/dashboard/analyses?limit=5&anlsTypeCd=POLICY_SIM'
```

### 응답

`200 OK` · `application/json` · 배열. `excnDt` 내림차순이며 결과가 없으면 `[]`이다.

```json
[
  {
    "excnId": 100,
    "anlsTypeCd": "POLICY_SIM",
    "anlsTypeNm": "정책시뮬레이션",
    "excnSttsCd": "DONE",
    "excnSttsNm": "완료",
    "excnDt": "2026-11-17T10:30:00",
    "emdCd": "50110137",
    "rsltId": 10,
    "anlsRngCn": "제주시 연동",
    "plcyType": "EXPAND",
    "expandMethod": "VACLOT",
    "addPkltSlot": "30",
    "roadMtncType": null,
    "roadMtncLen": null,
    "feeAplcnMthd": null,
    "feeIncrsRt": null
  }
]
```

| 필드 | 타입 | null | 설명 |
| --- | --- | --- | --- |
| `[].excnId` | Long | 불가 | 분석 실행 ID |
| `[].anlsTypeCd` | String | 불가 | 실행유형코드 |
| `[].anlsTypeNm` | String | 불가 | 실행유형명 |
| `[].excnSttsCd` | String | 불가 | 실행상태코드(`WAIT`, `RUN`, `DONE`, `FAIL`) |
| `[].excnSttsNm` | String | 가능 | 실행상태명. 공통코드에 없으면 `null` |
| `[].excnDt` | LocalDateTime | 불가 | 실행일시 |
| `[].emdCd` | String | 가능 | 읍면동코드 |
| `[].rsltId` | Long | 가능 | 정책 시뮬레이션 결과 ID. `POLICY_SIM`이 아니면 `null` |
| `[].anlsRngCn` | String | 가능 | 정책 시뮬레이션 분석 범위. `POLICY_SIM`이 아니면 `null` |
| `[].plcyType` | String | 가능 | 분석 파라미터 `plcyType`(`EXPAND`, `REPAIR`, `FEE`) |
| `[].expandMethod` | String | 가능 | 분석 파라미터 `expandMethod`(`VACLOT`, `MULTFLR`, `NEW`) |
| `[].addPkltSlot` | String | 가능 | 분석 파라미터 `addPkltSlot`. 추가 주차면수 문자열 |
| `[].roadMtncType` | String | 가능 | 분석 파라미터 `roadMtncType`(`RSDNC`, `CMRC`) |
| `[].roadMtncLen` | String | 가능 | 분석 파라미터 `roadMtncLen`. 도로정비연장(m) 문자열 |
| `[].feeAplcnMthd` | String | 가능 | 분석 파라미터 `feeAplcnMthd`(`RAISE`, `CONVERT`) |
| `[].feeIncrsRt` | String | 가능 | 분석 파라미터 `feeIncrsRt`. 요금인상율(%) 문자열 |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status: 400, error: "Bad Request", path}` | `limit`이 정수가 아님 |
| 401 | 없음 | 로그인 세션이 없음 |
| 401 | `{timestamp, status: 401, error: "Unauthorized", path}` | 세션은 있으나 사용자가 없거나 정지 상태임 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | DB 조회 실패 등 서버 내부 오류 |

## 8. GET /api/dashboard/reports

### API 개요

로그인 사용자가 열람할 수 있는 보고서를 생성일시 내림차순으로 반환한다. `MANUAL`·`AI` 보고서는 본인이 생성한 것만, `AUTO` 보고서는 배포 부서가 `ALL`이거나 사용자 부서와 같을 때 또는 사용자 권한에 보고서 관리 메뉴(`M0402`)가 있을 때만 포함한다. 로그인 세션이 필요하다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 쿠키 |
| query | `limit` | int | 선택 | `5` | 반환 건수. 0이면 빈 배열이다 |
| query | `crtType` | String | 선택 | 없음 | 생성구분(`AUTO`, `MANUAL`, `AI`). 생략·빈 값은 전체이고, 미등록 코드는 빈 배열이다 |

### 호출 예시

```bash
curl -b 'JSESSIONID={세션ID}' \
  'http://localhost:8180/api/dashboard/reports?limit=5&crtType=MANUAL'
```

### 응답

`200 OK` · `application/json` · 배열. `crtYmd` 내림차순(`null`은 마지막)이며 결과가 없으면 `[]`이다.

```json
[
  {
    "rptpId": 100,
    "rptpNm": "민원 현황 보고서 제주시 연동",
    "sttsCd": "DONE",
    "sttsNm": "완료",
    "fileFmt": "PDF",
    "crtYmd": "2026-11-17T14:03:11"
  }
]
```

| 필드 | 타입 | null | 설명 |
| --- | --- | --- | --- |
| `[].rptpId` | Long | 불가 | 보고서 ID |
| `[].rptpNm` | String | 불가 | 보고서명 |
| `[].sttsCd` | String | 불가 | 상태코드(`WAIT`, `DONE`) |
| `[].sttsNm` | String | 가능 | 상태명. 공통코드에 없으면 `null` |
| `[].fileFmt` | String | 가능 | 파일형식 |
| `[].crtYmd` | LocalDateTime | 가능 | 생성일시 |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status: 400, error: "Bad Request", path}` | `limit`이 정수가 아님 |
| 401 | 없음 | 로그인 세션이 없음 |
| 401 | `{timestamp, status: 401, error: "Unauthorized", path}` | 세션은 있으나 사용자가 없거나 정지 상태임 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | DB 조회 실패 등 서버 내부 오류 |

## 9. GET /api/dashboard/ai-suggestions

### API 개요

노출 대상(`expsr_yn=true`)인 FAQ 질문을 노출순서대로 반환한다. 로그인 세션이 필요하다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 쿠키 |
| query | `limit` | int | 선택 | `3` | 반환 건수. 0이면 빈 배열이다 |

### 호출 예시

```bash
curl -b 'JSESSIONID={세션ID}' \
  'http://localhost:8180/api/dashboard/ai-suggestions?limit=3'
```

### 응답

`200 OK` · `application/json` · 배열. `expsr_seq` 오름차순(`null`은 마지막)이며 결과가 없으면 `[]`이다.

```json
[
  { "faqId": 1, "qstn": "이번 달 민원이 가장 많은 지역은 어디인가요?" }
]
```

| 필드 | 타입 | null | 설명 |
| --- | --- | --- | --- |
| `[].faqId` | Long | 불가 | FAQ ID |
| `[].qstn` | String | 불가 | 질문 |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status: 400, error: "Bad Request", path}` | `limit`이 정수가 아님 |
| 401 | 없음 | 로그인 세션이 없음 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | DB 조회 실패 등 서버 내부 오류 |

## 10. GET /api/dashboard/data-freshness

### API 개요

수집 배치 4종의 마지막 성공(`COMPLETED`) 실행 시각을 반환한다. 민원 발생일시를 주는 `GET /api/dashboard/data-status`와 달리 배치 실행 시각 기준이다. 로그인 세션이 필요하다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 쿠키 |

### 호출 예시

```bash
curl -b 'JSESSIONID={세션ID}' http://localhost:8180/api/dashboard/data-freshness
```

### 응답

`200 OK` · `application/json`. `items`는 항상 4행이며 순서는 민원, 단속, 공영주차장 현황, 공영주차장 이용률로 고정이다.

```json
{
  "latestDt": "2026-11-18T02:10:00",
  "items": [
    { "jobNm": "complaintFetchJob", "dataNm": "민원", "lastExcnDt": "2026-11-18T02:10:00", "cyclNm": "일 1회" },
    { "jobNm": "crackdownFetchJob", "dataNm": "단속", "lastExcnDt": null, "cyclNm": "수동 실행" },
    { "jobNm": "pblpkltFetchJob", "dataNm": "공영주차장 현황", "lastExcnDt": "2026-10-01T03:00:00", "cyclNm": "분기 1회" },
    { "jobNm": "pkltUtztnFetchJob", "dataNm": "공영주차장 이용률", "lastExcnDt": "2026-11-01T03:00:00", "cyclNm": "월 1회" }
  ]
}
```

| 필드 | 타입 | null | 설명 |
| --- | --- | --- | --- |
| `latestDt` | LocalDateTime | 가능 | `items[].lastExcnDt` 중 최댓값. 모두 `null`이면 `null` |
| `items[].jobNm` | String | 불가 | 배치 Job 이름 |
| `items[].dataNm` | String | 불가 | 데이터 표시명 |
| `items[].lastExcnDt` | LocalDateTime | 가능 | 마지막 성공 실행의 종료일시. 종료일시가 없으면 시작일시이며, 성공 이력이 없으면 `null` |
| `items[].cyclNm` | String | 불가 | 갱신 주기 문구 |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 401 | 없음 | 로그인 세션이 없음 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | DB 조회 실패 등 서버 내부 오류 |
