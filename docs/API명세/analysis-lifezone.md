# 생활권 시뮬레이션 분석 API

## 1. GET /api/analysis/lifezone

### API 개요

기간 내 민원 좌표를 격자 마스터(`TB_GRID`)에 공간조인해 격자별 민원 건수를 집계하고, 조회 결과의 최대값을 5등분한 경계로 5등급을 매긴다. 로그인 세션이 필요하며 메뉴 권한은 검사하지 않는다.

분석 결과는 저장하지 않는다. 다만 `ranking` 행의 구역명을 얻으려고 VWorld를 호출할 수 있고(요청당 기본 50좌표, `analysis.zone-name.max-lookups-per-run`), 조회 결과는 `TB_ZONE_NM_CACHE`에 별도 트랜잭션으로 저장된다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | - | 로그인 세션 |
| query | `bgngYmd` | String | 필수 | - | 분석 시작일. `YYYYMMDD`, 실재하는 날짜 |
| query | `endYmd` | String | 필수 | - | 분석 종료일(당일 포함). `YYYYMMDD`, 시작일 이후, 시작일 포함 최대 366일 |
| query | `anlsTrgtCd` | String | 선택 | `CVLCPT_CNT` | 분석내용코드. 이 경로는 `CVLCPT_CNT`만 처리한다. `SPLY_DMND`, `CNGST`는 400 |
| query | `gridSz` | int | 선택 | `500` | 격자크기(m). `1000`, `500`, `250`, `100` |
| query | `clsfMthd` | String | 선택 | `EQUAL_INTERVAL` | 등급 분류방법. `EQUAL_INTERVAL`만 허용(대소문자 무시). 빈 값은 기본값 |
| query | `sggNm` | String | 선택 | 전 지역 | 시군구명. 등록된 이름만 허용. 빈 문자열은 400 |
| query | `emdCd` | String | 선택 | 시군구 전체 | 읍면동코드. 등록된 코드만 허용. 빈 문자열은 400. `sggNm`과 함께 오면 집계에는 `emdCd`가 우선한다 |
| query | `limit` | Integer | 선택 | 상한 없음 | `ranking` 최대 행 수. 1 이상 |

### 호출 예시

```bash
curl -G 'http://localhost:8180/api/analysis/lifezone' \
  -b 'JSESSIONID=<세션ID>' \
  --data-urlencode 'bgngYmd=20260101' \
  --data-urlencode 'endYmd=20260131' \
  --data-urlencode 'gridSz=500' \
  --data-urlencode 'sggNm=제주시'
```

### 응답

`200 OK`, `application/json`. 공통 래퍼 없이 아래 객체를 그대로 반환한다. 결과가 0건이어도 200이며 `cells`, `ranking`은 빈 배열, `gradeCriteria`는 5행이다.

```json
{
  "bgngYmd": "20260101",
  "endYmd": "20260131",
  "sggNm": "제주시",
  "emdCd": null,
  "anlsTrgtCd": "CVLCPT_CNT",
  "anlsTrgtNm": "민원건수",
  "gridSz": 500,
  "gridSzNm": "500m",
  "clsfMthd": "EQUAL_INTERVAL",
  "clsfMthdNm": "등간격",
  "unit": "건",
  "sectionTitle": "민원 집중 구역",
  "sectionSub": "격자 기반 민원 집중 구역 (심각, 경고, 주의)",
  "availableTargets": [
    {
      "anlsTrgtCd": "CVLCPT_CNT",
      "anlsTrgtNm": "민원건수",
      "unit": "건",
      "available": true,
      "unavailableRsn": null,
      "gridSzs": [1000, 500, 250, 100],
      "dfltGridSz": 500,
      "anlsPathSfx": "",
      "clsfMthds": ["EQUAL_INTERVAL"],
      "dfltClsfMthd": "EQUAL_INTERVAL",
      "dataBgngYmd": null,
      "dataEndYmd": null
    }
  ],
  "gradeScale": { "cap": 160, "width": 32, "method": "EQUAL_INTERVAL" },
  "gradeCriteria": [
    { "grade": "SEVERE", "gradeNm": "심각", "minVl": 128, "lgndTxt": "심각 (128건 이상)" },
    { "grade": "WARN", "gradeNm": "경고", "minVl": 96, "lgndTxt": "경고 (96~127건)" },
    { "grade": "CAUTION", "gradeNm": "주의", "minVl": 64, "lgndTxt": "주의 (64~95건)" },
    { "grade": "NORMAL", "gradeNm": "보통", "minVl": 32, "lgndTxt": "보통 (32~63건)" },
    { "grade": "LOW", "gradeNm": "낮음", "minVl": 1, "lgndTxt": "낮음 (1~31건)" }
  ],
  "weights": [],
  "totGridCnt": 1,
  "severeCnt": 1,
  "warnCnt": 0,
  "cautionCnt": 0,
  "normalCnt": 0,
  "lowCnt": 0,
  "dataBgngYmd": "20251104",
  "dataEndYmd": "20260831",
  "cells": [
    {
      "gridCd": "가나1234",
      "anlsVl": 160,
      "grade": "SEVERE",
      "ctrLat": 33.5,
      "ctrLot": 126.5,
      "ring": [[33.498, 126.498], [33.498, 126.502], [33.502, 126.502], [33.502, 126.498]],
      "emdNm": "가상동",
      "cntrPoiNm": "가상동 격자 가나1234"
    }
  ],
  "ranking": [
    {
      "rnk": 1,
      "gridCd": "가나1234",
      "cntrPoiNm": "가상동 예시사거리",
      "anlsVl": 160,
      "grade": "SEVERE",
      "ctrLat": 33.5,
      "ctrLot": 126.5,
      "dtl": [
        { "cvlcptTypeCd": "01", "cvlcptTypeNm": "예시 민원유형", "cnt": 90 }
      ]
    }
  ]
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `bgngYmd`, `endYmd` | String | N | 적용된 기간(`YYYYMMDD`) |
| `sggNm` | String | Y | 요청한 시군구명. 없으면 `null` |
| `emdCd` | String | Y | 요청한 읍면동코드. 없으면 `null` |
| `anlsTrgtCd`, `anlsTrgtNm` | String | N | 분석내용코드, 분석유형명 |
| `gridSz` | int | N | 격자크기(m) |
| `gridSzNm` | String | N | 격자 표시명. `1000`은 `1km`, 그 밖은 `{n}m` |
| `clsfMthd`, `clsfMthdNm` | String | N | 적용 분류방법, 분류방법명 |
| `unit` | String | N | 값 단위(`건`) |
| `sectionTitle`, `sectionSub` | String | N | 결과 패널 제목, 부제 |
| `availableTargets[]` | Array | N | 분석유형 3종. 필드는 4번 API 응답과 같고 `dataBgngYmd`, `dataEndYmd`는 항상 `null` |
| `gradeScale.cap` | int | N | 조회 결과 최대값. 0건이면 `0` |
| `gradeScale.width` | int | N | 구간 폭 `max(1, ceil(cap / 5))` |
| `gradeScale.method` | String | N | 분류방법(`clsfMthd`와 같은 값) |
| `gradeCriteria[].grade` | String | N | `SEVERE`, `WARN`, `CAUTION`, `NORMAL`, `LOW` 순서 |
| `gradeCriteria[].gradeNm` | String | N | 심각, 경고, 주의, 보통, 낮음 |
| `gradeCriteria[].minVl` | int | N | 등급 하한(이상). 기본 경계는 `[1, w, 2w, 3w, 4w]`이고, 겹치면 앞 경계 + 1로 올린다. 0건이면 낮음부터 `1, 2, 3, 4, 5` |
| `gradeCriteria[].lgndTxt` | String | N | 범례 문구 |
| `weights` | Array | N | 항상 빈 배열 |
| `totGridCnt` | int | N | 값이 1 이상인 격자 수(`cells` 길이) |
| `severeCnt`, `warnCnt`, `cautionCnt`, `normalCnt`, `lowCnt` | int | N | 등급별 격자 수 |
| `dataBgngYmd`, `dataEndYmd` | String | Y | 미분류(`99`)를 뺀 민원의 `ocrn_dt` 보유 구간. 원천이 비면 `null` |
| `cells[]` | Array | N | 값이 1 이상인 격자 전량. `anlsVl` 내림차순, `gridCd` 오름차순 |
| `cells[].gridCd` | String | N | 격자코드 |
| `cells[].anlsVl` | int | N | 미분류(`99`)를 뺀 민원 건수 |
| `cells[].grade` | String | N | 등급코드 |
| `cells[].ctrLat`, `cells[].ctrLot` | Number | N | 격자 중심 위도, 경도(EPSG:4326) |
| `cells[].ring` | Array | N | 꼭짓점 `[[위도, 경도], …]`. 닫는 점 제외. 파싱에 실패하면 빈 배열 |
| `cells[].emdNm` | String | Y | 격자 중심이 속한 읍면동명 |
| `cells[].cntrPoiNm` | String | N | 구역명 캐시 이름. 없으면 `{읍면동명} 격자 {격자코드}`, 읍면동이 없으면 `격자 {격자코드}` |
| `ranking[]` | Array | N | 심각, 경고, 주의 격자. `cells` 순서를 따르며 `limit`까지 |
| `ranking[].rnk` | int | N | 1부터 시작하는 순번 |
| `ranking[].gridCd` | String | N | 격자코드 |
| `ranking[].cntrPoiNm` | String | N | POI 또는 역지오코딩 구역명. 얻지 못하면 `cells[].cntrPoiNm`과 같은 값 |
| `ranking[].anlsVl`, `ranking[].grade` | int, String | N | 분석값, 등급코드 |
| `ranking[].ctrLat`, `ranking[].ctrLot` | Number | N | 격자 중심 좌표 |
| `ranking[].dtl[]` | Array | N | 건수 상위 최대 2개 민원유형. 건수 내림차순, 동점은 코드 오름차순 |
| `ranking[].dtl[].cvlcptTypeCd`, `ranking[].dtl[].cvlcptTypeNm` | String | N | 민원유형코드, 유형명(`CVLCPT_TYPE_CD`) |
| `ranking[].dtl[].cnt` | int | N | 해당 유형 건수 |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":400,"error":"Bad Request","path":"/api/analysis/lifezone"}` | 기간 누락, 형식 오류, 실재하지 않는 날짜<br>종료일이 시작일보다 빠름, 366일 초과<br>`anlsTrgtCd` 미지원 또는 전용 경로 유형(`SPLY_DMND`, `CNGST`)<br>`gridSz` 목록 밖, 숫자 아님<br>`clsfMthd` 허용 목록 밖<br>`sggNm`, `emdCd`가 빈 값이거나 미등록<br>`limit`이 1 미만이거나 숫자 아님 |
| 401 | 본문 없음 | 로그인 세션 없음 |
| 500 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":500,"error":"Internal Server Error","path":"/api/analysis/lifezone"}` | DB 조회 실패 등 처리되지 않은 서버 오류 |

