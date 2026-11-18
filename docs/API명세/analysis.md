# 집중구역 분석·분석레이어·정책 시뮬레이션 API

## 1. GET /api/analysis/hotspot

### API 개요

기간 안의 민원·단속 좌표를 DBSCAN 으로 군집화하고, 종합점수 순위 비율로 등급을 매겨 주의 등급 이상인 구역을 돌려준다. 분석 결과는 저장하지 않는다. 다만 구역명을 채우려고 VWorld 를 호출할 수 있고(요청당 최대 50회), 조회한 구역명은 캐시 테이블에 저장한다.

로그인 세션이 필요하다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | - | 로그인 세션 |
| query | `bgngYmd` | String | 필수 | - | 분석 시작일. `YYYYMMDD`, 실재하는 날짜 |
| query | `endYmd` | String | 필수 | - | 분석 종료일(당일 포함). `YYYYMMDD`. 시작일 이후이며 기간은 최대 366일 |
| query | `sggNm` | String | 선택 | 전 지역 | 시군구명. `TB_ADMDST` 에 등록된 이름. 빈 값은 400 |
| query | `emdCd` | String | 선택 | 시군구 전체 | 읍면동코드. 실재하는 코드. 빈 값은 400. `sggNm` 과 함께 오면 집계는 `emdCd` 기준 |
| query | `limit` | Integer | 선택 | 전량 | 결과 구역 수. 1 이상, 상한 없음 |
| query | `cvlcptWgvl` | BigDecimal | 선택 | 설정값 | 민원 가중치(%). `crdnWgvl` 과 함께 보내며 합계 100, 소수 둘째 자리까지, 설정 행의 `minVl`~`maxVl` 범위 안 |
| query | `crdnWgvl` | BigDecimal | 선택 | 설정값 | 단속 가중치(%). 규칙은 `cvlcptWgvl` 과 같다 |
| query | `vrblVls` | String(JSON) | 선택 | 설정값 | 등급 비율 일시 조정. 허용 키는 `GRD_BNDRY1`(심각)·`GRD_BNDRY2`(경고)·`GRD_BNDRY3`(주의)뿐이다. 값은 정수이고 설정 범위 안이며 심각 < 경고 < 주의. 설정 테이블에는 쓰지 않는다 |

### 호출 예시

```bash
curl -G 'http://localhost:8180/api/analysis/hotspot' \
  -b 'JSESSIONID=<세션ID>' \
  --data-urlencode 'bgngYmd=20251201' \
  --data-urlencode 'endYmd=20251231' \
  --data-urlencode 'sggNm=제주시' \
  --data-urlencode 'limit=15' \
  --data-urlencode 'vrblVls={"GRD_BNDRY1":3,"GRD_BNDRY2":7,"GRD_BNDRY3":12}'
```

### 응답

`200 OK` · `application/json`. 조건에 맞는 구역이 없어도 200 이며 `zones` 는 빈 배열이다.

```json
{
  "bgngYmd": "20251201",
  "endYmd": "20251231",
  "sggNm": "제주시",
  "emdCd": null,
  "eps": 50,
  "minPoints": 3,
  "scoreFormula": "(CVLCPT/MAX_CVLCPT*60 + CRDN/MAX_CRDN*40) / MAX * 100",
  "weights": [
    {
      "anlsTypeCd": "HOTSPOT",
      "anlsTrgtCd": null,
      "anlsTrgtNm": null,
      "vrblCd": "COMPLAINT",
      "vrblNm": "민원빈도",
      "vrblExpln": "예시 설명",
      "vrblSeCd": "WGVL",
      "stngVl": 60.00,
      "bscStngVl": 60.0000,
      "minVl": 0.0000,
      "maxVl": 100.0000,
      "unitNm": "%",
      "useYn": "Y",
      "expsrYn": "Y",
      "sortSeq": 1,
      "mdfcnDt": null
    }
  ],
  "wgvlAjmtYn": "N",
  "dataBgngYmd": "20251104",
  "dataEndYmd": "20251231",
  "gradeCriteria": [
    { "grade": "SEVERE", "gradeNm": "심각", "maxRankPct": 3 },
    { "grade": "WARN", "gradeNm": "경고", "maxRankPct": 7 },
    { "grade": "CAUTION", "gradeNm": "주의", "maxRankPct": 12 }
  ],
  "vrblAjmt": {
    "ajmtYn": "Y",
    "vrbls": [
      {
        "vrblCd": "GRD_BNDRY1",
        "vrblNm": "심각 상위 비율",
        "unitNm": "%",
        "bscStngVl": 5,
        "stngVl": 5,
        "aplcnVl": 3,
        "expsrYn": "Y"
      }
    ]
  },
  "zones": [
    {
      "rnk": 1,
      "zoneNm": "연동 예시로",
      "emdNm": "연동",
      "zoneNmSrc": "CRDN_ADDR",
      "cvlcptCnt": 164,
      "crdnCnt": 402,
      "snthsScr": 100.00,
      "grade": "SEVERE",
      "gradeNm": "심각",
      "mrkLat": 33.4869,
      "mrkLot": 126.4903
    }
  ]
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `bgngYmd` / `endYmd` | String | 정규화한 분석 기간 (`YYYYMMDD`) |
| `sggNm` | String \| null | 요청 시군구명. 생략했으면 null |
| `emdCd` | String \| null | 요청 읍면동코드. 생략했으면 null |
| `eps` | int | 적용한 군집 반경(m). 설정 `analysis.hotspot.eps`, 기본 50 |
| `minPoints` | int | 적용한 군집 최소 유일좌표 수. 설정 `analysis.hotspot.min-points`, 기본 3 |
| `scoreFormula` | String | 점수 산식 표기. 가중치가 0 인 항은 빠진다 |
| `weights[]` | Array | 계산에 쓴 가중치(`COMPLAINT`·`ENFORCE`). 필드는 위 예시와 같다 |
| `weights[].stngVl` | BigDecimal | 적용 가중치(%). 입력이 없으면 사용 중인 가중치를 합계 100 으로 재정규화한 값, 입력이 있으면 입력값 |
| `wgvlAjmtYn` | String | 입력 가중치가 설정값과 다르면 `Y`, 그 밖에는 `N` |
| `dataBgngYmd` / `dataEndYmd` | String \| null | 원천 데이터 보유 구간. 원천이 비어 있으면 null |
| `gradeCriteria[].grade` / `gradeNm` | String | 등급코드 / 등급명 (`SEVERE` 심각 · `WARN` 경고 · `CAUTION` 주의) |
| `gradeCriteria[].maxRankPct` | int | 이 등급의 순위 비율 상한(%). 오름차순 |
| `vrblAjmt.ajmtYn` | String | 입력 등급 비율이 설정값과 다르면 `Y` |
| `vrblAjmt.vrbls[]` | Array | 사용 중인 등급 비율 행. `vrblCd`·`vrblNm`·`unitNm`·`bscStngVl`(기본값)·`stngVl`(설정값)·`aplcnVl`(적용값)·`expsrYn` |
| `zones[].rnk` | int | 표시 순번. 1부터 연속 |
| `zones[].zoneNm` | String | 구역명. 단속주소 → VWorld POI → 역지오코딩 → `{읍면동명} 일대` 순으로 정하고, 읍면동도 없으면 `위치 미상` |
| `zones[].emdNm` | String \| null | 군집 중심이 속한 읍면동명 |
| `zones[].zoneNmSrc` | String | 구역명 출처. `CRDN_ADDR` · `POI` · `REVERSE_GEOCODE` · `EMD` |
| `zones[].cvlcptCnt` / `crdnCnt` | int | 군집의 민원 / 단속 건수 |
| `zones[].snthsScr` | BigDecimal | 종합점수(0~100). 조회 결과 1위가 100 |
| `zones[].grade` / `gradeNm` | String | 등급코드 / 등급명 |
| `zones[].mrkLat` / `mrkLot` | BigDecimal | 군집 중심 위도 / 경도 (EPSG:4326) |

`zones` 는 종합점수 내림차순, 동점이면 민원 건수 내림차순이다.

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status, error, path}` | 필수값 누락, 날짜 형식 오류, 기간 역전, 366일 초과, `limit` 1 미만, 빈 지역값, 없는 시군구·읍면동 |
| 400 | `{timestamp, status, error, path}` | 가중치를 한쪽만 보냄, 합계 100 아님, 소수 셋째 자리 이상, 범위 밖, 민원·단속 지표 중 꺼진 지표가 있음 |
| 400 | `{timestamp, status, error, path}` | `vrblVls` 가 JSON 객체가 아님, 허용 밖 키, 숨김·미사용 변수, null·소수·범위 밖 값, 심각 < 경고 < 주의 위반 |
| 401 | 없음 | 로그인 세션 없음 |
| 409 | `{timestamp, status, error, path}` | 가중치 입력 없이 호출했는데 설정에 쓸 수 있는 가중치가 없거나 합계가 0 |

