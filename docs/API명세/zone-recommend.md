# 구역추천·투자우선순위 분석 API

## 1. GET /api/analysis/zone-recommendation/zones

### API 개요
구역추천(요금제 적용) 화면에서 읍면동을 고르기 위한 선택 목록을 조회한다. `TB_ADMDST` 전량을 시군구명 → 읍면동명 순으로 반환하며, 민원이 0건인 읍면동도 포함한다.

로그인 세션이 필요하다. 메뉴 권한 검사는 없다. 실행 이력을 남기지 않고 DB를 변경하지 않는다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `Cookie: JSESSIONID` | String | 필수 | 없음 | 로그인 세션 쿠키 |
| query | `bgngYmd` | String | 필수 | 없음 | 민원 조회 시작일 (`YYYYMMDD`) |
| query | `endYmd` | String | 필수 | 없음 | 민원 조회 종료일 (`YYYYMMDD`, 당일 포함). 시작일보다 빠를 수 없고, 시작 달부터 종료 달까지 36개월 이내 |
| query | `crdnBgngYmd` | String | 선택 | `20250101` | 단속 창 시작일 (`YYYYMMDD`). 생략·공백이면 기본값 |
| query | `crdnEndYmd` | String | 선택 | `20260831` | 단속 창 종료일 (`YYYYMMDD`, 당일 포함). 생략·공백이면 기본값. 단속 창도 36개월 이내 |
| query | `trgtTypeSe` | String | 선택 | `NARROW` | 후보 주차장 유형 범위. `NARROW` \| `WIDE` (대소문자 무관) |
| query | `vrblVls` | String(JSON 객체) | 선택 | 설정값 | 분석 계수 일시 입력. 허용 코드와 규칙은 2번 API의 `vrblVls` 와 같다. 검증만 하며 이 API의 결과에는 영향을 주지 않는다 |

### 호출 예시
```bash
curl -G "http://localhost:8180/api/analysis/zone-recommendation/zones" \
  -H "Cookie: JSESSIONID=example-session-id" \
  --data-urlencode "bgngYmd=20260101" \
  --data-urlencode "endYmd=20260831"
```

### 응답
`200 OK` · `application/json` · 배열

```json
[
  { "emdCd": "50110000", "emdNm": "예시동", "sggNm": "제주시" }
]
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `[].emdCd` | String | 아니오 | 읍면동 코드 (8자리) |
| `[].emdNm` | String | 아니오 | 읍면동명 |
| `[].sggNm` | String | 아니오 | 시군구명 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status, error, path}` | 필수 파라미터 누락, 날짜 형식 오류·실재하지 않는 날짜, 종료일이 시작일보다 빠름, 민원 창 또는 단속 창이 36개월 초과, `trgtTypeSe` 허용값 밖, `vrblVls` 규칙 위반 |
| 401 | 빈 본문 | 로그인 세션 없음 |
| 500 | `{timestamp, status, error, path}` | 처리되지 않은 서버 오류 |

## 2. GET /api/analysis/zone-recommendation

### API 개요
선택한 읍면동 또는 시군구 안의 후보 공영주차장을 유료화 검토 우선도 순으로 조회한다. 후보는 무료·미소멸·비민영이면서 최소 면수 이상이고 `trgtTypeSe` 유형 범위에 드는 주차장이다. 민원·단속은 같은 읍면동 안에서 배정 반경 이내의 가장 가까운 후보 1곳에만 배정한다.

로그인 세션이 필요하고 세션 사용자가 `TB_USER` 에 사용 상태로 있어야 한다. 메뉴 권한 검사는 없다. GET 이지만 호출 1회마다 `TB_ANLS_EXCN` 1행과 `TB_ANLS_RSLT` 1행을 한 트랜잭션으로 저장한다. 오류 응답이면 저장하지 않는다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `Cookie: JSESSIONID` | String | 필수 | 없음 | 로그인 세션 쿠키 |
| query | `emdCd` | String | 조건부 | 없음 | 읍면동 코드(8자리). `sggNm` 과 정확히 하나만 보낸다. 공백은 생략으로 본다 |
| query | `sggNm` | String | 조건부 | 없음 | 시군구명(40자 이내, 시군구 모드). `emdCd` 와 정확히 하나만 보낸다. 공백은 생략으로 본다 |
| query | `bgngYmd` | String | 필수 | 없음 | 민원 조회 시작일 (`YYYYMMDD`) |
| query | `endYmd` | String | 필수 | 없음 | 민원 조회 종료일 (`YYYYMMDD`, 당일 포함). 시작 달부터 종료 달까지 36개월 이내 |
| query | `crdnBgngYmd` | String | 선택 | `20250101` | 단속 창 시작일 (`YYYYMMDD`). 생략·공백이면 기본값 |
| query | `crdnEndYmd` | String | 선택 | `20260831` | 단속 창 종료일 (`YYYYMMDD`, 당일 포함). 생략·공백이면 기본값. 36개월 이내 |
| query | `trgtTypeSe` | String | 선택 | `NARROW` | `NARROW`: 공영·노외(공영)·복층화. `WIDE`: 여기에 노상·노상(23년 조사)·공한지 추가. 대소문자 무관 |
| query | `vrblVls` | String(JSON 객체) | 선택 | 설정값 | 분석 계수 일시 입력. 설정값은 바꾸지 않는다. 값은 숫자이고 정수부 8자리·소수 4자리 이내이며, 같은 키를 중복할 수 없다. 범위는 `tb_anls_vrbl_stng` 의 `min_vl`~`max_vl` 이고 아래 범위는 초기 데이터 기준이다 |
| query | `vrblVls.ALTMNT_RNG_VL` | Number | 선택 | 설정값 (초기 300) | 배정 반경(m). 범위 100~1000 |
| query | `vrblVls.SWTC_TRGT_MIN_CPCTY` | Number | 선택 | 설정값 (초기 20) | 후보 최소 면수. 범위 0~100 |
| query | `vrblVls.FEE_CVLCPT_CNT_WGVL` | Number | 선택 | 설정값 (초기 70) | 민원강도 가중치(%). 범위 0~100. `FEE_CVLCPT_RT_WGVL` 과 합이 100 이어야 한다 |
| query | `vrblVls.FEE_CVLCPT_RT_WGVL` | Number | 선택 | 설정값 (초기 30) | 면당민원 가중치(%). 범위 0~100. `FEE_CVLCPT_CNT_WGVL` 과 합이 100 이어야 한다 |
| query | `vrblVls.FEE_CVLCPT_MM_CRTR_JEJU` | Number | 선택 | 설정값 (초기 22) | 제주시 민원 월 기준값(건/월). 범위 1~500 |
| query | `vrblVls.FEE_CVLCPT_MM_CRTR_SEOGWIPO` | Number | 선택 | 설정값 (초기 10) | 서귀포시 민원 월 기준값(건/월). 범위 1~500 |
| query | `vrblVls.FEE_CVLCPT_RT_CRTR_JEJU` | Number | 선택 | 설정값 (초기 72) | 제주시 면당민원 기준값(건/100면·월). 범위 1~1000 |
| query | `vrblVls.FEE_CVLCPT_RT_CRTR_SEOGWIPO` | Number | 선택 | 설정값 (초기 28) | 서귀포시 면당민원 기준값(건/100면·월). 범위 1~1000 |
| query | `vrblVls.FEE_CVLCPT_INCRS_WGVL` | Number | 선택 | 설정값 (초기 0.5) | 민원증가 가중치(배). 범위 0~1 |
| query | `vrblVls.FEE_CVLCPT_INCRS_RT_UPLMT_VL` | Number | 선택 | 설정값 (초기 100) | 민원증가율 상한(%). 범위 10~300 |

### 호출 예시
```bash
curl -G "http://localhost:8180/api/analysis/zone-recommendation" \
  -H "Cookie: JSESSIONID=example-session-id" \
  --data-urlencode "emdCd=50110000" \
  --data-urlencode "bgngYmd=20260101" \
  --data-urlencode "endYmd=20260831" \
  --data-urlencode 'vrblVls={"ALTMNT_RNG_VL":500}'
```

### 응답
`200 OK` · `application/json`. 후보 주차장이 0개소여도 `lots` 가 빈 배열인 200 이다.

