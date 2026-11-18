# 위험단계 알림 API

## 1. GET /api/risk-criteria

### API 개요
위험단계별 알림 발생 기준 전체를 조회한다. 기준은 제주도 전 지역과 전 수신자에 공통으로 적용된다. 로그인 세션이 필요하며 메뉴 권한은 검사하지 않는다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | Cookie | string | Y | - | 로그인 세션 쿠키 `JSESSIONID` |

### 호출 예시
```bash
curl -b 'JSESSIONID=EXAMPLE_SESSION_ID' \
  'http://localhost:8180/api/risk-criteria'
```

### 응답
`200 OK`, `application/json`. 래퍼 없는 최상위 배열이며 공통코드 `RISK_LVL_CD` 의 `sort_seq` 순(CRIT, WARN, CAUT)으로 내려간다.

```json
[
  { "thrshId": "THRSH_CRIT", "riskLvlCd": "CRIT", "incrsRt": 50.00, "minCvlcptCnt": 10, "bscMinCvlcptCnt": 10 },
  { "thrshId": "THRSH_WARN", "riskLvlCd": "WARN", "incrsRt": 30.00, "minCvlcptCnt": 10, "bscMinCvlcptCnt": 10 },
  { "thrshId": "THRSH_CAUT", "riskLvlCd": "CAUT", "incrsRt": 20.00, "minCvlcptCnt": 10, "bscMinCvlcptCnt": 10 }
]
```

| 필드 | 타입 | null 가능 | 설명 |
| --- | --- | --- | --- |
| `[].thrshId` | string | N | 기준 ID. `THRSH_` + 위험단계 코드 |
| `[].riskLvlCd` | string | N | 위험단계 코드. `CRIT`(심각), `WARN`(경고), `CAUT`(주의) |
| `[].incrsRt` | number | Y | 전일 대비 민원 증가율 기준(%) |
| `[].minCvlcptCnt` | integer | N | 최소 민원 수 하한. 0 이면 하한 없음 |
| `[].bscMinCvlcptCnt` | integer | N | 기본 최소 민원 수. 저장 API 로 바뀌지 않는 초기화 기준값 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 401 | 없음 | 로그인 세션이 없음 |

## 2. PUT /api/risk-criteria

### API 개요
위험단계 3건의 알림 기준을 한 번에 저장(UPSERT)하고 저장 후 재조회한 3건을 돌려준다. 로그인 세션, CSRF 토큰, 메뉴 `M0404`(위험단계 알림 관리) 권한이 필요하다. 이미 적재된 알림 이력의 판정 당시 기준값은 바뀌지 않는다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | Cookie | string | Y | - | `JSESSIONID`, `XSRF-TOKEN` 쿠키 |
| header | X-XSRF-TOKEN | string | Y | - | `XSRF-TOKEN` 쿠키 값 |
| header | Content-Type | string | Y | - | `application/json` |
| body | criteria | array | Y | - | 단계별 기준. 사용 중인 `RISK_LVL_CD` 코드 전부를 단계당 1건씩 보낸다 |
| body | criteria[].riskLvlCd | string | Y | - | 위험단계 코드. `CRIT`, `WARN`, `CAUT` |
| body | criteria[].incrsRt | number | Y | - | 증가율 기준(%). 0 초과 100 이하, 정수 3자리, 소수 2자리 이내 |
| body | criteria[].minCvlcptCnt | integer | Y | - | 최소 민원 수 하한. 0 이상 1000 이하 |

### 호출 예시
```bash
curl -X PUT 'http://localhost:8180/api/risk-criteria' \
  -b 'JSESSIONID=EXAMPLE_SESSION_ID; XSRF-TOKEN=EXAMPLE_XSRF_TOKEN' \
  -H 'X-XSRF-TOKEN: EXAMPLE_XSRF_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{
    "criteria": [
      { "riskLvlCd": "CRIT", "incrsRt": 50.00, "minCvlcptCnt": 10 },
      { "riskLvlCd": "WARN", "incrsRt": 30.00, "minCvlcptCnt": 10 },
      { "riskLvlCd": "CAUT", "incrsRt": 20.00, "minCvlcptCnt": 10 }
    ]
  }'
```

### 응답
`200 OK`, `application/json`. 본문 형식과 필드, 정렬은 `GET /api/risk-criteria` 와 같다.