## 2. POST /api/analysis/hotspot/reports

### API 개요

요청 조건으로 서버가 다시 분석해 집중구역 보고서를 만든다. `TB_ANLS_EXCN`·`TB_ANLS_RSLT`·`TB_RPTP` 에 1행씩 한 트랜잭션으로 저장한다. 파일은 서버에 만들지 않고 응답 스냅샷으로 문서를 그린다. 구역명 캐시는 별도 트랜잭션이라 보고서 저장이 롤백돼도 남는다.

로그인 세션과 CSRF 토큰이 필요하다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | - | 로그인 세션 |
| cookie | `XSRF-TOKEN` | String | 필수 | - | CSRF 토큰 쿠키 |
| header | `X-XSRF-TOKEN` | String | 필수 | - | `XSRF-TOKEN` 쿠키와 같은 값 |
| header | `Content-Type` | String | 필수 | - | `application/json` |
| body | `bgngYmd` | String | 필수 | - | 분석 시작일. `YYYYMMDD` |
| body | `endYmd` | String | 필수 | - | 분석 종료일(당일 포함). `YYYYMMDD`. 기간은 최대 366일 |
| body | `fileFmt` | String | 필수 | - | `PDF` · `DOCX` · `XLSX` |
| body | `sggNm` | String | 선택 | 전 지역 | 시군구명. 빈 값은 400 |
| body | `emdCd` | String | 선택 | 시군구 전체 | 읍면동코드. 빈 값은 400 |
| body | `limit` | Integer | 선택 | 전량 | 결과 구역 수. 1 이상 |
| body | `cvlcptWgvl` | BigDecimal | 선택 | 설정값 | 민원 가중치(%). 규칙은 1번 API 와 같다 |
| body | `crdnWgvl` | BigDecimal | 선택 | 설정값 | 단속 가중치(%). 규칙은 1번 API 와 같다 |
| body | `vrblVls` | Object | 선택 | 설정값 | 등급 비율 일시 조정 `{변수코드: 숫자}`. 규칙은 1번 API 와 같다 |

### 호출 예시

```bash
curl -X POST 'http://localhost:8180/api/analysis/hotspot/reports' \
  -b 'JSESSIONID=<세션ID>; XSRF-TOKEN=<토큰>' \
  -H 'X-XSRF-TOKEN: <토큰>' \
  -H 'Content-Type: application/json' \
  -d '{
    "bgngYmd": "20251201",
    "endYmd": "20251231",
    "sggNm": "제주시",
    "fileFmt": "PDF",
    "vrblVls": { "GRD_BNDRY1": 3, "GRD_BNDRY2": 7, "GRD_BNDRY3": 12 }
  }'
```

### 응답

`201 Created` · `application/json`