```json
{
  "excnId": 1001,
  "scope": "EMD",
  "sggNm": "제주시",
  "anlsMthd": "RVW_SCR",
  "crtrVls": {
    "trgtTypeSe": "NARROW",
    "altmntRngVl": 500,
    "swtcTrgtMinCpcty": 20.0000,
    "minTrendBase": 10,
    "trendDayCnt": 121,
    "feeCvlcptCntWgvl": 70.0000,
    "feeCvlcptRtWgvl": 30.0000,
    "feeCvlcptMmCrtrJeju": 22.0000,
    "feeCvlcptMmCrtrSeogwipo": 10.0000,
    "feeCvlcptRtCrtrJeju": 72.0000,
    "feeCvlcptRtCrtrSeogwipo": 28.0000,
    "feeCvlcptIncrsWgvl": 0.5000,
    "feeCvlcptIncrsRtUplmtVl": 100.0000
  },
  "vrblAjmt": {
    "ajmtYn": "Y",
    "vrbls": [
      {
        "vrblCd": "ALTMNT_RNG_VL",
        "vrblNm": "배정범위값",
        "unitNm": "m",
        "bscStngVl": 300,
        "stngVl": 300,
        "aplcnVl": 500,
        "expsrYn": "Y"
      }
    ]
  },
  "warnings": ["TREND_BFR_FALLBACK"],
  "zone": { "emdCd": "50110000", "emdNm": "예시동", "sggNm": "제주시" },
  "asgn": { "cvlcptTot": 1000, "cvlcptAsgn": 300, "asgnRt": 30.0 },
  "lots": [
    {
      "pkltId": 101,
      "pkltNm": "예시 공영주차장",
      "pkltTypeNm": "공영",
      "pkltCpcty": 30,
      "pkltAddr": "제주특별자치도 제주시 예시동 1-1",
      "emdCd": "50110000",
      "emdNm": "예시동",
      "lat": 33.5000000,
      "lot": 126.5000000,
      "asgnCvlcptCnt": 120,
      "asgnCvlcptCntRcnt": 70,
      "asgnCvlcptCntBfr": 50,
      "asgnCrdnCnt": 40,
      "cvlcptPerSlot": 4.00,
      "rnk": 1,
      "cumCpcty": 30,
      "cvlcptIncrsRt": 40.0,
      "trendMm": [{ "ym": "2026-05", "cnt": 15 }],
      "sggNm": "제주시",
      "cvlcptCntScr": 68.2,
      "cvlcptRtScr": 69.4,
      "rvwScr": 68.6,
      "rvwGrdCd": "HIGH",
      "incrsCfc": 1.200,
      "incrsRfltRvwScr": 82.3
    }
  ],
  "exclLots": [
    {
      "pkltId": 102,
      "pkltNm": "예시 유료주차장",
      "pkltCpcty": 14,
      "lat": 33.5100000,
      "lot": 126.5100000,
      "exclRsnCd": "PAID"
    }
  ]
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `excnId` | Long | 아니오 | 저장된 분석 실행 ID |
| `scope` | String | 아니오 | `EMD`(읍면동) \| `SGG`(시군구) |
| `sggNm` | String | 아니오 | 시군구명 |
| `anlsMthd` | String | 아니오 | 분석 방법. 항상 `RVW_SCR` |
| `crtrVls.trgtTypeSe` | String | 아니오 | 적용한 유형 범위 |
| `crtrVls.altmntRngVl` | Number | 아니오 | 적용한 배정 반경(m) |
| `crtrVls.swtcTrgtMinCpcty` | Number | 아니오 | 적용한 후보 최소 면수 |
| `crtrVls.minTrendBase` | Integer | 아니오 | 증가율 분모 하한(건). 코드 상수 10 |
| `crtrVls.trendDayCnt` | Integer | 예 | 증가율 비교 블록 일수. 직전 동일 기간 비교면 조회 일수 D, 반 분할이면 D/2, 조회 기간 14일 미만이면 null |
| `crtrVls.feeCvlcptCntWgvl` | Number | 아니오 | 적용한 민원강도 가중치(%) |
| `crtrVls.feeCvlcptRtWgvl` | Number | 아니오 | 적용한 면당민원 가중치(%) |
| `crtrVls.feeCvlcptMmCrtrJeju` | Number | 아니오 | 적용한 제주시 민원 월 기준값 |
| `crtrVls.feeCvlcptMmCrtrSeogwipo` | Number | 아니오 | 적용한 서귀포시 민원 월 기준값 |
| `crtrVls.feeCvlcptRtCrtrJeju` | Number | 아니오 | 적용한 제주시 면당민원 기준값 |
| `crtrVls.feeCvlcptRtCrtrSeogwipo` | Number | 아니오 | 적용한 서귀포시 면당민원 기준값 |
| `crtrVls.feeCvlcptIncrsWgvl` | Number | 아니오 | 적용한 민원증가 가중치 |
| `crtrVls.feeCvlcptIncrsRtUplmtVl` | Number | 아니오 | 적용한 민원증가율 상한(%) |
| `vrblAjmt.ajmtYn` | String | 아니오 | 입력값이 설정값과 다른 계수가 있으면 `Y`, 없으면 `N` |
| `vrblAjmt.vrbls[].vrblCd` | String | 아니오 | 변수코드 |
| `vrblAjmt.vrbls[].vrblNm` | String | 아니오 | 변수명 |
| `vrblAjmt.vrbls[].unitNm` | String | 예 | 단위명 |
| `vrblAjmt.vrbls[].bscStngVl` | Number | 예 | 기본값 |
| `vrblAjmt.vrbls[].stngVl` | Number | 예 | 현재 설정값 |
| `vrblAjmt.vrbls[].aplcnVl` | Number | 예 | 이번 실행 적용값 |
| `vrblAjmt.vrbls[].expsrYn` | String | 아니오 | 노출 여부 (`Y` \| `N`) |
| `warnings[]` | String | 아니오 | `COEF_FALLBACK`(계수를 코드 기본값으로 대체) \| `TREND_WINDOW_SHORT`(조회 기간 14일 미만) \| `TREND_BFR_FALLBACK`(증가율을 조회 기간 반 분할로 계산). 없으면 빈 배열 |
| `zone` | Object | 예 | 선택한 읍면동. 시군구 모드는 null |
| `zone.emdCd` | String | 아니오 | 읍면동 코드 |
| `zone.emdNm` | String | 아니오 | 읍면동명 |
| `zone.sggNm` | String | 아니오 | 시군구명 |
| `asgn` | Object | 예 | 배정 커버리지. 시군구 모드는 null |
| `asgn.cvlcptTot` | Long | 아니오 | 조회 기간 민원 총건수(유형 `99` 제외) |
| `asgn.cvlcptAsgn` | Long | 아니오 | 후보 주차장에 배정된 민원 합 |
| `asgn.asgnRt` | Number | 예 | 배정률(%, 소수 1자리). 총건수 0 이면 null |
| `lots[]` | Array | 아니오 | 후보 주차장. 우선도 내림차순 → 배정 민원 내림차순 → 면수 내림차순 → 주차장 ID 오름차순 |
| `lots[].pkltId` | Long | 아니오 | 주차장 ID |
| `lots[].pkltNm` | String | 아니오 | 주차장명 |
| `lots[].pkltTypeNm` | String | 아니오 | 주차장 유형명 |
| `lots[].pkltCpcty` | Integer | 아니오 | 주차면수 |
| `lots[].pkltAddr` | String | 예 | 주차장 주소 |
| `lots[].emdCd` | String | 아니오 | 읍면동 코드 |
| `lots[].emdNm` | String | 예 | 읍면동명 |
| `lots[].lat` | Number | 아니오 | 위도 |
| `lots[].lot` | Number | 아니오 | 경도 |
| `lots[].asgnCvlcptCnt` | Long | 아니오 | 조회 기간 배정 민원 수 |
| `lots[].asgnCvlcptCntRcnt` | Long | 예 | 최근 블록 배정 민원 수. 조회 기간 14일 미만이면 null |
| `lots[].asgnCvlcptCntBfr` | Long | 예 | 이전 블록 배정 민원 수. 조회 기간 14일 미만이면 null |
| `lots[].asgnCrdnCnt` | Long | 아니오 | 단속 창 배정 단속 수 |
| `lots[].cvlcptPerSlot` | Number | 예 | 면당 민원(소수 2자리). 면수 0 이면 null |
| `lots[].rnk` | Integer | 아니오 | 순위(1부터) |
| `lots[].cumCpcty` | Number | 예 | 이 순위까지의 누적 면수. 시군구 모드는 null |
| `lots[].cvlcptIncrsRt` | Number | 예 | 민원 증가율(%, 소수 1자리). 이전 블록 10건 미만이거나 조회 기간 14일 미만이면 null |
| `lots[].trendMm[].ym` | String | 아니오 | 최근 블록이 걸친 연월 (`YYYY-MM`). 조회 기간 14일 미만이면 `trendMm` 은 빈 배열 |
| `lots[].trendMm[].cnt` | Long | 아니오 | 그 달의 배정 민원 수. 0건인 달도 포함 |
| `lots[].sggNm` | String | 예 | 시군구명 |
| `lots[].cvlcptCntScr` | Number | 아니오 | 민원강도 점수(소수 1자리, 상한 없음) = 월 배정 민원 ÷ 시군구 민원 월 기준값 × 100 |
| `lots[].cvlcptRtScr` | Number | 아니오 | 면당압력 점수(소수 1자리, 상한 없음) = 100면당 월 배정 민원 ÷ 시군구 면당민원 기준값 × 100 |
| `lots[].rvwScr` | Number | 아니오 | 검토지수(소수 1자리, 상한 없음). 두 점수의 가중평균 |
| `lots[].rvwGrdCd` | String | 아니오 | 우선도 단계. `TOP`(100 이상) \| `HIGH`(60 이상) \| `LOW`(60 미만) |
| `lots[].incrsCfc` | Number | 아니오 | 증가계수(소수 3자리) = 1 + 가중치 × clamp(증가율, ±상한). 증가율이 null 이면 1 |
| `lots[].incrsRfltRvwScr` | Number | 아니오 | 우선도(소수 1자리) = 검토지수 × 증가계수 |
| `exclLots[]` | Array | 아니오 | 범위 안의 후보 밖 주차장(소멸분 제외). 주차장 ID 오름차순 |
| `exclLots[].pkltId` | Long | 아니오 | 주차장 ID |
| `exclLots[].pkltNm` | String | 아니오 | 주차장명 |
| `exclLots[].pkltCpcty` | Integer | 예 | 주차면수. 원천 미상이면 null |
| `exclLots[].lat` | Number | 아니오 | 위도 |
| `exclLots[].lot` | Number | 아니오 | 경도 |
| `exclLots[].exclRsnCd` | String | 아니오 | 제외 사유. 우선순위 `PAID` > `CPCTY_LACK` > `PRVT` > `TYPE_EXCL` |

증가율은 조회 기간(D일)과 직전 D일을 비교한다. 직전 기간의 시작이 민원 자료 시작일보다 앞이거나 직전 기간 배정 민원 합이 0건이면 조회 기간의 앞 D/2일과 뒤 D/2일을 비교한다.

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status, error, path}` | 필수 파라미터 누락, `emdCd`·`sggNm` 이 둘 다 없거나 둘 다 있음, `sggNm` 40자 초과 또는 없는 시군구, 날짜 형식 오류·역전, 민원 창 또는 단속 창 36개월 초과, `trgtTypeSe` 허용값 밖, `vrblVls` 형식 오류·허용 목록 밖 코드·사용 안 함 또는 숨김 변수·null 값·자릿수 초과·범위 밖·FEE 가중치 합계 100 아님 |
| 401 | 빈 본문 | 로그인 세션 없음 |
| 401 | `{timestamp, status, error, path}` | 세션 사용자가 `TB_USER` 에 없거나 사용 정지 상태 |
| 404 | `{timestamp, status, error, path}` | 없는 `emdCd` |
| 500 | `{timestamp, status, error, path}` | 처리되지 않은 서버 오류 |