```json
[
  { "thrshId": "THRSH_CRIT", "riskLvlCd": "CRIT", "incrsRt": 50.00, "minCvlcptCnt": 10, "bscMinCvlcptCnt": 10 },
  { "thrshId": "THRSH_WARN", "riskLvlCd": "WARN", "incrsRt": 30.00, "minCvlcptCnt": 10, "bscMinCvlcptCnt": 10 },
  { "thrshId": "THRSH_CAUT", "riskLvlCd": "CAUT", "incrsRt": 20.00, "minCvlcptCnt": 10, "bscMinCvlcptCnt": 10 }
]
```

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | 본문 형식 오류, 필수값 누락, 값 범위 또는 자릿수 위반 |
| 400 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | 사용할 수 없는 위험단계 코드, 단계 중복, 단계 누락 |
| 401 | 없음 | 로그인 세션이 없음 |
| 403 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | CSRF 토큰 누락 또는 불일치, `M0404` 권한 없음 |

## 3. GET /api/risk-alerts

### API 개요
위험단계 알림 이력을 최신순으로 페이징 조회하고 같은 필터 기준의 요약을 함께 돌려준다. 수신자 여부와 관계없이 전체 알림을 대상으로 한다. 로그인 세션이 필요하고 세션 사용자가 `TB_USER` 에 활성 상태로 있어야 한다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | Cookie | string | Y | - | 로그인 세션 쿠키 `JSESSIONID` |
| query | riskLvlCd | string | N | 전체 | `CRIT`, `WARN`, `CAUT`. 생략, 빈 값, `all` 은 전체 |
| query | sggNm | string | N | 전체 | 시군구명. 생략, 빈 값, `all` 은 전체. 존재 여부는 검증하지 않는다 |
| query | emdCd | string | N | 전체 | 읍면동코드. 생략, 빈 값, `all` 은 전체. 존재 여부는 검증하지 않는다 |
| query | from | date | N | - | 시작일 `yyyy-MM-dd`. 해당일 00:00:00 부터 포함 |
| query | to | date | N | - | 종료일 `yyyy-MM-dd`. 해당일 23:59:59 까지 포함. `from` 과 함께 보내면 양끝 포함 최대 366일 |
| query | page | integer | N | 0 | 0부터 시작하는 페이지 번호. 0 이상 |
| query | size | integer | N | 10 | 페이지 크기. 1 이상 100 이하 |

### 호출 예시
```bash
curl -b 'JSESSIONID=EXAMPLE_SESSION_ID' \
  'http://localhost:8180/api/risk-alerts?riskLvlCd=WARN&sggNm=%EC%A0%9C%EC%A3%BC%EC%8B%9C&from=2026-05-01&to=2026-05-31&page=0&size=10'
```

### 응답
`200 OK`, `application/json`. `content` 는 `ocrnDt` 내림차순, `notiId` 내림차순으로 정렬된다.

```json
{
  "content": [
    {
      "notiId": 4,
      "riskLvlCd": "WARN",
      "sggNm": "제주시",
      "emdCd": "50110104",
      "emdNm": "이도이동",
      "regionNm": "이도이동",
      "notiCtt": "이도이동 민원 21% 증가",
      "incrsRt": 21.0,
      "incrsRtThrsh": 20.0,
      "cvlcptCnt": 22,
      "ocrnDt": "2026-05-28T00:00:00",
      "prcsSttsCd": "OPEN",
      "prslYn": "N",
      "prslDt": null,
      "idntyYn": "N",
      "idntyDt": null
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 18,
  "totalPages": 2,
  "summary": {
    "totalCnt": 18,
    "critCnt": 4,
    "warnCnt": 8,
    "cautCnt": 6,
    "cvlcptSumCnt": 168
  }
}
```