```json
{
  "rptpId": 100,
  "excnId": 200,
  "rptpNm": "집중 구역 분석 보고서 제주시 전체",
  "regionNm": "제주시 전체",
  "anlsPrdExpln": "2025.12.01 ~ 2025.12.31",
  "fileFmt": "PDF",
  "crtYmd": "2026-01-05T14:03:11.123",
  "weights": [],
  "wgvlAjmtYn": "N",
  "anlsMthd": "LIST_MAX",
  "gradeCriteria": [],
  "vrblAjmt": { "ajmtYn": "N", "vrbls": [] },
  "zones": [],
  "wbk": {
    "cvlcptWgvl": 60,
    "maxCvlcptCnt": 51,
    "maxCrdnCnt": 120,
    "scrCnts": [{ "cvlcptWgvl": 60, "scrCnt": 244 }],
    "zones": [
      { "cid": 17, "zoneNm": "연동 예시로", "emdNm": "연동", "cvlcptCnt": 51, "crdnCnt": 32 }
    ]
  }
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `rptpId` / `excnId` | Long | 보고서 ID / 분석 실행 ID |
| `rptpNm` | String | `집중 구역 분석 보고서 {regionNm}` |
| `regionNm` | String | `전체 지역` · `{시군구} 전체` · `{시군구} {읍면동}` |
| `anlsPrdExpln` | String | `yyyy.MM.dd ~ yyyy.MM.dd`. 시작일과 종료일이 같으면 날짜 하나 |
| `fileFmt` | String | 요청한 파일 형식 |
| `crtYmd` | LocalDateTime | 생성 일시 (ISO-8601) |
| `weights[]` · `wgvlAjmtYn` · `gradeCriteria[]` · `vrblAjmt` · `zones[]` | - | 1번 API 응답의 같은 이름 필드와 같다 |
| `anlsMthd` | String | 점수 산정 방식. 항상 `LIST_MAX` |
| `wbk.cvlcptWgvl` | BigDecimal | 적용 민원 가중치(%) |
| `wbk.maxCvlcptCnt` / `maxCrdnCnt` | int | 전체 군집의 최대 민원 / 단속 건수 |
| `wbk.scrCnts[].cvlcptWgvl` | BigDecimal | 민원 가중치(%). 0~100 정수와 적용 가중치, 오름차순 |
| `wbk.scrCnts[].scrCnt` | long | 그 가중치에서 종합점수가 0 보다 큰 군집 수 |
| `wbk.zones[].cid` | int | 군집 번호. 오름차순. 실행마다 달라질 수 있어 식별자로 쓰지 않는다 |
| `wbk.zones[].zoneNm` / `emdNm` | String / String \| null | 구역명 / 읍면동명 |
| `wbk.zones[].cvlcptCnt` / `crdnCnt` | int | 군집의 민원 / 단속 건수 |

`wbk.zones` 는 `limit` 과 무관하게, 민원 가중치 0~100%(정수)와 적용 가중치 중 어느 하나에서든 주의 등급 이상에 드는 군집 전부다.

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status, error, path}` | 본문 형식 오류, 필수값 누락, `fileFmt` 허용 밖, 1번 API 의 입력 검증 실패 |
| 400 | `{timestamp, status, error, path}` | 분석 결과가 0건. 아무것도 저장하지 않는다 |
| 401 | 없음 | 로그인 세션 없음 |
| 401 | `{timestamp, status, error, path}` | 세션 사용자가 `TB_USER` 에 없거나 사용 중지 상태 |
| 403 | `{timestamp, status, error, path}` | CSRF 토큰 누락 또는 불일치 |
| 409 | `{timestamp, status, error, path}` | 가중치 입력 없이 호출했는데 설정에 쓸 수 있는 가중치가 없거나 합계가 0 |

## 3. GET /api/hotspots/ranking

### API 개요

민원현황 지도의 분석레이어에 표시할 읍면동 순위를 `view` 별로 조회한다. 저장하지 않는 조회 API 다.

로그인 세션이 필요하다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | - | 로그인 세션 |
| query | `view` | String | 선택 | `most-complaint` | `most-complaint`(민원 다발) · `enforcement`(집중 단속) · `shortage`(수요 부족) |
| query | `unit` | String | 선택 | `YESTERDAY` | `YESTERDAY`(어제) · `WEEK`(어제까지 7일) · `MONTH`(어제까지 30일) |
| query | `startDt` | LocalDate | 선택 | - | 시작일 `yyyy-MM-dd`. `endDt` 와 함께 주면 `unit` 보다 우선한다. 하나만 주면 무시한다 |
| query | `endDt` | LocalDate | 선택 | - | 종료일 `yyyy-MM-dd`. 규칙은 `startDt` 와 같다 |
| query | `sggCd` | String | 선택 | 전 지역 | 시군구코드 5자리 (`50110` 제주시 · `50130` 서귀포시) |
| query | `emdCd` | String | 선택 | 전 지역 | 읍면동코드 8자리 |
| query | `cvlcptTypeCd` | String | 선택 | 전체 | `most-complaint` 는 민원유형코드로 거른다. `enforcement` 는 같은 값을 단속유형코드(`crdn_type_cd`)와 비교한다. `shortage` 는 사용하지 않는다 |

### 호출 예시

```bash
curl 'http://localhost:8180/api/hotspots/ranking?view=enforcement&unit=MONTH&sggCd=50110' \
  -b 'JSESSIONID=<세션ID>'
```

### 응답

`200 OK` · `application/json`. 결과가 없으면 `zones` 는 빈 배열이다.

```json
{
  "view": "enforcement",
  "unit": "MONTH",
  "zones": [
    {
      "rank": 1,
      "zoneNm": "연동",
      "emdCd": "50110000",
      "cvlcptCnt": 52,
      "crdnCnt": 340,
      "mrkLat": 33.4869,
      "mrkLot": 126.4903,
      "bndryGeoJson": "{\"type\":\"MultiPolygon\",\"coordinates\":[]}",
      "typeCnts": [],
      "pkltCpctySum": null,
      "demandIndex": null,
      "shortageRatio": null,
      "deltaPct": -12,
      "shortageDeltaPp": null,
      "deltaPctInferred": false,
      "topCrdnMthd": "고정형CCTV",
      "topCrdnMthdRatio": 0.4985
    }
  ]
}
```