## 2. GET /api/analysis/lifezone/supply

### API 개요

민원 발생일시(`ocrn_dt`) 기준으로 주차 민원(유형 `01`~`05`)과 공영주차면(`PBLPKLT`, `ONSTR`, `VACLOT`)을 EPSG:5179 계산 격자에 집계하고, 부족률 `민원 ÷ (공영주차면 + 민원)`으로 5등급을 판정한다. 월평균 민원이 최소 기준 이상인 격자만 평가한다(`민원 × 30 ≥ 기준 × 조회 일수`). 결과값은 주차 부족 면수가 아니라 공영주차 공급 대비 민원 발생량이며, 응답의 `disclaimer`를 화면에 상시 표기한다.

로그인 세션이 필요하며 메뉴 권한은 검사하지 않는다. 분석 결과와 설정값은 저장하지 않는다. 심각, 경고, 주의 격자의 구역명을 얻으려고 VWorld를 호출할 수 있고(요청당 기본 50좌표), 조회 결과는 `TB_ZONE_NM_CACHE`에 별도 트랜잭션으로 저장된다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | - | 로그인 세션 |
| query | `bgngYmd` | String | 필수 | - | 분석 시작일. `YYYYMMDD`, 실재하는 날짜 |
| query | `endYmd` | String | 필수 | - | 분석 종료일(당일 포함). `YYYYMMDD`, 시작일 이후, 시작일 포함 최대 366일 |
| query | `gridSz` | int | 선택 | `500` | 격자크기(m). `1000`, `500`, `250`, `100` |
| query | `sggNm` | String | 선택 | 전 지역 | 시군구명. 등록된 이름만 허용. 빈 문자열은 400. 민원에만 적용하고 공급에는 적용하지 않는다 |
| query | `emdCd` | String | 선택 | 시군구 전체 | 읍면동코드. 등록된 코드만 허용. 빈 문자열은 400. `sggNm`과 함께 오면 `emdCd`가 우선한다 |
| query | `vrblVls` | String(JSON) | 선택 | 설정값 | 이번 요청에만 적용할 계수 JSON 객체. 허용 키: `SPLY_MIN_MNTH_AVG_CVLCPT`(초기 범위 1~30), `SPLY_GRD_BNDRY1`~`SPLY_GRD_BNDRY4`(초기 범위 1~99). 모두 정수이고 경계는 설정값과 합친 결과가 엄격히 증가해야 한다. 범위는 `TB_ANLS_VRBL_STNG`의 `min_vl`, `max_vl`을 따른다 |

### 호출 예시

```bash
curl -G 'http://localhost:8180/api/analysis/lifezone/supply' \
  -b 'JSESSIONID=<세션ID>' \
  --data-urlencode 'bgngYmd=20260101' \
  --data-urlencode 'endYmd=20260131' \
  --data-urlencode 'gridSz=500' \
  --data-urlencode 'vrblVls={"SPLY_MIN_MNTH_AVG_CVLCPT":3,"SPLY_GRD_BNDRY4":85}'
```

### 응답

`200 OK`, `application/json`. 공통 래퍼 없이 아래 객체를 그대로 반환한다. 결과가 0건이어도 200이며 `cells`는 빈 배열, `gradeCriteria`는 5행이다.