## 3. POST /api/analysis/zone-recommendation/reports

### API 개요
구역추천 요금제 적용 결과를 같은 조건으로 다시 계산해 보고서로 저장한다. 호출 1회마다 `TB_ANLS_EXCN`, `TB_ANLS_RSLT`, `TB_RPTP`(생성구분 `MANUAL`)에 1행씩 한 트랜잭션으로 저장한다. 서버에 파일을 만들지 않고 응답 본문으로 스냅샷을 반환한다.

로그인 세션과 CSRF 토큰이 필요하고 세션 사용자가 `TB_USER` 에 사용 상태로 있어야 한다. 메뉴 권한 검사는 없다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `Cookie: JSESSIONID` | String | 필수 | 없음 | 로그인 세션 쿠키 |
| header | `Cookie: XSRF-TOKEN` | String | 필수 | 없음 | CSRF 토큰 쿠키 |
| header | `X-XSRF-TOKEN` | String | 필수 | 없음 | `XSRF-TOKEN` 쿠키와 같은 값 |
| header | `Content-Type` | String | 필수 | 없음 | `application/json` |
| body | `emdCd` | String | 조건부 | 없음 | 읍면동 코드(8자리). `sggNm` 과 정확히 하나만 보낸다 |
| body | `sggNm` | String | 조건부 | 없음 | 시군구명(40자 이내). `emdCd` 와 정확히 하나만 보낸다 |
| body | `bgngYmd` | String | 필수 | 없음 | 민원 조회 시작일. 숫자 8자리 (`YYYYMMDD`) |
| body | `endYmd` | String | 필수 | 없음 | 민원 조회 종료일. 숫자 8자리, 당일 포함. 36개월 이내 |
| body | `crdnBgngYmd` | String | 선택 | `20250101` | 단속 창 시작일. 보내면 숫자 8자리여야 한다 |
| body | `crdnEndYmd` | String | 선택 | `20260831` | 단속 창 종료일. 보내면 숫자 8자리여야 한다. 36개월 이내 |
| body | `trgtTypeSe` | String | 선택 | `NARROW` | `NARROW` \| `WIDE` (대소문자 무관) |
| body | `vrblVls` | Object(코드 → Number) | 선택 | 설정값 | 분석 계수 일시 입력. 허용 코드·범위·규칙은 2번 API의 `vrblVls` 와 같다 |
| body | `fileFmt` | String | 필수 | 없음 | `PDF` \| `DOCX` \| `XLSX` |

### 호출 예시
```bash
curl -X POST "http://localhost:8180/api/analysis/zone-recommendation/reports" \
  -H "Cookie: JSESSIONID=example-session-id; XSRF-TOKEN=example-csrf-token" \
  -H "X-XSRF-TOKEN: example-csrf-token" \
  -H "Content-Type: application/json" \
  -d '{
    "emdCd": "50110000",
    "bgngYmd": "20260101",
    "endYmd": "20260831",
    "trgtTypeSe": "NARROW",
    "vrblVls": { "ALTMNT_RNG_VL": 500 },
    "fileFmt": "XLSX"
  }'
```

### 응답
`201 Created` · `application/json`

```json
{
  "rptpId": 2001,
  "excnId": 1002,
  "rptpNm": "구역추천 요금제 적용 보고서 제주시 예시동",
  "regionNm": "제주시 예시동",
  "anlsPrdExpln": "2026.01.01 ~ 2026.08.31",
  "fileFmt": "XLSX",
  "crtYmd": "2026-11-18T10:00:00",
  "bgngYmd": "20260101",
  "endYmd": "20260831",
  "vrblAjmt": { "ajmtYn": "Y", "vrbls": [] },
  "rcmdtnTypeCd": "FEE",
  "fee": {
    "excnId": null,
    "scope": "EMD",
    "sggNm": "제주시",
    "anlsMthd": "RVW_SCR",
    "crtrVls": { "trgtTypeSe": "NARROW", "altmntRngVl": 500 },
    "vrblAjmt": null,
    "warnings": [],
    "zone": { "emdCd": "50110000", "emdNm": "예시동", "sggNm": "제주시" },
    "asgn": { "cvlcptTot": 1000, "cvlcptAsgn": 300, "asgnRt": 30.0 },
    "lots": [],
    "exclLots": [],
    "wbk": {
      "months": 8,
      "fixed": { "prrtTop": 100, "prrtHigh": 60 },
      "vrbls": [
        {
          "vrblCd": "ALTMNT_RNG_VL",
          "vrblNm": "배정범위값",
          "aplcnVl": 500,
          "minVl": 100,
          "maxVl": 1000,
          "intOnlyYn": "N",
          "inptYn": "N"
        }
      ]
    }
  },
  "share": null
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `rptpId` | Long | 아니오 | 보고서 ID |
| `excnId` | Long | 아니오 | 분석 실행 ID |
| `rptpNm` | String | 아니오 | `구역추천 요금제 적용 보고서 {regionNm}` |
| `regionNm` | String | 아니오 | `{시군구} {읍면동}` 또는 `{시군구} 전체` |
| `anlsPrdExpln` | String | 아니오 | 민원 기간 `yyyy.MM.dd ~ yyyy.MM.dd`. 시작일과 종료일이 같으면 날짜 하나 |
| `fileFmt` | String | 아니오 | 요청한 파일 형식 |
| `crtYmd` | String | 아니오 | 생성일시 (ISO-8601) |
| `bgngYmd` | String | 아니오 | 적용한 민원 시작일 (`YYYYMMDD`) |
| `endYmd` | String | 아니오 | 적용한 민원 종료일 (`YYYYMMDD`) |
| `vrblAjmt` | Object | 아니오 | 분석 계수 적용 내역. 구조는 2번 API의 `vrblAjmt` 와 같다 |
| `rcmdtnTypeCd` | String | 아니오 | 항상 `FEE` |
| `fee` | Object | 아니오 | 요금제 적용 본문. 필드는 2번 API 응답과 같고 아래 항목만 다르다 |
| `fee.excnId` | Long | 예 | 항상 null. 실행 ID는 최상위 `excnId` 를 쓴다 |
| `fee.vrblAjmt` | Object | 예 | 항상 null. 적용 내역은 최상위 `vrblAjmt` 를 쓴다 |
| `fee.exclLots` | Array | 아니오 | 항상 빈 배열 |
| `fee.wbk.months` | Number | 아니오 | 민원 기간 개월 수(달별 조회 비율 합, 소수 최대 6자리) |
| `fee.wbk.fixed.prrtTop` | Number | 아니오 | 우선도 `TOP` 하한. 100 |
| `fee.wbk.fixed.prrtHigh` | Number | 아니오 | 우선도 `HIGH` 하한. 60 |
| `fee.wbk.vrbls[].vrblCd` | String | 아니오 | 변수코드. 2번 API의 `vrblVls` 10개 순서 |
| `fee.wbk.vrbls[].vrblNm` | String | 아니오 | 변수명. 설정 행이 없으면 변수코드 |
| `fee.wbk.vrbls[].aplcnVl` | Number | 예 | 적용값 |
| `fee.wbk.vrbls[].minVl` | Number | 예 | 최솟값. 설정 행이 없으면 null |
| `fee.wbk.vrbls[].maxVl` | Number | 예 | 최댓값. 설정 행이 없으면 null |
| `fee.wbk.vrbls[].intOnlyYn` | String | 아니오 | 정수 전용 여부. 항상 `N` |
| `fee.wbk.vrbls[].inptYn` | String | 아니오 | 엑셀 입력 셀 여부. `ALTMNT_RNG_VL`·`SWTC_TRGT_MIN_CPCTY`·`FEE_CVLCPT_RT_WGVL` 과 설정 행이 없는 변수는 `N` |
| `share` | Object | 예 | 항상 null |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status, error, path}` | 본문 JSON 오류, 필수값 누락, 날짜·`fileFmt` 형식 오류, `emdCd`·`sggNm` 조합 오류, `sggNm` 40자 초과 또는 없는 시군구, 실재하지 않는 날짜·기간 역전·36개월 초과, `trgtTypeSe` 허용값 밖, `vrblVls` 규칙 위반, 후보 주차장 0개소 |
| 401 | 빈 본문 | 로그인 세션 없음 (CSRF 토큰은 유효한 경우) |
| 401 | `{timestamp, status, error, path}` | 세션 사용자가 `TB_USER` 에 없거나 사용 정지 상태 |
| 403 | `{timestamp, status, error, path}` | CSRF 토큰 누락 또는 불일치 |
| 404 | `{timestamp, status, error, path}` | 없는 `emdCd` |
| 500 | `{timestamp, status, error, path}` | 처리되지 않은 서버 오류 |