| 필드 | 타입 | 적용 view | 설명 |
| --- | --- | --- | --- |
| `view` | String | 전체 | 적용한 view |
| `unit` | String \| null | 전체 | 적용한 단위. `startDt`·`endDt` 로 조회하면 null |
| `zones[].rank` | long | 전체 | 순위. 같은 값은 같은 순위이고 다음 순위를 건너뛴다 |
| `zones[].zoneNm` | String \| null | 전체 | 읍면동명 |
| `zones[].emdCd` | String | 전체 | 읍면동코드 |
| `zones[].cvlcptCnt` | long | 전체 | 민원 건수(유형 `99` 제외). `shortage` 는 0 |
| `zones[].crdnCnt` | long | 전체 | 단속 건수. `most-complaint`·`shortage` 는 0 |
| `zones[].mrkLat` / `mrkLot` | BigDecimal \| null | 전체 | 마커 위도 / 경도. 경계 내부 점, 경계가 없으면 읍면동 중심점 |
| `zones[].bndryGeoJson` | String \| null | 전체 | 행정동 경계 GeoJSON 문자열 |
| `zones[].typeCnts[]` | Array | `most-complaint` | 민원유형별 `cvlcptTypeCd`·`cvlcptTypeNm`·`cvlcptCnt`. 다른 view 는 빈 배열 |
| `zones[].pkltCpctySum` | BigDecimal \| null | `shortage` | 공급 면수 |
| `zones[].demandIndex` | BigDecimal \| null | `shortage` | 공급 면수 + 주차 민원 수 |
| `zones[].shortageRatio` | BigDecimal \| null | `shortage` | 주차 민원 수 ÷ 수요 지수 (0~1, 소수 4자리) |
| `zones[].shortageDeltaPp` | BigDecimal \| null | `shortage` | 직전 동일 길이 구간 대비 부족률 차이(%p, 소수 1자리). 직전 분모가 0 이면 null |
| `zones[].deltaPct` | Integer \| null | `enforcement` | 직전 동일 길이 구간 대비 단속 증감률(%). 직전 0건이면 분모를 1 로 계산 |
| `zones[].deltaPctInferred` | boolean | 전체 | `enforcement` 는 직전 0건, `shortage` 는 직전 분모 0 이면 true. `most-complaint` 는 false |
| `zones[].topCrdnMthd` | String \| null | `enforcement` | 최다 단속유형 원문. 동률이면 이름 오름차순 |
| `zones[].topCrdnMthdRatio` | Double \| null | `enforcement` | 최다 단속유형 건수 ÷ `crdnCnt` (0~1) |

정렬은 `most-complaint` 가 민원 건수 내림차순, `enforcement` 가 단속 건수 내림차순, `shortage` 가 PSDR(공급 면수 ÷ 주차 민원 수) 오름차순이며, 동률이면 읍면동코드 오름차순이다. `shortage` 는 기간 안에 주차 민원(유형 `01`~`05`)이 있는 읍면동만 포함한다.

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status, error, path}` | `view`·`unit` 허용 밖, `startDt`·`endDt` 형식 오류 |
| 401 | 없음 | 로그인 세션 없음 |

## 4. GET /api/hotspots/heatmap

### API 개요

분석레이어 히트맵에 쓸 칸별 건수를 조회한다. 원본 좌표 대신 위도·경도를 소수 3자리(0.001°)로 반올림한 칸 단위로 집계한다.

로그인 세션이 필요하다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | - | 로그인 세션 |
| query | `view` | String | 선택 | `most-complaint` | `most-complaint` · `enforcement`. `shortage` 는 400 |
| query | `unit` | String | 선택 | `YESTERDAY` | `YESTERDAY` · `WEEK` · `MONTH` |
| query | `startDt` | LocalDate | 선택 | - | 시작일 `yyyy-MM-dd`. `endDt` 와 함께 주면 `unit` 보다 우선한다. 하나만 주면 무시한다 |
| query | `endDt` | LocalDate | 선택 | - | 종료일 `yyyy-MM-dd`. 규칙은 `startDt` 와 같다 |
| query | `sggCd` | String | 선택 | 전 지역 | 시군구코드 5자리 |
| query | `emdCd` | String | 선택 | 전 지역 | 읍면동코드 8자리 |
| query | `cvlcptTypeCd` | String | 선택 | 전체 | `most-complaint` 는 민원유형코드로 거른다. `enforcement` 는 같은 값을 단속유형코드(`crdn_type_cd`)와 비교한다 |

### 호출 예시

```bash
curl 'http://localhost:8180/api/hotspots/heatmap?view=most-complaint&startDt=2025-12-01&endDt=2025-12-31' \
  -b 'JSESSIONID=<세션ID>'