| 필드 | 타입 | null 가능 | 설명 |
| --- | --- | --- | --- |
| `content[].notiId` | integer | N | 알림 ID |
| `content[].riskLvlCd` | string | N | 위험단계 코드. `CRIT`, `WARN`, `CAUT` |
| `content[].sggNm` | string | N | 시군구명 |
| `content[].emdCd` | string | N | 읍면동코드 |
| `content[].emdNm` | string | N | 읍면동명 |
| `content[].regionNm` | string | N | 지역 표시명. 항상 읍면동명 |
| `content[].notiCtt` | string | Y | 판정 시점에 저장된 알림 내용 |
| `content[].incrsRt` | number | Y | 전일 대비 증가율(%) |
| `content[].incrsRtThrsh` | number | Y | 판정 당시 기준 증가율(%) |
| `content[].cvlcptCnt` | integer | Y | 판정 대상 읍면동의 당일 민원 건수 |
| `content[].ocrnDt` | string | N | 발생일시. `yyyy-MM-ddTHH:mm:ss` |
| `content[].prcsSttsCd` | string | N | 처리상태 코드 |
| `content[].prslYn` | string | N | 알림 이력 테이블의 읽음여부. `Y`, `N` |
| `content[].prslDt` | string | Y | 알림 이력 테이블의 읽은일시 |
| `content[].idntyYn` | string | N | 확인여부. `Y`, `N` |
| `content[].idntyDt` | string | Y | 확인일시 |
| `page` | integer | N | 0부터 시작하는 현재 페이지 번호 |
| `size` | integer | N | 페이지 크기 |
| `totalElements` | integer | N | 필터 전체 건수 |
| `totalPages` | integer | N | 전체 페이지 수 |
| `summary.totalCnt` | integer | N | 필터 전체 알림 건수 |
| `summary.critCnt` | integer | N | 심각 건수 |
| `summary.warnCnt` | integer | N | 경고 건수 |
| `summary.cautCnt` | integer | N | 주의 건수 |
| `summary.cvlcptSumCnt` | integer | N | 선택한 기간과 지역의 민원 총건수. 민원 테이블에서 읍면동코드가 있는 행을 세며 `riskLvlCd` 필터는 반영하지 않는다 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | 지원하지 않는 `riskLvlCd`, `page` 또는 `size` 범위 위반, 날짜 또는 숫자 형식 오류 |
| 400 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | `to` 가 `from` 보다 빠름, 조회 기간이 366일 초과 |
| 401 | 없음 | 로그인 세션이 없음 |
| 401 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | 세션 사용자가 `TB_USER` 에 없거나 사용 중지됨 |

## 4. GET /api/risk-alerts/daily

### API 개요
상단 알림 벨에 표시할 요청자의 전일 알림을 개수 제한 없이 조회한다. 기준일은 서버가 `Asia/Seoul` 기준 전일로 정한다. 로그인 세션이 필요하고, 활성 수신자가 아닌 사용자는 200 응답에 0건을 받는다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | Cookie | string | Y | - | 로그인 세션 쿠키 `JSESSIONID` |

### 호출 예시
```bash
curl -b 'JSESSIONID=EXAMPLE_SESSION_ID' \
  'http://localhost:8180/api/risk-alerts/daily'
```

### 응답
`200 OK`, `application/json`. `items` 는 `ocrnDt` 내림차순, `notiId` 내림차순으로 정렬된다.

```json
{
  "baseDe": "2026-05-28",
  "totalCnt": 1,
  "unreadCnt": 1,
  "items": [
    {
      "notiId": 4,
      "riskLvlCd": "WARN",
      "sggNm": "제주시",
      "emdCd": "50110104",
      "emdNm": "이도이동",
      "regionNm": "이도이동",
      "notiCtt": "이도이동 민원 21% 증가",
      "incrsRt": 21.0,
      "incrsRtThrsh": 20.0,
      "cvlcptCnt": 22,
      "ocrnDt": "2026-05-28T00:00:00",
      "prcsSttsCd": "OPEN",
      "prslYn": "N",
      "prslDt": null,
      "idntyYn": "N",
      "idntyDt": null
    }
  ]
}
```