```json
{
  "bgngYmd": "20260101",
  "endYmd": "20260131",
  "sggNm": null,
  "emdCd": null,
  "anlsTrgtCd": "SPLY_DMND",
  "anlsTrgtNm": "주차 수급",
  "gridSz": 500,
  "gridSzNm": "500m",
  "crs": "EPSG:5179",
  "sectionTitle": "주차 수급 부족 구역",
  "sectionSub": "격자 기반 수요·공급 격차 (심각, 경고, 주의)",
  "disclaimer": "공영주차장 기준이며 민영·부설주차장은 포함되지 않습니다. 민원 발생량 기준으로, 주차 수요를 직접 측정한 값이 아닙니다.",
  "anlsMthd": "SHRT_RT",
  "severeCnt": 1,
  "warnCnt": 0,
  "cautionCnt": 0,
  "normalCnt": 0,
  "lowCnt": 0,
  "zeroSplyGridCnt": 1,
  "minMnthAvgCvlcpt": 3,
  "gradeCriteria": [
    { "grade": "SEVERE", "gradeNm": "심각", "minShrtRt": 85, "gridCnt": 1, "cvlcptCnt": 12, "zeroSplyGridCnt": 1, "lgndTxt": "심각 (85% 이상)" },
    { "grade": "WARN", "gradeNm": "경고", "minShrtRt": 60, "gridCnt": 0, "cvlcptCnt": 0, "zeroSplyGridCnt": 0, "lgndTxt": "경고 (60~85%)" },
    { "grade": "CAUTION", "gradeNm": "주의", "minShrtRt": 40, "gridCnt": 0, "cvlcptCnt": 0, "zeroSplyGridCnt": 0, "lgndTxt": "주의 (40~60%)" },
    { "grade": "NORMAL", "gradeNm": "보통", "minShrtRt": 20, "gridCnt": 0, "cvlcptCnt": 0, "zeroSplyGridCnt": 0, "lgndTxt": "보통 (20~40%)" },
    { "grade": "LOW", "gradeNm": "낮음", "minShrtRt": 0, "gridCnt": 0, "cvlcptCnt": 0, "zeroSplyGridCnt": 0, "lgndTxt": "낮음 (20% 미만)" }
  ],
  "vrblAjmt": {
    "ajmtYn": "Y",
    "vrbls": [
      { "vrblCd": "SPLY_MIN_MNTH_AVG_CVLCPT", "vrblNm": "수급 최소 월평균 민원", "unitNm": "건", "bscStngVl": 5, "stngVl": 5, "aplcnVl": 3, "expsrYn": "Y" }
    ]
  },
  "totGridCnt": 1,
  "totCvlcptCnt": 12,
  "totPkltCpctySum": 0,
  "dataBgngYmd": "20251104",
  "dataEndYmd": "20260831",
  "cells": [
    {
      "gridId": "500m_1800_3000",
      "grade": "SEVERE",
      "gradeNm": "심각",
      "shortageRatio": 1.0000,
      "demandIndex": 12,
      "rnk": 1,
      "sggNm": "제주시",
      "emdNm": "가상동",
      "cntrPoiNm": "가상동 예시사거리",
      "emdCd": "50110000",
      "cvlcptCnt": 12,
      "pkltCpctySum": 0,
      "pkltCnt": 0,
      "dstncPtCnt": 4,
      "cncntWarnYn": "N",
      "ctrLat": 33.5,
      "ctrLot": 126.5,
      "ring": [[33.498, 126.498], [33.498, 126.502], [33.502, 126.502], [33.502, 126.498]]
    }
  ]
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `bgngYmd`, `endYmd` | String | N | 적용된 기간(`YYYYMMDD`) |
| `sggNm`, `emdCd` | String | Y | 요청한 지역 조건. 없으면 `null` |
| `anlsTrgtCd`, `anlsTrgtNm` | String | N | `SPLY_DMND`, `주차 수급` |
| `gridSz`, `gridSzNm` | int, String | N | 격자크기, 표시명 |
| `crs` | String | N | 격자 좌표계(`EPSG:5179`) |
| `sectionTitle`, `sectionSub` | String | N | 결과 패널 제목, 부제 |
| `disclaimer` | String | N | 상시 표기 안내 문구 |
| `gridNotice` | String | 생략 가능 | 100m 격자에서만 내려가는 해석 주의 문구. 그 밖에는 키 생략 |
| `anlsMthd` | String | N | 분석 방법. 항상 `SHRT_RT` |
| `severeCnt`, `warnCnt`, `cautionCnt`, `normalCnt`, `lowCnt` | int | N | 등급별 격자 수 |
| `zeroSplyGridCnt` | int | N | 공영주차면이 0인 격자 수 |
| `minMnthAvgCvlcpt` | int | N | 적용된 월평균 민원 최소 기준 |
| `gradeCriteria[].grade`, `gradeCriteria[].gradeNm` | String | N | 등급코드, 등급명. 심각부터 낮음 순서 |
| `gradeCriteria[].minShrtRt` | int | N | 부족률 하한(%, 포함). 낮음은 `0` |
| `gradeCriteria[].gridCnt` | int | N | 등급별 격자 수 |
| `gradeCriteria[].cvlcptCnt` | int | N | 등급별 민원 합계 |
| `gradeCriteria[].zeroSplyGridCnt` | int | N | 등급별 공영주차면 0 격자 수 |
| `gradeCriteria[].lgndTxt` | String | N | 범례 문구 |
| `vrblAjmt.ajmtYn` | String | N | 입력값이 설정값과 다른 행이 있으면 `Y`, 아니면 `N` |
| `vrblAjmt.vrbls[]` | Array | N | 허용 변수 중 `use_yn='Y'`인 행 전체(조정하지 않은 행 포함) |
| `vrblAjmt.vrbls[].vrblCd`, `vrblAjmt.vrbls[].vrblNm` | String | Y | 변수코드, 변수명 |
| `vrblAjmt.vrbls[].unitNm` | String | Y | 단위 |
| `vrblAjmt.vrbls[].bscStngVl` | Number | Y | 기본설정값 |
| `vrblAjmt.vrbls[].stngVl` | Number | Y | 현재 설정값 |
| `vrblAjmt.vrbls[].aplcnVl` | Number | Y | 입력값이 있으면 입력값, 없으면 설정값 |
| `vrblAjmt.vrbls[].expsrYn` | String | N | 노출 여부(`Y`, `N`) |
| `totGridCnt` | int | N | 최소 기준을 통과한 격자 수(`cells` 길이) |
| `totCvlcptCnt` | int | N | 통과 격자의 민원 합계 |
| `totPkltCpctySum` | int | N | 통과 격자의 공영주차면 합계 |
| `dataBgngYmd`, `dataEndYmd` | String | Y | 유형 `01`~`05`, 좌표 유효 민원의 `ocrn_dt` 보유 구간 |
| `cells[]` | Array | N | 최소 기준 통과 격자 전량. `rnk` 오름차순 |
| `cells[].gridId` | String | N | `{격자크기}m_{gx}_{gy}` |
| `cells[].grade`, `cells[].gradeNm` | String | N | 등급코드, 등급명. 반올림 전 값으로 `민원 × 100 ≥ 경계 × 수요지수`를 판정 |
| `cells[].shortageRatio` | Number | N | 부족률(0~1, 소수 4자리) |
| `cells[].demandIndex` | int | N | 수요지수 = 공영주차면 + 민원 |
| `cells[].rnk` | int | N | 전체 순위. 면수 ÷ 민원 오름차순, 민원 내림차순, `gx`, `gy` 순 |
| `cells[].sggNm`, `cells[].emdNm`, `cells[].emdCd` | String | Y | 격자 내 최빈 읍면동 기준 지역 정보 |
| `cells[].cntrPoiNm` | String | 생략 가능 | 심각, 경고, 주의는 POI 또는 역지오코딩 이름, 보통, 낮음은 캐시 이름. 없으면 키 생략 |
| `cells[].cvlcptCnt` | int | N | 주차 민원 건수 |
| `cells[].pkltCpctySum`, `cells[].pkltCnt` | int | N | 공영주차면 수, 공영주차장 수 |
| `cells[].dstncPtCnt` | int | N | 민원 고유 좌표 수 |
| `cells[].cncntWarnYn` | String | N | 고유 좌표 3개 이하이고 민원 50건 이상이면 `Y` |
| `cells[].ctrLat`, `cells[].ctrLot` | Number | N | 격자 중심 좌표(EPSG:4326) |
| `cells[].ring` | Array | N | 꼭짓점 `[[위도, 경도], …]`. 닫는 점 제외. 파싱에 실패하면 빈 배열 |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":400,"error":"Bad Request","path":"/api/analysis/lifezone/supply"}` | 기간 누락, 형식 오류, 실재하지 않는 날짜<br>종료일이 시작일보다 빠름, 366일 초과<br>`gridSz` 목록 밖, 숫자 아님<br>`sggNm`, `emdCd`가 빈 값이거나 미등록<br>`vrblVls`가 숫자 값의 JSON 객체가 아니거나 키 중복<br>허용 목록 밖 코드, 설정 행 없음, `expsr_yn='N'`, `use_yn='N'`, 값 없음<br>범위 밖, 소수, 경계 역전 |
| 401 | 본문 없음 | 로그인 세션 없음 |
| 500 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":500,"error":"Internal Server Error","path":"/api/analysis/lifezone/supply"}` | DB 조회 실패 등 처리되지 않은 서버 오류 |

## 3. GET /api/analysis/lifezone/congestion

### API 개요

단속(`crdn_dt`)과 민원(`ocrn_dt`)을 EPSG:5179 계산 격자에 집계해 공영주차 공급이 있는 격자의 혼잡 점수를 산정한다. 실측 점유율이 아니라 단속과 민원에 기반한 대리지표다.

```text
월 단속 = (PDA + 수동 + 고정형 가중치 × 고정형) ÷ 개월수
월 민원 = 민원 ÷ 개월수
추정량 = (적발 배수 × 월 단속 + 민원 가중치 × 월 민원) ÷ 30 × 좌표 결측 보정
H = 기준 점유율 + 추정량 ÷ 공영주차면
anlsVl = round(H × 10000)  (혼잡 점수 = anlsVl ÷ 100)
```

추정량이 0인 격자와 면수가 `MIN_CPCTY_VL` 미만인 격자는 등급 계산 전에 결과에서 제외한다. 로그인 세션이 필요하며 메뉴 권한은 검사하지 않는다. 분석 결과와 설정값은 저장하지 않는다. `ranking` 행의 구역명을 얻으려고 VWorld를 호출할 수 있고(요청당 기본 50좌표), 조회 결과는 `TB_ZONE_NM_CACHE`에 별도 트랜잭션으로 저장된다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | - | 로그인 세션 |
| query | `bgngYmd` | String | 필수 | - | 분석 시작일. `YYYYMMDD`, 실재하는 날짜 |
| query | `endYmd` | String | 필수 | - | 분석 종료일(당일 포함). `YYYYMMDD`, 시작일 이후, 시작일 포함 최대 366일 |
| query | `gridSz` | int | 선택 | `500` | 격자크기(m). `1000`, `500`, `250`, `100` |
| query | `sggNm` | String | 선택 | 전 지역 | 시군구명. 격자의 최빈 읍면동(공영주차장 기준)으로 격자를 고른다. 빈 문자열은 400 |
| query | `emdCd` | String | 선택 | 시군구 전체 | 읍면동코드. 격자의 최빈 읍면동과 비교한다. 빈 문자열은 400. `sggNm`과 함께 오면 `emdCd`가 우선한다 |
| query | `clsfMthd` | String | 선택 | `CNGST_ABSOLUTE` | 등급 분류방법. `CNGST_ABSOLUTE`만 허용(대소문자 무시) |
| query | `limit` | Integer | 선택 | 상한 없음 | `ranking` 최대 행 수. 1 이상 |
| query | `vrblVls` | String(JSON) | 선택 | 설정값 | 이번 요청에만 적용할 계수 JSON 객체. 초기 설정 기준 조정 가능 키: `CNGST_GRD_BNDRY1`~`CNGST_GRD_BNDRY4`(50~100점, 엄격히 증가), `GEO_CVRG_CRCT_VL`(1~4). 소수 넷째 자리까지 허용. `PCSHR_CRTR_VL`, `DSCL_MLTP`, `FIX_WGVL`, `CVLCPT_WGVL`, `MIN_CPCTY_VL`은 초기 설정이 `expsr_yn='N'`이라 400이다 |

### 호출 예시

```bash
curl -G 'http://localhost:8180/api/analysis/lifezone/congestion' \
  -b 'JSESSIONID=<세션ID>' \
  --data-urlencode 'bgngYmd=20260101' \
  --data-urlencode 'endYmd=20260131' \
  --data-urlencode 'gridSz=500' \
  --data-urlencode 'vrblVls={"CNGST_GRD_BNDRY4":98}'
```

### 응답

`200 OK`, `application/json`. 공통 래퍼 없이 아래 객체를 그대로 반환한다. 결과가 0건이어도 200이며 `cells`, `ranking`은 빈 배열이다.