```

### 응답

`200 OK` · `application/json`. 결과가 없으면 `points` 는 빈 배열이고 `totalCnt` 는 0 이다.

```json
{
  "view": "most-complaint",
  "unit": null,
  "cellDeg": 0.001,
  "totalCnt": 4118,
  "points": [
    { "lat": 33.487, "lot": 126.490, "cnt": 12 }
  ]
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `view` | String | 적용한 view |
| `unit` | String \| null | 적용한 단위. `startDt`·`endDt` 로 조회하면 null |
| `cellDeg` | BigDecimal | 칸 크기(도). 항상 0.001 |
| `totalCnt` | long | `points[].cnt` 합계 |
| `points[].lat` / `lot` | BigDecimal | 소수 3자리로 반올림한 위도 / 경도 |
| `points[].cnt` | long | 칸 안의 민원 또는 단속 건수 |

`points` 의 순서는 정해져 있지 않다.

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status, error, path}` | `view=shortage`, `view`·`unit` 허용 밖, `startDt`·`endDt` 형식 오류 |
| 401 | 없음 | 로그인 세션 없음 |

## 5. GET /api/analysis/policy-sim

### API 개요

주차장 확충(`EXPAND`)·이면도로 정비(`REPAIR`)·요금제 적용(`FEE`) 정책의 민원·주차 혼잡 효과를 계산한다. 저장하지 않는다. 기준선은 민원·단속 모두 최근 완결 3개월 구간으로 산정한다.

로그인 세션이 필요하다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | - | 로그인 세션 |
| query | `plcyType` | String | 필수 | - | `EXPAND` · `REPAIR` · `FEE` |
| query | `predcMm` | Integer | 필수 | - | 예측 기간(개월). `3` · `6` · `12` · `24`. `REPAIR`·`FEE` 계산에는 쓰지 않는다 |
| query | `expandMethod` | String | `EXPAND` 필수 | - | `VACLOT`(공한지) · `MULTFLR`(복층화) · `NEW`(신규) |
| query | `addPkltSlot` | Integer | `EXPAND` 필수 | - | 추가 면수. `50` · `80` · `100` · `150` · `200` |
| query | `roadMtncType` | String | `REPAIR` 필수 | - | `RSDNC`(주거지역형) · `CMRC`(상업지역형) |
| query | `roadMtncLen` | Integer | `REPAIR` 필수 | - | 정비 구간 연장(m). 1~10000 |
| query | `feeAplcnMthd` | String | `FEE` 필수 | - | `RAISE`(요금 인상) · `CONVERT`(무료→유료 전환) |
| query | `feeIncrsRt` | Integer | `RAISE` 필수 | - | 요금 인상률(%). `10` · `20` · `30`. `CONVERT` 에서는 무시한다 |
| query | `feeAplcnZone` | String | 선택 | `ALL` | `FEE` 적용 급지. `ALL` · `Z1`(동) · `Z2`(읍·면). `emdCd` 가 있으면 무시하고 그 읍면동의 급지를 쓴다 |
| query | `sggNm` | String | 선택 | 제주 전체 | `제주시` · `서귀포시`. 빈 값은 400 |
| query | `emdCd` | String | 선택 | 시 전체 | 읍면동코드 8자리. 있으면 `sggNm` 대신 이 읍면동을 대상으로 한다. 빈 값은 400 |

`vrblVls` 는 이 API 에서 받지 않으며, 쿼리로 보내도 무시한다.

### 호출 예시

```bash
curl -G 'http://localhost:8180/api/analysis/policy-sim' \
  -b 'JSESSIONID=<세션ID>' \
  --data-urlencode 'plcyType=EXPAND' \
  --data-urlencode 'expandMethod=VACLOT' \
  --data-urlencode 'addPkltSlot=100' \
  --data-urlencode 'predcMm=12' \
  --data-urlencode 'sggNm=제주시'