| 필드 | 타입 | null 가능 | 설명 |
| --- | --- | --- | --- |
| `baseDe` | string | N | 기준일. `yyyy-MM-dd` |
| `totalCnt` | integer | N | 기준일에 요청자에게 온 알림 건수 |
| `unreadCnt` | integer | N | 그중 미읽음 건수 |
| `items[].notiId` | integer | N | 알림 ID |
| `items[].riskLvlCd` | string | N | 위험단계 코드. `CRIT`, `WARN`, `CAUT` |
| `items[].sggNm` | string | N | 시군구명 |
| `items[].emdCd` | string | N | 읍면동코드 |
| `items[].emdNm` | string | N | 읍면동명 |
| `items[].regionNm` | string | N | 지역 표시명. 항상 읍면동명 |
| `items[].notiCtt` | string | Y | 판정 시점에 저장된 알림 내용 |
| `items[].incrsRt` | number | Y | 전일 대비 증가율(%) |
| `items[].incrsRtThrsh` | number | Y | 판정 당시 기준 증가율(%) |
| `items[].cvlcptCnt` | integer | Y | 판정 대상 읍면동의 당일 민원 건수 |
| `items[].ocrnDt` | string | N | 발생일시. `yyyy-MM-ddTHH:mm:ss` |
| `items[].prcsSttsCd` | string | N | 처리상태 코드 |
| `items[].prslYn` | string | N | 요청자 기준 읽음여부. 요청자의 수신 행이 모두 읽음일 때 `Y` |
| `items[].prslDt` | string | Y | 요청자 기준 읽은일시 |
| `items[].idntyYn` | string | N | 확인여부. `Y`, `N` |
| `items[].idntyDt` | string | Y | 확인일시 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 401 | 없음 | 로그인 세션이 없음 |
| 401 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | 세션 사용자가 `TB_USER` 에 없거나 사용 중지됨 |

## 5. PATCH /api/risk-alerts/{notiId}/read

### API 개요
요청자에게 온 알림 1건을 읽음으로 처리한다. 수신 매핑 테이블의 요청자 행만 갱신하며, 이미 읽은 알림을 다시 호출해도 성공하고 처음 읽은 일시는 유지된다. 로그인 세션과 CSRF 토큰이 필요하다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| path | notiId | integer | Y | - | 알림 ID |
| header | Cookie | string | Y | - | `JSESSIONID`, `XSRF-TOKEN` 쿠키 |
| header | X-XSRF-TOKEN | string | Y | - | `XSRF-TOKEN` 쿠키 값 |

### 호출 예시
```bash
curl -X PATCH 'http://localhost:8180/api/risk-alerts/4/read' \
  -b 'JSESSIONID=EXAMPLE_SESSION_ID; XSRF-TOKEN=EXAMPLE_XSRF_TOKEN' \
  -H 'X-XSRF-TOKEN: EXAMPLE_XSRF_TOKEN'
```

### 응답
`204 No Content`. 응답 본문은 없다.

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | `notiId` 가 숫자가 아님 |
| 401 | 없음 | 로그인 세션이 없음 |
| 401 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | 세션 사용자가 `TB_USER` 에 없거나 사용 중지됨 |
| 403 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | CSRF 토큰 누락 또는 불일치 |
| 404 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | 활성 수신자가 아니거나 요청자에게 온 알림이 아니거나 없는 알림임 |

## 6. GET /api/noti-receivers

### API 개요
삭제되지 않은 알림 수신자를 `rcvrId` 오름차순으로 페이징 조회한다. 로그인 세션이 필요하며 메뉴 권한은 검사하지 않는다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | Cookie | string | Y | - | 로그인 세션 쿠키 `JSESSIONID` |
| query | page | integer | N | 0 | 0부터 시작하는 페이지 번호. 0 이상 |
| query | size | integer | N | 10 | 페이지 크기. 1 이상 100 이하 |

### 호출 예시
```bash
curl -b 'JSESSIONID=EXAMPLE_SESSION_ID' \
  'http://localhost:8180/api/noti-receivers?page=0&size=10'
```

### 응답
`200 OK`, `application/json`.

```json
{
  "content": [
    {
      "rcvrId": 100,
      "deptCd": "DEPT001",
      "deptNm": "예시부서",
      "rcvrNm": "홍길동",
      "eml": "user@example.com",
      "regions": [
        { "sggNm": "제주시", "emdCd": "50110137", "emdNm": "연동", "regionNm": "제주시 연동" }
      ],
      "regionNm": "제주시 연동",
      "riskLvlCds": ["CRIT", "WARN"],
      "useYn": "Y"
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 1,
  "totalPages": 1
}
```