```json
{
  "bgngYmd": "20260101",
  "endYmd": "20260131",
  "sggNm": null,
  "emdCd": null,
  "anlsTrgtCd": "CNGST",
  "anlsTrgtNm": "혼잡도",
  "gridSz": 500,
  "gridSzNm": "500m",
  "clsfMthd": "CNGST_ABSOLUTE",
  "clsfMthdNm": "혼잡 1.0 기준",
  "availableClsfMthds": ["CNGST_ABSOLUTE"],
  "unit": "점",
  "sectionTitle": "혼잡 집중 구역",
  "sectionSub": "격자 기반 혼잡도 분석 (심각, 경고, 주의)",
  "availableTargets": [],
  "gradeScale": { "cap": 10250, "width": 0, "method": "CNGST_ABSOLUTE" },
  "gradeCriteria": [
    { "grade": "SEVERE", "gradeNm": "심각", "minVl": 9800, "lgndTxt": "심각 (98점 이상)" },
    { "grade": "WARN", "gradeNm": "경고", "minVl": 9600, "lgndTxt": "경고 (96~98점)" },
    { "grade": "CAUTION", "gradeNm": "주의", "minVl": 9200, "lgndTxt": "주의 (92~96점)" },
    { "grade": "NORMAL", "gradeNm": "보통", "minVl": 8800, "lgndTxt": "보통 (88~92점)" },
    { "grade": "LOW", "gradeNm": "낮음", "minVl": 1, "lgndTxt": "낮음 (88점 미만)" }
  ],
  "weights": [],
  "totGridCnt": 1,
  "grdTrgtGridCnt": 1,
  "noEnfGridCnt": 2,
  "refGridCnt": 1,
  "over1GridCnt": 1,
  "occUsedVl": 0.85,
  "vrblAjmt": {
    "ajmtYn": "Y",
    "vrbls": [
      { "vrblCd": "CNGST_GRD_BNDRY4", "vrblNm": "혼잡 경계 심각", "unitNm": "점", "bscStngVl": 100, "stngVl": 100, "aplcnVl": 98, "expsrYn": "Y" }
    ]
  },
  "severeCnt": 1,
  "warnCnt": 0,
  "cautionCnt": 0,
  "normalCnt": 0,
  "lowCnt": 0,
  "dataBgngYmd": "20250201",
  "dataEndYmd": "20261030",
  "cells": [
    {
      "gridCd": "500m_1800_3000",
      "anlsVl": 10250,
      "grade": "SEVERE",
      "ctrLat": 33.5,
      "ctrLot": 126.5,
      "ring": [[33.498, 126.498], [33.498, 126.502], [33.502, 126.502], [33.502, 126.498]],
      "emdNm": "가상동",
      "cntrPoiNm": "가상동 격자 500m_1800_3000",
      "pkltCpctySum": 120,
      "pkltCnt": 3,
      "crdnCnt": 42,
      "cvlcptCnt": 15,
      "excsPrkPrsmpVl": 3.4,
      "noEnfYn": "N",
      "refYn": "N"
    }
  ],
  "ranking": [
    {
      "rnk": 1,
      "gridCd": "500m_1800_3000",
      "cntrPoiNm": "가상동 예시사거리",
      "anlsVl": 10250,
      "grade": "SEVERE",
      "ctrLat": 33.5,
      "ctrLot": 126.5,
      "dtl": []
    }
  ]
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `bgngYmd`, `endYmd` | String | N | 적용된 기간(`YYYYMMDD`) |
| `sggNm`, `emdCd` | String | Y | 요청한 지역 조건. 없으면 `null` |
| `anlsTrgtCd`, `anlsTrgtNm` | String | N | `CNGST`, `혼잡도` |
| `gridSz`, `gridSzNm` | int, String | N | 격자크기, 표시명 |
| `clsfMthd`, `clsfMthdNm` | String | N | 적용 분류방법, 분류방법명 |
| `availableClsfMthds` | Array | N | 허용 분류방법 목록 |
| `unit` | String | N | 값 단위(`점`) |
| `sectionTitle`, `sectionSub` | String | N | 결과 패널 제목, 부제 |
| `availableTargets[]` | Array | N | 분석유형 3종. 필드는 4번 API 응답과 같고 `dataBgngYmd`, `dataEndYmd`는 항상 `null` |
| `gradeScale.cap` | int | N | 결과 격자의 최대 `anlsVl`. 없으면 `0` |
| `gradeScale.width` | int | N | `CNGST_ABSOLUTE`는 `0` |
| `gradeScale.method` | String | N | 분류방법 |
| `gradeCriteria[].grade`, `gradeCriteria[].gradeNm` | String | N | 등급코드, 등급명 |
| `gradeCriteria[].minVl` | int | N | 등급 하한(점수 × 100). 낮음은 `1` |
| `gradeCriteria[].lgndTxt` | String | N | 범례 문구. 점수 소수 첫째 자리, `.0` 생략 |
| `weights` | Array | N | 항상 빈 배열 |
| `totGridCnt`, `grdTrgtGridCnt` | int | N | 결과 격자 수(`cells` 길이). 두 값은 같다 |
| `noEnfGridCnt` | int | N | 단속과 민원 근거가 없어 제외된 격자 수. 면수 하한 미만과 겹치면 여기로 센다 |
| `refGridCnt` | int | N | 근거는 있으나 면수 하한 미만으로 제외된 격자 수 |
| `over1GridCnt` | int | N | `anlsVl > 10000`(100점 초과) 격자 수 |
| `occUsedVl` | Number | N | 적용된 기준 점유율 |
| `vrblAjmt` | Object | N | 형식은 2번 API의 `vrblAjmt`와 같다. 허용 변수 10종 중 `use_yn='Y'`인 행 |
| `severeCnt`, `warnCnt`, `cautionCnt`, `normalCnt`, `lowCnt` | int | N | 등급별 격자 수 |
| `dataBgngYmd`, `dataEndYmd` | String | Y | 좌표 유효 단속의 `crdn_dt` 보유 구간 |
| `cells[]` | Array | N | 결과 격자 전량. 원 혼잡지수 H 내림차순, 동점은 `gx`, `gy` 순 |
| `cells[].gridCd` | String | N | `{격자크기}m_{gx}_{gy}` |
| `cells[].anlsVl` | int | N | 혼잡 점수 × 100(10000 = 100점, 상한 없음) |
| `cells[].grade` | String | N | 등급코드 |
| `cells[].ctrLat`, `cells[].ctrLot` | Number | N | 격자 중심 좌표(EPSG:4326) |
| `cells[].ring` | Array | N | 꼭짓점 `[[위도, 경도], …]`. 닫는 점 제외. 파싱에 실패하면 빈 배열 |
| `cells[].emdNm` | String | Y | 공영주차장 기준 최빈 읍면동명 |
| `cells[].cntrPoiNm` | String | N | 구역명 캐시 이름. 없으면 `{읍면동명} 격자 {격자코드}`, 읍면동이 없으면 `격자 {격자코드}` |
| `cells[].pkltCpctySum`, `cells[].pkltCnt` | int | N | 공영주차면 수, 공영주차장 수 |
| `cells[].crdnCnt` | int | N | 전 수단 단속 건수 |
| `cells[].cvlcptCnt` | int | N | 같은 기간의 민원 건수 |
| `cells[].excsPrkPrsmpVl` | Number | N | 초과주차 추정량(대, 소수 1자리) |
| `cells[].noEnfYn`, `cells[].refYn` | String | N | 응답 셀에서는 항상 `N` |
| `ranking[]` | Array | N | 심각, 경고, 주의 격자. `cells` 순서를 따르며 `limit`까지. 필드는 1번 API의 `ranking[]`과 같다 |
| `ranking[].dtl` | Array | N | 항상 빈 배열 |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":400,"error":"Bad Request","path":"/api/analysis/lifezone/congestion"}` | 기간 누락, 형식 오류, 실재하지 않는 날짜<br>종료일이 시작일보다 빠름, 366일 초과<br>`gridSz` 목록 밖, 숫자 아님<br>`clsfMthd` 허용 목록 밖<br>`sggNm`, `emdCd`가 빈 값이거나 미등록<br>`limit`이 1 미만이거나 숫자 아님<br>`vrblVls`가 숫자 값의 JSON 객체가 아니거나 키 중복<br>허용 목록 밖 코드, 설정 행 없음, `expsr_yn='N'`, `use_yn='N'`, 값 없음<br>범위 밖, 소수 다섯째 자리 이상, 경계 역전 |
| 401 | 본문 없음 | 로그인 세션 없음 |
| 500 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":500,"error":"Internal Server Error","path":"/api/analysis/lifezone/congestion"}` | DB 조회 실패 등 처리되지 않은 서버 오류 |

## 4. GET /api/analysis/lifezone/targets

### API 개요

생활권 분석유형 3종의 구현 여부, 지원 격자크기, 분류방법, 조회 경로 접미사, 원천 보유 구간을 조회한다. 로그인 세션이 필요하며 메뉴 권한은 검사하지 않는다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | - | 로그인 세션 |

### 호출 예시

```bash
curl 'http://localhost:8180/api/analysis/lifezone/targets' \
  -b 'JSESSIONID=<세션ID>'
```

### 응답

`200 OK`, `application/json`. 공통 래퍼 없는 배열이며 `CVLCPT_CNT`, `SPLY_DMND`, `CNGST` 순서로 3행을 반환한다.