```

### 응답

`200 OK` · `application/json`

```json
{
  "anlsRngCn": "제주시 전체",
  "crtrCvlcptCnt": 818.63,
  "crtrIlglCnt": 174.75,
  "crtrCngstIdx": 0.925,
  "exsPkltCpcty": 2315,
  "vldAddPkltSlot": 96.80,
  "cvlcptRdctRt": -27.70,
  "cngstImpvRt": 4.01,
  "aftrCngstIdx": 0.888,
  "aftrCvlcptCnt": 591.89,
  "calcSttsCd": null,
  "excsYn": false,
  "cstAmt": 187000000,
  "cstLvlCd": "LOW",
  "roadMtncType": null,
  "roadMtncLen": null,
  "adjcCvlcptCnt": null,
  "adjcEmdCnt": null,
  "feeAplcnMthd": null,
  "feeAplcnZone": null,
  "feeIncrsRt": null,
  "aplcnTrgtSlot": null,
  "lvngVhclCnt": null,
  "absrpCnt": null,
  "trnsfCnt": null,
  "cvlcptRdctRtRng": null,
  "wrnngCn": null,
  "aplcnParam": {
    "occ": 0.85,
    "occTh": 0.85,
    "alpha": 0.5,
    "m": 3,
    "wFixed": 0.5,
    "rRet": 0.032,
    "gSgg": { "제주시": 2.89, "서귀포시": 1.385 },
    "cVacant": 1870000,
    "cPublic": 38220000,
    "cMultflr": 18150000,
    "cstLowBndry": 5000000,
    "cstHighBndry": 30000000,
    "cvlcptPrd": "20250901~20251130",
    "crdnPrd": "20250901~20251130",
    "n100Rsdnc": null,
    "n100Cmrc": null,
    "alphaB": null,
    "balloonRt": null,
    "eps": null,
    "lambda": null,
    "tau": null,
    "tauLocal": null
  },
  "vrblAjmt": {
    "ajmtYn": "N",
    "vrbls": [
      {
        "vrblCd": "ALPHA_VL",
        "vrblNm": "흡수율",
        "unitNm": null,
        "bscStngVl": 0.5,
        "stngVl": 0.5,
        "aplcnVl": 0.5,
        "expsrYn": "Y"
      }
    ]
  }
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `anlsRngCn` | String | 조회 범위. `전체 지역` · `{시군구} 전체` · `{시군구} {읍면동}` |
| `crtrCvlcptCnt` | BigDecimal | 기준 월평균 민원 합계(건/월). 단속 없는 읍면동 제외. 범위 전체가 `NO_ENF` 면 실제 합계 |
| `crtrIlglCnt` | BigDecimal | 기준 불법주차 추정 합계(대) |
| `crtrCngstIdx` | BigDecimal \| null | 기준 주차 혼잡 지수. 공급이 없으면 null |
| `exsPkltCpcty` | Long | 기존 공영주차면수 합계 |
| `vldAddPkltSlot` | BigDecimal \| null | `EXPAND` 유효 증설 면수, `REPAIR` 환산 확보 면수. `FEE` 는 null |
| `cvlcptRdctRt` | BigDecimal \| null | 민원 변화율(%). 음수는 감소, 양수는 증가. `NO_ENF` 이거나 기준 민원이 0 이면 null |
| `cngstImpvRt` | BigDecimal \| null | 주차 혼잡 개선율(%). `NO_ENF`·`NO_SPLY` 면 null |
| `aftrCngstIdx` | BigDecimal \| null | 정책 후 주차 혼잡 지수. `NO_ENF`·`NO_SPLY` 면 null |
| `aftrCvlcptCnt` | BigDecimal \| null | 정책 후 월평균 민원 합계. `REPAIR`·`FEE` 는 인접 지역 증가분 포함. `NO_ENF` 면 null |
| `calcSttsCd` | String \| null | 산출 상태. 정상은 null, `NO_ENF`(단속 없음) · `NO_SPLY`(공영주차면 없음) |
| `excsYn` | boolean | 흡수 포화가 발생한 읍면동이 있으면 true |
| `cstAmt` | BigDecimal \| null | `EXPAND` 예상 비용(원). 그 밖에는 null |
| `cstLvlCd` | String | 비용 수준. `LOW` · `MID` · `HIGH`, `REPAIR`·`FEE` 는 `TBD` |
| `roadMtncType` / `roadMtncLen` | String / Integer \| null | `REPAIR` 요청값 |
| `adjcCvlcptCnt` | BigDecimal \| null | `REPAIR`·`FEE` 인접 지역 민원 증가분(건/월) |
| `adjcEmdCnt` | Integer \| null | `REPAIR`·`FEE` 배분에 쓴 인접 읍면동 수 |
| `feeAplcnMthd` / `feeAplcnZone` / `feeIncrsRt` | String / String / Integer \| null | `FEE` 적용 방식 / 급지 / 인상률 |
| `aplcnTrgtSlot` | Long \| null | `FEE` 적용 대상 주차면수 |
| `lvngVhclCnt` / `absrpCnt` / `trnsfCnt` | BigDecimal \| null | `FEE` 이탈 차량 / 흡수 불법주차 / 전이 불법주차(대) |
| `cvlcptRdctRtRng` | Object \| null | `FEE` 민감도 범위(%). `CONVERT` 는 `lambda`·`tau`, `RAISE` 는 `eps`·`tau` 키에 `[하한, 상한]` |
| `wrnngCn` | String \| null | `FEE` 경고 문구 |
| `aplcnParam` | Object | 적용 계수와 기준 구간. `n100Rsdnc`·`n100Cmrc`·`alphaB`·`balloonRt` 는 `REPAIR` 만, `eps`·`lambda`·`tau`·`tauLocal` 은 `FEE` 만 값이 있다 |
| `aplcnParam.cvlcptPrd` / `crdnPrd` | String \| null | 민원 / 단속 기준 구간 `YYYYMMDD~YYYYMMDD`. `cvlcptPrd` 는 민원이 없으면 null |
| `vrblAjmt.ajmtYn` | String | 입력 계수가 설정값과 다르면 `Y` |
| `vrblAjmt.vrbls[]` | Array | 정책 유형별 조정 가능 변수의 `vrblCd`·`vrblNm`·`unitNm`·`bscStngVl`·`stngVl`·`aplcnVl`·`expsrYn` |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status, error, path}` | 필수값 누락, 정책 유형별 필수값 누락, 허용 밖 값, 형식 오류 |
| 400 | `{timestamp, status, error, path}` | 없는 읍면동코드, 조회 범위에 해당 급지 읍면동이 없음 |
| 401 | 없음 | 로그인 세션 없음 |
| 503 | `{"code":"POLICY_SIM_PARAM_MISSING"}` | 정책 유형에 필요한 `POLICY_SIM` 계수 행이 없거나 쓸 값이 없음 |

## 6. POST /api/analysis/policy-sim/results

### API 개요

요청 조건으로 서버가 다시 계산해 결과를 저장한다. `TB_ANLS_EXCN`·`TB_PLCY_SMLT_RSLT` 에 1행씩 한 트랜잭션으로 저장한다. `calcSttsCd` 가 `NO_ENF`·`NO_SPLY` 이면 저장하지 않고 계산 결과만 돌려준다.

로그인 세션과 CSRF 토큰이 필요하다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | - | 로그인 세션 |
| cookie | `XSRF-TOKEN` | String | 필수 | - | CSRF 토큰 쿠키 |
| header | `X-XSRF-TOKEN` | String | 필수 | - | `XSRF-TOKEN` 쿠키와 같은 값 |
| header | `Content-Type` | String | 필수 | - | `application/json` |
| body | `plcyType` | String | 필수 | - | `EXPAND` · `REPAIR` · `FEE` |
| body | `predcMm` | Integer | 필수 | - | `3` · `6` · `12` · `24` |
| body | `expandMethod` | String | `EXPAND` 필수 | - | `VACLOT` · `MULTFLR` · `NEW` |
| body | `addPkltSlot` | Integer | `EXPAND` 필수 | - | `50` · `80` · `100` · `150` · `200` |
| body | `roadMtncType` | String | `REPAIR` 필수 | - | `RSDNC` · `CMRC` |
| body | `roadMtncLen` | Integer | `REPAIR` 필수 | - | 1~10000 (m) |
| body | `feeAplcnMthd` | String | `FEE` 필수 | - | `RAISE` · `CONVERT` |
| body | `feeIncrsRt` | Integer | `RAISE` 필수 | - | `10` · `20` · `30`. `CONVERT` 에서는 무시한다 |
| body | `feeAplcnZone` | String | 선택 | `ALL` | `ALL` · `Z1` · `Z2`. `emdCd` 가 있으면 무시한다 |
| body | `sggNm` | String | 선택 | 제주 전체 | `제주시` · `서귀포시` |
| body | `emdCd` | String | 선택 | 시 전체 | 읍면동코드 8자리 |
| body | `vrblVls` | Object | 선택 | 설정값 | 계수 일시 조정 `{변수코드: 숫자}`. 공통 허용 키는 `PCSHR_CRTR_VL`·`ALPHA_VL`·`GEO_CVRG_CRCT_JEJU`·`GEO_CVRG_CRCT_SEOGWIPO`. `REPAIR` 는 `N100_RSDNC`·`N100_CMRC`·`ALPHA_B_VL`·`BALLOON_RT`, `FEE` 는 `PRC_ELST_VL`·`PAID_DMND_RT`·`TRNSF_RT`·`TRNSF_LOCAL_RT` 를 더 받는다. 값은 설정 범위 안, 소수 넷째 자리까지 |

### 호출 예시

```bash
curl -X POST 'http://localhost:8180/api/analysis/policy-sim/results' \
  -b 'JSESSIONID=<세션ID>; XSRF-TOKEN=<토큰>' \
  -H 'X-XSRF-TOKEN: <토큰>' \
  -H 'Content-Type: application/json' \
  -d '{
    "plcyType": "REPAIR",
    "roadMtncType": "RSDNC",
    "roadMtncLen": 600,
    "predcMm": 12,
    "emdCd": "50110000",
    "vrblVls": { "BALLOON_RT": 0.25 }
  }'
