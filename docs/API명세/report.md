# 보고서 API

## 1. GET /api/reports

### API 개요
생성구분(`AUTO`·`MANUAL`·`AI`)별 보고서 목록을 페이징 조회한다. 정렬은 생성일시 내림차순, 같으면 보고서 ID 내림차순이다.

로그인 세션이 필요하다. `MANUAL`·`AI` 는 본인이 만든 보고서만, `AUTO` 는 배포 부서가 요청자 부서와 같거나 `ALL` 이거나 요청자 권한에 보고서 관리 메뉴(`M0402`)가 있을 때만 조회된다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |
| query | `crtType` | String | 필수 | 없음 | 생성구분. `AUTO` \| `MANUAL` \| `AI` |
| query | `page` | int | 선택 | `0` | 0부터 시작하는 페이지 번호. 0 이상 |
| query | `size` | int | 선택 | `10` | 페이지 크기. 1~100 |
| query | `keyword` | String | 선택 | 없음 | 보고서명 부분 일치(대소문자 구분). 앞뒤 공백 제거 후 최대 100자, 빈 값은 미적용 |
| query | `anlsTypeCd` | String | 선택 | 없음 | 출처. 공통코드 `ANLS_TYPE_CD` 의 사용중 코드. 빈 값은 미적용 |
| query | `fromYmd` | String(`yyyy-MM-dd`) | 선택 | 없음 | 생성일 시작(포함) |
| query | `toYmd` | String(`yyyy-MM-dd`) | 선택 | 없음 | 생성일 종료(포함). `fromYmd` 이후여야 한다 |

### 호출 예시
```bash
curl "http://localhost:8180/api/reports?crtType=MANUAL&page=0&size=10&keyword=%EB%AF%BC%EC%9B%90&fromYmd=2026-01-01&toYmd=2026-01-31" \
  -H 'Cookie: JSESSIONID=<세션ID>'
```

### 응답
`200 OK` · `application/json`

```json
{
  "content": [
    {
      "rptpId": 1001,
      "excnId": 2001,
      "crtType": "MANUAL",
      "crtTypeNm": "직접",
      "rptpNm": "민원 현황 보고서 제주시 연동",
      "cyclCd": null,
      "cyclNm": null,
      "srcNm": "민원현황",
      "anlsTypeCd": "CVLCPT",
      "anlsPrdExpln": "2026.01.01 ~ 2026.01.31",
      "creatrNm": "홍길동",
      "deptCd": null,
      "deptNm": null,
      "sggNm": "제주시",
      "emdCd": "50110137",
      "emdNm": "연동",
      "regionNm": "제주시 연동",
      "sttsCd": "DONE",
      "sttsNm": "완료",
      "fileFmt": "PDF",
      "crtYmd": "2026-02-01T09:00:00"
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 1,
  "totalPages": 1
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `content[]` | Array | 현재 페이지의 행. 결과가 없으면 빈 배열 |
| `content[].rptpId` | Long | 보고서 ID |
| `content[].excnId` | Long \| null | 분석 실행 ID. `AI` 보고서는 null |
| `content[].crtType` | String | 생성구분 코드 |
| `content[].crtTypeNm` | String \| null | 생성구분명(공통코드 `RPTP_CRT_TYPE_CD`) |
| `content[].rptpNm` | String | 보고서명 |
| `content[].cyclCd` | String \| null | 배포주기 코드(생성 시점 값). `AUTO` 만 값이 있다 |
| `content[].cyclNm` | String \| null | 배포주기명(공통코드 `CRLTN_CYCL_CD`) |
| `content[].srcNm` | String \| null | 출처명(공통코드 `ANLS_TYPE_CD` 의 코드명). 실행이 연결되지 않으면 null |
| `content[].anlsTypeCd` | String \| null | 분석유형 코드. 실행이 연결되지 않으면 null |
| `content[].anlsPrdExpln` | String \| null | 분석기간 설명 |
| `content[].creatrNm` | String \| null | 생성자명 |
| `content[].deptCd` | String \| null | 배포부서 코드(생성 시점 값). `AUTO` 만 값이 있다 |
| `content[].deptNm` | String \| null | 배포부서명 |
| `content[].sggNm` | String \| null | 시군구명(실행 기준). null 이면 시군구 조건 없음 |
| `content[].emdCd` | String \| null | 읍면동 코드(실행 기준) |
| `content[].emdNm` | String \| null | 읍면동명 |
| `content[].regionNm` | String \| null | 지역 표시명. `{시군구} {읍면동}` \| `{시군구} 전체` \| `전체 지역`. 실행이 연결되지 않으면 null |
| `content[].sttsCd` | String | 상태 코드(공통코드 `RPTP_STTS_CD`) |
| `content[].sttsNm` | String \| null | 상태명 |
| `content[].fileFmt` | String \| null | 파일 형식. `AUTO`·`AI` 는 null |
| `content[].crtYmd` | String(ISO-8601) \| null | 생성일시 |
| `page` | int | 요청한 페이지 번호 |
| `size` | int | 요청한 페이지 크기 |
| `totalElements` | long | 조건에 맞는 전체 건수 |
| `totalPages` | int | 전체 페이지 수 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | Spring Boot 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | `crtType` 누락·허용값 외, `page` 음수, `size` 범위 밖, 숫자·날짜 형식 오류, `keyword` 100자 초과, 사용할 수 없는 `anlsTypeCd`, `fromYmd` 가 `toYmd` 보다 늦음 |
| 401 | 없음 | 세션 없음 |
| 401 | Spring Boot 기본 오류 JSON | 세션 사용자가 `TB_USER` 에 없거나 사용 중지됨 |

## 2. GET /api/reports/{rptpId}

### API 개요
보고서 1건의 머리 정보를 조회한다. 미리보기 헤더에 쓴다.

로그인 세션이 필요하다. `MANUAL`·`AI` 는 생성자 본인만, `AUTO` 는 배포 부서·`ALL`·보고서 관리 메뉴(`M0402`) 보유자만 조회할 수 있다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |
| path | `rptpId` | Long | 필수 | 없음 | 보고서 ID |

### 호출 예시
```bash
curl http://localhost:8180/api/reports/1001 \
  -H 'Cookie: JSESSIONID=<세션ID>'
```

### 응답
`200 OK` · `application/json`. 본문은 1번 API 의 `content[]` 한 행과 같은 객체이며 래퍼가 없다.

```json
{
  "rptpId": 1001,
  "excnId": 2001,
  "crtType": "MANUAL",
  "crtTypeNm": "직접",
  "rptpNm": "민원 현황 보고서 제주시 연동",
  "cyclCd": null,
  "cyclNm": null,
  "srcNm": "민원현황",
  "anlsTypeCd": "CVLCPT",
  "anlsPrdExpln": "2026.01.01 ~ 2026.01.31",
  "creatrNm": "홍길동",
  "deptCd": null,
  "deptNm": null,
  "sggNm": "제주시",
  "emdCd": "50110137",
  "emdNm": "연동",
  "regionNm": "제주시 연동",
  "sttsCd": "DONE",
  "sttsNm": "완료",
  "fileFmt": "PDF",
  "crtYmd": "2026-02-01T09:00:00"
}
```

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | Spring Boot 기본 오류 JSON | `rptpId` 가 숫자가 아님 |
| 401 | 없음 | 세션 없음 |
| 401 | Spring Boot 기본 오류 JSON | 세션 사용자가 없거나 사용 중지됨 |
| 404 | Spring Boot 기본 오류 JSON | 보고서가 없거나 요청자가 볼 수 없음 |

## 3. DELETE /api/reports/{rptpId}

### API 개요
직접 생성(`MANUAL`)·AI 생성(`AI`) 보고서 1건을 영구 삭제한다. `TB_RPTP` 행을 지우며 AI 보고서 본문(`TB_AI_RPTP_CN`)도 함께 삭제된다. 연결된 분석 실행·결과는 남는다.

로그인 세션과 CSRF 토큰이 필요하다. `MANUAL`·`AI` 는 생성자 본인만 삭제할 수 있다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |
| cookie | `XSRF-TOKEN` | String | 필수 | 없음 | CSRF 토큰 쿠키 |
| header | `X-XSRF-TOKEN` | String | 필수 | 없음 | `XSRF-TOKEN` 쿠키와 같은 값 |
| path | `rptpId` | Long | 필수 | 없음 | 보고서 ID |

### 호출 예시
```bash
curl -X DELETE http://localhost:8180/api/reports/1001 \
  -H 'Cookie: JSESSIONID=<세션ID>; XSRF-TOKEN=<CSRF토큰>' \
  -H 'X-XSRF-TOKEN: <CSRF토큰>'
```

### 응답
`204 No Content` · 본문 없음

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | Spring Boot 기본 오류 JSON | `rptpId` 가 숫자가 아님, 또는 요청자가 볼 수 있는 `AUTO` 보고서를 삭제하려 함 |
| 401 | 없음 | 세션 없음 |
| 401 | Spring Boot 기본 오류 JSON | 세션 사용자가 없거나 사용 중지됨 |
| 403 | Spring Boot 기본 오류 JSON | CSRF 토큰 누락·불일치 |
| 404 | Spring Boot 기본 오류 JSON | 보고서가 없거나 요청자가 볼 수 없음 |

## 4. GET /api/reports/{rptpId}/hotspot

### API 개요
저장된 집중구역 분석 보고서를 생성 시점 스냅샷으로 조회한다. 다시 분석하지 않는다.

로그인 세션이 필요하다. 열람 범위는 2번 API 와 같다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |
| path | `rptpId` | Long | 필수 | 없음 | 보고서 ID |

### 호출 예시
```bash
curl http://localhost:8180/api/reports/1001/hotspot \
  -H 'Cookie: JSESSIONID=<세션ID>'
