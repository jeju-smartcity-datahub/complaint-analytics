# 민원현황 API

## 1. GET /api/complaints/gis

### API 개요

민원현황 화면의 핵심 지표, 감정 분포, 읍면동별 민원 현황, 부정 민원 상위 지역을 한 번에 반환한다. 민원 집계는 유형코드 `99`와 읍면동코드가 없는 행을 제외한다. 로그인 세션이 필요하다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 쿠키 |
| query | `unit` | Enum | 선택 | `YESTERDAY` | 조회 단위. `YESTERDAY`(어제), `WEEK`(오늘-7일~어제), `MONTH`(오늘-30일~어제). 기준일은 Asia/Seoul이며 대소문자를 구분한다. `startDt`·`endDt`를 함께 주면 기간 계산에 쓰지 않는다 |
| query | `startDt` | LocalDate | 선택 | 없음 | 시작일(`yyyy-MM-dd`). `endDt`와 함께 줄 때만 적용한다 |
| query | `endDt` | LocalDate | 선택 | 없음 | 종료일(`yyyy-MM-dd`, 당일 포함). `startDt`와 함께 줄 때만 적용한다 |
| query | `sggCd` | String | 선택 | 없음 | 시군구코드 5자리(`50110` 제주시, `50130` 서귀포시). `emdCd`와 함께 주면 두 조건을 모두 만족하는 행만 집계한다 |
| query | `emdCd` | String | 선택 | 없음 | 읍면동코드 8자리 |
| query | `cvlcptTypeCd` | String | 선택 | 없음 | 민원유형코드(`01`~`05`). `totalCount`, `negativityRatio`, `sentimentDist`, `emdCompare`, `negEmdCompare`에 적용하고 `enforcementCount`, `shortageCount`에는 적용하지 않는다 |

### 호출 예시

```bash
curl -b 'JSESSIONID={세션ID}' \
  'http://localhost:8180/api/complaints/gis?unit=WEEK&sggCd=50110'
```

### 응답

`200 OK` · `application/json`

```json
{
  "lastSyncDt": "2026-11-17T00:00:00",
  "totalCount": 320,
  "negativityRatio": 0.25,
  "sentimentDist": { "positive": 40, "neutral": 200, "negative": 80 },
  "enforcementCount": 4,
  "shortageCount": 6,
  "emdCompare": [
    {
      "rank": 1,
      "emdCd": "50110137",
      "emdNm": "연동",
      "cvlcptCnt": 42,
      "deltaPct": 20,
      "topTypeCd": "02",
      "topTypeNm": "인도·횡단보도 점유",
      "topTypeRatio": 0.5,
      "deltaPctInferred": false,
      "prevCnt": 35
    }
  ],
  "negEmdCompare": [
    {
      "rank": 1,
      "emdCd": "50110137",
      "emdNm": "연동",
      "negativeCount": 14,
      "totalCount": 42,
      "sentimentDist": { "positive": 4, "neutral": 24, "negative": 14 },
      "negativeRatio": 0.3333,
      "deltaPct": 40,
      "deltaPctInferred": false
    }
  ],
  "bgngYmd": "20261111",
  "endYmd": "20261117"
}
```