오류 응답이면 어떤 테이블에도 저장하지 않는다.

## 4. GET /api/analysis/zone-recommendation/share

### API 개요
민원 기간을 기준으로 전체 읍면동을 판정해 공유주차 검토 후보를 조회한다. 후보는 월평균 민원과 공영 100면당 월평균 민원이 모두 기준값 이상인 읍면동이고, 두 기준의 2배 이상이면 핵심 후보다. 판정·`smry` 는 지역 필터와 무관한 도 전체 기준이며 `rnk` 만 필터 적용 후 결과 안에서 매긴다.

로그인 세션이 필요하고 세션 사용자가 `TB_USER` 에 사용 상태로 있어야 한다. 메뉴 권한 검사는 없다. GET 이지만 호출 1회마다 `TB_ANLS_EXCN` 1행과 `TB_ANLS_RSLT` 1행을 한 트랜잭션으로 저장한다. 오류 응답이면 저장하지 않는다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `Cookie: JSESSIONID` | String | 필수 | 없음 | 로그인 세션 쿠키 |
| query | `bgngYmd` | String | 필수 | 없음 | 민원 조회 시작일 (`YYYYMMDD`) |
| query | `endYmd` | String | 필수 | 없음 | 민원 조회 종료일 (`YYYYMMDD`, 당일 포함). 시작 달부터 종료 달까지 36개월 이내 |
| query | `sggNm` | String | 선택 | 없음 | 시군구명 필터(40자 이내). 생략·공백이면 걸지 않는다 |
| query | `emdCd` | String | 선택 | 없음 | 읍면동 코드 필터(8자리). 생략·공백이면 걸지 않는다. `sggNm` 과 함께 보내면 둘 다 만족하는 읍면동만 반환한다 |
| query | `vrblVls` | String(JSON 객체) | 선택 | 설정값 | 분석 계수 일시 입력. 설정값은 바꾸지 않는다. 값은 숫자이고 정수부 8자리·소수 4자리 이내이며, 같은 키를 중복할 수 없다. 범위는 `tb_anls_vrbl_stng` 의 `min_vl`~`max_vl` 이고 아래 범위는 초기 데이터 기준이다 |
| query | `vrblVls.CVLCPT_MM_CRTR_VL` | Number | 선택 | 설정값 (초기 50) | 민원 월기준값(건/월). 범위 0~500 |
| query | `vrblVls.CVLCPT_RT_CRTR_VL` | Number | 선택 | 설정값 (초기 7) | 민원 비율기준값(건/100면·월). 범위 3~20 |
| query | `vrblVls.CVLCPT_INCRS_WGVL` | Number | 선택 | 설정값 (초기 0.5) | 민원증가 가중치(배). 범위 0~1 |
| query | `vrblVls.CVLCPT_INCRS_RT_UPLMT_VL` | Number | 선택 | 설정값 (초기 100) | 민원증가율 상한(%). 범위 10~300 |

### 호출 예시
```bash
curl -G "http://localhost:8180/api/analysis/zone-recommendation/share" \
  -H "Cookie: JSESSIONID=example-session-id" \
  --data-urlencode "bgngYmd=20260101" \
  --data-urlencode "endYmd=20260831" \
  --data-urlencode "sggNm=제주시" \
  --data-urlencode 'vrblVls={"CVLCPT_RT_CRTR_VL":10}'
```

### 응답
`200 OK` · `application/json`. 후보가 0곳이어도 200 이며 `zones` 에는 필터에 걸린 읍면동이 모두 담긴다.

```json
{
  "excnId": 1003,
  "crtrVls": {
    "cvlcptMmCrtrVl": 50,
    "cvlcptRtCrtrVl": 10,
    "coreMltp": 2,
    "months": 8,
    "incrsWgvl": 0.5,
    "incrsRtUplmtVl": 100,
    "minTrendBase": 10,
    "trendDayCnt": 121
  },
  "vrblAjmt": { "ajmtYn": "Y", "vrbls": [] },
  "dataBgngYmd": "20260101",
  "dataEndYmd": "20261031",
  "warnings": ["TREND_BFR_FALLBACK"],
  "smry": {
    "emdCnt": 40,
    "rvwTrgtCnt": 10,
    "coreCnt": 3,
    "cvlcptOnlyCnt": 5,
    "unableCnt": 2,
    "vaclotExclRvwTrgtCnt": 12,
    "cvlcptTot": 10000,
    "rvwTrgtCvlcptRt": 60.5
  },
  "zones": [
    {
      "rnk": 1,
      "emdCd": "50110000",
      "sggNm": "제주시",
      "emdNm": "예시동",
      "lat": 33.5000000,
      "lot": 126.5000000,
      "cvlcptCnt": 1600,
      "cvlcptMmAvg": 200.0,
      "pkltCnt": 20,
      "pkltCpcty": 500,
      "cvlcptRt": 40.00,
      "rvwScr": 4.00,
      "rvwGrdCd": "CORE",
      "rvwTrgtYn": "Y",
      "cutnCds": [],
      "topPntRt": 5.0,
      "vaclotExclCvlcptRt": 44.00,
      "vaclotExclRvwTrgtYn": "Y",
      "bfrCvlcptCnt": 700,
      "cvlcptIncrsRt": 14.3,
      "incrsCfc": 1.072,
      "incrsRfltRvwScr": 4.28
    }
  ]
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `excnId` | Long | 아니오 | 저장된 분석 실행 ID |
| `crtrVls.cvlcptMmCrtrVl` | Number | 아니오 | 적용한 민원 월기준값 |
| `crtrVls.cvlcptRtCrtrVl` | Number | 아니오 | 적용한 민원 비율기준값 |
| `crtrVls.coreMltp` | Number | 아니오 | 핵심 후보 배수. 코드 상수 2 |
| `crtrVls.months` | Number | 아니오 | 월평균 분모(달별 조회 비율 합, 소수 최대 6자리) |
| `crtrVls.incrsWgvl` | Number | 아니오 | 적용한 민원증가 가중치 |
| `crtrVls.incrsRtUplmtVl` | Number | 아니오 | 적용한 민원증가율 상한(%) |
| `crtrVls.minTrendBase` | Integer | 아니오 | 증가율 분모 하한(건). 코드 상수 10 |
| `crtrVls.trendDayCnt` | Integer | 예 | 증가율 블록 일수. 직전 동일 기간 비교면 D, 반 분할이면 D/2, 조회 기간 14일 미만이면 null |
| `vrblAjmt` | Object | 아니오 | 분석 계수 적용 내역. 구조는 2번 API의 `vrblAjmt` 와 같다 |
| `dataBgngYmd` | String | 예 | 민원 자료 시작일 (`YYYYMMDD`). 조회 기간과 무관. 자료가 없으면 null |
| `dataEndYmd` | String | 예 | 민원 자료 종료일 (`YYYYMMDD`). 조회 기간과 무관. 자료가 없으면 null |
| `warnings[]` | String | 아니오 | `CRTR_FALLBACK`(코드 기본값으로 판정) \| `NO_CVLCPT_IN_RANGE`(기간 내 민원 0건) \| `CRTR_ALL_ZERO`(두 기준값이 모두 0) \| `TREND_WINDOW_SHORT`(조회 기간 14일 미만) \| `TREND_BFR_FALLBACK`(증가율을 반 분할로 계산). 없으면 빈 배열 |
| `smry.emdCnt` | Integer | 아니오 | 전체 읍면동 수 |
| `smry.rvwTrgtCnt` | Integer | 아니오 | 후보 읍면동 수 (`CORE` + `CAND`) |
| `smry.coreCnt` | Integer | 아니오 | 핵심 후보 수 |
| `smry.cvlcptOnlyCnt` | Integer | 아니오 | 민원 기준만 충족한 읍면동 수 |
| `smry.unableCnt` | Integer | 아니오 | 판정 불가 읍면동 수 |
| `smry.vaclotExclRvwTrgtCnt` | Integer | 아니오 | 공한지를 뺀 면수로 판정한 후보 수 |
| `smry.cvlcptTot` | Long | 아니오 | 조회 기간 주차 민원 총건수 |
| `smry.rvwTrgtCvlcptRt` | Number | 예 | 후보 읍면동 민원 비중(%, 소수 1자리). 민원 0건이면 null |
| `zones[]` | Array | 아니오 | 필터에 걸린 읍면동 전부. `rnk` 오름차순, 순번이 없는 읍면동은 맨 뒤(민원 건수 내림차순 → 읍면동 코드 오름차순) |
| `zones[].rnk` | Integer | 예 | 표시 순번. 추세 반영 지수 내림차순 → 민원 건수 내림차순 → 읍면동 코드 오름차순. 검토 지수가 없으면 null |
| `zones[].emdCd` | String | 아니오 | 읍면동 코드 |
| `zones[].sggNm` | String | 아니오 | 시군구명 |
| `zones[].emdNm` | String | 아니오 | 읍면동명 |
| `zones[].lat` | Number | 예 | 읍면동 중심 위도 |
| `zones[].lot` | Number | 예 | 읍면동 중심 경도 |
| `zones[].cvlcptCnt` | Long | 아니오 | 조회 기간 주차 민원 건수(유형 `99` 제외) |
| `zones[].cvlcptMmAvg` | Number | 아니오 | 월평균 민원(소수 1자리) |
| `zones[].pkltCnt` | Long | 아니오 | 공영주차장 개소 수(소멸분 제외) |
| `zones[].pkltCpcty` | Long | 아니오 | 공영 주차면수 합(공한지 포함) |
| `zones[].cvlcptRt` | Number | 예 | 공영 100면당 월평균 민원(소수 2자리). 면수 0 이면 null |
| `zones[].rvwScr` | Number | 예 | 검토 지수(소수 2자리 버림) = min(월평균 ÷ 월기준값, 100면당 월평균 ÷ 비율기준값). 면수 0 이거나 두 기준값이 모두 0 이면 null |
| `zones[].rvwGrdCd` | String | 아니오 | `CORE` \| `CAND` \| `CVLCPT_ONLY` \| `UNABLE` \| `NONE`. 이름은 공통코드 `SHRN_RVW_GRD_CD` |
| `zones[].rvwTrgtYn` | String | 아니오 | 후보(`CORE`·`CAND`)면 `Y`, 그 밖은 `N` |
| `zones[].cutnCds[]` | String | 아니오 | 후보에만 붙는 주의 표시. `TOP_PNT`(민원 최다 좌표 1곳을 빼면 후보 미달) \| `CRTR_BNDRY`(기준 대비 여유 5% 미만). 없으면 빈 배열 |
| `zones[].topPntRt` | Number | 예 | 민원 최다 좌표 1곳의 비중(%, 소수 1자리). 민원 0건이면 null |
| `zones[].vaclotExclCvlcptRt` | Number | 예 | 공한지를 뺀 면수 기준 100면당 월평균 민원(소수 2자리). 해당 면수 0 이면 null |
| `zones[].vaclotExclRvwTrgtYn` | String | 아니오 | 공한지를 뺀 면수로 판정한 후보 여부 |
| `zones[].bfrCvlcptCnt` | Long | 예 | 증가율 분모로 쓴 이전 블록 민원 건수. 조회 기간 14일 미만이면 null |
| `zones[].cvlcptIncrsRt` | Number | 예 | 민원 증가율(%, 소수 1자리). 이전 블록 10건 미만이거나 조회 기간 14일 미만이면 null |
| `zones[].incrsCfc` | Number | 아니오 | 증가계수(소수 3자리). 증가율이 null 이면 1 |
| `zones[].incrsRfltRvwScr` | Number | 예 | 추세 반영 지수(소수 2자리 버림) = 검토 지수 × 증가계수. 검토 지수가 null 이면 null |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status, error, path}` | 필수 파라미터 누락, 날짜 형식 오류·역전, 36개월 초과, `sggNm` 40자 초과 또는 없는 시군구, 없는 `emdCd`, `vrblVls` 형식 오류·허용 목록 밖 코드·사용 안 함 또는 숨김 변수·null 값·자릿수 초과·범위 밖 |
| 401 | 빈 본문 | 로그인 세션 없음 |
| 401 | `{timestamp, status, error, path}` | 세션 사용자가 `TB_USER` 에 없거나 사용 정지 상태 |
| 500 | `{timestamp, status, error, path}` | 처리되지 않은 서버 오류 |

