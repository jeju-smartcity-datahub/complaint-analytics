# 인증 API

## 1. GET /api/auth/authorize-url

### API 개요
OIDC 로그인을 시작할 IdP 인가 URL을 발급한다. `state`, PKCE `code_verifier`, 발급 시각을 서버 세션에 저장하고 브라우저에는 인가 URL만 내려준다.

로그인 없이 호출할 수 있다. 세션이 없으면 새로 만들어 `JSESSIONID` 쿠키를 내려주며, 이 세션을 `POST /api/auth/callback` 까지 유지해야 한다.

### 파라미터
없음

### 호출 예시
```bash
curl -c cookies.txt http://localhost:8180/api/auth/authorize-url
```

### 응답
`200 OK` (`application/json`)

```json
{
  "authorizeUrl": "https://idp.example.com/authorize?response_type=code&client_id=example-client&redirect_uri=http://localhost:5173&scope=openid%20profile%20email&state=example-state&code_challenge=example-challenge&code_challenge_method=S256"
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `authorizeUrl` | String | 불가 | IdP 인가 URL. 쿼리에 `response_type=code`, `client_id`, `redirect_uri`, `scope`, `state`, `code_challenge`, `code_challenge_method=S256` 을 포함한다 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | `app.auth.registration-id` 에 해당하는 OIDC 클라이언트 등록을 찾을 수 없음 |

## 2. POST /api/auth/callback

### API 개요
IdP가 돌려준 인가 코드를 토큰으로 교환하고 로그인 세션을 확립한다. `id_token` 과 `/userinfo` 의 `sub` 를 대조한 뒤 사용자(`TB_USER`)를 등록·갱신하고, 세션 ID를 교체한다.

로그인 없이 호출할 수 있지만 CSRF 토큰은 필요하다. `GET /api/auth/authorize-url` 을 호출한 세션으로 10분 안에 호출해야 한다.

부수 효과로 `TB_USER` 등록·갱신, `TB_DEPT` 부서 자동 등재, `TB_LGN_HSTRY` 성공·실패 이력 기록이 일어난다. IdP의 토큰, JWKS, userinfo, 토큰 교환, 프로필 API를 호출한다. 토큰은 세션에만 보관하고 응답으로 내려주지 않는다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 인가 URL을 발급받은 세션 |
| cookie | `XSRF-TOKEN` | String | 필수 | 없음 | CSRF 토큰 쿠키 |
| header | `X-XSRF-TOKEN` | String | 필수 | 없음 | `XSRF-TOKEN` 쿠키와 같은 값 |
| header | `Content-Type` | String | 필수 | 없음 | `application/json` |
| body | `code` | String | 필수 | 없음 | IdP가 발급한 인가 코드. 공백만 있으면 400 |
| body | `state` | String | 필수 | 없음 | 인가 URL 발급 때 세션에 저장한 값과 일치해야 한다 |

### 호출 예시
```bash
TOKEN=$(awk '$6=="XSRF-TOKEN"{print $7}' cookies.txt)

curl -b cookies.txt -c cookies.txt -X POST http://localhost:8180/api/auth/callback \
  -H "X-XSRF-TOKEN: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"code":"example-code","state":"example-state"}'