| 필드 | 타입 | null 가능 | 설명 |
| --- | --- | --- | --- |
| `content[].rcvrId` | integer | N | 수신자 ID |
| `content[].deptCd` | string | Y | 소속부서 코드. 사용자 행이 있으면 `TB_USER` 최신값, 없으면 등록 시점 복사본 |
| `content[].deptNm` | string | Y | 소속부서명 |
| `content[].rcvrNm` | string | Y | 수신자명. 사용자 행이 있으면 `TB_USER` 최신값, 없으면 등록 시점 복사본 |
| `content[].eml` | string | Y | 이메일. 사용자 행이 있으면 `TB_USER` 최신값, 없으면 등록 시점 복사본 |
| `content[].regions[].sggNm` | string | Y | 시군구명. 전체 지역이면 null |
| `content[].regions[].emdCd` | string | Y | 읍면동코드. 시군구 전체 또는 전체 지역이면 null |
| `content[].regions[].emdNm` | string | Y | 읍면동명 |
| `content[].regions[].regionNm` | string | N | 지역 표시명. `제주시 연동`, `제주시 전체`, `전체 지역` 형태 |
| `content[].regionNm` | string | N | 지역 표시명을 `, ` 로 연결한 값. 지역 행이 없으면 `지역 없음` |
| `content[].riskLvlCds[]` | string | N | 수신 위험단계 코드. `sort_seq` 순 |
| `content[].useYn` | string | N | 사용여부. `Y`, `N` |
| `page` | integer | N | 0부터 시작하는 현재 페이지 번호 |
| `size` | integer | N | 페이지 크기 |
| `totalElements` | integer | N | 삭제되지 않은 수신자 전체 건수 |
| `totalPages` | integer | N | 전체 페이지 수 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | `page` 또는 `size` 범위 위반, 숫자 형식 오류 |
| 401 | 없음 | 로그인 세션이 없음 |

## 7. GET /api/noti-receivers/candidates

### API 개요
알림 수신자로 등록할 수 있는 활성 사용자 후보 전체를 조회한다. 로그인 세션과 메뉴 `M0404`(위험단계 알림 관리) 권한이 필요하다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | Cookie | string | Y | - | 로그인 세션 쿠키 `JSESSIONID` |

### 호출 예시
```bash
curl -b 'JSESSIONID=EXAMPLE_SESSION_ID' \
  'http://localhost:8180/api/noti-receivers/candidates'
```

### 응답
`200 OK`, `application/json`. 래퍼 없는 최상위 배열이다. 소속이 있는 사용자가 먼저 오고 부서 정렬순서, 부서명, 사용자명, 사용자 ID 순으로 정렬된다.

```json
[
  {
    "userId": "example.user",
    "userNm": "홍길동",
    "deptCd": "DEPT001",
    "deptNm": "예시부서",
    "eml": "user@example.com",
    "registered": false
  }
]
```

| 필드 | 타입 | null 가능 | 설명 |
| --- | --- | --- | --- |
| `[].userId` | string | N | 사용자 ID. 수신자 등록 요청의 `userId` 로 쓴다 |
| `[].userNm` | string | N | 사용자명 |
| `[].deptCd` | string | Y | 소속부서 코드. 무소속이면 null |
| `[].deptNm` | string | Y | 소속부서명. 무소속이면 null |
| `[].eml` | string | Y | 이메일 |
| `[].registered` | boolean | N | 삭제되지 않은 수신자로 이미 등록됐으면 `true` |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 401 | 없음 | 로그인 세션이 없음 |
| 403 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | `M0404` 권한 없음 |

## 8. POST /api/noti-receivers

### API 개요
활성 사용자 1명을 알림 수신자로 등록하고 수신 단계와 수신 지역을 한 트랜잭션으로 저장한다. 이름, 이메일, 부서는 서버가 사용자 정보에서 복사하고 통보채널은 `EMAIL`, 사용여부는 `Y` 로 저장한다. 로그인 세션, CSRF 토큰, 메뉴 `M0404`(위험단계 알림 관리) 권한이 필요하다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | Cookie | string | Y | - | `JSESSIONID`, `XSRF-TOKEN` 쿠키 |
| header | X-XSRF-TOKEN | string | Y | - | `XSRF-TOKEN` 쿠키 값 |
| header | Content-Type | string | Y | - | `application/json` |
| body | userId | string | Y | - | 등록할 사용자 ID. 공백 불가, 20자 이내 |
| body | regions | array | Y | - | 수신 지역. 1개 이상. 같은 지역 중복은 서버가 제거한다 |
| body | regions[].sggNm | string | N | - | 시군구명. 40자 이내. `emdCd` 를 함께 보내면 무시되고 서버가 다시 채운다 |
| body | regions[].emdCd | string | N | - | 읍면동코드. 8자 이내. 비우고 `sggNm` 만 보내면 시군구 전체, 둘 다 비우면(`{}`) 전체 지역 |
| body | riskLvlCds | string[] | Y | - | 수신 위험단계 코드. 1개 이상, `CRIT`, `WARN`, `CAUT`. 중복은 서버가 제거한다 |