```json
[
  {
    "anlsTrgtCd": "CVLCPT_CNT",
    "anlsTrgtNm": "민원건수",
    "unit": "건",
    "available": true,
    "unavailableRsn": null,
    "gridSzs": [1000, 500, 250, 100],
    "dfltGridSz": 500,
    "anlsPathSfx": "",
    "clsfMthds": ["EQUAL_INTERVAL"],
    "dfltClsfMthd": "EQUAL_INTERVAL",
    "dataBgngYmd": "20251104",
    "dataEndYmd": "20260831"
  },
  {
    "anlsTrgtCd": "SPLY_DMND",
    "anlsTrgtNm": "주차 수급",
    "unit": "%",
    "available": true,
    "unavailableRsn": null,
    "gridSzs": [1000, 500, 250, 100],
    "dfltGridSz": 500,
    "anlsPathSfx": "/supply",
    "clsfMthds": [],
    "dfltClsfMthd": null,
    "dataBgngYmd": "20251104",
    "dataEndYmd": "20260831"
  },
  {
    "anlsTrgtCd": "CNGST",
    "anlsTrgtNm": "혼잡도",
    "unit": "점",
    "available": true,
    "unavailableRsn": null,
    "gridSzs": [1000, 500, 250, 100],
    "dfltGridSz": 500,
    "anlsPathSfx": "/congestion",
    "clsfMthds": ["CNGST_ABSOLUTE"],
    "dfltClsfMthd": "CNGST_ABSOLUTE",
    "dataBgngYmd": "20250201",
    "dataEndYmd": "20261030"
  }
]
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `[].anlsTrgtCd`, `[].anlsTrgtNm` | String | N | 분석내용코드, 분석유형명 |
| `[].unit` | String | N | 값 단위 |
| `[].available` | boolean | N | 구현 여부. 현재 3종 모두 `true` |
| `[].unavailableRsn` | String | Y | 미구현 사유. 구현 유형은 `null` |
| `[].gridSzs` | Array | N | 지원 격자크기(m) |
| `[].dfltGridSz` | int | N | 기본 격자크기(m) |
| `[].anlsPathSfx` | String | N | `/api/analysis/lifezone` 뒤에 붙는 조회 경로 접미사 |
| `[].clsfMthds` | Array | N | 허용 분류방법. 수급은 빈 배열 |
| `[].dfltClsfMthd` | String | Y | 기본 분류방법. 수급은 `null` |
| `[].dataBgngYmd`, `[].dataEndYmd` | String | Y | 유형별 원천 보유 구간(`YYYYMMDD`). 보유 구간 조회에 실패하면 `null`이고 응답은 200을 유지한다 |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 401 | 본문 없음 | 로그인 세션 없음 |
| 500 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":500,"error":"Internal Server Error","path":"/api/analysis/lifezone/targets"}` | 처리되지 않은 서버 오류 |

## 5. POST /api/analysis/lifezone/reports

### API 개요

요청 조건으로 민원건수 분석을 서버에서 다시 실행하고 결과를 보고서로 저장한다. `TB_ANLS_EXCN`(`anls_type_cd=LIFEZONE`), `TB_ANLS_RSLT`, `TB_RPTP`(`MANUAL`, `DONE`)에 각 1행을 한 트랜잭션으로 기록한다. 서버는 파일을 생성하지 않으며 `fileFmt`만 저장한다.

로그인 세션과 CSRF 토큰이 필요하며 메뉴 권한은 검사하지 않는다. 분석 중 VWorld 호출과 `TB_ZONE_NM_CACHE` 저장이 일어날 수 있고, 캐시 저장은 보고서 트랜잭션이 롤백되어도 유지된다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | - | 로그인 세션 |
| cookie | `XSRF-TOKEN` | String | 필수 | - | CSRF 토큰 쿠키 |
| header | `X-XSRF-TOKEN` | String | 필수 | - | `XSRF-TOKEN` 쿠키와 같은 값 |
| header | `Content-Type` | String | 필수 | - | `application/json` |
| body | `bgngYmd` | String | 필수 | - | 분석 시작일. `YYYYMMDD`, 실재하는 날짜 |
| body | `endYmd` | String | 필수 | - | 분석 종료일(당일 포함). 시작일 포함 최대 366일 |
| body | `fileFmt` | String | 필수 | - | `PDF`, `DOCX`, `XLSX` |
| body | `anlsTrgtCd` | String | 선택 | `CVLCPT_CNT` | `CVLCPT_CNT`만 처리한다. `null`을 명시하면 400 |
| body | `gridSz` | int | 선택 | `500` | `1000`, `500`, `250`, `100` |
| body | `clsfMthd` | String | 선택 | `EQUAL_INTERVAL` | `EQUAL_INTERVAL`만 허용 |
| body | `sggNm` | String | 선택 | 전 지역 | 시군구명. 빈 문자열은 400 |
| body | `emdCd` | String | 선택 | 시군구 전체 | 읍면동코드. 빈 문자열은 400 |
| body | `limit` | Integer | 선택 | 상한 없음 | 저장할 `ranking` 최대 행 수. 1 이상 |

### 호출 예시

```bash
curl -X POST 'http://localhost:8180/api/analysis/lifezone/reports' \
  -b 'JSESSIONID=<세션ID>; XSRF-TOKEN=<CSRF토큰>' \
  -H 'X-XSRF-TOKEN: <CSRF토큰>' \
  -H 'Content-Type: application/json' \
  -d '{"bgngYmd":"20260101","endYmd":"20260131","gridSz":500,"sggNm":"제주시","fileFmt":"PDF"}'
```

### 응답

`201 Created`, `application/json`. 1번 API의 응답 필드 전체에 아래 보고서 헤더 7개가 더해진다. `dataBgngYmd`, `dataEndYmd`는 현재 원천 보유 구간으로 채워진다. `sggNm`은 분석 응답 값이라 `emdCd`만 보낸 요청에서는 `null`이다.

```json
{
  "rptpId": 100,
  "excnId": 200,
  "rptpNm": "생활권 민원건수 보고서 제주시 전체 500m",
  "regionNm": "제주시 전체",
  "anlsPrdExpln": "2026.01.01 ~ 2026.01.31",
  "fileFmt": "PDF",
  "crtYmd": "2026-11-18T10:00:00",
  "bgngYmd": "20260101",
  "endYmd": "20260131",
  "sggNm": "제주시",
  "emdCd": null,
  "anlsTrgtCd": "CVLCPT_CNT",
  "gridSz": 500,
  "totGridCnt": 1,
  "cells": [],
  "ranking": []
}
```

위 예시는 헤더 필드를 중심으로 줄인 것이다.

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `rptpId` | Long | N | 보고서 ID |
| `excnId` | Long | N | 분석 실행 ID |
| `rptpNm` | String | N | `생활권 {분석유형명} 보고서 {지역명} {격자표시명}` |
| `regionNm` | String | N | `전체 지역`, `{시군구} 전체`, `{시군구} {읍면동}` |
| `anlsPrdExpln` | String | N | `yyyy.MM.dd ~ yyyy.MM.dd`. 시작일과 종료일이 같으면 날짜 하나 |
| `fileFmt` | String | N | 요청한 파일형식 |
| `crtYmd` | String | N | 생성일시(ISO-8601 `LocalDateTime`) |
| 그 밖의 필드 | - | - | 1번 API 응답과 같다 |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":400,"error":"Bad Request","path":"/api/analysis/lifezone/reports"}` | 본문 누락, JSON 형식 오류<br>필수값 누락, 기간 형식 오류, `fileFmt` 허용값 밖<br>1번 API의 조회 조건 검증 실패<br>분석 결과 0건(저장하지 않음) |
| 401 | 세션 없음: 본문 없음<br>사용자 해석 실패: `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":401,"error":"Unauthorized","path":"/api/analysis/lifezone/reports"}` | 로그인 세션 없음<br>`TB_USER`에 없거나 `use_yn`이 `Y`가 아닌 사용자 |
| 403 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":403,"error":"Forbidden","path":"/api/analysis/lifezone/reports"}` | CSRF 토큰 누락 또는 불일치 |
| 415 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":415,"error":"Unsupported Media Type","path":"/api/analysis/lifezone/reports"}` | JSON이 아닌 `Content-Type` |
| 500 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":500,"error":"Internal Server Error","path":"/api/analysis/lifezone/reports"}` | 결과 직렬화 실패, DB 저장 실패 등 서버 오류. 저장 내용은 롤백된다 |

## 6. GET /api/reports/{rptpId}/lifezone

### API 개요

저장된 민원건수 보고서의 스냅샷을 조회한다. 재분석하지 않으며 데이터를 변경하지 않는다.

로그인 세션이 필요하다. 직접 생성(`MANUAL`)과 AI 생성(`AI`) 보고서는 생성자 본인만 조회할 수 있고, 자동 생성(`AUTO`) 보고서는 배포 부서가 요청자 부서이거나 `ALL`이거나 요청자가 보고서 관리 메뉴를 가진 경우 조회할 수 있다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | - | 로그인 세션 |
| path | `rptpId` | Long | 필수 | - | 보고서 ID |

### 호출 예시

```bash
curl 'http://localhost:8180/api/reports/100/lifezone' \
  -b 'JSESSIONID=<세션ID>'
```

### 응답

`200 OK`, `application/json`. 필드는 5번 API 응답과 같으며 값의 출처는 아래와 같다.

```json
{
  "rptpId": 100,
  "excnId": 200,
  "rptpNm": "생활권 민원건수 보고서 제주시 전체 500m",
  "regionNm": "제주시 전체",
  "anlsPrdExpln": "2026.01.01 ~ 2026.01.31",
  "fileFmt": "PDF",
  "crtYmd": "2026-11-18T10:00:00",
  "bgngYmd": "20260101",
  "endYmd": "20260131",
  "sggNm": "제주시",
  "emdCd": null,
  "dataBgngYmd": null,
  "dataEndYmd": null,
  "cells": [],
  "ranking": []
}
```