```

### 응답
`200 OK` (`application/json`). 교체된 세션 ID가 `Set-Cookie: JSESSIONID` 로 내려간다.

```json
{
  "userId": "example-user",
  "userNm": "홍길동",
  "userAuthrtCd": "ROLE_USER"
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `userId` | String | 불가 | 사용자 ID (최대 20자) |
| `userNm` | String | 불가 | 사용자명 |
| `userAuthrtCd` | String | 불가 | 권한코드. 예: `ROLE_USER`, `ROLE_SERVICE_ADMIN`, `ROLE_SYSTEM_ADMIN` |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status: 400, error: "Bad Request", path}` | `code` 누락·공백, `state` 불일치, 세션에 인가 정보 없음, 인가 URL 발급 후 10분 초과, 본문 JSON 형식 오류 |
| 401 | `{timestamp, status: 401, error: "Unauthorized", path}` | 토큰 교환 실패, userinfo 응답이 비어 있음, userinfo 와 `id_token` 의 `sub` 불일치 |
| 401 | 없음 | 정지된 사용자(`use_yn='N'`) 또는 `sub` 가 없는 토큰 |
| 403 | `{timestamp, status: 403, error: "Forbidden", path}` | CSRF 토큰 누락·불일치 |
| 415 | `{timestamp, status: 415, error: "Unsupported Media Type", path}` | `Content-Type` 이 JSON이 아님 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | `id_token` 검증 실패, userinfo 호출 실패, DB 오류 |

## 3. POST /api/auth/login

### API 개요
이메일과 비밀번호로 로그인하고 세션을 확립한다. 자격 증명은 DHub2 Manager로 전달해 검증하며 서버에 저장하지 않는다.

로그인 없이 호출할 수 있지만 CSRF 토큰은 필요하다. 같은 이메일과 IP 조합으로 10분 안에 5회 실패하면 10분간 차단한다. 횟수와 시간은 `app.auth.login-attempt.*` 설정값이다.

부수 효과로 `TB_USER` 등록·갱신, `TB_DEPT` 부서 자동 등재, `TB_LGN_HSTRY` 성공·실패 이력 기록이 일어난다. Manager의 로그인, userinfo, 프로필 API를 호출한다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `XSRF-TOKEN` | String | 필수 | 없음 | CSRF 토큰 쿠키 |
| header | `X-XSRF-TOKEN` | String | 필수 | 없음 | `XSRF-TOKEN` 쿠키와 같은 값 |
| header | `Content-Type` | String | 필수 | 없음 | `application/json` |
| body | `email` | String | 필수 | 없음 | 이메일. `@NotBlank` |
| body | `password` | String | 필수 | 없음 | 비밀번호. `@NotBlank` |

### 호출 예시
```bash
curl -c cookies.txt http://localhost:8180/api/auth/authorize-url
TOKEN=$(awk '$6=="XSRF-TOKEN"{print $7}' cookies.txt)

curl -b cookies.txt -c cookies.txt -X POST http://localhost:8180/api/auth/login \
  -H "X-XSRF-TOKEN: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"example-password"}'