```

### 응답

| 상태 | 조건 |
| --- | --- |
| `201 Created` | 저장함 |
| `200 OK` | `calcSttsCd` 가 `NO_ENF`·`NO_SPLY` 라 저장하지 않음 |

`application/json`. 본문은 5번 API 응답과 같은 구조다.

```json
{
  "anlsRngCn": "제주시 연동",
  "vldAddPkltSlot": 86.40,
  "cvlcptRdctRt": -12.30,
  "calcSttsCd": null,
  "cstAmt": null,
  "cstLvlCd": "TBD",
  "roadMtncType": "RSDNC",
  "roadMtncLen": 600,
  "adjcCvlcptCnt": 8.52,
  "adjcEmdCnt": 6
}
```

위 JSON 은 일부 필드만 보여 준다. 응답에 `rsltId` 는 없으며, 저장된 결과의 ID 는 7번 API 로 조회한다.

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status, error, path}` | 본문 형식 오류, 5번 API 의 입력 검증 실패 |
| 400 | `{timestamp, status, error, path}` | `vrblVls` 허용 밖 키, 숨김·미사용 변수, null·범위 밖 값, 소수 다섯째 자리 이상 |
| 401 | 없음 | 로그인 세션 없음 |
| 401 | `{timestamp, status, error, path}` | 세션 사용자가 `TB_USER` 에 없거나 사용 중지 상태 |
| 403 | `{timestamp, status, error, path}` | CSRF 토큰 누락 또는 불일치 |
| 503 | `{"code":"POLICY_SIM_PARAM_MISSING"}` | 필요한 `POLICY_SIM` 계수 행이 없거나 쓸 값이 없음 |

## 7. GET /api/analysis/policy-sim/results

### API 개요

로그인 사용자가 저장한 정책 시뮬레이션 결과를 저장 시각 최신순으로 페이징 조회한다. 저장 당시 값을 돌려주며 모델을 다시 계산하지 않는다.

로그인 세션이 필요하다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | - | 로그인 세션 |
| query | `page` | int | 선택 | `0` | 페이지 번호(0부터). 0 이상 |
| query | `size` | int | 선택 | `10` | 페이지 크기. 1~100 |

### 호출 예시

```bash
curl 'http://localhost:8180/api/analysis/policy-sim/results?page=0&size=10' \
  -b 'JSESSIONID=<세션ID>'
```

### 응답

`200 OK` · `application/json`

```json
{
  "content": [
    {
      "rsltId": 12,
      "excnDt": "2026-01-05T14:03:21.123",
      "plcyType": "EXPAND",
      "expandMethod": "VACLOT",
      "addPkltSlot": 100,
      "predcMm": 12,
      "sggNm": "제주시",
      "emdCd": null,
      "anlsRngCn": "제주시 전체",
      "cvlcptRdctRt": -27.70,
      "cstAmt": 187000000,
      "adjcEmdCnt": null,
      "cvlcptRdctRtRng": null,
      "wrnngCn": null
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 1,
  "totalPages": 1
}
```

위 JSON 의 `content[]` 는 일부 필드만 보여 준다.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `content[]` | Array | 5번 API 응답 필드 전부와 아래 필드 |
| `content[].rsltId` | Long | 결과 ID |
| `content[].excnDt` | LocalDateTime | 저장 일시 (ISO-8601) |
| `content[].plcyType` | String | 요청 정책 유형 |
| `content[].expandMethod` / `addPkltSlot` | String / Integer \| null | 요청 확충 방식 / 추가 면수. `EXPAND` 만 값이 있다 |
| `content[].predcMm` | Integer | 요청 예측 기간(개월) |
| `content[].sggNm` / `emdCd` | String \| null | 요청 시군구명 / 읍면동코드 |
| `content[].cstAmt` | BigDecimal \| null | 추가 면수 × 저장된 단가로 다시 만든 값. `EXPAND` 외에는 null |
| `content[].adjcEmdCnt` · `cvlcptRdctRtRng` · `wrnngCn` | - | 저장하지 않는 값이라 항상 null |
| `content[].vrblAjmt` | Object \| null | 저장 당시 계수 적용 내역. 이 값을 저장하기 전 결과는 null |
| `page` / `size` | int | 요청 페이지 번호 / 크기 |
| `totalElements` | long | 전체 건수 |
| `totalPages` | int | 전체 페이지 수 |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status, error, path}` | `page` 0 미만, `size` 1~100 범위 밖, 숫자가 아닌 값 |
| 401 | 없음 | 로그인 세션 없음 |
| 401 | `{timestamp, status, error, path}` | 세션 사용자가 `TB_USER` 에 없거나 사용 중지 상태 |

## 8. DELETE /api/analysis/policy-sim/results/{rsltId}

### API 개요

로그인 사용자의 저장 결과를 삭제한다. `TB_PLCY_SMLT_RSLT` 1행과 연결된 `TB_ANLS_EXCN` 1행을 한 트랜잭션으로 지운다.

로그인 세션과 CSRF 토큰이 필요하며, 본인이 저장한 결과만 삭제할 수 있다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | - | 로그인 세션 |
| cookie | `XSRF-TOKEN` | String | 필수 | - | CSRF 토큰 쿠키 |
| header | `X-XSRF-TOKEN` | String | 필수 | - | `XSRF-TOKEN` 쿠키와 같은 값 |
| path | `rsltId` | Long | 필수 | - | 결과 ID |

### 호출 예시

```bash
curl -X DELETE 'http://localhost:8180/api/analysis/policy-sim/results/12' \
  -b 'JSESSIONID=<세션ID>; XSRF-TOKEN=<토큰>' \
  -H 'X-XSRF-TOKEN: <토큰>'
```

### 응답

`204 No Content`. 본문 없음.

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status, error, path}` | `rsltId` 가 숫자가 아님 |
| 401 | 없음 | 로그인 세션 없음 |
| 401 | `{timestamp, status, error, path}` | 세션 사용자가 `TB_USER` 에 없거나 사용 중지 상태 |
| 403 | `{timestamp, status, error, path}` | CSRF 토큰 누락 또는 불일치 |
| 404 | `{timestamp, status, error, path}` | 결과가 없거나, 다른 사용자의 결과이거나, `POLICY_SIM` 실행이 아님 |