## 5. POST /api/analysis/zone-recommendation/share/reports

### API 개요
공유주차제 적용 결과를 같은 조건으로 다시 계산해 보고서로 저장한다. 호출 1회마다 `TB_ANLS_EXCN`, `TB_ANLS_RSLT`, `TB_RPTP`(생성구분 `MANUAL`)에 1행씩 한 트랜잭션으로 저장한다. 서버에 파일을 만들지 않고 응답 본문으로 스냅샷을 반환한다.

로그인 세션과 CSRF 토큰이 필요하고 세션 사용자가 `TB_USER` 에 사용 상태로 있어야 한다. 메뉴 권한 검사는 없다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `Cookie: JSESSIONID` | String | 필수 | 없음 | 로그인 세션 쿠키 |
| header | `Cookie: XSRF-TOKEN` | String | 필수 | 없음 | CSRF 토큰 쿠키 |
| header | `X-XSRF-TOKEN` | String | 필수 | 없음 | `XSRF-TOKEN` 쿠키와 같은 값 |
| header | `Content-Type` | String | 필수 | 없음 | `application/json` |
| body | `bgngYmd` | String | 필수 | 없음 | 민원 조회 시작일. 숫자 8자리 (`YYYYMMDD`) |
| body | `endYmd` | String | 필수 | 없음 | 민원 조회 종료일. 숫자 8자리, 당일 포함. 36개월 이내 |
| body | `sggNm` | String | 선택 | 없음 | 시군구명 필터(40자 이내). 생략·공백이면 전체 지역. 읍면동 필터는 받지 않는다 |
| body | `vrblVls` | Object(코드 → Number) | 선택 | 설정값 | 분석 계수 일시 입력. 허용 코드·범위·규칙은 4번 API의 `vrblVls` 와 같다 |
| body | `fileFmt` | String | 필수 | 없음 | `PDF` \| `DOCX` \| `XLSX` |

### 호출 예시
```bash
curl -X POST "http://localhost:8180/api/analysis/zone-recommendation/share/reports" \
  -H "Cookie: JSESSIONID=example-session-id; XSRF-TOKEN=example-csrf-token" \
  -H "X-XSRF-TOKEN: example-csrf-token" \
  -H "Content-Type: application/json" \
  -d '{
    "bgngYmd": "20260101",
    "endYmd": "20260831",
    "sggNm": "제주시",
    "fileFmt": "PDF"
  }'
```

### 응답
`201 Created` · `application/json`