위 예시는 스냅샷에서 값이 달라지는 필드를 중심으로 줄인 것이다.

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `sggNm`, `emdCd` | String | Y | 실행 이력에 저장된 지역. `emdCd`로 생성한 보고서는 소속 시군구명이 채워진다 |
| `dataBgngYmd`, `dataEndYmd` | String | Y | 항상 `null` |
| `availableTargets[]` | Array | N | 조회 시점의 분석유형 목록 |
| `gradeScale`, `gradeCriteria[]` | Object, Array | N | 생성 시 저장한 값 |
| `severeCnt`, `warnCnt`, `cautionCnt`, `normalCnt`, `lowCnt`, `totGridCnt` | int | N | 생성 시 저장한 값 |
| `ranking[]` | Array | N | 저장된 순서와 등급 그대로 |
| `cells[].grade` | String | N | 저장된 `gradeCriteria[].minVl`로 다시 판정한 등급 |
| `cells[].cntrPoiNm` | String | N | 조회 시점의 구역명 캐시 이름. 없으면 `{읍면동명} 격자 {격자코드}` |
| 그 밖의 필드 | - | - | 5번 API 응답과 같다 |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":400,"error":"Bad Request","path":"/api/reports/abc/lifezone"}` | `rptpId`가 숫자가 아님 |
| 401 | 세션 없음: 본문 없음<br>사용자 해석 실패: `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":401,"error":"Unauthorized","path":"/api/reports/100/lifezone"}` | 로그인 세션 없음<br>`TB_USER`에 없거나 `use_yn`이 `Y`가 아닌 사용자 |
| 404 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":404,"error":"Not Found","path":"/api/reports/100/lifezone"}` | 보고서 없음, 조회 권한 없음<br>`anls_type_cd`가 `LIFEZONE`이 아님<br>저장된 분석 결과 없음 |
| 500 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":500,"error":"Internal Server Error","path":"/api/reports/100/lifezone"}` | `anls_param_json` 누락 또는 손상<br>`gradeCriteria`가 5행이 아니거나 `gradeScale`, `ranking`, `cells`가 손상됨 |

## 7. POST /api/analysis/lifezone/supply/reports

### API 개요

요청 조건으로 수급 분석을 서버에서 다시 실행하고 결과와 엑셀 재계산 데이터(`wbk`)를 보고서로 저장한다. `TB_ANLS_EXCN`(`anls_type_cd=LIFEZONE_SPLY`), `TB_ANLS_RSLT`, `TB_RPTP`(`MANUAL`, `DONE`)에 각 1행을 한 트랜잭션으로 기록한다. 일시 계수는 이 보고서에만 적용되며 설정 표는 변경하지 않는다.

로그인 세션과 CSRF 토큰이 필요하며 메뉴 권한은 검사하지 않는다. 분석 중 VWorld 호출과 `TB_ZONE_NM_CACHE` 저장이 일어날 수 있다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | - | 로그인 세션 |
| cookie | `XSRF-TOKEN` | String | 필수 | - | CSRF 토큰 쿠키 |
| header | `X-XSRF-TOKEN` | String | 필수 | - | `XSRF-TOKEN` 쿠키와 같은 값 |
| header | `Content-Type` | String | 필수 | - | `application/json` |
| body | `bgngYmd` | String | 필수 | - | 분석 시작일. `YYYYMMDD`, 실재하는 날짜 |
| body | `endYmd` | String | 필수 | - | 분석 종료일(당일 포함). 시작일 포함 최대 366일 |
| body | `fileFmt` | String | 필수 | - | `PDF`, `DOCX`, `XLSX` |
| body | `gridSz` | int | 선택 | `500` | `1000`, `500`, `250`, `100` |
| body | `sggNm` | String | 선택 | 전 지역 | 시군구명. 빈 문자열은 400 |
| body | `emdCd` | String | 선택 | 시군구 전체 | 읍면동코드. 빈 문자열은 400 |
| body | `grade` | String | 선택 | 전체(`null`) | 보고서 표에 실을 등급. `SEVERE`, `WARN`, `CAUTION`(대소문자 무시). `cells`는 이 값과 무관하게 전량 저장 |
| body | `vrblVls` | Object | 선택 | 설정값 | 변수코드와 숫자의 객체. 키와 검증 규칙은 2번 API의 `vrblVls`와 같다 |

### 호출 예시

```bash
curl -X POST 'http://localhost:8180/api/analysis/lifezone/supply/reports' \
  -b 'JSESSIONID=<세션ID>; XSRF-TOKEN=<CSRF토큰>' \
  -H 'X-XSRF-TOKEN: <CSRF토큰>' \
  -H 'Content-Type: application/json' \
  -d '{"bgngYmd":"20260101","endYmd":"20260131","gridSz":500,"fileFmt":"XLSX","grade":"SEVERE","vrblVls":{"SPLY_MIN_MNTH_AVG_CVLCPT":3}}'
```

### 응답

`201 Created`, `application/json`. 2번 API의 응답 필드 전체에 아래 필드가 더해진다. `dataBgngYmd`, `dataEndYmd`는 `null`이다.

```json
{
  "rptpId": 101,
  "excnId": 201,
  "rptpNm": "생활권 주차 수급 보고서 전체 지역 500m",
  "regionNm": "전체 지역",
  "anlsPrdExpln": "2026.01.01 ~ 2026.01.31",
  "fileFmt": "XLSX",
  "grade": "SEVERE",
  "crtYmd": "2026-11-18T10:00:00",
  "dataBgngYmd": null,
  "dataEndYmd": null,
  "cells": [],
  "wbk": {
    "prdDays": 31,
    "vrbls": [
      { "vrblCd": "SPLY_MIN_MNTH_AVG_CVLCPT", "vrblNm": "수급 최소 월평균 민원", "aplcnVl": 3, "minVl": 1, "maxVl": 30, "intOnlyYn": "Y", "inptYn": "Y" }
    ],
    "cells": [
      { "gridId": "500m_1800_3000", "cntrPoiNm": "가상동 예시사거리", "emdNm": "가상동", "cvlcptCnt": 12, "pkltCpctySum": 0, "pkltCnt": 0 }
    ]
  }
}
```

위 예시는 보고서 전용 필드를 중심으로 줄인 것이다.

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `rptpId`, `excnId` | Long | N | 보고서 ID, 분석 실행 ID |
| `rptpNm`, `regionNm`, `anlsPrdExpln` | String | N | 보고서명, 지역명, 분석기간 설명 |
| `fileFmt` | String | N | 요청한 파일형식 |
| `grade` | String | Y | 보고서 표 등급. `null`이면 전체 |
| `crtYmd` | String | N | 생성일시(ISO-8601 `LocalDateTime`) |
| `wbk.prdDays` | int | N | 조회 일수(양끝 포함) |
| `wbk.vrbls[].vrblCd`, `wbk.vrbls[].vrblNm` | String | N | 변수코드, 변수명 |
| `wbk.vrbls[].aplcnVl` | Number | Y | 이번 분석 적용값 |
| `wbk.vrbls[].minVl`, `wbk.vrbls[].maxVl` | Number | Y | 입력 허용 범위 |
| `wbk.vrbls[].intOnlyYn` | String | N | 수급은 `Y` |
| `wbk.vrbls[].inptYn` | String | N | 엑셀 입력 셀 여부. 설정 행이 없거나 숨김이면 `N` |
| `wbk.cells[]` | Array | N | 최소 월평균 민원을 설정 범위 하한(`min_vl`)으로 조회한 후보 격자. 서버 순위 순 |
| `wbk.cells[].gridId` | String | N | 격자 ID |
| `wbk.cells[].cntrPoiNm` | String | Y | 응답 셀 이름, 없으면 캐시 이름, 둘 다 없으면 `null` |
| `wbk.cells[].emdNm` | String | Y | 읍면동명 |
| `wbk.cells[].cvlcptCnt` | int | N | 주차 민원 건수 |
| `wbk.cells[].pkltCpctySum`, `wbk.cells[].pkltCnt` | int | N | 공영주차면 수, 공영주차장 수 |
| 그 밖의 필드 | - | - | 2번 API 응답과 같다 |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":400,"error":"Bad Request","path":"/api/analysis/lifezone/supply/reports"}` | 본문 누락, JSON 형식 오류, `vrblVls` 값이 숫자가 아님<br>필수값 누락, 기간 형식 오류, `fileFmt` 허용값 밖<br>`grade`가 `SEVERE`, `WARN`, `CAUTION` 밖(`NORMAL`, `LOW` 포함)<br>2번 API의 조회 조건 또는 계수 검증 실패<br>분석 결과 0건(저장하지 않음) |
| 401 | 세션 없음: 본문 없음<br>사용자 해석 실패: `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":401,"error":"Unauthorized","path":"/api/analysis/lifezone/supply/reports"}` | 로그인 세션 없음<br>`TB_USER`에 없거나 `use_yn`이 `Y`가 아닌 사용자 |
| 403 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":403,"error":"Forbidden","path":"/api/analysis/lifezone/supply/reports"}` | CSRF 토큰 누락 또는 불일치 |
| 415 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":415,"error":"Unsupported Media Type","path":"/api/analysis/lifezone/supply/reports"}` | JSON이 아닌 `Content-Type` |
| 500 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":500,"error":"Internal Server Error","path":"/api/analysis/lifezone/supply/reports"}` | 결과 직렬화 실패, DB 저장 실패 등 서버 오류. 저장 내용은 롤백된다 |