| 필드 | 타입 | null | 설명 |
| --- | --- | --- | --- |
| `lastSyncDt` | LocalDateTime | 가능 | 민원 테이블 전체 행의 `MAX(load_dt)`. 조회 조건과 무관하며 행이 없으면 `null` |
| `totalCount` | Long | 불가 | 조건에 맞는 민원 건수 |
| `negativityRatio` | Double | 불가 | 부정 건수 ÷ 감정 분석 완료 건수(0.0~1.0). 분모가 0이면 0.0 |
| `sentimentDist.positive` | Long | 불가 | 긍정 건수 |
| `sentimentDist.neutral` | Long | 불가 | 보통 건수 |
| `sentimentDist.negative` | Long | 불가 | 부정 건수 |
| `enforcementCount` | Long | 불가 | 기간 내 단속이 있는 읍면동 수 × 0.1의 내림값 |
| `shortageCount` | Long | 불가 | 기간 내 주차 관련 민원이 있는 읍면동 수 × 0.1의 내림값 |
| `emdCompare[]` | Array | 불가 | 현재 기간 민원이 1건 이상인 읍면동. `cvlcptCnt` 내림차순, 동률이면 `emdCd` 오름차순 |
| `emdCompare[].rank` | Integer | 불가 | 1부터 연속하는 순번 |
| `emdCompare[].emdCd` | String | 불가 | 읍면동코드 |
| `emdCompare[].emdNm` | String | 가능 | 읍면동명. 행정구역에 없으면 `null` |
| `emdCompare[].cvlcptCnt` | Long | 불가 | 현재 기간 민원 건수 |
| `emdCompare[].deltaPct` | Integer | 불가 | 직전 동일 길이 기간 대비 증감률(%). 직전 건수가 0이면 분모를 1로 계산한다 |
| `emdCompare[].topTypeCd` | String | 가능 | 최다 민원유형코드 |
| `emdCompare[].topTypeNm` | String | 가능 | 최다 민원유형명 |
| `emdCompare[].topTypeRatio` | Double | 불가 | 최다 유형 건수 ÷ `cvlcptCnt`(0.0~1.0) |
| `emdCompare[].deltaPctInferred` | Boolean | 불가 | 직전 건수가 0이라 분모를 1로 계산했으면 `true` |
| `emdCompare[].prevCnt` | Long | 불가 | 직전 동일 길이 기간 민원 건수 |
| `negEmdCompare[]` | Array | 불가 | 현재 기간 부정 민원이 1건 이상인 읍면동. `negativeRatio` 내림차순, 동률이면 `emdCd` 오름차순 |
| `negEmdCompare[].rank` | Integer | 불가 | 1부터 연속하는 순번 |
| `negEmdCompare[].emdCd` | String | 불가 | 읍면동코드 |
| `negEmdCompare[].emdNm` | String | 가능 | 읍면동명. 행정구역에 없으면 `null` |
| `negEmdCompare[].negativeCount` | Long | 불가 | 현재 기간 부정 민원 건수 |
| `negEmdCompare[].totalCount` | Long | 불가 | 현재 기간 감정 분석 완료 건수 |
| `negEmdCompare[].sentimentDist.positive` | Long | 불가 | 긍정 건수 |
| `negEmdCompare[].sentimentDist.neutral` | Long | 불가 | 보통 건수 |
| `negEmdCompare[].sentimentDist.negative` | Long | 불가 | 부정 건수 |
| `negEmdCompare[].negativeRatio` | Double | 불가 | 부정 건수 ÷ 감정 분석 완료 건수(0.0~1.0) |
| `negEmdCompare[].deltaPct` | Integer | 불가 | 부정 건수의 직전 기간 대비 증감률(%). 직전 건수가 0이면 분모를 1로 계산한다 |
| `negEmdCompare[].deltaPctInferred` | Boolean | 불가 | 직전 부정 건수가 0이라 분모를 1로 계산했으면 `true` |
| `bgngYmd` | String | 불가 | 실제 적용한 시작일(`YYYYMMDD`) |
| `endYmd` | String | 불가 | 실제 적용한 종료일(`YYYYMMDD`, 당일 포함) |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status: 400, error: "Bad Request", path}` | `unit` 허용값 밖, 날짜 형식 오류 |
| 401 | 없음 | 로그인 세션이 없음 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | DB 조회 실패 등 서버 내부 오류 |

## 2. POST /api/complaints/reports

### API 개요

요청 조건으로 서버가 민원현황을 다시 집계해 보고서로 저장하고, 저장한 스냅샷을 반환한다. 한 트랜잭션에서 `TB_ANLS_EXCN`, `TB_ANLS_RSLT`, `TB_RPTP`에 각 1행을 추가하며 파일은 생성하지 않는다. 집계 결과가 0건이어도 보고서를 저장한다.

로그인 세션과 CSRF 토큰이 필요하다. 별도 메뉴 권한은 검사하지 않는다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 쿠키 |
| cookie | `XSRF-TOKEN` | String | 필수 | 없음 | 서버가 내려 준 CSRF 토큰 쿠키 |
| header | `X-XSRF-TOKEN` | String | 필수 | 없음 | `XSRF-TOKEN` 쿠키와 같은 값 |
| header | `Content-Type` | String | 필수 | 없음 | `application/json` |
| body | `bgngYmd` | String | 필수 | 없음 | 조회 시작일(`YYYYMMDD`). 실재하는 날짜여야 한다 |
| body | `endYmd` | String | 필수 | 없음 | 조회 종료일(`YYYYMMDD`, 당일 포함). 시작일 이후여야 하며 기간은 최대 366일 |
| body | `fileFmt` | String | 필수 | 없음 | 파일형식(`PDF`, `DOCX`). 대소문자를 구분한다 |
| body | `sggCd` | String | 선택 | 없음 | 시군구코드 5자리. 생략·빈 값은 전 지역이며 등록된 코드여야 한다 |
| body | `emdCd` | String | 선택 | 없음 | 읍면동코드 8자리. 등록된 코드여야 한다. `sggCd`와 함께 주면 집계는 두 조건을 모두 적용하고, 지역명은 `emdCd` 기준으로 정한다 |
| body | `cvlcptTypeCd` | String | 선택 | 없음 | 민원유형코드(`01`~`05`) |

### 호출 예시

```bash
curl -X POST http://localhost:8180/api/complaints/reports \
  -b 'JSESSIONID={세션ID}; XSRF-TOKEN={CSRF토큰}' \
  -H 'X-XSRF-TOKEN: {CSRF토큰}' \
  -H 'Content-Type: application/json' \
  -d '{"bgngYmd":"20260901","endYmd":"20260917","sggCd":"50110","fileFmt":"PDF"}'