```

### 응답
`200 OK` (`application/json`). 로그인 세션이 `Set-Cookie: JSESSIONID` 로 내려간다.

```json
{
  "userId": "example-user",
  "userNm": "홍길동",
  "userAuthrtCd": "ROLE_USER"
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `userId` | String | 불가 | 사용자 ID (최대 20자) |
| `userNm` | String | 불가 | 사용자명 |
| `userAuthrtCd` | String | 불가 | 권한코드. 예: `ROLE_USER`, `ROLE_SERVICE_ADMIN`, `ROLE_SYSTEM_ADMIN` |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{timestamp, status: 400, error: "Bad Request", path}` | `email`·`password` 누락·공백, 본문 JSON 형식 오류 |
| 401 | `{"message":"이메일 또는 비밀번호가 올바르지 않습니다."}` | 자격 증명 오류, 없는 계정, 정지된 사용자, Manager 호출 실패, Manager 응답 검증 실패 |
| 403 | `{timestamp, status: 403, error: "Forbidden", path}` | CSRF 토큰 누락·불일치 |
| 415 | `{timestamp, status: 415, error: "Unsupported Media Type", path}` | `Content-Type` 이 JSON이 아님 |
| 429 | `{"message":"이메일 또는 비밀번호가 올바르지 않습니다."}` | 실패 횟수 초과로 차단 중. `Retry-After` 헤더는 없다 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | OIDC 등록·issuer·userinfo URI 설정 누락, DB 오류 |

## 4. GET /api/auth/me

### API 개요
로그인한 사용자의 정보, 권한, 접근 가능한 메뉴 경로를 조회한다.

로그인 세션이 필요하다. 세션이 살아 있어도 사용자가 정지되었거나 삭제되었으면 세션을 무효화하고 401을 반환한다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |

### 호출 예시
```bash
curl -b cookies.txt http://localhost:8180/api/auth/me
```

### 응답
`200 OK` (`application/json`)

```json
{
  "userId": "example-user",
  "userNm": "홍길동",
  "userEmlAddr": "user@example.com",
  "userAuthrtCd": "ROLE_USER",
  "authrtNm": "일반 사용자",
  "deptNm": "예시부서",
  "menuPathNms": ["/", "/complaints"]
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `userId` | String | 불가 | 사용자 ID |
| `userNm` | String | 불가 | 사용자명 |
| `userEmlAddr` | String | 가능 | 이메일 |
| `userAuthrtCd` | String | 불가 | 권한코드 |
| `authrtNm` | String | 불가 | 권한명 |
| `deptNm` | String | 가능 | 부서명. 부서가 없으면 `null` |
| `menuPathNms` | String[] | 불가 | 권한에 매핑된 사용 중 메뉴 경로. 경로 오름차순이며 없으면 빈 배열 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 401 | 없음 | 세션 없음 또는 만료 |
| 401 | `{timestamp, status: 401, error: "Unauthorized", path}` | 세션의 사용자가 정지되었거나 존재하지 않음 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | DB 오류 |

## 5. POST /api/auth/logout

### API 개요
로그인 세션을 종료하고 `JSESSIONID` 쿠키를 만료시킨다. 프론트엔드는 응답의 `idpLogoutUrl` 이 있을 때만 그 주소로 이동한다.

로그인 세션과 CSRF 토큰이 필요하다.

부수 효과로 `TB_LGN_HSTRY` 의 접속 종료 시각을 기록한다. OIDC 로그인 세션이고 `app.auth.revoke-on-logout=true` 이면 IdP에 토큰 폐기를 요청하며, 폐기 실패는 로그아웃 결과에 영향을 주지 않는다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `JSESSIONID` | String | 필수 | 없음 | 로그인 세션 |
| cookie | `XSRF-TOKEN` | String | 필수 | 없음 | CSRF 토큰 쿠키 |
| header | `X-XSRF-TOKEN` | String | 필수 | 없음 | `XSRF-TOKEN` 쿠키와 같은 값 |

### 호출 예시
```bash
TOKEN=$(awk '$6=="XSRF-TOKEN"{print $7}' cookies.txt)

curl -b cookies.txt -c cookies.txt -X POST http://localhost:8180/api/auth/logout \
  -H "X-XSRF-TOKEN: $TOKEN"
```

### 응답
`200 OK` (`application/json`). `Set-Cookie: JSESSIONID=; Max-Age=0` 으로 세션 쿠키를 만료시킨다.

```json
{
  "idpLogoutUrl": null
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `idpLogoutUrl` | String | 가능 | IdP 로그아웃 URL. `app.auth.idp-logout-enabled=true` 이고 OIDC 로그인 세션이며 로그아웃 엔드포인트가 설정된 경우에만 값이 있다. 기본 설정에서는 `null` |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 401 | 없음 | 세션 없음 또는 만료 |
| 403 | `{timestamp, status: 403, error: "Forbidden", path}` | CSRF 토큰 누락·불일치. 세션 검사보다 먼저 판정한다 |

## 6. POST /api/auth/mock-login

### API 개요
IdP 없이 고정된 mock 사용자(`mock-user`)로 로그인 세션을 발급한다. 개발·테스트 전용이다.

`app.auth.mode=mock` 일 때만 활성화된다. `prod` 프로파일에서 이 모드를 켜면 애플리케이션이 기동하지 않는다. 로그인 없이 호출할 수 있지만 CSRF 토큰은 필요하다.

부수 효과로 `TB_USER` 등록·갱신과 `TB_LGN_HSTRY` 성공·실패 이력 기록이 일어난다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | `XSRF-TOKEN` | String | 필수 | 없음 | CSRF 토큰 쿠키 |
| header | `X-XSRF-TOKEN` | String | 필수 | 없음 | `XSRF-TOKEN` 쿠키와 같은 값 |

### 호출 예시
```bash
curl -c cookies.txt http://localhost:8180/api/auth/authorize-url
TOKEN=$(awk '$6=="XSRF-TOKEN"{print $7}' cookies.txt)

curl -b cookies.txt -c cookies.txt -X POST http://localhost:8180/api/auth/mock-login \
  -H "X-XSRF-TOKEN: $TOKEN"
```

### 응답
`200 OK` (`application/json`). 로그인 세션이 `Set-Cookie: JSESSIONID` 로 내려간다.

```json
{
  "userId": "mock-user",
  "userNm": "mock 사용자",
  "userAuthrtCd": "ROLE_USER"
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `userId` | String | 불가 | 사용자 ID. 항상 `mock-user` |
| `userNm` | String | 불가 | 사용자명. 항상 `mock 사용자` |
| `userAuthrtCd` | String | 불가 | 권한코드. 신규 사용자는 `ROLE_USER`, 기존 사용자는 저장된 권한 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 401 | 없음 | `mock-user` 가 정지된 사용자(`use_yn='N'`) |
| 403 | `{timestamp, status: 403, error: "Forbidden", path}` | CSRF 토큰 누락·불일치 |
| 404 | `{timestamp, status: 404, error: "Not Found", path}` | `app.auth.mode` 가 `mock` 이 아님 |
| 500 | `{timestamp, status: 500, error: "Internal Server Error", path}` | DB 오류 |
