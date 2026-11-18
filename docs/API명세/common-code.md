# 공통코드 API

## 1. GET /api/common-codes

### API 개요
사용 중인 공통코드를 조회한다. `groupCd` 를 지정하면 해당 그룹만 `sortSeq` 순으로, 지정하지 않으면 전체를 그룹코드와 `sortSeq` 순으로 반환한다.

로그인 세션이 필요하며 메뉴 권한은 검사하지 않는다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| query | `groupCd` | String | 선택 | 없음 | 그룹코드. 예: `RISK_LVL_CD`, `PKLT_TYPE_CD`, `ANLS_TYPE_CD`. 비어 있거나 공백이면 전체 조회 |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |

### 호출 예시
```bash
# 전체 조회
curl -b cookies.txt http://localhost:8180/api/common-codes

# 그룹 조회
curl -b cookies.txt "http://localhost:8180/api/common-codes?groupCd=PKLT_TYPE_CD"
```

### 응답
`200 OK` (`application/json`). 공통코드 배열을 반환하며, 일치하는 코드가 없으면 빈 배열을 반환한다.

```json
[
  {
    "groupCd": "PKLT_TYPE_CD",
    "groupNm": "주차장구분코드",
    "cdVl": "ONSTR",
    "cdNm": "노상주차장",
    "sortSeq": 1,
    "useYn": "Y",
    "addlAtrb1": null
  }
]
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `[].groupCd` | String | 불가 | 그룹코드 |
| `[].groupNm` | String | 불가 | 그룹명 |
| `[].cdVl` | String | 불가 | 코드값 |
| `[].cdNm` | String | 불가 | 코드명 |
| `[].sortSeq` | Integer | 가능 | 정렬 순서 |
| `[].useYn` | String | 불가 | 사용 여부. 항상 `Y` |
| `[].addlAtrb1` | String | 가능 | 부가 속성. 그룹마다 의미가 다르다. `ANLS_TYPE_CD` 그룹에서는 용도 구분(`ANLS` 분석 화면 전용, `RPTP` 보고서 전용, `BOTH` 양쪽) |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 401 | 없음 | 세션 없음 또는 만료 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | DB 오류 |

## 2. GET /api/common-codes/{groupCd}

### API 개요
특정 그룹의 사용 중인 공통코드를 `sortSeq` 순으로 조회한다.

로그인 세션이 필요하며 메뉴 권한은 검사하지 않는다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| path | `groupCd` | String | 필수 | 없음 | 그룹코드. 대소문자를 구분한다 |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |

### 호출 예시
```bash
curl -b cookies.txt http://localhost:8180/api/common-codes/RISK_LVL_CD
```

### 응답
`200 OK` (`application/json`). 공통코드 배열을 반환하며, 없는 그룹이면 빈 배열을 반환한다.

```json
[
  {
    "groupCd": "ANLS_TYPE_CD",
    "groupNm": "실행유형코드",
    "cdVl": "HOTSPOT",
    "cdNm": "불법 주차 집중 구역 분석",
    "sortSeq": 1,
    "useYn": "Y",
    "addlAtrb1": "BOTH"
  }
]
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `[].groupCd` | String | 불가 | 그룹코드 |
| `[].groupNm` | String | 불가 | 그룹명 |
| `[].cdVl` | String | 불가 | 코드값 |
| `[].cdNm` | String | 불가 | 코드명 |
| `[].sortSeq` | Integer | 가능 | 정렬 순서 |
| `[].useYn` | String | 불가 | 사용 여부. 항상 `Y` |
| `[].addlAtrb1` | String | 가능 | 부가 속성. 그룹마다 의미가 다르다. `ANLS_TYPE_CD` 그룹에서는 용도 구분(`ANLS` 분석 화면 전용, `RPTP` 보고서 전용, `BOTH` 양쪽) |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 401 | 없음 | 세션 없음 또는 만료 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | DB 오류 |