### 호출 예시
```bash
curl -X POST 'http://localhost:8180/api/noti-receivers' \
  -b 'JSESSIONID=EXAMPLE_SESSION_ID; XSRF-TOKEN=EXAMPLE_XSRF_TOKEN' \
  -H 'X-XSRF-TOKEN: EXAMPLE_XSRF_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": "example.user",
    "regions": [{ "emdCd": "50110137" }, { "sggNm": "서귀포시" }],
    "riskLvlCds": ["CRIT", "WARN"]
  }'
```

### 응답
`201 Created`, `application/json`. 필드는 `GET /api/noti-receivers` 의 `content[]` 원소와 같다.

```json
{
  "rcvrId": 100,
  "deptCd": "DEPT001",
  "deptNm": "예시부서",
  "rcvrNm": "홍길동",
  "eml": "user@example.com",
  "regions": [
    { "sggNm": "서귀포시", "emdCd": null, "emdNm": null, "regionNm": "서귀포시 전체" },
    { "sggNm": "제주시", "emdCd": "50110137", "emdNm": "연동", "regionNm": "제주시 연동" }
  ],
  "regionNm": "서귀포시 전체, 제주시 연동",
  "riskLvlCds": ["CRIT", "WARN"],
  "useYn": "Y"
}
```

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | 본문 형식 오류, 필수값 누락, 길이 위반, 빈 배열 |
| 400 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | 없거나 사용 중지된 사용자, 없는 위험단계 코드, 없는 읍면동코드 또는 시군구명 |
| 400 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | 전체 지역과 다른 지역을 함께 지정, 시군구 전체와 그 시군구의 읍면동을 함께 지정 |
| 401 | 없음 | 로그인 세션이 없음 |
| 403 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | CSRF 토큰 누락 또는 불일치, `M0404` 권한 없음 |
| 409 | `{"code":"RCVR_DUPLICATED"}` | 같은 사용자의 삭제되지 않은 수신자가 이미 있음 |

## 9. PUT /api/noti-receivers/{rcvrId}

### API 개요
수신자의 수신 지역과 수신 단계를 요청 내용으로 통째로 교체한다. 사람 정보와 사용여부는 바꾸지 않으며, 이미 적재된 알림 매핑은 유지되고 다음 판정부터 반영된다. 로그인 세션, CSRF 토큰, 메뉴 `M0404`(위험단계 알림 관리) 권한이 필요하다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| path | rcvrId | integer | Y | - | 수신자 ID |
| header | Cookie | string | Y | - | `JSESSIONID`, `XSRF-TOKEN` 쿠키 |
| header | X-XSRF-TOKEN | string | Y | - | `XSRF-TOKEN` 쿠키 값 |
| header | Content-Type | string | Y | - | `application/json` |
| body | regions | array | Y | - | 수신 지역. 1개 이상. 같은 지역 중복은 서버가 제거한다 |
| body | regions[].sggNm | string | N | - | 시군구명. 40자 이내. `emdCd` 를 함께 보내면 무시되고 서버가 다시 채운다 |
| body | regions[].emdCd | string | N | - | 읍면동코드. 8자 이내. 비우고 `sggNm` 만 보내면 시군구 전체, 둘 다 비우면(`{}`) 전체 지역 |
| body | riskLvlCds | string[] | Y | - | 수신 위험단계 코드. 1개 이상, `CRIT`, `WARN`, `CAUT`. 중복은 서버가 제거한다 |

### 호출 예시
```bash
curl -X PUT 'http://localhost:8180/api/noti-receivers/100' \
  -b 'JSESSIONID=EXAMPLE_SESSION_ID; XSRF-TOKEN=EXAMPLE_XSRF_TOKEN' \
  -H 'X-XSRF-TOKEN: EXAMPLE_XSRF_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{ "regions": [{}], "riskLvlCds": ["CRIT"] }'
```

### 응답
`200 OK`, `application/json`. 필드는 `GET /api/noti-receivers` 의 `content[]` 원소와 같다.