```json
{
  "rptpId": 2002,
  "excnId": 1004,
  "rptpNm": "구역추천 공유주차제 적용 보고서 제주시 전체",
  "regionNm": "제주시 전체",
  "anlsPrdExpln": "2026.01.01 ~ 2026.08.31",
  "fileFmt": "PDF",
  "crtYmd": "2026-11-18T10:05:00",
  "bgngYmd": "20260101",
  "endYmd": "20260831",
  "vrblAjmt": { "ajmtYn": "N", "vrbls": [] },
  "rcmdtnTypeCd": "SHARE",
  "fee": null,
  "share": {
    "excnId": null,
    "crtrVls": { "cvlcptMmCrtrVl": 50, "cvlcptRtCrtrVl": 7 },
    "vrblAjmt": null,
    "dataBgngYmd": "20260101",
    "dataEndYmd": "20261031",
    "warnings": [],
    "smry": { "emdCnt": 40, "rvwTrgtCnt": 10 },
    "zones": [],
    "wbk": {
      "rows": [
        {
          "emdCd": "50110000",
          "sggNm": "제주시",
          "emdNm": "예시동",
          "cvlcptCnt": 1600,
          "pkltCnt": 20,
          "pkltCpcty": 500,
          "topPntCvlcptCnt": 80,
          "vaclotExclPkltCpcty": 450,
          "rcntCnt": 800,
          "bfrCnt": 700
        }
      ],
      "months": 8,
      "trendMode": "FALLBACK",
      "fixed": { "coreMltp": 2, "bndryRt": 1.05, "minTrendBase": 10 },
      "vrbls": [
        {
          "vrblCd": "CVLCPT_MM_CRTR_VL",
          "vrblNm": "민원월기준값",
          "aplcnVl": 50,
          "minVl": 0,
          "maxVl": 500,
          "intOnlyYn": "N",
          "inptYn": "Y"
        }
      ]
    }
  }
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `rptpId` | Long | 아니오 | 보고서 ID |
| `excnId` | Long | 아니오 | 분석 실행 ID |
| `rptpNm` | String | 아니오 | `구역추천 공유주차제 적용 보고서 {regionNm}` |
| `regionNm` | String | 아니오 | `{시군구} 전체` 또는 `전체 지역` |
| `anlsPrdExpln` | String | 아니오 | 민원 기간 `yyyy.MM.dd ~ yyyy.MM.dd`. 시작일과 종료일이 같으면 날짜 하나 |
| `fileFmt` | String | 아니오 | 요청한 파일 형식 |
| `crtYmd` | String | 아니오 | 생성일시 (ISO-8601) |
| `bgngYmd` | String | 아니오 | 적용한 민원 시작일 (`YYYYMMDD`) |
| `endYmd` | String | 아니오 | 적용한 민원 종료일 (`YYYYMMDD`) |
| `vrblAjmt` | Object | 아니오 | 분석 계수 적용 내역. 구조는 2번 API의 `vrblAjmt` 와 같다 |
| `rcmdtnTypeCd` | String | 아니오 | 항상 `SHARE` |
| `fee` | Object | 예 | 항상 null |
| `share` | Object | 아니오 | 공유주차제 적용 본문. 필드는 4번 API 응답과 같고 아래 항목만 다르다 |
| `share.excnId` | Long | 예 | 항상 null. 실행 ID는 최상위 `excnId` 를 쓴다 |
| `share.vrblAjmt` | Object | 예 | 항상 null. 적용 내역은 최상위 `vrblAjmt` 를 쓴다 |
| `share.wbk.rows[]` | Array | 아니오 | `zones` 와 같은 읍면동의 원자료. 민원 건수 내림차순 → 읍면동 코드 오름차순 |
| `share.wbk.rows[].emdCd` | String | 아니오 | 읍면동 코드 |
| `share.wbk.rows[].sggNm` | String | 아니오 | 시군구명 |
| `share.wbk.rows[].emdNm` | String | 아니오 | 읍면동명 |
| `share.wbk.rows[].cvlcptCnt` | Long | 아니오 | 조회 기간 민원 건수 |
| `share.wbk.rows[].pkltCnt` | Long | 아니오 | 공영주차장 개소 수 |
| `share.wbk.rows[].pkltCpcty` | Long | 아니오 | 공영 주차면수 |
| `share.wbk.rows[].topPntCvlcptCnt` | Long | 아니오 | 민원 최다 좌표 1곳의 건수 |
| `share.wbk.rows[].vaclotExclPkltCpcty` | Long | 아니오 | 공한지를 뺀 주차면수 |
| `share.wbk.rows[].rcntCnt` | Long | 예 | 최근 블록 민원 건수. `trendMode` 가 `NONE` 이면 null |
| `share.wbk.rows[].bfrCnt` | Long | 예 | 이전 블록 민원 건수. `trendMode` 가 `NONE` 이면 null |
| `share.wbk.months` | Number | 아니오 | 월평균 분모. `crtrVls.months` 와 같다 |
| `share.wbk.trendMode` | String | 아니오 | `PRIMARY`(직전 동일 기간) \| `FALLBACK`(반 분할) \| `NONE`(조회 기간 14일 미만) |
| `share.wbk.fixed.coreMltp` | Number | 아니오 | 핵심 후보 배수. 2 |
| `share.wbk.fixed.bndryRt` | Number | 아니오 | `CRTR_BNDRY` 판정 배율. 1.05 |
| `share.wbk.fixed.minTrendBase` | Integer | 아니오 | 증가율 분모 하한(건). 10 |
| `share.wbk.vrbls[].vrblCd` | String | 아니오 | 변수코드. 4번 API의 `vrblVls` 4개 순서 |
| `share.wbk.vrbls[].vrblNm` | String | 아니오 | 변수명. 설정 행이 없으면 변수코드 |
| `share.wbk.vrbls[].aplcnVl` | Number | 예 | 적용값 |
| `share.wbk.vrbls[].minVl` | Number | 예 | 최솟값. 설정 행이 없으면 null |
| `share.wbk.vrbls[].maxVl` | Number | 예 | 최댓값. 설정 행이 없으면 null |
| `share.wbk.vrbls[].intOnlyYn` | String | 아니오 | 정수 전용 여부. 항상 `N` |
| `share.wbk.vrbls[].inptYn` | String | 아니오 | 엑셀 입력 셀 여부. 설정 행이 있으면 `Y` |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status, error, path}` | 본문 JSON 오류, 필수값 누락, 날짜·`fileFmt` 형식 오류, 실재하지 않는 날짜·기간 역전·36개월 초과, `sggNm` 40자 초과 또는 없는 시군구, `vrblVls` 규칙 위반, 검토 후보(`rvwTrgtYn='Y'`) 0곳 |
| 401 | 빈 본문 | 로그인 세션 없음 (CSRF 토큰은 유효한 경우) |
| 401 | `{timestamp, status, error, path}` | 세션 사용자가 `TB_USER` 에 없거나 사용 정지 상태 |
| 403 | `{timestamp, status, error, path}` | CSRF 토큰 누락 또는 불일치 |
| 500 | `{timestamp, status, error, path}` | 처리되지 않은 서버 오류 |

오류 응답이면 어떤 테이블에도 저장하지 않는다.

## 6. GET /api/analysis/investment-priority

### API 개요
노상·공한지를 제외한 미소멸 공영주차장의 투자 우선순위를 종합점수·순위·등급으로 조회한다. 종합점수는 `INVEST` 분석변수에서 가중치가 0보다 큰 지표의 가중평균이며, 초기 데이터 기준 안전·민원·이용·사고 지표를 각 25% 반영한다. `sggNm`·`emdCd` 는 `lots` 만 거르는 표시 필터이고 점수·순위·`smry`·`warnings` 는 전 지역 기준이다.

로그인 세션이 필요하다. 메뉴 권한 검사는 없다. 실행 이력을 남기지 않고 DB를 변경하지 않는다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `Cookie: JSESSIONID` | String | 필수 | 없음 | 로그인 세션 쿠키 |
| query | `bgngYmd` | String | 조건부 | 오늘(Asia/Seoul)에서 12개월 전 | 분석 시작일 (`YYYYMMDD`). `endYmd` 와 함께 보내거나 함께 생략한다 |
| query | `endYmd` | String | 조건부 | 오늘(Asia/Seoul) | 분석 종료일 (`YYYYMMDD`, 당일 포함). 시작 달부터 종료 달까지 36개월 이내 |
| query | `sggNm` | String | 선택 | 없음 | 시군구명 표시 필터. 없는 값이면 `lots` 가 빈 배열이다 |
| query | `emdCd` | String | 선택 | 없음 | 읍면동 코드 표시 필터(8자리). 없는 값이면 `lots` 가 빈 배열이다 |
| query | `vrblVls` | String(JSON 객체) | 선택 | 설정값 | 분석 계수 일시 입력. 설정값은 바꾸지 않는다. 값은 숫자이고 정수부 8자리·소수 4자리 이내이며, 같은 키를 중복할 수 없다. 범위는 `tb_anls_vrbl_stng` 의 `min_vl`~`max_vl` 이고 아래 범위는 초기 데이터 기준이다 |
| query | `vrblVls.SAFETY` | Number | 선택 | 설정값 (초기 25) | 안전 가중치(%). 범위 0~100. 가중치 4개의 합은 100 이어야 한다 |
| query | `vrblVls.COMPLAINT` | Number | 선택 | 설정값 (초기 25) | 민원 가중치(%). 범위 0~100 |
| query | `vrblVls.USAGE` | Number | 선택 | 설정값 (초기 25) | 이용 가중치(%). 범위 0~100 |
| query | `vrblVls.ACCIDENT` | Number | 선택 | 설정값 (초기 25) | 사고 가중치(%). 범위 0~100 |
| query | `vrblVls.ALTMNT_RNG_VL` | Number | 선택 | 설정값 (초기 300) | 배정 반경(m). 범위 50~1000 |
| query | `vrblVls.UP_GRD_CRTR_SCR` | Number | 선택 | 설정값 (초기 80) | 등급 `H` 기준 점수. 범위 0~100. `MD_GRD_CRTR_SCR` 보다 커야 한다 |
| query | `vrblVls.MD_GRD_CRTR_SCR` | Number | 선택 | 설정값 (초기 60) | 등급 `M` 기준 점수. 범위 0~100 |
| query | `vrblVls.INDCT_PKLT_CNT` | Number | 선택 | 설정값 (초기 50) | 표시 주차장 수. 초기 데이터는 `expsr_yn='N'` 이라 보내면 400 이다 |

### 호출 예시
```bash
curl -G "http://localhost:8180/api/analysis/investment-priority" \
  -H "Cookie: JSESSIONID=example-session-id" \
  --data-urlencode "bgngYmd=20260101" \
  --data-urlencode "endYmd=20260831" \
  --data-urlencode "sggNm=제주시" \
  --data-urlencode 'vrblVls={"ALTMNT_RNG_VL":500}'
```

### 응답
`200 OK` · `application/json`. 결과가 0건이어도 200 이다.

