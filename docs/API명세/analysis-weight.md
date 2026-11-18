# 분석 변수 설정 API

## 1. GET /api/analysis-weights

### API 개요

분석유형별 변수(가중치 `WGVL`, 계수 `CFC`) 설정값을 조회한다. 노출 변수가 1개 이상 있는 분석유형만 공통코드 정렬순서로 돌려준다.

로그인 세션이 필요하다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | - | 로그인 세션 |

### 호출 예시

```bash
curl 'http://localhost:8180/api/analysis-weights' \
  -b 'JSESSIONID=<세션ID>'
```

### 응답

`200 OK` · `application/json`

```json
[
  {
    "anlsTypeCd": "HOTSPOT",
    "anlsTypeNm": "주차 집중구역 분석",
    "sortSeq": 1,
    "weights": [
      {
        "anlsTypeCd": "HOTSPOT",
        "anlsTrgtCd": null,
        "anlsTrgtNm": null,
        "vrblCd": "COMPLAINT",
        "vrblNm": "민원빈도",
        "vrblExpln": "예시 설명",
        "vrblSeCd": "WGVL",
        "stngVl": 60.0000,
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
    "stngVlSum": 100.0000,
    "trgtSums": [
      {
        "anlsTrgtCd": null,
        "anlsTrgtNm": null,
        "stngVlSum": 100.0000,
        "wgvlYn": "Y"
      }
    ]
  }
]
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `[].anlsTypeCd` / `anlsTypeNm` | String | 분석유형코드 / 분석유형명 |
| `[].sortSeq` | Integer | 분석유형 정렬순서 |
| `[].weights[]` | Array | 변수 목록. 숨김 변수(`expsrYn='N'`)도 포함한다. 공통 묶음(`anlsTrgtCd` null)이 먼저 오고, 하위 유형은 공통코드 순, 묶음 안은 `sortSeq` 순이다 |
| `[].weights[].anlsTypeCd` | String | 분석유형코드 |
| `[].weights[].anlsTrgtCd` / `anlsTrgtNm` | String \| null | 하위 유형 코드 / 이름. null 이면 공통 |
| `[].weights[].vrblCd` / `vrblNm` | String | 변수코드 / 변수명 |
| `[].weights[].vrblExpln` | String \| null | 변수 설명 |
| `[].weights[].vrblSeCd` | String | `WGVL`(가중치) · `CFC`(계수) |
| `[].weights[].stngVl` / `bscStngVl` | BigDecimal | 현재 설정값 / 기본 설정값 |
| `[].weights[].minVl` / `maxVl` | BigDecimal | 허용 최솟값 / 최댓값 |
| `[].weights[].unitNm` | String \| null | 단위명 |
| `[].weights[].useYn` | String | 사용 여부 (`Y` · `N`) |
| `[].weights[].expsrYn` | String | 화면 노출 여부 (`Y` · `N`) |
| `[].weights[].sortSeq` | Integer | 변수 정렬순서 |
| `[].weights[].mdfcnDt` | LocalDateTime \| null | 최종 수정 일시 (ISO-8601) |
| `[].stngVlSum` | BigDecimal | 분석유형 전체에서 `WGVL`·`useYn='Y'` 행의 합계 |
| `[].trgtSums[].anlsTrgtCd` / `anlsTrgtNm` | String \| null | 하위 유형 코드 / 이름 |
| `[].trgtSums[].stngVlSum` | BigDecimal | 묶음의 `WGVL`·`useYn='Y'` 행 합계 |
| `[].trgtSums[].wgvlYn` | String | 합계 100 검증 대상 묶음이면 `Y`, 계수만 있으면 `N` |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 401 | 없음 | 로그인 세션 없음 |

## 2. PUT /api/analysis-weights/{anlsTypeCd}

### API 개요

분석유형 1건의 변수 설정값을 전체 교체한다. 값이 바뀌면 `TB_ANLS_VRBL_STNG` 를 갱신하고 `TB_ANLS_VRBL_HSTRY` 에 이력 1행을 한 트랜잭션으로 남긴다. 바뀐 값이 없으면 저장하지 않고 현재값을 돌려준다.

로그인 세션과 CSRF 토큰이 필요하다. 사용자 권한에 `M0603`(분석 지표 가중치 설정) 메뉴가 매핑돼 있어야 하며, 권한은 요청마다 DB 에서 확인한다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | - | 로그인 세션 |
| cookie | `XSRF-TOKEN` | String | 필수 | - | CSRF 토큰 쿠키 |
| header | `X-XSRF-TOKEN` | String | 필수 | - | `XSRF-TOKEN` 쿠키와 같은 값 |
| header | `Content-Type` | String | 필수 | - | `application/json` |
| path | `anlsTypeCd` | String | 필수 | - | 분석유형코드. 1번 API 가 돌려주는 값. 초기 데이터 기준 `HOTSPOT` · `INVEST` · `LIFEZONE` · `POLICY_SIM` · `RECOMMEND` |
| body | `weights[]` | Array | 필수 | - | 해당 분석유형의 변수 전체(숨김 변수 포함). 1건 이상이며 중복·누락·미등록 변수는 400 |
| body | `weights[].vrblCd` | String | 필수 | - | 변수코드. 최대 30자 |
| body | `weights[].stngVl` | BigDecimal | 필수 | - | 설정값. 정수부 8자리, 소수부 4자리 이내이며 행의 `minVl`~`maxVl` 범위 안. 숨김 변수의 값은 무시하고 현재값을 유지한다 |
| body | `chgRsn` | String | 선택 | null | 변경 사유. 최대 300자. 이력에 기록한다 |

저장 시 다음 규칙도 함께 검증한다.

- `WGVL`·`useYn='Y'` 행의 합계는 하위 유형 묶음마다 100 이어야 한다.
- `CNGST_GRD_BNDRY1~4`, `SPLY_GRD_BNDRY1~4` 는 번호 순으로 엄격히 증가해야 한다.
- `SPLY_MIN_MNTH_AVG_CVLCPT`, `SPLY_GRD_BNDRY1~4` 는 정수만 받는다.
- `GRD_BNDRY1~3` 은 정수만 받으며 번호 순으로 엄격히 증가해야 한다.

### 호출 예시

```bash
curl -X PUT 'http://localhost:8180/api/analysis-weights/HOTSPOT' \
  -b 'JSESSIONID=<세션ID>; XSRF-TOKEN=<토큰>' \
  -H 'X-XSRF-TOKEN: <토큰>' \
  -H 'Content-Type: application/json' \
  -d '{
    "weights": [
      { "vrblCd": "COMPLAINT", "stngVl": 55 },
      { "vrblCd": "ENFORCE", "stngVl": 45 },
      { "vrblCd": "GRD_BNDRY1", "stngVl": 5 },
      { "vrblCd": "GRD_BNDRY2", "stngVl": 10 },
      { "vrblCd": "GRD_BNDRY3", "stngVl": 15 }
    ],
    "chgRsn": "단속 실적 반영 비중 상향"
  }'