```json
{
  "rcvrId": 100,
  "deptCd": "DEPT001",
  "deptNm": "예시부서",
  "rcvrNm": "홍길동",
  "eml": "user@example.com",
  "regions": [
    { "sggNm": null, "emdCd": null, "emdNm": null, "regionNm": "전체 지역" }
  ],
  "regionNm": "전체 지역",
  "riskLvlCds": ["CRIT"],
  "useYn": "Y"
}
```

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | 본문 형식 오류, 필수값 누락, 길이 위반, 빈 배열, `rcvrId` 형식 오류 |
| 400 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | 없는 위험단계 코드, 없는 읍면동코드 또는 시군구명 |
| 400 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | 전체 지역과 다른 지역을 함께 지정, 시군구 전체와 그 시군구의 읍면동을 함께 지정 |
| 401 | 없음 | 로그인 세션이 없음 |
| 403 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | CSRF 토큰 누락 또는 불일치, `M0404` 권한 없음 |
| 404 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | 없거나 이미 삭제된 수신자 |

## 10. PATCH /api/noti-receivers/{rcvrId}/activation

### API 개요
수신자의 사용여부만 변경하고 갱신된 수신자 1건을 돌려준다. 로그인 세션, CSRF 토큰, 메뉴 `M0404`(위험단계 알림 관리) 권한이 필요하다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| path | rcvrId | integer | Y | - | 수신자 ID |
| header | Cookie | string | Y | - | `JSESSIONID`, `XSRF-TOKEN` 쿠키 |
| header | X-XSRF-TOKEN | string | Y | - | `XSRF-TOKEN` 쿠키 값 |
| header | Content-Type | string | Y | - | `application/json` |
| body | useYn | string | Y | - | 사용여부. `Y` 또는 `N` |

### 호출 예시
```bash
curl -X PATCH 'http://localhost:8180/api/noti-receivers/100/activation' \
  -b 'JSESSIONID=EXAMPLE_SESSION_ID; XSRF-TOKEN=EXAMPLE_XSRF_TOKEN' \
  -H 'X-XSRF-TOKEN: EXAMPLE_XSRF_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{ "useYn": "N" }'
```

### 응답
`200 OK`, `application/json`. 필드는 `GET /api/noti-receivers` 의 `content[]` 원소와 같다.

```json
{
  "rcvrId": 100,
  "deptCd": "DEPT001",
  "deptNm": "예시부서",
  "rcvrNm": "홍길동",
  "eml": "user@example.com",
  "regions": [
    { "sggNm": "제주시", "emdCd": "50110137", "emdNm": "연동", "regionNm": "제주시 연동" }
  ],
  "regionNm": "제주시 연동",
  "riskLvlCds": ["CRIT", "WARN"],
  "useYn": "N"
}
```

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | 본문 형식 오류, `useYn` 누락 또는 `Y`, `N` 외의 값, `rcvrId` 형식 오류 |
| 401 | 없음 | 로그인 세션이 없음 |
| 403 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | CSRF 토큰 누락 또는 불일치, `M0404` 권한 없음 |
| 404 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | 없거나 이미 삭제된 수신자 |

## 11. DELETE /api/noti-receivers/{rcvrId}

### API 개요
수신자를 소프트 삭제한다. 행을 지우지 않고 `del_yn` 을 `Y` 로 바꾸므로 목록에서만 사라지고 단계 매핑과 과거 알림 이력은 남는다. 로그인 세션, CSRF 토큰, 메뉴 `M0404`(위험단계 알림 관리) 권한이 필요하다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| path | rcvrId | integer | Y | - | 수신자 ID |
| header | Cookie | string | Y | - | `JSESSIONID`, `XSRF-TOKEN` 쿠키 |
| header | X-XSRF-TOKEN | string | Y | - | `XSRF-TOKEN` 쿠키 값 |

### 호출 예시
```bash
curl -X DELETE 'http://localhost:8180/api/noti-receivers/100' \
  -b 'JSESSIONID=EXAMPLE_SESSION_ID; XSRF-TOKEN=EXAMPLE_XSRF_TOKEN' \
  -H 'X-XSRF-TOKEN: EXAMPLE_XSRF_TOKEN'
```

### 응답
`204 No Content`. 응답 본문은 없다.

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | `rcvrId` 형식 오류 |
| 401 | 없음 | 로그인 세션이 없음 |
| 403 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | CSRF 토큰 누락 또는 불일치, `M0404` 권한 없음 |
| 404 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | 없거나 이미 삭제된 수신자 |