```json
{
  "bgngYmd": "20260101",
  "endYmd": "20260831",
  "utztnBgngYm": "202601",
  "utztnEndYm": "202608",
  "sftyCrtrYmd": "20260831",
  "cvlcptLastYmd": "20260831",
  "utztnLastYm": "202608",
  "sftyChckLastYmd": "20260701",
  "acdntLastYmd": "20260610",
  "scoreFormula": "안전 25% + 민원 25% + 이용 25% + 사고 25% (안전은 점검 결과 점수 · 그 밖은 백분위 점수)",
  "weights": [
    {
      "anlsTypeCd": "INVEST",
      "anlsTrgtCd": null,
      "anlsTrgtNm": null,
      "vrblCd": "SAFETY",
      "vrblNm": "안전위험도",
      "vrblExpln": "예시 설명",
      "vrblSeCd": "WGVL",
      "stngVl": 25.00,
      "bscStngVl": 25.0000,
      "minVl": 0.0000,
      "maxVl": 100.0000,
      "unitNm": "%",
      "useYn": "Y",
      "expsrYn": "Y",
      "sortSeq": 1,
      "mdfcnDt": null
    }
  ],
  "gradeCriteria": [
    { "prrtLvlCd": "H", "prrtLvlNm": "높음", "minScr": 80 },
    { "prrtLvlCd": "M", "prrtLvlNm": "중간", "minScr": 60 },
    { "prrtLvlCd": "L", "prrtLvlNm": "낮음", "minScr": 0 }
  ],
  "crtrVls": { "altmntRngVl": 500, "indctPkltCnt": 50 },
  "vrblAjmt": { "ajmtYn": "Y", "vrbls": [] },
  "warnings": [
    {
      "code": "SFTY_CHCK_MISSING",
      "message": "안전점검 기록이 없는 주차장 3곳을 안전 0점으로 계산했습니다.",
      "cnt": 3
    }
  ],
  "smry": { "trgtCnt": 100, "highCnt": 2, "acdntCnt": 12 },
  "lots": [
    {
      "rnk": 1,
      "sggRnk": 1,
      "crdnExclRnk": 1,
      "pkltId": 101,
      "pkltNm": "예시 공영주차장",
      "pkltAddr": "제주특별자치도 제주시 예시동 1-1",
      "sggNm": "제주시",
      "emdCd": "50110000",
      "emdNm": "예시동",
      "pkltCpcty": 100,
      "instlYm": null,
      "lat": 33.5000000,
      "lot": 126.5000000,
      "snthsScr": 91.250000000,
      "prrtLvlCd": "H",
      "rsnCd": "BOTH",
      "sftyChckRsltCd": "C",
      "sftyChckYmd": "20260701",
      "sftyRiskScr": 100,
      "cvlcptFrqScr": 90.000000,
      "utztnPttnScr": 85.000000,
      "crdnScr": 70.000000,
      "acdntScr": 90.000000,
      "cvlcptCnt": 98,
      "dayAvgTrnvrRt": 4.7776,
      "crdnCnt": 30,
      "acdntCnt": 2
    }
  ]
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `bgngYmd` | String | 아니오 | 적용한 시작일 (`YYYYMMDD`) |
| `endYmd` | String | 아니오 | 적용한 종료일 (`YYYYMMDD`) |
| `utztnBgngYm` | String | 아니오 | 이용률 집계 시작 달 (`YYYYMM`). 시작일이 1일이 아니면 다음 달부터 집계하되 종료 달을 넘지 않는다 |
| `utztnEndYm` | String | 아니오 | 이용률 집계 끝 달 (`YYYYMM`) |
| `sftyCrtrYmd` | String | 아니오 | 안전점검 기준일 (`YYYYMMDD`). 종료일과 같다 |
| `cvlcptLastYmd` | String | 예 | 종료일 이하 주차 민원 최근 발생일. 자료가 없으면 null |
| `utztnLastYm` | String | 예 | 종료 달 이하 회전율 최근 자료 달. 자료가 없으면 null |
| `sftyChckLastYmd` | String | 예 | 종료일 이하 대상 주차장 최근 점검일. 자료가 없으면 null |
| `acdntLastYmd` | String | 예 | 종료일 이하 대상 주차장 최근 사고일. 자료가 없으면 null |
| `scoreFormula` | String | 아니오 | 실제 반영한 지표와 가중치의 산식 표기 |
| `weights[]` | Array | 아니오 | 계산에 반영한 가중치. 가중치 0 인 지표는 빠진다 |
| `weights[].anlsTypeCd` | String | 아니오 | 분석유형코드. `INVEST` |
| `weights[].anlsTrgtCd` | String | 예 | 분석내용코드 |
| `weights[].anlsTrgtNm` | String | 예 | 분석내용명 |
| `weights[].vrblCd` | String | 아니오 | 변수코드 |
| `weights[].vrblNm` | String | 아니오 | 변수명 |
| `weights[].vrblExpln` | String | 예 | 변수설명 |
| `weights[].vrblSeCd` | String | 아니오 | 변수구분코드. `WGVL` |
| `weights[].stngVl` | Number | 아니오 | 적용 가중치(%) |
| `weights[].bscStngVl` | Number | 예 | 기본값 |
| `weights[].minVl` | Number | 예 | 최솟값 |
| `weights[].maxVl` | Number | 예 | 최댓값 |
| `weights[].unitNm` | String | 예 | 단위명 |
| `weights[].useYn` | String | 아니오 | 사용 여부 |
| `weights[].expsrYn` | String | 예 | 노출 여부 |
| `weights[].sortSeq` | Integer | 예 | 정렬 순서 |
| `weights[].mdfcnDt` | String | 예 | 설정 수정일시 (ISO-8601) |
| `gradeCriteria[].prrtLvlCd` | String | 아니오 | 등급 코드 `H` \| `M` \| `L`. 이 순서로 반환 |
| `gradeCriteria[].prrtLvlNm` | String | 아니오 | 등급명(공통코드 `IVST_PRRT_LVL_CD`). 없으면 코드값 |
| `gradeCriteria[].minScr` | Number | 아니오 | 이 등급의 최소 종합점수(이상) |
| `crtrVls.altmntRngVl` | Number | 아니오 | 적용한 배정 반경(m) |
| `crtrVls.indctPkltCnt` | Integer | 아니오 | 화면 표시 주차장 수. `lots` 를 자르지 않는다 |
| `vrblAjmt` | Object | 아니오 | 분석 계수 적용 내역(가중치 4 + 계수 4). 구조는 2번 API의 `vrblAjmt` 와 같다 |
| `warnings[].code` | String | 아니오 | `CRDN_SGG_SKEW` \| `SFTY_CHCK_MISSING` \| `SFTY_CD_UNKNOWN` \| `ACDNT_NO_DATA` \| `UTZTN_PARTIAL` \| `UTZTN_MISSING` \| `WGVL_NOT_APPLIED` |
| `warnings[].message` | String | 아니오 | 사람이 읽는 설명 |
| `warnings[].cnt` | Integer | 예 | 해당 주차장 수. 개수가 없는 알림은 null |
| `smry.trgtCnt` | Integer | 아니오 | 분석 대상 주차장 수(전 지역) |
| `smry.highCnt` | Integer | 아니오 | 등급 `H` 주차장 수(전 지역) |
| `smry.acdntCnt` | Long | 예 | 기간 내 사고 건수(`tb_pklt_acdnt_hstry` 전체 기준). 사고이력 자료가 한 행도 없으면 null |
| `lots[]` | Array | 아니오 | 필터를 통과한 주차장. `rnk` 오름차순 |
| `lots[].rnk` | Integer | 아니오 | 전 지역 순위. 종합점수 내림차순 → 주차장 ID 오름차순. 필터를 걸어도 다시 매기지 않는다 |
| `lots[].sggRnk` | Integer | 예 | 같은 시군구 안 등수. 시군구가 없으면 null |
| `lots[].crdnExclRnk` | Integer | 예 | 단속 가중치를 뺀 순위. 단속이 반영되지 않았으면 `rnk` 와 같고, 단속만 반영 중이면 null |
| `lots[].pkltId` | Long | 아니오 | 주차장 ID |
| `lots[].pkltNm` | String | 아니오 | 주차장명 |
| `lots[].pkltAddr` | String | 예 | 주차장 주소 |
| `lots[].sggNm` | String | 예 | 시군구명 |
| `lots[].emdCd` | String | 예 | 읍면동 코드 |
| `lots[].emdNm` | String | 예 | 읍면동명 |
| `lots[].pkltCpcty` | Integer | 예 | 주차면수 |
| `lots[].instlYm` | String | 예 | 설치연월 (`YYYYMM`) |
| `lots[].lat` | Number | 아니오 | 위도 |
| `lots[].lot` | Number | 아니오 | 경도 |
| `lots[].snthsScr` | Number | 아니오 | 종합점수(0~100, 소수 9자리) |
| `lots[].prrtLvlCd` | String | 아니오 | 우선순위 등급 `H` \| `M` \| `L` |
| `lots[].rsnCd` | String | 아니오 | `SAFETY`(점검 결과 `B`·`C`) \| `DEMAND`(민원·이용 백분위 평균 상위 25%) \| `BOTH` \| `NONE` |
| `lots[].sftyChckRsltCd` | String | 예 | 종료일 이전 최근 안전점검 결과 `A` \| `B` \| `C`. 점검이 없으면 null |
| `lots[].sftyChckYmd` | String | 예 | 안전점검 일자 (`YYYYMMDD`). 점검이 없으면 null |
| `lots[].sftyRiskScr` | Number | 아니오 | 안전위험 점수. `A` 0, `B` 50, `C` 100, 점검 없음 0 |
| `lots[].cvlcptFrqScr` | Number | 아니오 | 민원빈도 백분위 점수(0~100, 소수 6자리) |
| `lots[].utztnPttnScr` | Number | 아니오 | 이용패턴 백분위 점수(0~100, 소수 6자리). 회전율이 없으면 0 으로 보고 산정 |
| `lots[].crdnScr` | Number | 아니오 | 단속 백분위 점수(0~100, 소수 6자리). 단속 가중치가 없으면 종합점수에 반영하지 않는다 |
| `lots[].acdntScr` | Number | 아니오 | 사고 백분위 점수(0~100, 소수 6자리) |
| `lots[].cvlcptCnt` | Long | 아니오 | 배정 반경 안 최근접 배정 민원 수 |
| `lots[].dayAvgTrnvrRt` | Number | 예 | 일평균 회전율. 자료가 없으면 null |
| `lots[].crdnCnt` | Long | 아니오 | 배정 반경 안 최근접 배정 단속 수 |
| `lots[].acdntCnt` | Long | 아니오 | 그 주차장에 기록된 기간 내 사고 건수 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status, error, path}` | `bgngYmd`·`endYmd` 중 한쪽만 보냄, 날짜 형식 오류·역전, 36개월 초과, `vrblVls` 형식 오류·허용 목록 밖 코드·사용 안 함 또는 숨김 변수·null 값·자릿수 초과·범위 밖·가중치 합계 100 아님·`MD_GRD_CRTR_SCR` ≥ `UP_GRD_CRTR_SCR` |
| 401 | 빈 본문 | 로그인 세션 없음 |
| 409 | `{timestamp, status, error, path}` | 반영할 가중치가 하나도 없거나, 입력 없이 설정된 등급 기준점수가 역전됨 |
| 500 | `{timestamp, status, error, path}` | 처리되지 않은 서버 오류 |