## 8. GET /api/reports/{rptpId}/lifezone/supply

### API 개요

저장된 수급 보고서의 스냅샷을 조회한다. 재분석하거나 등급을 다시 판정하지 않으며 데이터를 변경하지 않는다.

로그인 세션이 필요하다. 직접 생성(`MANUAL`)과 AI 생성(`AI`) 보고서는 생성자 본인만 조회할 수 있고, 자동 생성(`AUTO`) 보고서는 배포 부서가 요청자 부서이거나 `ALL`이거나 요청자가 보고서 관리 메뉴를 가진 경우 조회할 수 있다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | - | 로그인 세션 |
| path | `rptpId` | Long | 필수 | - | 보고서 ID |

### 호출 예시

```bash
curl 'http://localhost:8180/api/reports/101/lifezone/supply' \
  -b 'JSESSIONID=<세션ID>'
```

### 응답

`200 OK`, `application/json`. `anlsMthd`가 `SHRT_RT`이면 부족률 5등급 스냅샷으로 7번 API 응답과 같은 필드를 반환한다. `anlsMthd` 키가 없으면 사분면 방식의 구 스냅샷이다.

```json
{
  "rptpId": 101,
  "excnId": 201,
  "rptpNm": "생활권 주차 수급 보고서 전체 지역 500m",
  "regionNm": "전체 지역",
  "anlsPrdExpln": "2026.01.01 ~ 2026.01.31",
  "fileFmt": "XLSX",
  "grade": "SEVERE",
  "crtYmd": "2026-11-18T10:00:00",
  "anlsMthd": "SHRT_RT",
  "dataBgngYmd": null,
  "dataEndYmd": null,
  "cells": [],
  "wbk": null
}
```

위 예시는 스냅샷에서 값이 달라지는 필드를 중심으로 줄인 것이다.

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `anlsMthd` | String | 생략 가능 | 신규 스냅샷은 `SHRT_RT`, 구 스냅샷은 키 생략 |
| `disclaimer` | String | N | 저장된 문구. 없으면 현재 문구 |
| `dataBgngYmd`, `dataEndYmd` | String | Y | 항상 `null` |
| `vrblAjmt` | Object | 생략 가능 | 저장 전 생성된 보고서는 키 생략 |
| `wbk` | Object | Y | 저장 전 생성된 보고서이거나 읽기에 실패하면 `null` |
| `qdrn` | String | 생략 가능 | 구 스냅샷의 보고서 표 사분면(`Q1`~`Q4`) |
| `cvlcptHighThld`, `splyLowThld` | Number | 생략 가능 | 구 스냅샷의 민원, 공급 임계값 |
| `qdrnCriteria[].qdrn`, `qdrnCriteria[].qdrnNm` | String | 생략 가능 | 구 스냅샷의 사분면코드, 사분면명 |
| `qdrnCriteria[].qdrnExpln`, `qdrnCriteria[].lgndTxt` | String | 생략 가능 | 구 스냅샷의 판정 근거, 범례 문구 |
| `qdrnCriteria[].gridCnt`, `qdrnCriteria[].cvlcptCnt`, `qdrnCriteria[].zeroSplyGridCnt` | int | 생략 가능 | 구 스냅샷의 사분면별 격자 수, 민원 합계, 공급 0 격자 수 |
| `q1Cnt`, `q2Cnt`, `q3Cnt`, `q4Cnt` | int | 생략 가능 | 구 스냅샷의 사분면별 격자 수 |
| `cells[].qdrn`, `cells[].qdrnNm` | String | 생략 가능 | 구 스냅샷 셀의 사분면코드, 사분면명 |
| `cells[].cvlcptPerSlot` | Number | 생략 가능 | 구 스냅샷 셀의 면당 민원 |
| 그 밖의 필드 | - | - | 7번 API 응답과 같다. 구 스냅샷에는 `severeCnt` 등 등급 필드와 `gradeCriteria`가 없다 |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":400,"error":"Bad Request","path":"/api/reports/abc/lifezone/supply"}` | `rptpId`가 숫자가 아님 |
| 401 | 세션 없음: 본문 없음<br>사용자 해석 실패: `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":401,"error":"Unauthorized","path":"/api/reports/101/lifezone/supply"}` | 로그인 세션 없음<br>`TB_USER`에 없거나 `use_yn`이 `Y`가 아닌 사용자 |
| 404 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":404,"error":"Not Found","path":"/api/reports/101/lifezone/supply"}` | 보고서 없음, 조회 권한 없음<br>`anls_type_cd`가 `LIFEZONE_SPLY`가 아님<br>저장된 분석 결과 또는 `anls_param_json` 없음 |
| 500 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":500,"error":"Internal Server Error","path":"/api/reports/101/lifezone/supply"}` | 저장된 JSON 또는 격자 셀 손상 |

## 9. POST /api/analysis/lifezone/congestion/reports

### API 개요

요청 조건으로 혼잡도 분석을 서버에서 다시 실행하고 결과와 엑셀 재계산 데이터(`wbk`)를 보고서로 저장한다. `TB_ANLS_EXCN`(`anls_type_cd=LIFEZONE_CNGST`), `TB_ANLS_RSLT`, `TB_RPTP`(`MANUAL`, `DONE`)에 각 1행을 한 트랜잭션으로 기록한다. 일시 계수는 이 보고서에만 적용되며 설정 표는 변경하지 않는다.

로그인 세션과 CSRF 토큰이 필요하며 메뉴 권한은 검사하지 않는다. 분석 중 VWorld 호출과 `TB_ZONE_NM_CACHE` 저장이 일어날 수 있다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | - | 로그인 세션 |
| cookie | `XSRF-TOKEN` | String | 필수 | - | CSRF 토큰 쿠키 |
| header | `X-XSRF-TOKEN` | String | 필수 | - | `XSRF-TOKEN` 쿠키와 같은 값 |
| header | `Content-Type` | String | 필수 | - | `application/json` |
| body | `bgngYmd` | String | 필수 | - | 분석 시작일. `YYYYMMDD`, 실재하는 날짜 |
| body | `endYmd` | String | 필수 | - | 분석 종료일(당일 포함). 시작일 포함 최대 366일 |
| body | `fileFmt` | String | 필수 | - | `PDF`, `DOCX`, `XLSX` |
| body | `gridSz` | int | 선택 | `500` | `1000`, `500`, `250`, `100` |
| body | `sggNm` | String | 선택 | 전 지역 | 시군구명. 빈 문자열은 400 |
| body | `emdCd` | String | 선택 | 시군구 전체 | 읍면동코드. 빈 문자열은 400 |
| body | `vrblVls` | Object | 선택 | 설정값 | 변수코드와 숫자의 객체. 키와 검증 규칙은 3번 API의 `vrblVls`와 같다 |

### 호출 예시

```bash
curl -X POST 'http://localhost:8180/api/analysis/lifezone/congestion/reports' \
  -b 'JSESSIONID=<세션ID>; XSRF-TOKEN=<CSRF토큰>' \
  -H 'X-XSRF-TOKEN: <CSRF토큰>' \
  -H 'Content-Type: application/json' \
  -d '{"bgngYmd":"20260101","endYmd":"20260131","gridSz":500,"fileFmt":"XLSX","vrblVls":{"CNGST_GRD_BNDRY4":98}}'
```

### 응답

`201 Created`, `application/json`. 3번 API의 응답 필드 전체에 아래 필드가 더해진다. `dataBgngYmd`, `dataEndYmd`는 `null`이다. 격자가 있고 `ranking`만 0행인 경우도 201이다.

```json
{
  "rptpId": 102,
  "excnId": 202,
  "rptpNm": "생활권 혼잡도 보고서 전체 지역 500m",
  "regionNm": "전체 지역",
  "anlsPrdExpln": "2026.01.01 ~ 2026.01.31",
  "fileFmt": "XLSX",
  "crtYmd": "2026-11-18T10:00:00",
  "dataBgngYmd": null,
  "dataEndYmd": null,
  "cells": [],
  "ranking": [],
  "wbk": {
    "mnthCnt": 1.0,
    "bgngYmd": "20260101",
    "endYmd": "20260131",
    "vrbls": [
      { "vrblCd": "CNGST_GRD_BNDRY4", "vrblNm": "혼잡 경계 심각", "aplcnVl": 98, "minVl": 50, "maxVl": 100, "intOnlyYn": "N", "inptYn": "Y" }
    ],
    "cells": [
      { "gridCd": "500m_1800_3000", "cntrPoiNm": "가상동 예시사거리", "emdNm": "가상동", "pkltCpctySum": 120, "pkltCnt": 3, "nFixed": 10, "nPda": 20, "nManual": 5, "cvlcptCnt": 15 }
    ]
  }
}
```