```

### 응답

`201 Created` · `application/json`

```json
{
  "rptpId": 100,
  "excnId": 100,
  "rptpNm": "민원 현황 보고서 제주시 전체",
  "regionNm": "제주시 전체",
  "anlsPrdExpln": "2026.09.01 ~ 2026.09.17",
  "fileFmt": "PDF",
  "crtYmd": "2026-09-18T14:03:11.123456",
  "kpi": {
    "totalCount": 320,
    "negativityRatio": 0.25,
    "sentimentDist": { "positive": 40, "neutral": 200, "negative": 80 },
    "enforcementCount": 4,
    "shortageCount": 6,
    "prevAnlsPrdExpln": "2026.08.15 ~ 2026.08.31",
    "prevTotalCount": 280
  },
  "emdCompare": [
    {
      "rank": 1,
      "emdCd": "50110137",
      "emdNm": "연동",
      "cvlcptCnt": 42,
      "deltaPct": 20,
      "topTypeCd": "02",
      "topTypeNm": "인도·횡단보도 점유",
      "topTypeRatio": 0.5,
      "deltaPctInferred": false,
      "prevCnt": 35
    }
  ],
  "negEmdCompare": [
    {
      "rank": 1,
      "emdCd": "50110137",
      "emdNm": "연동",
      "negativeCount": 14,
      "totalCount": 42,
      "sentimentDist": { "positive": 4, "neutral": 24, "negative": 14 },
      "negativeRatio": 0.3333,
      "deltaPct": 40,
      "deltaPctInferred": false
    }
  ],
  "crdnCompare": [
    {
      "rank": 1,
      "emdCd": "50110137",
      "emdNm": "연동",
      "crdnCnt": 120,
      "cvlcptCnt": 42,
      "deltaPct": -12,
      "deltaPctInferred": false,
      "prevCrdnCnt": 136,
      "topCrdnMthd": "고정형CCTV",
      "topCrdnMthdRatio": 0.4985
    }
  ],
  "shortageCompare": [
    {
      "rank": 1,
      "emdCd": "50110137",
      "emdNm": "연동",
      "shortageRatio": 0.7838,
      "pkltCpctySum": 16,
      "demandIndex": 74,
      "shortageDeltaPp": 3.4,
      "deltaPctInferred": false,
      "prevShortageRatio": 0.7498
    }
  ]
}
```

| 필드 | 타입 | null | 설명 |
| --- | --- | --- | --- |
| `rptpId` | Long | 불가 | 보고서 ID |
| `excnId` | Long | 불가 | 분석 실행 ID |
| `rptpNm` | String | 불가 | `민원 현황 보고서 {regionNm}` |
| `regionNm` | String | 불가 | `전체 지역`, `{시군구} 전체`, `{시군구} {읍면동}` 중 하나 |
| `anlsPrdExpln` | String | 불가 | 분석 기간(`yyyy.MM.dd ~ yyyy.MM.dd`). 시작일과 종료일이 같으면 날짜 하나만 표기한다 |
| `fileFmt` | String | 불가 | 요청한 파일형식 |
| `crtYmd` | LocalDateTime | 불가 | 보고서 생성일시 |
| `kpi.totalCount` | Long | 불가 | 조건에 맞는 민원 건수 |
| `kpi.negativityRatio` | Double | 불가 | 부정 건수 ÷ 감정 분석 완료 건수(0.0~1.0). 분모가 0이면 0.0 |
| `kpi.sentimentDist.positive` | Long | 불가 | 긍정 건수 |
| `kpi.sentimentDist.neutral` | Long | 불가 | 보통 건수 |
| `kpi.sentimentDist.negative` | Long | 불가 | 부정 건수 |
| `kpi.enforcementCount` | Long | 불가 | 기간 내 단속이 있는 읍면동 수 × 0.1의 내림값. `cvlcptTypeCd`를 적용하지 않는다 |
| `kpi.shortageCount` | Long | 불가 | 기간 내 주차 관련 민원이 있는 읍면동 수 × 0.1의 내림값. `cvlcptTypeCd`를 적용하지 않는다 |
| `kpi.prevAnlsPrdExpln` | String | 불가 | 직전 동일 길이 기간 표기 |
| `kpi.prevTotalCount` | Long | 불가 | 직전 동일 길이 기간 민원 건수 |
| `emdCompare[]` | Array | 불가 | 현재 기간 민원이 1건 이상인 읍면동. `cvlcptCnt` 내림차순, 동률이면 `emdCd` 오름차순 |
| `emdCompare[].rank` | Integer | 불가 | 1부터 연속하는 순번 |
| `emdCompare[].emdCd` | String | 불가 | 읍면동코드 |
| `emdCompare[].emdNm` | String | 가능 | 읍면동명. 행정구역에 없으면 `null` |
| `emdCompare[].cvlcptCnt` | Long | 불가 | 현재 기간 민원 건수 |
| `emdCompare[].deltaPct` | Integer | 불가 | 직전 기간 대비 증감률(%). 직전 건수가 0이면 분모를 1로 계산한다 |
| `emdCompare[].topTypeCd` | String | 가능 | 최다 민원유형코드 |
| `emdCompare[].topTypeNm` | String | 가능 | 최다 민원유형명 |
| `emdCompare[].topTypeRatio` | Double | 불가 | 최다 유형 건수 ÷ `cvlcptCnt`(0.0~1.0) |
| `emdCompare[].deltaPctInferred` | Boolean | 불가 | 직전 건수가 0이라 분모를 1로 계산했으면 `true` |
| `emdCompare[].prevCnt` | Long | 불가 | 직전 기간 민원 건수 |
| `negEmdCompare[]` | Array | 불가 | 현재 기간 부정 민원이 1건 이상인 읍면동. `negativeRatio` 내림차순, 동률이면 `emdCd` 오름차순 |
| `negEmdCompare[].rank` | Integer | 불가 | 1부터 연속하는 순번 |
| `negEmdCompare[].emdCd` | String | 불가 | 읍면동코드 |
| `negEmdCompare[].emdNm` | String | 가능 | 읍면동명. 행정구역에 없으면 `null` |
| `negEmdCompare[].negativeCount` | Long | 불가 | 현재 기간 부정 민원 건수 |
| `negEmdCompare[].totalCount` | Long | 불가 | 현재 기간 감정 분석 완료 건수 |
| `negEmdCompare[].sentimentDist.positive` | Long | 불가 | 긍정 건수 |
| `negEmdCompare[].sentimentDist.neutral` | Long | 불가 | 보통 건수 |
| `negEmdCompare[].sentimentDist.negative` | Long | 불가 | 부정 건수 |
| `negEmdCompare[].negativeRatio` | Double | 불가 | 부정 건수 ÷ 감정 분석 완료 건수(0.0~1.0) |
| `negEmdCompare[].deltaPct` | Integer | 불가 | 부정 건수의 직전 기간 대비 증감률(%). 직전 건수가 0이면 분모를 1로 계산한다 |
| `negEmdCompare[].deltaPctInferred` | Boolean | 불가 | 직전 부정 건수가 0이라 분모를 1로 계산했으면 `true` |
| `crdnCompare[]` | Array | 불가 | 현재 기간 단속이 1건 이상인 읍면동 전체. `crdnCnt` 내림차순, 동률이면 `emdCd` 오름차순 |
| `crdnCompare[].rank` | Long | 불가 | 단속 건수 순위. 동률은 같은 순위이고 다음 순위를 건너뛴다 |
| `crdnCompare[].emdCd` | String | 불가 | 읍면동코드 |
| `crdnCompare[].emdNm` | String | 가능 | 읍면동명. 행정구역에 없으면 `null` |
| `crdnCompare[].crdnCnt` | Long | 불가 | 현재 기간 단속 건수 |
| `crdnCompare[].cvlcptCnt` | Long | 불가 | 같은 읍면동·기간의 민원 건수(유형 `99` 제외) |
| `crdnCompare[].deltaPct` | Integer | 불가 | 단속 건수의 직전 기간 대비 증감률(%). 직전 건수가 0이면 분모를 1로 계산한다 |
| `crdnCompare[].deltaPctInferred` | Boolean | 불가 | 직전 건수가 0이라 분모를 1로 계산했으면 `true` |
| `crdnCompare[].prevCrdnCnt` | Long | 불가 | 직전 기간 단속 건수 |
| `crdnCompare[].topCrdnMthd` | String | 가능 | 최다 단속유형 원문 |
| `crdnCompare[].topCrdnMthdRatio` | Double | 가능 | 최다 단속유형 건수 ÷ `crdnCnt`(0~1) |
| `shortageCompare[]` | Array | 불가 | 주차 관련 민원이 1건 이상인 읍면동 전체. 공급 면수 ÷ 민원 수(PSDR) 오름차순, 동률이면 `emdCd` 오름차순 |
| `shortageCompare[].rank` | Long | 불가 | PSDR 순위. 동률은 같은 순위이고 다음 순위를 건너뛴다 |
| `shortageCompare[].emdCd` | String | 불가 | 읍면동코드 |
| `shortageCompare[].emdNm` | String | 불가 | 읍면동명 |
| `shortageCompare[].shortageRatio` | BigDecimal | 가능 | 주차 관련 민원 수 ÷ `demandIndex`(0~1, 소수 4자리) |
| `shortageCompare[].pkltCpctySum` | BigDecimal | 불가 | 공급 면수 |
| `shortageCompare[].demandIndex` | BigDecimal | 불가 | 공급 면수 + 주차 관련 민원 수 |
| `shortageCompare[].shortageDeltaPp` | BigDecimal | 가능 | 현재 부족률 - 직전 부족률(%p, 소수 1자리). 직전 분모가 0이면 `null` |
| `shortageCompare[].deltaPctInferred` | Boolean | 불가 | 직전 분모가 0이라 `shortageDeltaPp`를 계산할 수 없으면 `true` |
| `shortageCompare[].prevShortageRatio` | BigDecimal | 가능 | 직전 기간 부족률(0~1, 소수 4자리). 직전 분모가 0이면 `null` |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status: 400, error: "Bad Request", path}` | 본문 없음·JSON 형식 오류, 필수값 누락, `bgngYmd`·`endYmd` 형식 오류 또는 실재하지 않는 날짜, `fileFmt` 허용값 밖, 종료일이 시작일보다 빠름, 기간 366일 초과, 등록되지 않은 `sggCd`·`emdCd`. 저장되는 데이터는 없다 |
| 401 | 없음 | 로그인 세션이 없음 |
| 401 | `{timestamp, status: 401, error: "Unauthorized", path}` | 세션은 있으나 사용자가 없거나 정지 상태임 |
| 403 | `{timestamp, status: 403, error: "Forbidden", path}` | CSRF 토큰이 없거나 일치하지 않음 |
| 415 | `{timestamp, status: 415, error: "Unsupported Media Type", path}` | `Content-Type`이 JSON이 아님 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | DB 처리 실패 등 서버 내부 오류. 트랜잭션은 롤백된다 |