```

### 응답

`200 OK` · `application/json`. 저장 후의 분석유형 1건을 돌려주며, 구조는 1번 API 응답의 배열 원소와 같다.

```json
{
  "anlsTypeCd": "HOTSPOT",
  "anlsTypeNm": "주차 집중구역 분석",
  "sortSeq": 1,
  "weights": [
    {
      "anlsTypeCd": "HOTSPOT",
      "anlsTrgtCd": null,
      "anlsTrgtNm": null,
      "vrblCd": "COMPLAINT",
      "vrblNm": "민원빈도",
      "vrblExpln": "예시 설명",
      "vrblSeCd": "WGVL",
      "stngVl": 55,
      "bscStngVl": 60.0000,
      "minVl": 0.0000,
      "maxVl": 100.0000,
      "unitNm": "%",
      "useYn": "Y",
      "expsrYn": "Y",
      "sortSeq": 1,
      "mdfcnDt": "2026-01-05T10:15:30.123"
    }
  ],
  "stngVlSum": 100,
  "trgtSums": [
    { "anlsTrgtCd": null, "anlsTrgtNm": null, "stngVlSum": 100, "wgvlYn": "Y" }
  ]
}
```

필드 설명은 1번 API 응답과 같다.

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status, error, path}` | 본문 형식 오류, `weights` 누락·빈 배열, `vrblCd`·`stngVl` 누락, 자릿수·길이 초과 |
| 400 | `{timestamp, status, error, path}` | 변수 중복, 분석유형에 없는 변수, 변수 누락 |
| 400 | `{timestamp, status, error, path}` | 가중치 합계가 100 이 아님, 허용 범위 밖, 등급 경계 순서 위반, 정수 전용 변수에 소수 입력 |
| 401 | 없음 | 로그인 세션 없음 |
| 401 | `{timestamp, status, error, path}` | 세션 사용자가 `TB_USER` 에 없거나 사용 중지 상태 |
| 403 | `{timestamp, status, error, path}` | CSRF 토큰 누락 또는 불일치, `M0603` 메뉴 권한 없음 |
| 404 | `{timestamp, status, error, path}` | 없는 분석유형코드 |

## 3. GET /api/analysis-weights/histories

### API 개요

변수 설정 변경 이력을 최신순으로 조회한다. `anlsTypeCd` 로 거른 뒤 `size` 만큼 자른다.

로그인 세션이 필요하다.

### 파라미터

| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | - | 로그인 세션 |
| query | `size` | int | 선택 | `20` | 조회 건수. 1~100 |
| query | `anlsTypeCd` | String | 선택 | 전 분석유형 | 분석유형코드. 빈 값과 공백은 생략과 같다. 없는 코드는 빈 배열을 돌려준다 |

### 호출 예시

```bash
curl 'http://localhost:8180/api/analysis-weights/histories?size=20&anlsTypeCd=HOTSPOT' \
  -b 'JSESSIONID=<세션ID>'
```

### 응답

`200 OK` · `application/json`. 변경 일시 내림차순, 같으면 일련번호 내림차순이다.

```json
[
  {
    "chgHstrySn": 12,
    "anlsTypeCd": "HOTSPOT",
    "chgDt": "2026-01-05T10:15:30.123",
    "chnrgNm": "홍길동",
    "chgArtclNm": "주차 집중구역 분석",
    "chgCn": "민원빈도 60 → 55, 단속건수 40 → 45",
    "chgRsn": "단속 실적 반영 비중 상향"
  }
]
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `[].chgHstrySn` | Long | 변경 이력 일련번호 |
| `[].anlsTypeCd` | String | 분석유형코드 |
| `[].chgDt` | LocalDateTime | 변경 일시 (ISO-8601) |
| `[].chnrgNm` | String | 변경자명 |
| `[].chgArtclNm` | String | 기록 시점의 분석유형명 |
| `[].chgCn` | String | 변경 내용 |
| `[].chgRsn` | String \| null | 변경 사유 |

### 예외

| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status, error, path}` | `size` 가 1~100 범위 밖이거나 숫자가 아님 |
| 401 | 없음 | 로그인 세션 없음 |