위 예시는 보고서 전용 필드를 중심으로 줄인 것이다.

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `rptpId`, `excnId` | Long | N | 보고서 ID, 분석 실행 ID |
| `rptpNm`, `regionNm`, `anlsPrdExpln` | String | N | 보고서명, 지역명, 분석기간 설명 |
| `fileFmt` | String | N | 요청한 파일형식 |
| `crtYmd` | String | N | 생성일시(ISO-8601 `LocalDateTime`) |
| `wbk.mnthCnt` | Number | N | 개월수. 부분월은 일수 비례, 반올림 없음 |
| `wbk.bgngYmd`, `wbk.endYmd` | String | N | 조회 기간(`YYYYMMDD`) |
| `wbk.vrbls[]` | Array | N | 혼잡도 계수 10행. 필드는 7번 API의 `wbk.vrbls[]`와 같고 `intOnlyYn`은 `N`이다 |
| `wbk.cells[]` | Array | N | 제외 격자를 포함한 공급 > 0 격자 전량. `gx`, `gy` 순 |
| `wbk.cells[].gridCd` | String | N | 격자코드 |
| `wbk.cells[].cntrPoiNm` | String | N | 순위 행 이름, 없으면 셀 구역명 |
| `wbk.cells[].emdNm` | String | Y | 읍면동명 |
| `wbk.cells[].pkltCpctySum`, `wbk.cells[].pkltCnt` | int | N | 공영주차면 수, 공영주차장 수 |
| `wbk.cells[].nFixed` | int | N | 고정형, 주행형, 버스장착형 CCTV 단속 건수 |
| `wbk.cells[].nPda` | int | N | PDA 단속 건수 |
| `wbk.cells[].nManual` | int | N | 그 밖의 단속 건수(단속수단 값 없음 포함) |
| `wbk.cells[].cvlcptCnt` | int | N | 민원 건수 |
| 그 밖의 필드 | - | - | 3번 API 응답과 같다 |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":400,"error":"Bad Request","path":"/api/analysis/lifezone/congestion/reports"}` | 본문 누락, JSON 형식 오류, `vrblVls` 값이 숫자가 아님<br>필수값 누락, 기간 형식 오류, `fileFmt` 허용값 밖<br>3번 API의 조회 조건 또는 계수 검증 실패<br>결과 격자 0건(저장하지 않음) |
| 401 | 세션 없음: 본문 없음<br>사용자 해석 실패: `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":401,"error":"Unauthorized","path":"/api/analysis/lifezone/congestion/reports"}` | 로그인 세션 없음<br>`TB_USER`에 없거나 `use_yn`이 `Y`가 아닌 사용자 |
| 403 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":403,"error":"Forbidden","path":"/api/analysis/lifezone/congestion/reports"}` | CSRF 토큰 누락 또는 불일치 |
| 415 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":415,"error":"Unsupported Media Type","path":"/api/analysis/lifezone/congestion/reports"}` | JSON이 아닌 `Content-Type` |
| 500 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":500,"error":"Internal Server Error","path":"/api/analysis/lifezone/congestion/reports"}` | 결과 직렬화 실패, DB 저장 실패 등 서버 오류. 저장 내용은 롤백된다 |

## 10. GET /api/reports/{rptpId}/lifezone/congestion

### API 개요

저장된 혼잡도 보고서의 스냅샷을 조회한다. 재분석하거나 등급을 다시 판정하지 않으며 데이터를 변경하지 않는다.

로그인 세션이 필요하다. 직접 생성(`MANUAL`)과 AI 생성(`AI`) 보고서는 생성자 본인만 조회할 수 있고, 자동 생성(`AUTO`) 보고서는 배포 부서가 요청자 부서이거나 `ALL`이거나 요청자가 보고서 관리 메뉴를 가진 경우 조회할 수 있다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | - | 로그인 세션 |
| path | `rptpId` | Long | 필수 | - | 보고서 ID |

### 호출 예시

```bash
curl 'http://localhost:8180/api/reports/102/lifezone/congestion' \
  -b 'JSESSIONID=<세션ID>'
```

### 응답

`200 OK`, `application/json`. 필드는 9번 API 응답과 같으며 값의 출처는 아래와 같다.

```json
{
  "rptpId": 102,
  "excnId": 202,
  "rptpNm": "생활권 혼잡도 보고서 전체 지역 500m",
  "regionNm": "전체 지역",
  "anlsPrdExpln": "2026.01.01 ~ 2026.01.31",
  "fileFmt": "XLSX",
  "crtYmd": "2026-11-18T10:00:00",
  "occUsedVl": 0.85,
  "vrblAjmt": null,
  "dataBgngYmd": null,
  "dataEndYmd": null,
  "cells": [],
  "ranking": [],
  "wbk": null
}
```

위 예시는 스냅샷에서 값이 달라지는 필드를 중심으로 줄인 것이다.

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `dataBgngYmd`, `dataEndYmd` | String | Y | 항상 `null` |
| `availableClsfMthds`, `availableTargets[]` | Array | N | 조회 시점의 목록 |
| `occUsedVl` | Number | Y | 생성 시 저장한 기준 점유율 |
| `vrblAjmt` | Object | Y | 저장 전 생성된 보고서는 `null` |
| `wbk` | Object | Y | 저장 전 생성된 보고서이거나 읽기에 실패하면 `null` |
| `cells[]`, `ranking[]` | Array | N | 저장된 값과 순서 그대로 |
| 그 밖의 필드 | - | - | 9번 API 응답과 같다 |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":400,"error":"Bad Request","path":"/api/reports/abc/lifezone/congestion"}` | `rptpId`가 숫자가 아님 |
| 401 | 세션 없음: 본문 없음<br>사용자 해석 실패: `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":401,"error":"Unauthorized","path":"/api/reports/102/lifezone/congestion"}` | 로그인 세션 없음<br>`TB_USER`에 없거나 `use_yn`이 `Y`가 아닌 사용자 |
| 404 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":404,"error":"Not Found","path":"/api/reports/102/lifezone/congestion"}` | 보고서 없음, 조회 권한 없음<br>`anls_type_cd`가 `LIFEZONE_CNGST`가 아님<br>저장된 분석 결과 또는 `anls_param_json` 없음 |
| 500 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":500,"error":"Internal Server Error","path":"/api/reports/102/lifezone/congestion"}` | 저장된 JSON 손상, `gradeCriteria`, `ranking`, `cells` 손상 |

## 11. POST /api/internal/zone-names/prefill

### API 개요

배치(`zoneNmPrefillJob`) 전용 내부 API다. 지정한 격자크기에서 민원(유형 `99` 제외) 또는 공영주차장 좌표가 있는 격자 중 구역명 캐시가 없는 격자를 좌표가 많은 순으로 최대 `limit`개 골라 VWorld로 조회하고 `TB_ZONE_NM_CACHE`에 저장한다.

세션과 CSRF 토큰은 필요 없다. `app.internal-api.enabled=true`(공통 기본값, dev, prod)이면 `X-API-Key` 헤더가 `INTERNAL_API_KEY`와 일치해야 하고, `false`(local 프로파일)이면 키를 검사하지 않는다. 구역명은 건별로 커밋되며, 일시 실패가 기본 3건 연속되면 해당 호출의 외부 조회를 중단한다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `X-API-Key` | String | 조건부 | - | `app.internal-api.enabled=true`일 때 필수 |
| query | `gridSz` | int | 필수 | - | 격자크기(m). `1000`, `500`, `250`, `100` |
| query | `limit` | int | 선택 | `100` | 이번 호출의 조회 대상 격자 수. 1~200 |

### 호출 예시

```bash
curl -X POST 'http://localhost:8180/api/internal/zone-names/prefill?gridSz=500&limit=100' \
  -H 'X-API-Key: <내부 API 키>'
```

### 응답

`200 OK`, `application/json`. 공통 래퍼 없이 아래 객체를 그대로 반환한다.

```json
{
  "gridSz": 500,
  "targetCnt": 1200,
  "resolvedCnt": 95,
  "transientCnt": 5,
  "remainingCnt": 305,
  "tripped": false
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `gridSz` | int | N | 격자크기(m) |
| `targetCnt` | int | N | 캐시 유무와 무관한 대상 격자 수 |
| `resolvedCnt` | int | N | 이번 호출에서 캐시에 저장한 격자 수(POI, 역지오코딩, 확정 실패 `NOT_FOUND`) |
| `transientCnt` | int | N | 이번에 고른 격자 중 저장되지 않은 수. 다음 호출에서 다시 시도한다 |
| `remainingCnt` | int | N | 호출 후 캐시가 없는 대상 격자 수. `0`이면 해당 격자크기는 완료 |
| `tripped` | boolean | N | 연속 일시 실패로 외부 조회를 중단했는지 여부 |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":400,"error":"Bad Request","path":"/api/internal/zone-names/prefill"}` | `gridSz` 누락, 숫자 아님, 목록 밖<br>`limit`이 숫자가 아니거나 1~200 밖 |
| 401 | `{"error":"missing or invalid X-API-Key"}` | 키 검사가 켜져 있고 `X-API-Key`가 없거나 일치하지 않음 |
| 500 | `{"timestamp":"2026-11-18T01:00:00.000+00:00","status":500,"error":"Internal Server Error","path":"/api/internal/zone-names/prefill"}` | 대상 조회 실패 등 처리되지 않은 서버 오류 |
| 503 | `{"error":"internal API key not configured (INTERNAL_API_KEY missing)"}` | 키 검사가 켜져 있는데 서버에 `INTERNAL_API_KEY`가 없음 |
