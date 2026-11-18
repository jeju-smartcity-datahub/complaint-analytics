# 메뉴 권한 API

## 1. GET /api/menu-authorities/authorities

### API 개요
사용 중인 권한 그룹 목록을 등록일시 오름차순으로 조회한다.

로그인 세션이 필요하며 메뉴 권한은 검사하지 않는다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |

### 호출 예시
```bash
curl -b cookies.txt http://localhost:8180/api/menu-authorities/authorities
```

### 응답
`200 OK` (`application/json`). 권한 그룹 배열을 반환한다.

```json
[
  {
    "authrtCd": "ROLE_SYSTEM_ADMIN",
    "authrtNm": "시스템 관리자",
    "authrtExpln": "전체 시스템 설정",
    "authrtTypeCd": "SUPER"
  },
  {
    "authrtCd": "ROLE_USER",
    "authrtNm": "일반 사용자",
    "authrtExpln": "대시보드 · 민원 현황 등 조회 전용",
    "authrtTypeCd": null
  }
]
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `[].authrtCd` | String | 불가 | 권한코드 |
| `[].authrtNm` | String | 불가 | 권한명 |
| `[].authrtExpln` | String | 가능 | 권한 설명 |
| `[].authrtTypeCd` | String | 가능 | 권한유형코드. `SUPER` 는 모든 화면 메뉴에 항상 매핑되는 권한이다 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 401 | 없음 | 세션 없음 또는 만료 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | DB 오류 |

## 2. GET /api/menu-authorities/menus

### API 개요
권한을 지정할 수 있는 화면 메뉴와 각 메뉴에 허용된 권한코드를 조회한다. 사용 중이고 경로가 있는 메뉴만 반환하며 폴더 메뉴는 제외한다. 상위 메뉴 묶음, 상위 메뉴 우선, `sortSeq` 순으로 정렬한다.

로그인 세션이 필요하며 메뉴 권한은 검사하지 않는다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |

### 호출 예시
```bash
curl -b cookies.txt http://localhost:8180/api/menu-authorities/menus
```

### 응답
`200 OK` (`application/json`). 메뉴 배열을 반환한다.

```json
[
  {
    "menuId": "M0602",
    "upMenuId": "M06",
    "menuNm": "메뉴 권한 관리",
    "menuPathNm": "/settings/menu-authority",
    "menuLvl": 2,
    "sortSeq": 1,
    "menuSeNm": "관리",
    "authrtCds": ["ROLE_SYSTEM_ADMIN", "ROLE_SERVICE_ADMIN"]
  }
]
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `[].menuId` | String | 불가 | 메뉴 ID |
| `[].upMenuId` | String | 가능 | 상위 메뉴 ID. 최상위 메뉴는 `null` |
| `[].menuNm` | String | 불가 | 메뉴명 |
| `[].menuPathNm` | String | 불가 | 화면 경로 |
| `[].menuLvl` | Integer | 불가 | 메뉴 단계. `1` 대메뉴, `2` 중메뉴 |
| `[].sortSeq` | Integer | 불가 | 정렬 순서 |
| `[].menuSeNm` | String | 불가 | 구분. `ROLE_USER` 매핑이 있으면 `메뉴`, 없으면 `관리` |
| `[].authrtCds` | String[] | 불가 | 메뉴에 매핑된 권한코드. 사용 중지된 권한의 매핑도 포함하며 매핑이 없으면 빈 배열 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 401 | 없음 | 세션 없음 또는 만료 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | DB 오류 |

## 3. PUT /api/menu-authorities/menus/{menuId}/authorities

### API 개요
메뉴 1건의 허용 권한을 요청한 목록으로 전체 교체한다. 기존 매핑을 모두 삭제하고 다시 등록하며 한 트랜잭션으로 처리한다. `SUPER` 유형 권한은 요청에 없어도 자동으로 추가되므로 저장 결과는 응답으로 확인한다.

로그인 세션, CSRF 토큰, `M0602`(메뉴 권한 관리) 메뉴 권한이 필요하다. 메뉴 권한은 요청마다 `TB_AUTHRT_MENU` 를 조회해 판정하므로 변경 즉시 반영된다.

부수 효과로 `TB_AUTHRT_MENU` 의 해당 메뉴 행을 삭제 후 등록한다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| path | `menuId` | String | 필수 | 없음 | 대상 메뉴 ID. 경로가 있는 사용 중 메뉴여야 한다 |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |
| cookie | `XSRF-TOKEN` | String | 필수 | 없음 | CSRF 토큰 쿠키 |
| header | `X-XSRF-TOKEN` | String | 필수 | 없음 | `XSRF-TOKEN` 쿠키와 같은 값 |
| header | `Content-Type` | String | 필수 | 없음 | `application/json` |
| body | `authrtCds` | String[] | 필수 | 없음 | 허용할 권한코드 목록. `@NotNull`. 빈 배열을 허용하고 중복은 제거한다. 각 값은 사용 중인 권한코드여야 한다 |

### 호출 예시
```bash
TOKEN=$(awk '$6=="XSRF-TOKEN"{print $7}' cookies.txt)

curl -b cookies.txt -X PUT http://localhost:8180/api/menu-authorities/menus/M0401/authorities \
  -H "X-XSRF-TOKEN: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"authrtCds":["ROLE_USER","ROLE_SERVICE_ADMIN"]}'
```

### 응답
`200 OK` (`application/json`). 저장 후 다시 조회한 메뉴 1건을 반환한다.

```json
{
  "menuId": "M0401",
  "upMenuId": "M04",
  "menuNm": "보고서 목록",
  "menuPathNm": "/reports",
  "menuLvl": 2,
  "sortSeq": 1,
  "menuSeNm": "메뉴",
  "authrtCds": ["ROLE_SYSTEM_ADMIN", "ROLE_SERVICE_ADMIN", "ROLE_USER"]
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `menuId` | String | 불가 | 메뉴 ID |
| `upMenuId` | String | 가능 | 상위 메뉴 ID. 최상위 메뉴는 `null` |
| `menuNm` | String | 불가 | 메뉴명 |
| `menuPathNm` | String | 불가 | 화면 경로 |
| `menuLvl` | Integer | 불가 | 메뉴 단계. `1` 대메뉴, `2` 중메뉴 |
| `sortSeq` | Integer | 불가 | 정렬 순서 |
| `menuSeNm` | String | 불가 | 구분. `ROLE_USER` 매핑이 있으면 `메뉴`, 없으면 `관리` |
| `authrtCds` | String[] | 불가 | 저장된 권한코드. 권한 등록일시 오름차순 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status: 400, error: "Bad Request", path}` | `authrtCds` 누락, 본문 JSON 형식 오류, 폴더 메뉴 지정, 사용할 수 없는 권한코드 포함. 본문 형식 오류는 메뉴 권한 판정보다 먼저 응답한다 |
| 401 | 없음 | 세션 없음 또는 만료 |
| 403 | `{timestamp, status: 403, error: "Forbidden", path}` | CSRF 토큰 누락·불일치, `M0602` 메뉴 권한 없음 |
| 404 | `{timestamp, status: 404, error: "Not Found", path}` | 메뉴가 없거나 사용 중지됨 |
| 415 | `{timestamp, status: 415, error: "Unsupported Media Type", path}` | `Content-Type` 이 JSON이 아님 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | DB 오류 |