```

### 응답
`200 OK` · `application/json`

```json
{
  "rptpId": 1001,
  "excnId": 2001,
  "rptpNm": "집중 구역 분석 보고서 제주시 연동",
  "regionNm": "제주시 연동",
  "anlsPrdExpln": "2026.01.01 ~ 2026.01.31",
  "fileFmt": "PDF",
  "crtYmd": "2026-02-01T09:00:00",
  "weights": [
    { "anlsTypeCd": "HOTSPOT", "vrblCd": "COMPLAINT", "vrblNm": "민원빈도", "vrblSeCd": "WGVL", "stngVl": 60, "unitNm": "%", "useYn": "Y" }
  ],
  "wgvlAjmtYn": "N",
  "anlsMthd": "LIST_MAX",
  "gradeCriteria": [ { "grade": "SEVERE", "gradeNm": "심각", "maxRankPct": 5 } ],
  "vrblAjmt": { "ajmtYn": "N", "vrbls": [] },
  "zones": [
    {
      "rnk": 1,
      "zoneNm": "예시 구역",
      "emdNm": null,
      "zoneNmSrc": null,
      "cvlcptCnt": 10,
      "crdnCnt": 20,
      "snthsScr": 100.00,
      "grade": "SEVERE",
      "gradeNm": "심각",
      "mrkLat": 33.4869,
      "mrkLot": 126.4903
    }
  ],
  "wbk": {
    "cvlcptWgvl": 60,
    "maxCvlcptCnt": 10,
    "maxCrdnCnt": 20,
    "scrCnts": [ { "cvlcptWgvl": 60, "scrCnt": 100 } ],
    "zones": [ { "cid": 1, "zoneNm": "예시 구역", "emdNm": "연동", "cvlcptCnt": 10, "crdnCnt": 20 } ]
  }
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `rptpId` | Long | 보고서 ID |
| `excnId` | Long | 분석 실행 ID |
| `rptpNm` | String | 보고서명 |
| `regionNm` | String \| null | 지역 표시명 |
| `anlsPrdExpln` | String \| null | 분석기간 설명 |
| `fileFmt` | String \| null | 파일 형식. `PDF` \| `DOCX` \| `XLSX`. 자동 생성은 null |
| `crtYmd` | String(ISO-8601) \| null | 생성일시 |
| `weights[]` | Array | 생성 시 적용한 가중치. 저장값이 없거나 읽을 수 없으면 빈 배열 |
| `weights[].anlsTypeCd` | String \| null | 분석유형 코드 |
| `weights[].anlsTrgtCd` | String \| null | 분석내용 코드 |
| `weights[].anlsTrgtNm` | String \| null | 분석내용명 |
| `weights[].vrblCd` | String \| null | 변수 코드 |
| `weights[].vrblNm` | String \| null | 변수명 |
| `weights[].vrblExpln` | String \| null | 변수 설명 |
| `weights[].vrblSeCd` | String \| null | 변수 구분. `WGVL` 가중치 \| `CFC` 계수 |
| `weights[].stngVl` | Number \| null | 설정값 |
| `weights[].bscStngVl` | Number \| null | 기본 설정값 |
| `weights[].minVl` | Number \| null | 최소값 |
| `weights[].maxVl` | Number \| null | 최대값 |
| `weights[].unitNm` | String \| null | 단위명 |
| `weights[].useYn` | String \| null | 사용 여부. `Y` \| `N` |
| `weights[].expsrYn` | String \| null | 노출 여부. `Y` \| `N` |
| `weights[].sortSeq` | Integer \| null | 정렬 순서 |
| `weights[].mdfcnDt` | String(ISO-8601) \| null | 수정일시 |
| `wgvlAjmtYn` | String \| null | 가중치 조정 여부. `Y` \| `N`. 값을 저장하기 전 보고서는 null |
| `anlsMthd` | String \| null | 점수 산정 방식. `LIST_MAX`. 값을 저장하기 전 보고서는 null |
| `gradeCriteria[]` | Array \| null | 생성 시 등급 비율. 값을 저장하기 전 보고서는 null |
| `gradeCriteria[].grade` | String | 등급 코드 |
| `gradeCriteria[].gradeNm` | String | 등급명 |
| `gradeCriteria[].maxRankPct` | int | 순위 비율 상한(%) |
| `vrblAjmt` | Object \| null | 분석 계수 적용 내역 `{ajmtYn, vrbls:[{vrblCd, vrblNm, unitNm, stngVl, aplcnVl}]}`. 값을 저장하기 전 보고서는 null |
| `zones[]` | Array | 집중구역. 저장 시점의 `rnk` 오름차순 |
| `zones[].rnk` | int | 순위 |
| `zones[].zoneNm` | String | 구역명 |
| `zones[].emdNm` | null | 스냅샷에 저장하지 않아 항상 null |
| `zones[].zoneNmSrc` | null | 스냅샷에 저장하지 않아 항상 null |
| `zones[].cvlcptCnt` | int | 민원 건수 |
| `zones[].crdnCnt` | int | 단속 건수 |
| `zones[].snthsScr` | Number | 종합점수 |
| `zones[].grade` | String | 등급 코드. `SEVERE` \| `WARN` \| `CAUTION` \| `NORMAL` |
| `zones[].gradeNm` | String | 등급명 |
| `zones[].mrkLat` | Number | 마커 위도(EPSG:4326) |
| `zones[].mrkLot` | Number | 마커 경도(EPSG:4326) |
| `wbk` | Object \| null | 엑셀 재계산 데이터. 값을 저장하기 전 보고서는 null |
| `wbk.cvlcptWgvl` | Number | 적용 민원 가중치(%) |
| `wbk.maxCvlcptCnt` | int | 전체 군집 최대 민원 건수 |
| `wbk.maxCrdnCnt` | int | 전체 군집 최대 단속 건수 |
| `wbk.scrCnts[].cvlcptWgvl` | Number | 민원 가중치(%) |
| `wbk.scrCnts[].scrCnt` | long | 해당 가중치에서 0점을 뺀 군집 수 |
| `wbk.zones[].cid` | int | 군집 번호 |
| `wbk.zones[].zoneNm` | String | 구역명 |
| `wbk.zones[].emdNm` | String \| null | 읍면동명 |
| `wbk.zones[].cvlcptCnt` | int | 민원 건수 |
| `wbk.zones[].crdnCnt` | int | 단속 건수 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | Spring Boot 기본 오류 JSON | `rptpId` 가 숫자가 아님 |
| 401 | 없음 | 세션 없음 |
| 401 | Spring Boot 기본 오류 JSON | 세션 사용자가 없거나 사용 중지됨 |
| 404 | Spring Boot 기본 오류 JSON | 보고서가 없음, 요청자가 볼 수 없음, 집중구역 보고서가 아님, 저장된 구역이 0건 |
| 500 | Spring Boot 기본 오류 JSON | 저장된 `zones` 를 읽을 수 없거나 등급 코드가 유효하지 않음 |

## 5. GET /api/reports/{rptpId}/complaint

### API 개요
저장된 민원현황 보고서를 생성 시점 스냅샷으로 조회한다. 다시 집계하지 않으며, 민원 0건 보고서도 정상 응답한다.

로그인 세션이 필요하다. 열람 범위는 2번 API 와 같다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |
| path | `rptpId` | Long | 필수 | 없음 | 보고서 ID |

### 호출 예시
```bash
curl http://localhost:8180/api/reports/1001/complaint \
  -H 'Cookie: JSESSIONID=<세션ID>'
```

### 응답
`200 OK` · `application/json`

```json
{
  "rptpId": 1001,
  "excnId": 2001,
  "rptpNm": "민원 현황 보고서 제주시 연동",
  "regionNm": "제주시 연동",
  "anlsPrdExpln": "2026.01.01 ~ 2026.01.31",
  "fileFmt": "PDF",
  "crtYmd": "2026-02-01T09:00:00",
  "kpi": {
    "totalCount": 100,
    "negativityRatio": 0.25,
    "sentimentDist": { "positive": 10, "neutral": 65, "negative": 25 },
    "enforcementCount": 3,
    "shortageCount": 2,
    "prevAnlsPrdExpln": "2025.12.01 ~ 2025.12.31",
    "prevTotalCount": 90
  },
  "emdCompare": [
    { "rank": 1, "emdCd": "50110137", "emdNm": "연동", "cvlcptCnt": 40, "deltaPct": 10, "topTypeCd": "01", "topTypeNm": "안전시설 인근 위반", "topTypeRatio": 0.5, "deltaPctInferred": false, "prevCnt": 36 }
  ],
  "negEmdCompare": [
    { "rank": 1, "emdCd": "50110137", "emdNm": "연동", "negativeCount": 10, "totalCount": 40, "sentimentDist": { "positive": 5, "neutral": 25, "negative": 10 }, "negativeRatio": 0.25, "deltaPct": 5, "deltaPctInferred": false }
  ],
  "crdnCompare": [
    { "rank": 1, "emdCd": "50110137", "emdNm": "연동", "crdnCnt": 50, "cvlcptCnt": 40, "deltaPct": 8, "deltaPctInferred": false, "prevCrdnCnt": 46, "topCrdnMthd": "고정형", "topCrdnMthdRatio": 0.6 }
  ],
  "shortageCompare": [
    { "rank": 1, "emdCd": "50110137", "emdNm": "연동", "shortageRatio": 0.7838, "pkltCpctySum": 100, "demandIndex": 140, "shortageDeltaPp": 1.2, "deltaPctInferred": false, "prevShortageRatio": 0.7718 }
  ]
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `rptpId` | Long | 보고서 ID |
| `excnId` | Long | 분석 실행 ID |
| `rptpNm` | String | 보고서명 |
| `regionNm` | String \| null | 지역 표시명 |
| `anlsPrdExpln` | String \| null | 분석기간 설명 |
| `fileFmt` | String \| null | 파일 형식. 자동 생성은 null |
| `crtYmd` | String(ISO-8601) \| null | 생성일시 |
| `kpi.totalCount` | long | 총 민원 건수 |
| `kpi.negativityRatio` | double | 부정 비율(0.0~1.0) |
| `kpi.sentimentDist.positive` | long | 긍정 건수 |
| `kpi.sentimentDist.neutral` | long | 보통 건수 |
| `kpi.sentimentDist.negative` | long | 부정 건수 |
| `kpi.enforcementCount` | long | 단속 집중 지역 중 심각 등급 읍면동 수 |
| `kpi.shortageCount` | long | 주차 민원이 있는 구역 수의 상위 10% |
| `kpi.prevAnlsPrdExpln` | String \| null | 직전 동일 길이 기간 표기. 값을 저장하기 전 보고서는 null |
| `kpi.prevTotalCount` | Long \| null | 직전 기간 민원 건수. 값을 저장하기 전 보고서는 null |
| `emdCompare[].rank` | int | 순위(민원 건수 내림차순, 같으면 읍면동 코드 오름차순) |
| `emdCompare[].emdCd` | String | 읍면동 코드 |
| `emdCompare[].emdNm` | String | 읍면동명 |
| `emdCompare[].cvlcptCnt` | long | 민원 건수 |
| `emdCompare[].deltaPct` | int | 직전 기간 대비 증감률(%) |
| `emdCompare[].topTypeCd` | String \| null | 최다 민원 유형 코드 |
| `emdCompare[].topTypeNm` | String \| null | 최다 민원 유형명 |
| `emdCompare[].topTypeRatio` | double | 최다 유형 비율(0.0~1.0) |
| `emdCompare[].deltaPctInferred` | boolean | 직전 건수가 0이라 분모를 1로 치환했으면 true |
| `emdCompare[].prevCnt` | Long \| null | 직전 기간 민원 건수 |
| `negEmdCompare[].rank` | int | 순위(부정 비율 내림차순, 같으면 읍면동 코드 오름차순) |
| `negEmdCompare[].emdCd` | String | 읍면동 코드 |
| `negEmdCompare[].emdNm` | String | 읍면동명 |
| `negEmdCompare[].negativeCount` | long | 부정 민원 건수 |
| `negEmdCompare[].totalCount` | long | 감성 분석 완료 민원 건수 |
| `negEmdCompare[].sentimentDist` | Object | `positive`, `neutral`, `negative` 건수(long) |
| `negEmdCompare[].negativeRatio` | double | 부정 비율(0.0~1.0) |
| `negEmdCompare[].deltaPct` | int | 부정 건수 증감률(%) |
| `negEmdCompare[].deltaPctInferred` | boolean | 직전 부정 건수가 0이라 분모를 1로 치환했으면 true |
| `crdnCompare[].rank` | long | 순위. 동률은 같은 순위 |
| `crdnCompare[].emdCd` | String | 읍면동 코드 |
| `crdnCompare[].emdNm` | String | 읍면동명 |
| `crdnCompare[].crdnCnt` | long | 단속 건수 |
| `crdnCompare[].cvlcptCnt` | long | 민원 건수 |
| `crdnCompare[].deltaPct` | Integer \| null | 단속 건수 증감률(%). 산출 불가면 null |
| `crdnCompare[].deltaPctInferred` | boolean | 직전 단속이 0건이라 분모를 1로 치환했으면 true |
| `crdnCompare[].prevCrdnCnt` | Long \| null | 직전 기간 단속 건수 |
| `crdnCompare[].topCrdnMthd` | String \| null | 최다 단속 유형 |
| `crdnCompare[].topCrdnMthdRatio` | Double \| null | 최다 단속 유형 비율(0~1) |
| `shortageCompare[].rank` | long | 순위. 동률은 같은 순위 |
| `shortageCompare[].emdCd` | String | 읍면동 코드 |
| `shortageCompare[].emdNm` | String | 읍면동명 |
| `shortageCompare[].shortageRatio` | Number \| null | 부족률(0~1, 소수 4자리). 분모가 0이면 null |
| `shortageCompare[].pkltCpctySum` | Number | 공급 면수 |
| `shortageCompare[].demandIndex` | Number | 주차 수요 지수(공급 면수 + 주차 관련 민원 수) |
| `shortageCompare[].shortageDeltaPp` | Number \| null | 부족률 증감(%p). 직전 분모가 0이면 null |
| `shortageCompare[].deltaPctInferred` | boolean | 직전 분모가 0이라 증감을 낼 수 없으면 true |
| `shortageCompare[].prevShortageRatio` | Number \| null | 직전 기간 부족률(0~1) |

`emdCompare`, `negEmdCompare`, `crdnCompare`, `shortageCompare` 는 값이 없으면 빈 배열이다.

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | Spring Boot 기본 오류 JSON | `rptpId` 가 숫자가 아님 |
| 401 | 없음 | 세션 없음 |
| 401 | Spring Boot 기본 오류 JSON | 세션 사용자가 없거나 사용 중지됨 |
| 404 | Spring Boot 기본 오류 JSON | 보고서가 없음, 요청자가 볼 수 없음, 민원현황 보고서가 아님(`anlsTypeCd` 가 `CVLCPT` 가 아님), 저장 결과가 없거나 읽을 수 없음 |

## 6. GET /api/reports/{rptpId}/investment-priority

### API 개요
저장된 투자우선순위 보고서를 저장 시점 스냅샷으로 조회한다. 다시 계산하지 않는다.

로그인 세션이 필요하다. 열람 범위는 2번 API 와 같다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |
| path | `rptpId` | Long | 필수 | 없음 | 보고서 ID |

### 호출 예시
```bash
curl http://localhost:8180/api/reports/1001/investment-priority \
  -H 'Cookie: JSESSIONID=<세션ID>'
```

### 응답
`200 OK` · `application/json`

```json
{
  "rptpId": 1001,
  "excnId": 2001,
  "rptpNm": "투자 우선순위 보고서 전체 지역",
  "regionNm": "전체 지역",
  "anlsPrdExpln": "2026.01.01 ~ 2026.01.31",
  "fileFmt": "PDF",
  "crtYmd": "2026-02-01T09:00:00",
  "bgngYmd": "20260101",
  "endYmd": "20260131",
  "scoreFormula": "안전 25% + 민원 25% + 이용 25% + 단속 25%",
  "weights": [ { "vrblCd": "COMPLAINT", "vrblNm": "민원빈도", "stngVl": 25 } ],
  "gradeCriteria": [ { "prrtLvlCd": "H", "prrtLvlNm": "높음", "minScr": 80 } ],
  "crtrVls": { "altmntRngVl": 300, "indctPkltCnt": 50 },
  "vrblAjmt": { "ajmtYn": "N", "vrbls": [] },
  "warnings": [ { "code": "SFTY_CHCK_MISSING", "message": "안전점검 기록이 없는 주차장이 있습니다.", "cnt": 3 } ],
  "smry": { "trgtCnt": 100, "highCnt": 5, "acdntCnt": 2 },
  "lots": [
    { "rnk": 1, "sggRnk": 1, "crdnExclRnk": 1, "pkltId": 1, "pkltNm": "예시 공영주차장", "snthsScr": 90.123456789, "prrtLvlCd": "H", "rsnCd": "BOTH" }
  ],
  "wbk": {
    "trgtCnt": 100,
    "rows": [ { "pkltId": 1, "cvlcptLtCnt": 90, "utztnLtCnt": 80, "acdntLtCnt": 0 } ],
    "vrbls": [ { "vrblCd": "EXAMPLE", "vrblNm": "예시 계수", "aplcnVl": 5, "minVl": 1, "maxVl": 30, "intOnlyYn": "N", "inptYn": "Y" } ]
  }
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `rptpId` | Long | 보고서 ID |
| `excnId` | Long | 분석 실행 ID |
| `rptpNm` | String | 보고서명 |
| `regionNm` | String \| null | 지역 표시명 |
| `anlsPrdExpln` | String \| null | 분석기간 설명 |
| `fileFmt` | String \| null | 파일 형식 |
| `crtYmd` | String(ISO-8601) \| null | 생성일시 |
| `bgngYmd` | String(`YYYYMMDD`) \| null | 적용한 분석 시작일 |
| `endYmd` | String(`YYYYMMDD`) \| null | 적용한 분석 종료일(포함) |
| `scoreFormula` | String \| null | 점수 산식 표기 |
| `weights[]` | Array | 계산에 쓴 가중치. 필드는 4번 API 의 `weights[]` 와 같다 |
| `gradeCriteria[].prrtLvlCd` | String | 우선순위 등급 코드. `H` \| `M` \| `L` |
| `gradeCriteria[].prrtLvlNm` | String | 우선순위 등급명 |
| `gradeCriteria[].minScr` | Number | 해당 등급 최소 종합점수(이상) |
| `crtrVls.altmntRngVl` | Number | 최근접 배정 반경(m) |
| `crtrVls.indctPkltCnt` | Integer | 목록·지도에 표시할 상위 주차장 수. `lots` 를 자르지 않는다 |
| `vrblAjmt` | Object \| null | 분석 계수 적용 내역 `{ajmtYn, vrbls:[{vrblCd, vrblNm, unitNm, stngVl, aplcnVl}]}` |
| `warnings[].code` | String | `CRDN_SGG_SKEW` \| `SFTY_CHCK_MISSING` \| `SFTY_CD_UNKNOWN` \| `ACDNT_NO_DATA` \| `UTZTN_PARTIAL` \| `UTZTN_MISSING` \| `WGVL_NOT_APPLIED` |
| `warnings[].message` | String | 알림 설명 |
| `warnings[].cnt` | Integer \| null | 해당 주차장 수 |
| `smry.trgtCnt` | Integer | 전 지역 분석 대상 주차장 수 |
| `smry.highCnt` | Integer | 전 지역 높음(`H`) 주차장 수 |
| `smry.acdntCnt` | Long \| null | 기간 내 사고 건수. 사고 자료가 없으면 null |
| `lots[].rnk` | Integer | 전 지역 기준 순위. 오름차순으로 내려간다 |
| `lots[].sggRnk` | Integer \| null | 시군구 내 순위 |
| `lots[].crdnExclRnk` | Integer \| null | 단속을 뺀 순위 |
| `lots[].pkltId` | Long | 주차장 ID |
| `lots[].pkltNm` | String | 주차장명 |
| `lots[].pkltAddr` | String \| null | 주차장 주소 |
| `lots[].sggNm` | String \| null | 시군구명 |
| `lots[].emdCd` | String \| null | 읍면동 코드 |
| `lots[].emdNm` | String \| null | 읍면동명 |
| `lots[].pkltCpcty` | Integer \| null | 주차면수 |
| `lots[].instlYm` | String(`YYYYMM`) \| null | 설치연월 |
| `lots[].lat` | Number | 위도 |
| `lots[].lot` | Number | 경도 |
| `lots[].snthsScr` | Number | 종합점수(0~100, 소수 9자리) |
| `lots[].prrtLvlCd` | String | 우선순위 등급 코드 |
| `lots[].rsnCd` | String | 사유 코드. `SAFETY` \| `DEMAND` \| `BOTH` \| `NONE` |
| `lots[].sftyChckRsltCd` | String \| null | 안전점검 결과. `A` \| `B` \| `C` |
| `lots[].sftyChckYmd` | String(`YYYYMMDD`) \| null | 안전점검 일자 |
| `lots[].sftyRiskScr` | Number | 안전위험 점수 |
| `lots[].cvlcptFrqScr` | Number | 민원빈도 점수 |
| `lots[].utztnPttnScr` | Number | 이용패턴 점수 |
| `lots[].crdnScr` | Number | 단속 점수 |
| `lots[].acdntScr` | Number | 사고 점수 |
| `lots[].cvlcptCnt` | Long | 배정 민원 수 |
| `lots[].dayAvgTrnvrRt` | Number \| null | 일평균 회전율 |
| `lots[].crdnCnt` | Long | 배정 단속 수 |
| `lots[].acdntCnt` | Long | 사고 건수 |
| `wbk` | Object | 엑셀 재계산 데이터. 값이 없으면 키가 응답에 없다 |
| `wbk.trgtCnt` | Integer | 백분위 모집단 수 |
| `wbk.rows[].pkltId` | Long | 주차장 ID |
| `wbk.rows[].cvlcptLtCnt` | Integer | 민원 백분위 개수 |
| `wbk.rows[].utztnLtCnt` | Integer | 이용 백분위 개수 |
| `wbk.rows[].acdntLtCnt` | Integer | 사고 백분위 개수 |
| `wbk.vrbls[].vrblCd` | String | 변수 코드 |
| `wbk.vrbls[].vrblNm` | String | 변수명 |
| `wbk.vrbls[].aplcnVl` | Number | 적용값 |
| `wbk.vrbls[].minVl` | Number \| null | 최솟값 |
| `wbk.vrbls[].maxVl` | Number \| null | 최댓값 |
| `wbk.vrbls[].intOnlyYn` | String | 정수 전용 여부. `Y` \| `N` |
| `wbk.vrbls[].inptYn` | String | 입력 여부. `Y` \| `N` |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | Spring Boot 기본 오류 JSON | `rptpId` 가 숫자가 아님 |
| 401 | 없음 | 세션 없음 |
| 401 | Spring Boot 기본 오류 JSON | 세션 사용자가 없거나 사용 중지됨 |
| 404 | Spring Boot 기본 오류 JSON | 보고서가 없음, 요청자가 볼 수 없음, 투자우선순위 보고서가 아님(`anlsTypeCd` 가 `INVEST` 가 아님), 저장 결과가 없음 |
| 500 | Spring Boot 기본 오류 JSON | 저장된 분석 결과 또는 계수 적용 내역을 읽을 수 없음 |

## 7. GET /api/reports/{rptpId}/zone-recommendation

### API 개요
저장된 구역추천 보고서를 저장 시점 스냅샷으로 조회한다. `rcmdtnTypeCd` 가 `FEE` 면 `fee` 만, `SHARE` 면 `share` 만 채워지고 다른 쪽은 null 이다.

로그인 세션이 필요하다. 열람 범위는 2번 API 와 같다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |
| path | `rptpId` | Long | 필수 | 없음 | 보고서 ID |

### 호출 예시
```bash
curl http://localhost:8180/api/reports/1001/zone-recommendation \
  -H 'Cookie: JSESSIONID=<세션ID>'
```

### 응답
`200 OK` · `application/json`

```json
{
  "rptpId": 1001,
  "excnId": 2001,
  "rptpNm": "구역추천 요금제 적용 보고서 제주시 연동",
  "regionNm": "제주시 연동",
  "anlsPrdExpln": "2026.01.01 ~ 2026.01.31",
  "fileFmt": "PDF",
  "crtYmd": "2026-02-01T09:00:00",
  "bgngYmd": "20260101",
  "endYmd": "20260131",
  "vrblAjmt": { "ajmtYn": "N", "vrbls": [] },
  "rcmdtnTypeCd": "FEE",
  "fee": {
    "excnId": null,
    "scope": "EMD",
    "sggNm": "제주시",
    "anlsMthd": "RVW_SCR",
    "crtrVls": { "trgtTypeSe": "NARROW", "altmntRngVl": 300, "swtcTrgtMinCpcty": 20, "minTrendBase": 10, "trendDayCnt": 15 },
    "vrblAjmt": null,
    "warnings": [],
    "zone": { "emdCd": "50110137", "emdNm": "연동", "sggNm": "제주시" },
    "asgn": { "cvlcptTot": 100, "cvlcptAsgn": 30, "asgnRt": 30.0 },
    "lots": [ { "rnk": 1, "pkltId": 1, "pkltNm": "예시 공영주차장", "rvwGrdCd": "HIGH" } ],
    "exclLots": []
  },
  "share": null
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `rptpId` | Long | 보고서 ID |
| `excnId` | Long | 분석 실행 ID |
| `rptpNm` | String | 보고서명 |
| `regionNm` | String \| null | 지역 표시명 |
| `anlsPrdExpln` | String \| null | 분석기간 설명 |
| `fileFmt` | String \| null | 파일 형식 |
| `crtYmd` | String(ISO-8601) \| null | 생성일시 |
| `bgngYmd` | String(`YYYYMMDD`) \| null | 적용한 분석 시작일 |
| `endYmd` | String(`YYYYMMDD`) \| null | 적용한 분석 종료일(포함) |
| `vrblAjmt` | Object \| null | 분석 계수 적용 내역 `{ajmtYn, vrbls:[{vrblCd, vrblNm, unitNm, stngVl, aplcnVl}]}` |
| `rcmdtnTypeCd` | String | 보기 판별 키. `FEE` \| `SHARE` |
| `fee` | Object \| null | 요금제 적용 본문 |
| `fee.excnId` | null | 항상 null. 실행 ID 는 최상위 `excnId` 를 쓴다 |
| `fee.scope` | String \| null | 실행 범위. `EMD` \| `SGG` |
| `fee.sggNm` | String \| null | 시군구명 |
| `fee.anlsMthd` | String \| null | 분석 방법. `RVW_SCR` |
| `fee.crtrVls.trgtTypeSe` | String | 후보 유형 범위. `NARROW` \| `WIDE` |
| `fee.crtrVls.altmntRngVl` | Number | 최근접 배정 반경(m) |
| `fee.crtrVls.swtcTrgtMinCpcty` | Number | 후보 최소 면수 |
| `fee.crtrVls.minTrendBase` | int | 증가율 분모 하한(건) |
| `fee.crtrVls.trendDayCnt` | Integer \| null | 증가율 비교 블록 일수 |
| `fee.crtrVls.feeCvlcptCntWgvl` | Number \| null | 민원강도 가중치(%) |
| `fee.crtrVls.feeCvlcptRtWgvl` | Number \| null | 면당민원 가중치(%) |
| `fee.crtrVls.feeCvlcptMmCrtrJeju` | Number \| null | 제주시 민원 월 기준값 |
| `fee.crtrVls.feeCvlcptMmCrtrSeogwipo` | Number \| null | 서귀포시 민원 월 기준값 |
| `fee.crtrVls.feeCvlcptRtCrtrJeju` | Number \| null | 제주시 면당민원 기준값 |
| `fee.crtrVls.feeCvlcptRtCrtrSeogwipo` | Number \| null | 서귀포시 면당민원 기준값 |
| `fee.crtrVls.feeCvlcptIncrsWgvl` | Number \| null | 민원증가 가중치(배) |
| `fee.crtrVls.feeCvlcptIncrsRtUplmtVl` | Number \| null | 민원증가율 상한(%) |
| `fee.vrblAjmt` | null | 항상 null. 최상위 `vrblAjmt` 를 쓴다 |
| `fee.warnings[]` | Array(String) | 경고 코드. 정상이면 빈 배열 |
| `fee.zone` | Object \| null | 선택 읍면동. 시군구 모드는 null |
| `fee.zone.emdCd` / `emdNm` / `sggNm` | String | 읍면동 코드 / 읍면동명 / 시군구명 |
| `fee.asgn` | Object \| null | 배정 커버리지. 시군구 모드는 null |
| `fee.asgn.cvlcptTot` | long | 조회 구간 민원 총건수 |
| `fee.asgn.cvlcptAsgn` | long | 배정된 민원 합 |
| `fee.asgn.asgnRt` | Number \| null | 배정률(%) |
| `fee.lots[].rnk` | Integer | 순위 |
| `fee.lots[].pkltId` | Long | 주차장 ID |
| `fee.lots[].pkltNm` | String | 주차장명 |
| `fee.lots[].pkltTypeNm` | String \| null | 주차장 유형명 |
| `fee.lots[].pkltCpcty` | Integer \| null | 주차면수 |
| `fee.lots[].pkltAddr` | String \| null | 주차장 주소 |
| `fee.lots[].sggNm` | String \| null | 시군구명 |
| `fee.lots[].emdCd` | String \| null | 읍면동 코드 |
| `fee.lots[].emdNm` | String \| null | 읍면동명 |
| `fee.lots[].lat` | Number | 위도 |
| `fee.lots[].lot` | Number | 경도 |
| `fee.lots[].asgnCvlcptCnt` | Long | 배정 민원 수 |
| `fee.lots[].asgnCvlcptCntRcnt` | Long \| null | 최근 블록 배정 민원 수 |
| `fee.lots[].asgnCvlcptCntBfr` | Long \| null | 이전 블록 배정 민원 수 |
| `fee.lots[].asgnCrdnCnt` | Long | 배정 단속 수 |
| `fee.lots[].cvlcptPerSlot` | Number \| null | 면당 민원 |
| `fee.lots[].cumCpcty` | Number | 누적 주차면수 |
| `fee.lots[].cvlcptIncrsRt` | Number \| null | 민원 증가율(%) |
| `fee.lots[].trendMm[].ym` | String(`YYYY-MM`) | 연월 |
| `fee.lots[].trendMm[].cnt` | long | 해당 월 배정 민원 건수 |
| `fee.lots[].cvlcptCntScr` | Number \| null | 민원강도 점수 |
| `fee.lots[].cvlcptRtScr` | Number \| null | 면당압력 점수 |
| `fee.lots[].rvwScr` | Number \| null | 검토지수 |
| `fee.lots[].rvwGrdCd` | String \| null | 우선도 단계. `TOP` \| `HIGH` \| `LOW` |
| `fee.lots[].incrsCfc` | Number \| null | 증가계수 |
| `fee.lots[].incrsRfltRvwScr` | Number \| null | 우선도(검토지수 × 증가계수) |
| `fee.exclLots[]` | Array | 스냅샷에 저장하지 않아 항상 빈 배열 |
| `fee.wbk` | Object | 엑셀 재계산 데이터. 값이 없으면 키가 응답에 없다 |
| `fee.wbk.months` | Number | 민원 창 개월수 |
| `fee.wbk.fixed.prrtTop` | Number | 종합지수 높음 하한 |
| `fee.wbk.fixed.prrtHigh` | Number | 종합지수 중간 하한 |
| `fee.wbk.vrbls[]` | Array | 분석 계수. 필드는 6번 API 의 `wbk.vrbls[]` 와 같다 |
| `share` | Object \| null | 공유주차제 적용 본문 |
| `share.excnId` | null | 항상 null |
| `share.crtrVls.cvlcptMmCrtrVl` | Number | 민원 월기준값(건/월) |
| `share.crtrVls.cvlcptRtCrtrVl` | Number | 민원 비율기준값(100면당 월평균) |
| `share.crtrVls.coreMltp` | Number | 핵심 후보 배수 |
| `share.crtrVls.months` | Number | 조회 개월 수 |
| `share.crtrVls.incrsWgvl` | Number | 민원증가가중치 |
| `share.crtrVls.incrsRtUplmtVl` | Number | 민원증가율상한값(%) |
| `share.crtrVls.minTrendBase` | Integer | 증가율 분모 하한(건) |
| `share.crtrVls.trendDayCnt` | Integer \| null | 증가율 블록 일수 |
| `share.vrblAjmt` | null | 항상 null. 최상위 `vrblAjmt` 를 쓴다 |
| `share.dataBgngYmd` | String(`YYYYMMDD`) \| null | 자료 구간 시작일 |
| `share.dataEndYmd` | String(`YYYYMMDD`) \| null | 자료 구간 종료일 |
| `share.warnings[]` | Array(String) | `CRTR_FALLBACK` \| `NO_CVLCPT_IN_RANGE` \| `CRTR_ALL_ZERO` \| `TREND_WINDOW_SHORT` \| `TREND_BFR_FALLBACK` |
| `share.smry.emdCnt` | Integer | 읍면동 수 |
| `share.smry.rvwTrgtCnt` | Integer | 후보 읍면동 수 |
| `share.smry.coreCnt` | Integer | 핵심 후보 읍면동 수 |
| `share.smry.cvlcptOnlyCnt` | Integer | 민원 기준만 충족한 읍면동 수 |
| `share.smry.unableCnt` | Integer | 판정 불가 읍면동 수 |
| `share.smry.vaclotExclRvwTrgtCnt` | Integer | 공한지를 뺀 면수 기준 후보 읍면동 수 |
| `share.smry.cvlcptTot` | Long | 주차 민원 총건수 |
| `share.smry.rvwTrgtCvlcptRt` | Number \| null | 후보 읍면동 민원 비중(%) |
| `share.zones[].rnk` | Integer \| null | 표시 순번. 판정 불가면 null |
| `share.zones[].emdCd` | String | 읍면동 코드 |
| `share.zones[].sggNm` | String | 시군구명 |
| `share.zones[].emdNm` | String | 읍면동명 |
| `share.zones[].lat` | Number \| null | 읍면동 중심 위도 |
| `share.zones[].lot` | Number \| null | 읍면동 중심 경도 |
| `share.zones[].cvlcptCnt` | Long | 조회 구간 주차 민원 건수 |
| `share.zones[].cvlcptMmAvg` | Number | 월평균 민원 |
| `share.zones[].pkltCnt` | Long | 공영주차장 개소 수 |
| `share.zones[].pkltCpcty` | Long | 공영 주차면수 합 |
| `share.zones[].cvlcptRt` | Number \| null | 공영 100면당 월평균 민원 |
| `share.zones[].rvwScr` | Number \| null | 검토 지수 |
| `share.zones[].rvwGrdCd` | String | 검토 등급. `CORE` \| `CAND` \| `CVLCPT_ONLY` \| `UNABLE` \| `NONE` |
| `share.zones[].rvwTrgtYn` | String | 검토 대상 여부. `Y` \| `N` |
| `share.zones[].cutnCds[]` | Array(String) | 주의 표시. `TOP_PNT` \| `CRTR_BNDRY` |
| `share.zones[].topPntRt` | Number \| null | 민원 최다 좌표 비중(%) |
| `share.zones[].vaclotExclCvlcptRt` | Number \| null | 공한지 제외 100면당 월평균 민원 |
| `share.zones[].vaclotExclRvwTrgtYn` | String | 공한지 제외 시 후보 여부. `Y` \| `N` |
| `share.zones[].bfrCvlcptCnt` | Long \| null | 이전 블록 민원 건수 |
| `share.zones[].cvlcptIncrsRt` | Number \| null | 민원 증가율(%) |
| `share.zones[].incrsCfc` | Number | 증가계수 |
| `share.zones[].incrsRfltRvwScr` | Number \| null | 추세 반영 지수 |
| `share.wbk` | Object | 엑셀 재계산 데이터. 값이 없으면 키가 응답에 없다 |
| `share.wbk.rows[].emdCd` / `sggNm` / `emdNm` | String | 읍면동 코드 / 시군구명 / 읍면동명 |
| `share.wbk.rows[].cvlcptCnt` | Long | 민원 건수 |
| `share.wbk.rows[].pkltCnt` | Long | 공영주차장 개소 수 |
| `share.wbk.rows[].pkltCpcty` | Long | 공영 주차면수 |
| `share.wbk.rows[].topPntCvlcptCnt` | Long | 최다 지점 민원 건수 |
| `share.wbk.rows[].vaclotExclPkltCpcty` | Long | 공한지 제외 주차면수 |
| `share.wbk.rows[].rcntCnt` | Long \| null | 최근 블록 민원 건수 |
| `share.wbk.rows[].bfrCnt` | Long \| null | 이전 블록 민원 건수 |
| `share.wbk.months` | Number | 개월수 |
| `share.wbk.trendMode` | String | 증가율 블록 방식. `PRIMARY` \| `FALLBACK` \| `NONE` |
| `share.wbk.fixed.coreMltp` | Number | 핵심 후보 배수 |
| `share.wbk.fixed.bndryRt` | Number | 기준 경계 폭 |
| `share.wbk.fixed.minTrendBase` | Integer | 증가율 분모 하한(건) |
| `share.wbk.vrbls[]` | Array | 분석 계수. 필드는 6번 API 의 `wbk.vrbls[]` 와 같다 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | Spring Boot 기본 오류 JSON | `rptpId` 가 숫자가 아님 |
| 401 | 없음 | 세션 없음 |
| 401 | Spring Boot 기본 오류 JSON | 세션 사용자가 없거나 사용 중지됨 |
| 404 | Spring Boot 기본 오류 JSON | 보고서가 없음, 요청자가 볼 수 없음, 구역추천 보고서가 아님(`anlsTypeCd` 가 `RECOMMEND` 가 아님), `rcmdtnTypeCd` 가 `FEE`·`SHARE` 가 아님, 저장 결과가 없음 |
| 500 | Spring Boot 기본 오류 JSON | 저장된 분석 결과 또는 계수 적용 내역을 읽을 수 없음 |

## 8. GET /api/reports/{rptpId}/policy-sim

### API 개요
저장된 정책 시뮬레이션 시나리오 비교 보고서를 저장 시점 스냅샷으로 조회한다. 다시 계산하지 않는다.

로그인 세션이 필요하다. 열람 범위는 2번 API 와 같다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |
| path | `rptpId` | Long | 필수 | 없음 | 보고서 ID |

### 호출 예시
```bash
curl http://localhost:8180/api/reports/1001/policy-sim \
  -H 'Cookie: JSESSIONID=<세션ID>'
```

### 응답
`200 OK` · `application/json`

```json
{
  "rptpId": 1001,
  "excnId": 2001,
  "rptpNm": "20260201(1)_정책 시뮬레이션 시나리오 비교",
  "anlsPrdExpln": "예측 기간 3개월",
  "fileFmt": "PDF",
  "crtYmd": "2026-02-01T09:00:00",
  "headSeq": 1,
  "scenarios": [
    {
      "seq": 1,
      "rcmdYn": true,
      "rsltId": 12,
      "params": { "plcyType": "EXPAND", "predcMm": 3, "sggNm": "제주시", "emdCd": "50110137" },
      "result": {
        "anlsRngCn": "제주시 연동",
        "crtrCvlcptCnt": 100.00,
        "cvlcptRdctRt": -10.00,
        "cstLvlCd": "LOW",
        "excsYn": false
      }
    }
  ]
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `rptpId` | Long | 보고서 ID |
| `excnId` | Long | 분석 실행 ID |
| `rptpNm` | String | 보고서명 |
| `anlsPrdExpln` | String \| null | 대표 시나리오의 예측 기간 설명 |
| `fileFmt` | String \| null | 파일 형식. `PDF` \| `DOCX` |
| `crtYmd` | String(ISO-8601) \| null | 생성일시 |
| `headSeq` | Integer | 대표 시나리오 순번(1부터) |
| `scenarios[].seq` | Integer | 시나리오 순번(1부터, 요청 순서) |
| `scenarios[].rcmdYn` | boolean | 추천 여부. 보고서 안에서 최대 1건 |
| `scenarios[].rsltId` | Long \| null | 저장된 결과 ID. 미저장분은 null |
| `scenarios[].params` | Object | 요청 조건. `plcyType`, `expandMethod`, `addPkltSlot`, `roadMtncType`, `roadMtncLen`, `feeAplcnMthd`, `feeAplcnZone`, `feeIncrsRt`, `predcMm`, `sggNm`, `emdCd`, `vrblAjmt`. 유형이 쓰지 않는 칸은 null |
| `scenarios[].result.anlsRngCn` | String | 조회 범위 라벨 |
| `scenarios[].result.crtrCvlcptCnt` | Number | 기준 월평균 민원 합계(건/월) |
| `scenarios[].result.crtrIlglCnt` | Number | 기준 불법주차 추정 합계(대) |
| `scenarios[].result.crtrCngstIdx` | Number | 기준 주차 혼잡 지수 |
| `scenarios[].result.exsPkltCpcty` | Long | 기존 공영주차면수 합계 |
| `scenarios[].result.vldAddPkltSlot` | Number \| null | 유효 증설 면수. 요금제는 null |
| `scenarios[].result.cvlcptRdctRt` | Number | 민원 감소율(%). 음수는 감소 |
| `scenarios[].result.cngstImpvRt` | Number | 주차 혼잡 개선율(%). 양수는 개선 |
| `scenarios[].result.aftrCngstIdx` | Number | 정책 후 주차 혼잡 지수 |
| `scenarios[].result.aftrCvlcptCnt` | Number | 정책 후 월평균 민원 합계(건/월) |
| `scenarios[].result.calcSttsCd` | String \| null | 산출 상태. null(정상) \| `NO_ENF` \| `NO_SPLY` |
| `scenarios[].result.excsYn` | boolean | 흡수 포화 여부 |
| `scenarios[].result.cstAmt` | Number \| null | 예상 비용(원). 이면도로·요금제는 null |
| `scenarios[].result.cstLvlCd` | String | 비용 수준. `LOW` \| `MID` \| `HIGH` \| `TBD` |
| `scenarios[].result.roadMtncType` | String \| null | 이면도로 정비 방식. `RSDNC` \| `CMRC` |
| `scenarios[].result.roadMtncLen` | Integer \| null | 이면도로 정비 구간 연장(m) |
| `scenarios[].result.adjcCvlcptCnt` | Number \| null | 인접 지역 민원 증가분(건/월) |
| `scenarios[].result.adjcEmdCnt` | Integer \| null | 배분에 쓴 인접 읍면동 수 |
| `scenarios[].result.feeAplcnMthd` | String \| null | 요금 적용 방식. `RAISE` \| `CONVERT` |
| `scenarios[].result.feeAplcnZone` | String \| null | 적용 급지. `ALL` \| `Z1` \| `Z2` |
| `scenarios[].result.feeIncrsRt` | Integer \| null | 요금 인상률(%). `10` \| `20` \| `30` |
| `scenarios[].result.aplcnTrgtSlot` | Long \| null | 적용 대상 주차면수 |
| `scenarios[].result.lvngVhclCnt` | Number \| null | 이탈 차량 수(대) |
| `scenarios[].result.absrpCnt` | Number \| null | 흡수 불법주차 수(대) |
| `scenarios[].result.trnsfCnt` | Number \| null | 전이 불법주차 수(대) |
| `scenarios[].result.cvlcptRdctRtRng` | Object \| null | 민원 감소율 민감도 범위. 키별 `[최소, 최대]` |
| `scenarios[].result.wrnngCn` | String \| null | 경고 문구 |
| `scenarios[].result.aplcnParam` | Object | 산출 시점 적용 계수. `occ`, `occTh`, `alpha`, `m`, `wFixed`, `rRet`, `gSgg`(시군구별 Number), `cVacant`, `cPublic`, `cMultflr`, `cstLowBndry`, `cstHighBndry`, `cvlcptPrd`, `crdnPrd`, `n100Rsdnc`, `n100Cmrc`, `alphaB`, `balloonRt`, `eps`, `lambda`, `tau`, `tauLocal` |
| `scenarios[].result.vrblAjmt` | Object \| null | 분석 계수 적용 내역 `{ajmtYn, vrbls:[{vrblCd, vrblNm, unitNm, stngVl, aplcnVl}]}` |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | Spring Boot 기본 오류 JSON | `rptpId` 가 숫자가 아님 |
| 401 | 없음 | 세션 없음 |
| 401 | Spring Boot 기본 오류 JSON | 세션 사용자가 없거나 사용 중지됨 |
| 404 | Spring Boot 기본 오류 JSON | 보고서가 없음, 요청자가 볼 수 없음, 정책 시뮬레이션 보고서가 아님(`anlsTypeCd` 가 `POLICY_SIM` 이 아님), 저장 결과가 없음 |
| 500 | Spring Boot 기본 오류 JSON | 저장된 분석 결과를 읽을 수 없음 |

## 9. GET /api/reports/{rptpId}/ai

### API 개요
등록된 AI 보고서의 HTML 본문을 조회한다. `html` 은 조회 시점에 다시 새니타이즈한 값이다.

로그인 세션이 필요하며, AI 보고서는 생성자 본인만 조회할 수 있다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |
| path | `rptpId` | Long | 필수 | 없음 | 보고서 ID |

### 호출 예시
```bash
curl http://localhost:8180/api/reports/1001/ai \
  -H 'Cookie: JSESSIONID=<세션ID>'
```

### 응답
`200 OK` · `application/json`

```json
{
  "rptpId": 1001,
  "rptpNm": "예시 AI 보고서",
  "html": "<section><h1>예시 AI 보고서</h1><p>본문</p></section>"
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `rptpId` | Long | 보고서 ID |
| `rptpNm` | String | 보고서명 |
| `html` | String | 새니타이즈된 HTML 조각 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | Spring Boot 기본 오류 JSON | `rptpId` 가 숫자가 아님 |
| 401 | 없음 | 세션 없음 |
| 401 | Spring Boot 기본 오류 JSON | 세션 사용자가 없거나 사용 중지됨 |
| 404 | Spring Boot 기본 오류 JSON | 보고서가 없음, 요청자가 볼 수 없음, 저장된 AI 본문이 없음 |

## 10. GET /api/report-settings

### API 개요
삭제되지 않은 보고서 자동 생성 설정 전체를 조회한다. 정렬은 생성일시 내림차순, 같으면 설정 ID 내림차순이며 페이징하지 않는다.

로그인 세션이 필요하다. 메뉴 권한은 검사하지 않는다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |

### 호출 예시
```bash
curl http://localhost:8180/api/report-settings \
  -H 'Cookie: JSESSIONID=<세션ID>'
```

### 응답
`200 OK` · `application/json` · 설정 배열(래퍼 없음)

```json
[
  {
    "stngId": 1,
    "rptpTypeCd": "CVLCPT",
    "rptpTypeNm": "민원현황",
    "crltnDeptCd": "DEPT01",
    "crltnDeptNm": "예시 부서",
    "crltnCyclCd": "MONTH",
    "crltnCyclNm": "월간",
    "crltnMm": null,
    "crltnDd": 5,
    "crltnYmd": "2026-03-05",
    "actvtnYn": true,
    "creatrId": "user01",
    "creatrNm": "홍길동",
    "crtDt": "2026-02-01T09:00:00",
    "lastCrtDt": null,
    "sggNm": "제주시",
    "emdCd": "50110137",
    "emdNm": "연동",
    "regionNm": "제주시 연동"
  }
]
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `[].stngId` | Long | 설정 ID |
| `[].rptpTypeCd` | String | 보고서 종류 코드 |
| `[].rptpTypeNm` | String \| null | 보고서 종류명(공통코드 `ANLS_TYPE_CD`) |
| `[].crltnDeptCd` | String | 배포부서 코드 |
| `[].crltnDeptNm` | String \| null | 배포부서명 |
| `[].crltnCyclCd` | String | 배포주기 코드 |
| `[].crltnCyclNm` | String \| null | 배포주기명 |
| `[].crltnMm` | Integer \| null | 배포 기준월(1~12). 월간은 null |
| `[].crltnDd` | Integer | 배포 기준일(1~28) |
| `[].crltnYmd` | String(`yyyy-MM-dd`) \| null | 다음 배포 예정일. 조회 시점에 계산하며 오늘을 포함한다. 주기가 `MONTH`·`QUARTER`·`HALF`·`YEAR` 가 아니면 null |
| `[].actvtnYn` | Boolean | 활성 여부 |
| `[].creatrId` | String | 생성자 ID |
| `[].creatrNm` | String \| null | 생성자명 |
| `[].crtDt` | String(ISO-8601) | 생성일시 |
| `[].lastCrtDt` | String(ISO-8601) \| null | 최근 보고서 생성일시 |
| `[].sggNm` | String \| null | 시군구명. null 이면 전체 지역 |
| `[].emdCd` | String \| null | 읍면동 코드. null 이면 시군구 전체 |
| `[].emdNm` | String \| null | 읍면동명 |
| `[].regionNm` | String | 지역 표시명. `{시군구} {읍면동}` \| `{시군구} 전체` \| `전체 지역` |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 401 | 없음 | 세션 없음 |

## 11. POST /api/report-settings

### API 개요
보고서 자동 생성 설정을 등록한다. 활성 상태로 저장하며 생성자·생성일시는 서버가 채운다. `TB_RPTP_AUTO_CRT_STNG` 에 1행을 추가한다.

로그인 세션, CSRF 토큰, 보고서 관리 메뉴(`M0402`) 권한이 필요하다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |
| cookie | `XSRF-TOKEN` | String | 필수 | 없음 | CSRF 토큰 쿠키 |
| header | `X-XSRF-TOKEN` | String | 필수 | 없음 | `XSRF-TOKEN` 쿠키와 같은 값 |
| header | `Content-Type` | String | 필수 | 없음 | `application/json` |
| body | `rptpTypeCd` | String | 필수 | 없음 | 보고서 종류. 공통코드 `ANLS_TYPE_CD` 중 사용중이고 `addl_atrb1` 이 `RPTP`·`BOTH` 인 코드 |
| body | `crltnDeptCd` | String | 필수 | 없음 | 배포부서 코드. `TB_DEPT` 의 사용중 부서 |
| body | `crltnCyclCd` | String | 필수 | 없음 | 배포주기. `MONTH` \| `QUARTER` \| `HALF` \| `YEAR` |
| body | `crltnMm` | Integer | 조건부 | 없음 | 배포 기준월(1~12). `MONTH` 외 주기에서 필수. `MONTH` 면 저장하지 않는다 |
| body | `crltnDd` | Integer | 필수 | 없음 | 배포 기준일(1~28) |
| body | `sggNm` | String | 선택 | 없음 | 시군구명. `emdCd` 가 있으면 무시하고 서버가 다시 채운다. 둘 다 없으면 전체 지역 |
| body | `emdCd` | String | 선택 | 없음 | 읍면동 코드. 없으면 시군구 전체 |

### 호출 예시
```bash
curl -X POST http://localhost:8180/api/report-settings \
  -H 'Content-Type: application/json' \
  -H 'Cookie: JSESSIONID=<세션ID>; XSRF-TOKEN=<CSRF토큰>' \
  -H 'X-XSRF-TOKEN: <CSRF토큰>' \
  -d '{"rptpTypeCd":"CVLCPT","crltnDeptCd":"DEPT01","crltnCyclCd":"QUARTER","crltnMm":1,"crltnDd":5,"sggNm":"제주시"}'
```

### 응답
`201 Created` · `application/json`. 본문은 10번 API 의 배열 원소 1건과 같다.

```json
{
  "stngId": 2,
  "rptpTypeCd": "CVLCPT",
  "rptpTypeNm": "민원현황",
  "crltnDeptCd": "DEPT01",
  "crltnDeptNm": "예시 부서",
  "crltnCyclCd": "QUARTER",
  "crltnCyclNm": "분기",
  "crltnMm": 1,
  "crltnDd": 5,
  "crltnYmd": "2026-04-05",
  "actvtnYn": true,
  "creatrId": "user01",
  "creatrNm": "홍길동",
  "crtDt": "2026-02-01T09:00:00",
  "lastCrtDt": null,
  "sggNm": "제주시",
  "emdCd": null,
  "emdNm": null,
  "regionNm": "제주시 전체"
}
```

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | Spring Boot 기본 오류 JSON | JSON 형식 오류, 필수값 누락, `crltnMm`·`crltnDd` 범위 밖, 사용할 수 없는 코드, 지원하지 않는 주기(`WEEK` 등), `MONTH` 외 주기에서 `crltnMm` 누락, 존재하지 않는 읍면동·시군구 |
| 401 | 없음 | 세션 없음 |
| 401 | Spring Boot 기본 오류 JSON | 세션 사용자를 확인할 수 없음 |
| 403 | Spring Boot 기본 오류 JSON | CSRF 토큰 누락·불일치, `M0402` 권한 없음 |
| 415 | Spring Boot 기본 오류 JSON | 지원하지 않는 `Content-Type` |

## 12. PUT /api/report-settings/{stngId}

### API 개요
자동 생성 설정 1건을 수정한다. 검증과 지역 정규화는 등록과 같고, 생성자·생성일시·활성 여부·최근 생성일시는 유지한다.

로그인 세션, CSRF 토큰, 보고서 관리 메뉴(`M0402`) 권한이 필요하다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |
| cookie | `XSRF-TOKEN` | String | 필수 | 없음 | CSRF 토큰 쿠키 |
| header | `X-XSRF-TOKEN` | String | 필수 | 없음 | `XSRF-TOKEN` 쿠키와 같은 값 |
| header | `Content-Type` | String | 필수 | 없음 | `application/json` |
| path | `stngId` | Long | 필수 | 없음 | 설정 ID |
| body | `rptpTypeCd` | String | 필수 | 없음 | 보고서 종류. 공통코드 `ANLS_TYPE_CD` 중 사용중이고 `addl_atrb1` 이 `RPTP`·`BOTH` 인 코드 |
| body | `crltnDeptCd` | String | 필수 | 없음 | 배포부서 코드. `TB_DEPT` 의 사용중 부서 |
| body | `crltnCyclCd` | String | 필수 | 없음 | 배포주기. `MONTH` \| `QUARTER` \| `HALF` \| `YEAR` |
| body | `crltnMm` | Integer | 조건부 | 없음 | 배포 기준월(1~12). `MONTH` 외 주기에서 필수. `MONTH` 면 null 로 저장한다 |
| body | `crltnDd` | Integer | 필수 | 없음 | 배포 기준일(1~28) |
| body | `sggNm` | String | 선택 | 없음 | 시군구명. `emdCd` 가 있으면 무시하고 서버가 다시 채운다. 둘 다 없으면 전체 지역 |
| body | `emdCd` | String | 선택 | 없음 | 읍면동 코드. 없으면 시군구 전체 |

### 호출 예시
```bash
curl -X PUT http://localhost:8180/api/report-settings/2 \
  -H 'Content-Type: application/json' \
  -H 'Cookie: JSESSIONID=<세션ID>; XSRF-TOKEN=<CSRF토큰>' \
  -H 'X-XSRF-TOKEN: <CSRF토큰>' \
  -d '{"rptpTypeCd":"HOTSPOT","crltnDeptCd":"DEPT01","crltnCyclCd":"MONTH","crltnDd":10}'
```

### 응답
`200 OK` · `application/json`. 본문은 10번 API 의 배열 원소 1건과 같다.

```json
{
  "stngId": 2,
  "rptpTypeCd": "HOTSPOT",
  "rptpTypeNm": "불법 주차 집중 구역 분석",
  "crltnDeptCd": "DEPT01",
  "crltnDeptNm": "예시 부서",
  "crltnCyclCd": "MONTH",
  "crltnCyclNm": "월간",
  "crltnMm": null,
  "crltnDd": 10,
  "crltnYmd": "2026-02-10",
  "actvtnYn": true,
  "creatrId": "user01",
  "creatrNm": "홍길동",
  "crtDt": "2026-02-01T09:00:00",
  "lastCrtDt": null,
  "sggNm": null,
  "emdCd": null,
  "emdNm": null,
  "regionNm": "전체 지역"
}
```

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | Spring Boot 기본 오류 JSON | `stngId` 형식 오류, JSON 형식 오류, 필수값 누락, 값 범위 밖, 사용할 수 없는 코드, 지원하지 않는 주기, `MONTH` 외 주기에서 `crltnMm` 누락, 존재하지 않는 읍면동·시군구 |
| 401 | 없음 | 세션 없음 |
| 403 | Spring Boot 기본 오류 JSON | CSRF 토큰 누락·불일치, `M0402` 권한 없음 |
| 404 | Spring Boot 기본 오류 JSON | 설정이 없거나 이미 삭제됨 |
| 415 | Spring Boot 기본 오류 JSON | 지원하지 않는 `Content-Type` |

## 13. DELETE /api/report-settings/{stngId}

### API 개요
자동 생성 설정 1건을 소프트 삭제한다. 행은 남고 `del_dt` 만 채워져 목록에서 빠지며, 이미 만들어진 보고서는 영향을 받지 않는다.

로그인 세션, CSRF 토큰, 보고서 관리 메뉴(`M0402`) 권한이 필요하다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |
| cookie | `XSRF-TOKEN` | String | 필수 | 없음 | CSRF 토큰 쿠키 |
| header | `X-XSRF-TOKEN` | String | 필수 | 없음 | `XSRF-TOKEN` 쿠키와 같은 값 |
| path | `stngId` | Long | 필수 | 없음 | 설정 ID |

### 호출 예시
```bash
curl -X DELETE http://localhost:8180/api/report-settings/2 \
  -H 'Cookie: JSESSIONID=<세션ID>; XSRF-TOKEN=<CSRF토큰>' \
  -H 'X-XSRF-TOKEN: <CSRF토큰>'
```

### 응답
`204 No Content` · 본문 없음

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | Spring Boot 기본 오류 JSON | `stngId` 가 숫자가 아님 |
| 401 | 없음 | 세션 없음 |
| 403 | Spring Boot 기본 오류 JSON | CSRF 토큰 누락·불일치, `M0402` 권한 없음 |
| 404 | Spring Boot 기본 오류 JSON | 설정이 없거나 이미 삭제됨 |

## 14. POST /api/ai/sessions/{sessionId}/reports

### API 개요
AI 답변 속 HTML 보고서 영역 1건을 AI 보고서로 등록한다. 서버가 Agent API 에서 세션 상세를 다시 받아 본문을 꺼내므로 HTML 을 요청으로 받지 않는다. 새로 등록하면 `TB_RPTP`·`TB_AI_RPTP_CN` 에 저장하고, 같은 사용자·세션·메시지·순번으로 이미 등록돼 있으면 기존 보고서를 돌려준다.

로그인 세션, CSRF 토큰, 세션에 보관된 Agent 토큰, Agent API 설정(`app.agent.base-url`, `app.agent.agent-id`)이 필요하다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |
| cookie | `XSRF-TOKEN` | String | 필수 | 없음 | CSRF 토큰 쿠키 |
| header | `X-XSRF-TOKEN` | String | 필수 | 없음 | `XSRF-TOKEN` 쿠키와 같은 값 |
| header | `Content-Type` | String | 필수 | 없음 | `application/json` |
| path | `sessionId` | String | 필수 | 없음 | AI 대화 세션 ID. 최대 512자, `/`·`%` 불가 |
| body | `msgId` | String | 필수 | 없음 | assistant 메시지 식별자. 공백만으로 구성할 수 없고 최대 64자 |
| body | `rptIdx` | Integer | 필수 | 없음 | 메시지 안 보고서 순번. 0 이상 |

### 호출 예시
```bash
curl -X POST http://localhost:8180/api/ai/sessions/portal-example-session/reports \
  -H 'Content-Type: application/json' \
  -H 'Cookie: JSESSIONID=<세션ID>; XSRF-TOKEN=<CSRF토큰>' \
  -H 'X-XSRF-TOKEN: <CSRF토큰>' \
  -d '{"msgId":"chatcmpl-example","rptIdx":0}'
```

### 응답
새로 등록하면 `201 Created`, 이미 등록돼 있으면 `200 OK` · `application/json`

```json
{
  "rptpId": 1001,
  "rptpNm": "예시 AI 보고서"
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `rptpId` | Long | 보고서 ID |
| `rptpNm` | String | 보고서명. 보고서 제목을 쓰고, 제목이 없으면 `AI 보고서_yyyyMMdd_HHmm`. 최대 256자 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | Spring Boot 기본 오류 JSON | JSON 형식 오류, `msgId` 누락·공백·64자 초과, `rptIdx` 누락·음수 |
| 400 | `{"code":"AGENT_BAD_REQUEST"}` | `sessionId` 가 512자 초과이거나 `/`·`%` 포함 |
| 401 | 없음 | 세션 없음 |
| 401 | Spring Boot 기본 오류 JSON | 세션 사용자가 없거나 사용 중지됨 |
| 401 | `{"code":"AGENT_UNAUTHORIZED"}` | Agent API 가 토큰 갱신 후에도 401 응답 |
| 403 | Spring Boot 기본 오류 JSON | CSRF 토큰 누락·불일치 |
| 403 | `{"code":"AGENT_UPSTREAM_REJECTED"}` | Agent API 가 403 응답 |
| 404 | `{"code":"AGENT_UPSTREAM_REJECTED"}` | Agent API 가 404 응답 |
| 404 | Spring Boot 기본 오류 JSON | 해당 assistant 메시지나 보고서 순번이 없거나 보고서가 완료(`DONE`) 상태가 아니거나 HTML 이 없음 |
| 415 | Spring Boot 기본 오류 JSON | 지원하지 않는 `Content-Type` |
| 500 | Spring Boot 기본 오류 JSON | 중복 등록 외의 데이터 무결성 오류 |
| 502 | `{"code":"AGENT_UPSTREAM_ERROR"}` | Agent API 5xx, 타임아웃, 연결 실패, 응답 파싱 실패, 401·403·404 외 4xx |
| 503 | `{"code":"AGENT_NOT_CONFIGURED"}` | Agent API `base-url` 또는 `agent-id` 미설정 |
| 503 | `{"code":"AGENT_TOKEN_UNAVAILABLE"}` | 세션에 Agent 토큰이 없거나 갱신 실패 |

## 15. POST /api/internal/reports/auto-crt

### API 개요
배치(`reportAutoCrtJob`) 전용 내부 API 로, 자동 생성 설정 1건과 대상기간 1개로 보고서를 만든다. 같은 `stngId`·`trgtPrdCd` 로 다시 호출하면 새로 만들지 않고 기존 보고서를 돌려준다. 새로 만들 때 분석 실행·결과·보고서를 한 트랜잭션으로 저장하며, `HOTSPOT` 은 구역명 산출에 VWorld API 를 호출할 수 있다.

세션과 CSRF 토큰은 쓰지 않는다. `app.internal-api.enabled=true`(공통·dev·prod 기본값)이면 `X-API-Key` 가 `app.internal-api.key` 와 일치해야 하고, local 프로파일은 `false` 라 키를 검사하지 않는다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `X-API-Key` | String | 조건부 | 없음 | 내부 API 키. `app.internal-api.enabled=true` 일 때 필수 |
| header | `Content-Type` | String | 필수 | 없음 | `application/json` |
| body | `stngId` | Long | 필수 | 없음 | 자동 생성 설정 ID |
| body | `rptpTypeCd` | String | 필수 | 없음 | 보고서 종류. `CVLCPT` \| `HOTSPOT`. 설정값과 같아야 한다 |
| body | `trgtPrdCd` | String | 필수 | 없음 | 대상기간 코드(멱등키). 최대 20자. 예: `2026-08`, `2026-Q1`, `2026-H1`, `2025` |
| body | `bgngYmd` | String(`YYYYMMDD`) | 필수 | 없음 | 대상기간 시작일 |
| body | `endYmd` | String(`YYYYMMDD`) | 필수 | 없음 | 대상기간 종료일(포함). 시작일 이후이며 기간은 최대 366일 |
| body | `sggNm` | String | 선택 | 없음 | 시군구명. 없으면 시군구 조건 없음 |
| body | `emdCd` | String | 선택 | 없음 | 읍면동 코드. 없으면 시군구 전체 |
| body | `crltnDeptCd` | String | 필수 | 없음 | 배포부서 코드 |
| body | `crltnCyclCd` | String | 필수 | 없음 | 배포주기. `MONTH` \| `QUARTER` \| `HALF` \| `YEAR`. 설정값과 같아야 한다 |

### 호출 예시
```bash
curl -X POST http://localhost:8180/api/internal/reports/auto-crt \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: <내부 API 키>' \
  -d '{"stngId":1,"rptpTypeCd":"CVLCPT","trgtPrdCd":"2026-01","bgngYmd":"20260101","endYmd":"20260131","sggNm":"제주시","emdCd":"50110137","crltnDeptCd":"DEPT01","crltnCyclCd":"MONTH"}'
```

### 응답
새로 만들면 `201 Created`(`created=true`), 이미 있으면 `200 OK`(`created=false`) · `application/json`

```json
{
  "rptpId": 1001,
  "excnId": 2001,
  "rptpNm": "2026년 1월 월간 민원 보고서 - 제주시 연동",
  "trgtPrdCd": "2026-01",
  "created": true
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `rptpId` | Long | 보고서 ID. `created=false` 면 기존 보고서 ID |
| `excnId` | Long \| null | 분석 실행 ID |
| `rptpNm` | String | 보고서명. `{기간표기} {민원\|집중구역} 보고서 - {지역}` |
| `trgtPrdCd` | String | 대상기간 코드 |
| `created` | boolean | 이번 호출에서 새로 만들었는지 여부 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | Spring Boot 기본 오류 JSON | JSON 형식 오류, 필수값 누락, `trgtPrdCd` 20자 초과, 날짜 형식 오류 또는 실재하지 않는 날짜, 시작일이 종료일보다 늦음, 기간 366일 초과 |
| 400 | Spring Boot 기본 오류 JSON | 설정이 없거나 삭제됨, `rptpTypeCd`·`crltnCyclCd` 가 설정과 다름, 자동 생성할 수 없는 종류 |
| 400 | Spring Boot 기본 오류 JSON | 존재하지 않는 읍면동·시군구, 설정의 주기·기준월로 보고서명을 만들 수 없음 |
| 400 | Spring Boot 기본 오류 JSON | `HOTSPOT` 분석 결과가 0건 |
| 401 | `{"error":"missing or invalid X-API-Key"}` | 키 검사가 켜져 있고 `X-API-Key` 가 없거나 틀림 |
| 415 | Spring Boot 기본 오류 JSON | 지원하지 않는 `Content-Type` |
| 500 | Spring Boot 기본 오류 JSON | 멱등키 충돌이 아닌 데이터 무결성 오류 |
| 503 | `{"error":"internal API key not configured (INTERNAL_API_KEY missing)"}` | 키 검사가 켜져 있는데 서버에 키가 설정되지 않음 |