## 9. POST /api/analysis/policy-sim/reports

### API 개요

시나리오 1~15건을 묶어 비교 보고서를 만든다. 저장된 시나리오는 저장값을 그대로 쓰고, 저장되지 않은 시나리오는 서버가 다시 계산한다. `TB_ANLS_EXCN`·`TB_ANLS_RSLT`·`TB_RPTP` 에 1행씩 한 트랜잭션으로 저장한다.

로그인 세션과 CSRF 토큰이 필요하다. 저장된 시나리오는 본인 결과만 쓸 수 있다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | - | 로그인 세션 |
| cookie | `XSRF-TOKEN` | String | 필수 | - | CSRF 토큰 쿠키 |
| header | `X-XSRF-TOKEN` | String | 필수 | - | `XSRF-TOKEN` 쿠키와 같은 값 |
| header | `Content-Type` | String | 필수 | - | `application/json` |
| body | `fileFmt` | String | 필수 | - | `PDF` · `DOCX` |
| body | `headIdx` | Integer | 필수 | - | 대표 시나리오의 `scenarios` 인덱스(0부터) |
| body | `scenarios[]` | Array | 필수 | - | 시나리오 1~15건. 항목마다 `rsltId` 와 `cond` 중 정확히 하나를 보낸다 |
| body | `scenarios[].rsltId` | Long | 조건부 | - | 저장된 결과 ID. 같은 ID 를 중복해서 보낼 수 없다 |
| body | `scenarios[].cond` | Object | 조건부 | - | 계산 조건. 필드와 규칙은 6번 API 의 body 와 같다 |

### 호출 예시

```bash
curl -X POST 'http://localhost:8180/api/analysis/policy-sim/reports' \
  -b 'JSESSIONID=<세션ID>; XSRF-TOKEN=<토큰>' \
  -H 'X-XSRF-TOKEN: <토큰>' \
  -H 'Content-Type: application/json' \
  -d '{
    "fileFmt": "PDF",
    "headIdx": 0,
    "scenarios": [
      { "rsltId": 12 },
      { "cond": { "plcyType": "FEE", "feeAplcnMthd": "CONVERT", "predcMm": 12, "sggNm": "제주시" } }
    ]
  }'
```

### 응답

`201 Created` · `application/json`

```json
{
  "rptpId": 101,
  "excnId": 345,
  "rptpNm": "20260105(1)_정책 시뮬레이션 시나리오 비교",
  "anlsPrdExpln": "예측 기간 12개월",
  "fileFmt": "PDF",
  "crtYmd": "2026-01-05T10:21:03.123",
  "headSeq": 1,
  "scenarios": [
    {
      "seq": 1,
      "rcmdYn": true,
      "rsltId": 12,
      "params": {
        "plcyType": "EXPAND",
        "expandMethod": "VACLOT",
        "addPkltSlot": 100,
        "roadMtncType": null,
        "roadMtncLen": null,
        "feeAplcnMthd": null,
        "feeAplcnZone": null,
        "feeIncrsRt": null,
        "predcMm": 12,
        "sggNm": "제주시",
        "emdCd": null,
        "vrblAjmt": null
      },
      "result": { "anlsRngCn": "제주시 전체", "cvlcptRdctRt": -27.70 }
    }
  ]
}
```

위 JSON 의 `result` 는 일부 필드만 보여 준다.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `rptpId` / `excnId` | Long | 보고서 ID / 분석 실행 ID |
| `rptpNm` | String | `{YYYYMMDD}({n})_정책 시뮬레이션 시나리오 비교`. `n` 은 같은 생성자의 그날 순번 |
| `anlsPrdExpln` | String | `예측 기간 {predcMm}개월`. 대표 시나리오 기준 |
| `fileFmt` | String | 요청한 파일 형식 |
| `crtYmd` | LocalDateTime | 생성 일시 (ISO-8601) |
| `headSeq` | Integer | 대표 시나리오 순번(`headIdx` + 1) |
| `scenarios[].seq` | Integer | 시나리오 순번. 요청 순서, 1부터 |
| `scenarios[].rcmdYn` | boolean | 추천 시나리오 여부. 최대 1건 |
| `scenarios[].rsltId` | Long \| null | 저장된 결과 ID. 재계산한 시나리오는 null |
| `scenarios[].params` | Object | 요청 조건 12개 키. 해당 정책 유형이 쓰지 않는 키는 null |
| `scenarios[].result` | Object | 5번 API 응답과 같은 구조 |

추천은 민원이 감소한 시나리오 중 감소 폭이 가장 큰 것을 고른다. 동률이면 비용 수준이 낮은 쪽(`LOW` → `MID` → `HIGH` → `TBD`), 그래도 같으면 요청 순서가 앞선 쪽이다.

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status, error, path}` | 본문 형식 오류, `fileFmt` 누락·허용 밖, `scenarios` 0건 또는 16건 이상, `headIdx` 범위 밖 |
| 400 | `{timestamp, status, error, path}` | 항목에 `rsltId`·`cond` 가 둘 다 있거나 둘 다 없음, 같은 `rsltId` 중복, `cond` 검증 실패 |
| 400 | `{timestamp, status, error, path}` | 재계산 결과의 `calcSttsCd` 가 `NO_ENF`·`NO_SPLY` |
| 401 | 없음 | 로그인 세션 없음 |
| 401 | `{timestamp, status, error, path}` | 세션 사용자가 `TB_USER` 에 없거나 사용 중지 상태 |
| 403 | `{timestamp, status, error, path}` | CSRF 토큰 누락 또는 불일치 |
| 404 | `{timestamp, status, error, path}` | `rsltId` 가 없거나 다른 사용자의 결과 |
| 503 | `{"code":"POLICY_SIM_PARAM_MISSING"}` | 재계산에 필요한 `POLICY_SIM` 계수 행이 없거나 쓸 값이 없음 |