## 7. POST /api/analysis/investment-priority/reports

### API 개요
투자우선순위를 같은 조건으로 다시 분석해 보고서로 저장한다. 호출 1회마다 `TB_ANLS_EXCN`, `TB_ANLS_RSLT`, `TB_RPTP`(생성구분 `MANUAL`)에 1행씩 한 트랜잭션으로 저장한다. 서버에 파일을 만들지 않고 응답 본문으로 스냅샷을 반환한다.

로그인 세션과 CSRF 토큰이 필요하고 세션 사용자가 `TB_USER` 에 사용 상태로 있어야 한다. 메뉴 권한 검사는 없다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `Cookie: JSESSIONID` | String | 필수 | 없음 | 로그인 세션 쿠키 |
| header | `Cookie: XSRF-TOKEN` | String | 필수 | 없음 | CSRF 토큰 쿠키 |
| header | `X-XSRF-TOKEN` | String | 필수 | 없음 | `XSRF-TOKEN` 쿠키와 같은 값 |
| header | `Content-Type` | String | 필수 | 없음 | `application/json` |
| body | `sggNm` | String | 선택 | 없음 | 시군구명 필터. 생략·공백이면 전 지역 |
| body | `emdCd` | String | 선택 | 없음 | 읍면동 코드 필터(8자리). 생략·공백이면 시군구 전체. `sggNm` 과 함께 보내면 그 시군구에 속해야 한다 |
| body | `bgngYmd` | String | 조건부 | 오늘(Asia/Seoul)에서 12개월 전 | 분석 시작일. 숫자 8자리. `endYmd` 와 함께 보내거나 함께 생략한다 |
| body | `endYmd` | String | 조건부 | 오늘(Asia/Seoul) | 분석 종료일. 숫자 8자리, 당일 포함. 36개월 이내 |
| body | `vrblVls` | Object(코드 → Number) | 선택 | 설정값 | 분석 계수 일시 입력. 허용 코드·범위·규칙은 6번 API의 `vrblVls` 와 같다 |
| body | `fileFmt` | String | 필수 | 없음 | `PDF` \| `DOCX` \| `XLSX` |

### 호출 예시
```bash
curl -X POST "http://localhost:8180/api/analysis/investment-priority/reports" \
  -H "Cookie: JSESSIONID=example-session-id; XSRF-TOKEN=example-csrf-token" \
  -H "X-XSRF-TOKEN: example-csrf-token" \
  -H "Content-Type: application/json" \
  -d '{
    "sggNm": "제주시",
    "bgngYmd": "20260101",
    "endYmd": "20260831",
    "fileFmt": "XLSX"
  }'
```

### 응답
`201 Created` · `application/json`

```json
{
  "rptpId": 2003,
  "excnId": 1005,
  "rptpNm": "투자 우선순위 보고서 제주시 전체",
  "regionNm": "제주시 전체",
  "anlsPrdExpln": "2026.01.01 ~ 2026.08.31",
  "fileFmt": "XLSX",
  "crtYmd": "2026-11-18T10:10:00",
  "bgngYmd": "20260101",
  "endYmd": "20260831",
  "scoreFormula": "안전 25% + 민원 25% + 이용 25% + 사고 25% (안전은 점검 결과 점수 · 그 밖은 백분위 점수)",
  "weights": [],
  "gradeCriteria": [],
  "crtrVls": { "altmntRngVl": 300, "indctPkltCnt": 50 },
  "vrblAjmt": { "ajmtYn": "N", "vrbls": [] },
  "warnings": [],
  "smry": { "trgtCnt": 100, "highCnt": 2, "acdntCnt": 12 },
  "lots": [],
  "wbk": {
    "trgtCnt": 100,
    "rows": [
      { "pkltId": 101, "cvlcptLtCnt": 90, "utztnLtCnt": 85, "acdntLtCnt": 90 }
    ],
    "vrbls": [
      {
        "vrblCd": "SAFETY",
        "vrblNm": "안전위험도",
        "aplcnVl": 25,
        "minVl": 0,
        "maxVl": 100,
        "intOnlyYn": "N",
        "inptYn": "Y"
      }
    ]
  }
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `rptpId` | Long | 아니오 | 보고서 ID |
| `excnId` | Long | 아니오 | 분석 실행 ID |
| `rptpNm` | String | 아니오 | `투자 우선순위 보고서 {regionNm}` |
| `regionNm` | String | 아니오 | `전체 지역`, `{시군구} 전체`, `{시군구} {읍면동}` 중 하나 |
| `anlsPrdExpln` | String | 아니오 | 분석 기간 `yyyy.MM.dd ~ yyyy.MM.dd`. 시작일과 종료일이 같으면 날짜 하나 |
| `fileFmt` | String | 아니오 | 요청한 파일 형식 |
| `crtYmd` | String | 아니오 | 생성일시 (ISO-8601) |
| `bgngYmd` | String | 아니오 | 실제 적용한 시작일 (`YYYYMMDD`) |
| `endYmd` | String | 아니오 | 실제 적용한 종료일 (`YYYYMMDD`) |
| `scoreFormula` | String | 아니오 | 6번 API의 같은 필드 |
| `weights[]` | Array | 아니오 | 6번 API의 같은 필드 |
| `gradeCriteria[]` | Array | 아니오 | 6번 API의 같은 필드 |
| `crtrVls` | Object | 아니오 | 6번 API의 같은 필드 |
| `vrblAjmt` | Object | 아니오 | 6번 API의 같은 필드 |
| `warnings[]` | Array | 아니오 | 6번 API의 같은 필드 |
| `smry` | Object | 아니오 | 6번 API의 같은 필드 |
| `lots[]` | Array | 아니오 | 필터 통과분 전체. 필드는 6번 API와 같고 `rnk` 는 전 지역 순위 그대로다 |
| `wbk` | Object | 예 | 엑셀 재계산 데이터. 단속(`ENFORCE`) 가중치가 반영된 경우 키가 없다 |
| `wbk.trgtCnt` | Integer | 아니오 | 백분위 모집단 수(필터 전 전 지역 대상 수) |
| `wbk.rows[].pkltId` | Long | 아니오 | 주차장 ID. `lots` 와 같은 주차장·같은 순서 |
| `wbk.rows[].cvlcptLtCnt` | Integer | 아니오 | 자기보다 민원이 적은 대상 수 |
| `wbk.rows[].utztnLtCnt` | Integer | 아니오 | 자기보다 회전율이 낮은 대상 수 |
| `wbk.rows[].acdntLtCnt` | Integer | 아니오 | 자기보다 사고가 적은 대상 수 |
| `wbk.vrbls[].vrblCd` | String | 아니오 | 변수코드. 6번 API의 `vrblVls` 8개 순서 |
| `wbk.vrbls[].vrblNm` | String | 아니오 | 변수명. 설정 행이 없으면 변수코드 |
| `wbk.vrbls[].aplcnVl` | Number | 예 | 적용값 |
| `wbk.vrbls[].minVl` | Number | 예 | 최솟값. 설정 행이 없으면 null |
| `wbk.vrbls[].maxVl` | Number | 예 | 최댓값. 설정 행이 없으면 null |
| `wbk.vrbls[].intOnlyYn` | String | 아니오 | 정수 전용 여부. 항상 `N` |
| `wbk.vrbls[].inptYn` | String | 아니오 | 엑셀 입력 셀 여부. `ALTMNT_RNG_VL`·`INDCT_PKLT_CNT`·`ACCIDENT` 와 설정 행이 없는 변수는 `N`. 가중치 4개 중 설정 행이 없는 것이 있으면 가중치는 모두 `N` |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status, error, path}` | 본문 JSON 오류, `fileFmt` 누락·허용값 밖, 날짜 형식 오류, `bgngYmd`·`endYmd` 중 한쪽만 보냄, 실재하지 않는 날짜·기간 역전·36개월 초과, 없는 `emdCd`, `emdCd` 와 `sggNm` 불일치, `vrblVls` 규칙 위반, 필터를 통과한 주차장 0곳 |
| 401 | 빈 본문 | 로그인 세션 없음 (CSRF 토큰은 유효한 경우) |
| 401 | `{timestamp, status, error, path}` | 세션 사용자가 `TB_USER` 에 없거나 사용 정지 상태 |
| 403 | `{timestamp, status, error, path}` | CSRF 토큰 누락 또는 불일치 |
| 409 | `{timestamp, status, error, path}` | 반영할 가중치가 하나도 없거나, 입력 없이 설정된 등급 기준점수가 역전됨 |
| 500 | `{timestamp, status, error, path}` | 처리되지 않은 서버 오류 |

오류 응답이면 어떤 테이블에도 저장하지 않는다.
