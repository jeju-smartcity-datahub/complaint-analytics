# AI 어시스턴트 API

## 1. GET /api/ai/sessions

### API 개요
로그인 사용자의 AI 대화 세션을 최신순으로 조회한다. 백엔드가 세션에 보관한 Agent 토큰으로 외부 Agent Service 를 호출하며 우리 DB 에는 저장하지 않는다. 로그인 세션이 필요하고, 설정 `app.agent.base-url`, `app.agent.agent-id` 가 비어 있으면 503 을 돌려준다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | Cookie | string | Y | - | 로그인 세션 쿠키 `JSESSIONID` |
| query | limit | integer | N | 20 (`app.agent.default-limit`) | 조회 건수. 1 이상 100 이하 |
| query | cursor | string | N | - | 이전 응답의 `nextCursor`. URL 인코딩해서 보낸다 |

### 호출 예시
```bash
curl -b 'JSESSIONID=EXAMPLE_SESSION_ID' \
  'http://localhost:8180/api/ai/sessions?limit=20'
```

### 응답
`200 OK`, `application/json`. 정렬과 페이지 구성은 Agent Service 응답 순서를 그대로 따른다.

```json
{
  "sessions": [
    {
      "sessionId": "portal-00000000-0000-4000-8000-000000000001",
      "agentId": "agent.00000000-0000-4000-8000-000000000000",
      "agentName": "예시 어시스턴트",
      "title": "연동 주차 민원 현황",
      "preview": "연동 지역 민원이 가장 많은 시간대는...",
      "createdAt": "2026-09-03T10:12:33.120Z",
      "updatedAt": "2026-09-03T10:31:02.004Z"
    }
  ],
  "nextCursor": "ZXhhbXBsZS1jdXJzb3I="
}
```

| 필드 | 타입 | null 가능 | 설명 |
| --- | --- | --- | --- |
| `sessions[].sessionId` | string | Y | 세션 ID. 상세, 삭제, 이어쓰기의 키 |
| `sessions[].agentId` | string | Y | Agent ID |
| `sessions[].agentName` | string | Y | Agent 이름 |
| `sessions[].title` | string | Y | 세션 제목 |
| `sessions[].preview` | string | Y | 미리보기 문구 |
| `sessions[].createdAt` | string | Y | 생성 일시. Agent Service 문자열 그대로 |
| `sessions[].updatedAt` | string | Y | 수정 일시. Agent Service 문자열 그대로 |
| `nextCursor` | string | Y | 다음 페이지 커서. 없으면 null |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"code":"AGENT_BAD_REQUEST"}` | `limit` 이 1~100 범위를 벗어남 |
| 400 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | `limit` 이 숫자가 아님 |
| 401 | 없음 | 로그인 세션이 없음 |
| 401 | `{"code":"AGENT_UNAUTHORIZED"}` | Agent Service 가 토큰 갱신 후 재시도에도 401 을 반환 |
| 403, 404 | `{"code":"AGENT_UPSTREAM_REJECTED"}` | Agent Service 가 403 또는 404 를 반환. 상태 코드를 그대로 전달 |
| 502 | `{"code":"AGENT_UPSTREAM_ERROR"}` | Agent Service 5xx, 그 밖의 4xx, 연결 실패, 타임아웃(연결 2초, 읽기 5초), 응답 파싱 실패 |
| 503 | `{"code":"AGENT_NOT_CONFIGURED"}` | `app.agent.base-url` 또는 `app.agent.agent-id` 미설정 |
| 503 | `{"code":"AGENT_TOKEN_UNAVAILABLE"}` | 세션에 Agent 토큰이 없거나 토큰 갱신 실패 |

## 2. GET /api/ai/sessions/{sessionId}

### API 개요
세션 1건의 메시지와 컨텍스트 토큰 사용량을 조회한다. `user`, `assistant` 메시지만 내려가고 모델 추론 블록과 내용이 빈 메시지는 제거된다. 로그인 세션이 필요하고, 설정 `app.agent.base-url`, `app.agent.agent-id` 가 비어 있으면 503 을 돌려준다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| path | sessionId | string | Y | - | 세션 ID. 512자 이내, `/`, `%` 포함 불가 |
| header | Cookie | string | Y | - | 로그인 세션 쿠키 `JSESSIONID` |

### 호출 예시
```bash
curl -b 'JSESSIONID=EXAMPLE_SESSION_ID' \
  'http://localhost:8180/api/ai/sessions/portal-00000000-0000-4000-8000-000000000001'
```

### 응답
`200 OK`, `application/json`. `messages` 는 Agent Service 응답 순서를 그대로 따른다.

```json
{
  "sessionId": "portal-00000000-0000-4000-8000-000000000001",
  "messages": [
    {
      "role": "user",
      "content": "연동 지역 주차 민원 보고서를 만들어 줘",
      "createdAt": "2026-09-03T10:12:33.120Z",
      "msgId": null,
      "reports": []
    },
    {
      "role": "assistant",
      "content": "보고서를 작성했습니다.\n\n[[AI_REPORT:0]]",
      "createdAt": "2026-09-03T10:12:40.500Z",
      "msgId": "chatcmpl-example0001",
      "reports": [
        {
          "idx": 0,
          "title": "연동 주차 민원 분석 보고서",
          "html": "<h1>연동 주차 민원 분석 보고서</h1><p>예시 본문</p>",
          "stts": "DONE"
        }
      ]
    }
  ],
  "context": {
    "usedTokens": 1820,
    "systemTokens": 640,
    "historyTokens": 1180,
    "budgetTokens": 8000,
    "limitTokens": 16000
  }
}
```

| 필드 | 타입 | null 가능 | 설명 |
| --- | --- | --- | --- |
| `sessionId` | string | Y | 세션 ID |
| `messages[].role` | string | N | `user` 또는 `assistant` |
| `messages[].content` | string | N | 마크다운 본문. 보고서 영역은 `[[AI_REPORT:{idx}]]` 자리표시로 바뀐다 |
| `messages[].createdAt` | string | Y | 작성 일시. Agent Service 문자열 그대로 |
| `messages[].msgId` | string | Y | assistant 메시지 식별자. `user` 는 null |
| `messages[].reports[].idx` | integer | N | 보고서 영역 순번. 0부터 시작하며 자리표시의 `idx` 와 같다 |
| `messages[].reports[].title` | string | Y | 보고서 제목. 256자 이내. `PENDING` 이면 null |
| `messages[].reports[].html` | string | Y | 새니타이즈된 보고서 HTML. `PENDING` 이면 null |
| `messages[].reports[].stts` | string | N | `DONE`(정상), `PENDING`(끝 표시 없는 미완성 영역) |
| `context` | object | Y | 컨텍스트 토큰 사용량 |
| `context.usedTokens` | integer | Y | 사용 토큰 수 |
| `context.systemTokens` | integer | Y | 시스템 프롬프트 토큰 수 |
| `context.historyTokens` | integer | Y | 대화 이력 토큰 수 |
| `context.budgetTokens` | integer | Y | 예산 토큰 수 |
| `context.limitTokens` | integer | Y | 상한 토큰 수 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"code":"AGENT_BAD_REQUEST"}` | `sessionId` 가 512자를 넘거나 `/`, `%` 를 포함 |
| 401 | 없음 | 로그인 세션이 없음 |
| 401 | `{"code":"AGENT_UNAUTHORIZED"}` | Agent Service 가 토큰 갱신 후 재시도에도 401 을 반환 |
| 403, 404 | `{"code":"AGENT_UPSTREAM_REJECTED"}` | Agent Service 가 403 또는 404 를 반환. 상태 코드를 그대로 전달 |
| 502 | `{"code":"AGENT_UPSTREAM_ERROR"}` | Agent Service 5xx, 그 밖의 4xx, 연결 실패, 타임아웃(연결 2초, 읽기 30초), 응답 파싱 실패 |
| 503 | `{"code":"AGENT_NOT_CONFIGURED"}` | `app.agent.base-url` 또는 `app.agent.agent-id` 미설정 |
| 503 | `{"code":"AGENT_TOKEN_UNAVAILABLE"}` | 세션에 Agent 토큰이 없거나 토큰 갱신 실패 |

## 3. DELETE /api/ai/sessions/{sessionId}

### API 개요
세션 1건을 Agent Service 에서 삭제하며 되돌릴 수 없다. 로그인 세션과 CSRF 토큰이 필요하고, 설정 `app.agent.base-url`, `app.agent.agent-id` 가 비어 있으면 503 을 돌려준다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| path | sessionId | string | Y | - | 세션 ID. 512자 이내, `/`, `%` 포함 불가 |
| header | Cookie | string | Y | - | `JSESSIONID`, `XSRF-TOKEN` 쿠키 |
| header | X-XSRF-TOKEN | string | Y | - | `XSRF-TOKEN` 쿠키 값 |

### 호출 예시
```bash
curl -X DELETE 'http://localhost:8180/api/ai/sessions/portal-00000000-0000-4000-8000-000000000001' \
  -b 'JSESSIONID=EXAMPLE_SESSION_ID; XSRF-TOKEN=EXAMPLE_XSRF_TOKEN' \
  -H 'X-XSRF-TOKEN: EXAMPLE_XSRF_TOKEN'
```

### 응답
`204 No Content`. 응답 본문은 없다.

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"code":"AGENT_BAD_REQUEST"}` | `sessionId` 가 512자를 넘거나 `/`, `%` 를 포함 |
| 401 | 없음 | 로그인 세션이 없음 |
| 401 | `{"code":"AGENT_UNAUTHORIZED"}` | Agent Service 가 토큰 갱신 후 재시도에도 401 을 반환 |
| 403 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | CSRF 토큰 누락 또는 불일치 |
| 403, 404 | `{"code":"AGENT_UPSTREAM_REJECTED"}` | Agent Service 가 403 또는 404 를 반환. 상태 코드를 그대로 전달 |
| 502 | `{"code":"AGENT_UPSTREAM_ERROR"}` | Agent Service 5xx, 그 밖의 4xx, 연결 실패, 타임아웃(연결 2초, 읽기 5초) |
| 503 | `{"code":"AGENT_NOT_CONFIGURED"}` | `app.agent.base-url` 또는 `app.agent.agent-id` 미설정 |
| 503 | `{"code":"AGENT_TOKEN_UNAVAILABLE"}` | 세션에 Agent 토큰이 없거나 토큰 갱신 실패 |

## 4. POST /api/ai/messages

### API 개요
새 대화를 시작하며 질문을 보내고 답변을 받는다. 세션 ID 는 백엔드가 `portal-{UUID}` 로 만들어 응답에 담는다. 답변 없이 승인 대기 도구 호출만 오면 백엔드가 자동으로 1회 거절한 뒤 그 답변을 돌려준다. 로그인 세션과 CSRF 토큰이 필요하고, 설정 `app.agent.base-url`, `app.agent.agent-id` 가 비어 있으면 503 을 돌려준다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | Cookie | string | Y | - | `JSESSIONID`, `XSRF-TOKEN` 쿠키 |
| header | X-XSRF-TOKEN | string | Y | - | `XSRF-TOKEN` 쿠키 값 |
| header | Content-Type | string | Y | - | `application/json` |
| body | message | string | Y | - | 사용자 질문. 공백만으로 구성 불가, 4000자 이내 |

### 호출 예시
```bash
curl -X POST 'http://localhost:8180/api/ai/messages' \
  -b 'JSESSIONID=EXAMPLE_SESSION_ID; XSRF-TOKEN=EXAMPLE_XSRF_TOKEN' \
  -H 'X-XSRF-TOKEN: EXAMPLE_XSRF_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{ "message": "연동 지역 주차 민원이 가장 많은 시간대는?" }'
```

### 응답
`200 OK`, `application/json`. 스트리밍을 제공하지 않으며 SSE 이벤트 없이 완성된 답변을 단일 JSON 으로 돌려준다.

```json
{
  "sessionId": "portal-00000000-0000-4000-8000-000000000001",
  "message": {
    "role": "assistant",
    "content": "**연동** 지역 민원은 오전 시간대에 가장 많습니다.",
    "createdAt": null,
    "msgId": "chatcmpl-example0001",
    "reports": []
  }
}
```

| 필드 | 타입 | null 가능 | 설명 |
| --- | --- | --- | --- |
| `sessionId` | string | N | 이 전송에 사용된 세션 ID |
| `message.role` | string | N | 항상 `assistant` |
| `message.content` | string | N | 마크다운 본문. 보고서 영역은 `[[AI_REPORT:{idx}]]` 자리표시로 바뀐다 |
| `message.createdAt` | string | Y | 전송 응답에서는 항상 null |
| `message.msgId` | string | Y | 메시지 식별자. Agent Service 응답의 `id` |
| `message.reports[].idx` | integer | N | 보고서 영역 순번. 0부터 시작하며 자리표시의 `idx` 와 같다 |
| `message.reports[].title` | string | Y | 보고서 제목. 256자 이내. `PENDING`, `TRUNCATED` 이면 null |
| `message.reports[].html` | string | Y | 새니타이즈된 보고서 HTML. `PENDING`, `TRUNCATED` 이면 null |
| `message.reports[].stts` | string | N | `DONE`(정상), `PENDING`(끝 표시 없는 미완성 영역), `TRUNCATED`(길이 상한으로 잘림) |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | 본문 형식 오류, `message` 누락 또는 공백, 4000자 초과 |
| 400 | `{"code":"AGENT_BAD_REQUEST"}` | Agent Service 가 요청을 SSE 오류 프레임으로 거절 |
| 401 | 없음 | 로그인 세션이 없음 |
| 401 | `{"code":"AGENT_UNAUTHORIZED"}` | Agent Service 가 토큰 갱신 후 재시도에도 401 을 반환 |
| 403 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | CSRF 토큰 누락 또는 불일치 |
| 403, 404 | `{"code":"AGENT_UPSTREAM_REJECTED"}` | Agent Service 가 403 또는 404 를 반환. 상태 코드를 그대로 전달 |
| 409 | `{"code":"AGENT_TOOL_CALL_UNRESOLVED"}` | 자동 거절 뒤에도 답변 없이 도구 호출만 다시 옴 |
| 502 | `{"code":"AGENT_NO_ANSWER"}` | `finish_reason` 이 `error` 이거나 필터 적용 후 답변 본문이 비어 있음 |
| 502 | `{"code":"AGENT_UPSTREAM_ERROR"}` | Agent Service 5xx, 그 밖의 4xx, 연결 실패, 타임아웃(연결 2초, 읽기 120초), 응답 파싱 실패 |
| 503 | `{"code":"AGENT_NOT_CONFIGURED"}` | `app.agent.base-url` 또는 `app.agent.agent-id` 미설정 |
| 503 | `{"code":"AGENT_TOKEN_UNAVAILABLE"}` | 세션에 Agent 토큰이 없거나 토큰 갱신 실패 |

## 5. POST /api/ai/sessions/{sessionId}/messages

### API 개요
기존 세션에 이어서 질문을 보내고 답변을 받는다. 세션 소유 여부는 Agent Service 가 판단한다. 답변 없이 승인 대기 도구 호출만 오면 백엔드가 자동으로 1회 거절한 뒤 그 답변을 돌려준다. 로그인 세션과 CSRF 토큰이 필요하고, 설정 `app.agent.base-url`, `app.agent.agent-id` 가 비어 있으면 503 을 돌려준다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| path | sessionId | string | Y | - | 이어 쓸 세션 ID. 512자 이내, `/`, `%` 포함 불가 |
| header | Cookie | string | Y | - | `JSESSIONID`, `XSRF-TOKEN` 쿠키 |
| header | X-XSRF-TOKEN | string | Y | - | `XSRF-TOKEN` 쿠키 값 |
| header | Content-Type | string | Y | - | `application/json` |
| body | message | string | Y | - | 사용자 질문. 공백만으로 구성 불가, 4000자 이내 |

### 호출 예시
```bash
curl -X POST 'http://localhost:8180/api/ai/sessions/portal-00000000-0000-4000-8000-000000000001/messages' \
  -b 'JSESSIONID=EXAMPLE_SESSION_ID; XSRF-TOKEN=EXAMPLE_XSRF_TOKEN' \
  -H 'X-XSRF-TOKEN: EXAMPLE_XSRF_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{ "message": "그 시간대의 민원 유형도 알려 줘" }'
```

### 응답
`200 OK`, `application/json`. 스트리밍을 제공하지 않으며 SSE 이벤트 없이 완성된 답변을 단일 JSON 으로 돌려준다. 필드는 `POST /api/ai/messages` 응답과 같고 `sessionId` 는 경로의 값이다.

```json
{
  "sessionId": "portal-00000000-0000-4000-8000-000000000001",
  "message": {
    "role": "assistant",
    "content": "해당 시간대에는 불법 주정차 민원이 가장 많습니다.",
    "createdAt": null,
    "msgId": "chatcmpl-example0002",
    "reports": []
  }
}
```

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | 본문 형식 오류, `message` 누락 또는 공백, 4000자 초과 |
| 400 | `{"code":"AGENT_BAD_REQUEST"}` | `sessionId` 형식 위반, Agent Service 가 요청을 SSE 오류 프레임으로 거절 |
| 401 | 없음 | 로그인 세션이 없음 |
| 401 | `{"code":"AGENT_UNAUTHORIZED"}` | Agent Service 가 토큰 갱신 후 재시도에도 401 을 반환 |
| 403 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | CSRF 토큰 누락 또는 불일치 |
| 403, 404 | `{"code":"AGENT_UPSTREAM_REJECTED"}` | 다른 사용자의 세션이거나 없는 세션 등으로 Agent Service 가 거절. 상태 코드를 그대로 전달 |
| 409 | `{"code":"AGENT_TOOL_CALL_UNRESOLVED"}` | 자동 거절 뒤에도 답변 없이 도구 호출만 다시 옴 |
| 502 | `{"code":"AGENT_NO_ANSWER"}` | `finish_reason` 이 `error` 이거나 필터 적용 후 답변 본문이 비어 있음 |
| 502 | `{"code":"AGENT_UPSTREAM_ERROR"}` | Agent Service 5xx, 그 밖의 4xx, 연결 실패, 타임아웃(연결 2초, 읽기 120초), 응답 파싱 실패 |
| 503 | `{"code":"AGENT_NOT_CONFIGURED"}` | `app.agent.base-url` 또는 `app.agent.agent-id` 미설정 |
| 503 | `{"code":"AGENT_TOKEN_UNAVAILABLE"}` | 세션에 Agent 토큰이 없거나 토큰 갱신 실패 |

## 6. GET /api/ai/surveys/status

### API 개요
로그인 사용자에게 지금 만족도 설문을 띄워도 되는지 조회한다. 마지막 제출이 공통코드 `AI_SRVY_SBMSN_PRD_CD` 의 일수(기본 14일) 안이면 대상이 아니다. 로그인 세션이 필요하고 세션 사용자가 `TB_USER` 에 활성 상태로 있어야 한다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | Cookie | string | Y | - | 로그인 세션 쿠키 `JSESSIONID` |

### 호출 예시
```bash
curl -b 'JSESSIONID=EXAMPLE_SESSION_ID' \
  'http://localhost:8180/api/ai/surveys/status'
```

### 응답
`200 OK`, `application/json`.

```json
{
  "eligible": true,
  "lastSbmsnDt": null,
  "postponeDays": 7
}
```

| 필드 | 타입 | null 가능 | 설명 |
| --- | --- | --- | --- |
| `eligible` | boolean | N | 설문 대상이면 `true` |
| `lastSbmsnDt` | string | Y | 마지막 제출 일시. `yyyy-MM-ddTHH:mm:ss` 형식이며 소수 초가 붙을 수 있다. 제출 이력이 없으면 null |
| `postponeDays` | integer | N | `나중에` 선택 시 다시 묻지 않는 일수. 공통코드 `AI_SRVY_PSTP_PRD_CD`, 기본 7일 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 401 | 없음 | 로그인 세션이 없음 |
| 401 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | 세션 사용자가 `TB_USER` 에 없거나 사용 중지됨 |

## 7. POST /api/ai/surveys

### API 개요
AI 어시스턴트 만족도 점수와 선택 의견을 `TB_AI_DGSTFN_SRVY` 에 저장한다. 사용자 ID 는 로그인 세션에서 얻는다. 로그인 세션과 CSRF 토큰이 필요하고 세션 사용자가 `TB_USER` 에 활성 상태로 있어야 한다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | Cookie | string | Y | - | `JSESSIONID`, `XSRF-TOKEN` 쿠키 |
| header | X-XSRF-TOKEN | string | Y | - | `XSRF-TOKEN` 쿠키 값 |
| header | Content-Type | string | Y | - | `application/json` |
| body | dgstfnScr | integer | Y | - | 만족도 점수. 1 이상 10 이하 |
| body | opnnCn | string | N | - | 자유 의견. 1000자 이내. 앞뒤 공백을 제거하고 빈 값이면 null 로 저장 |
| body | sesnId | string | N | - | 응답 당시 대화 세션 ID. 512자 이내. 앞뒤 공백을 제거하고 빈 값이면 null 로 저장하며 존재 여부는 확인하지 않는다 |

### 호출 예시
```bash
curl -X POST 'http://localhost:8180/api/ai/surveys' \
  -b 'JSESSIONID=EXAMPLE_SESSION_ID; XSRF-TOKEN=EXAMPLE_XSRF_TOKEN' \
  -H 'X-XSRF-TOKEN: EXAMPLE_XSRF_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{
    "dgstfnScr": 8,
    "opnnCn": "답변이 빠르고 정확합니다.",
    "sesnId": "portal-00000000-0000-4000-8000-000000000001"
  }'
```

### 응답
`201 Created`, `application/json`.

```json
{
  "srvySn": 1
}
```

| 필드 | 타입 | null 가능 | 설명 |
| --- | --- | --- | --- |
| `srvySn` | integer | N | 저장된 설문 일련번호 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | 본문 형식 오류, `dgstfnScr` 누락 또는 범위 위반, `opnnCn`, `sesnId` 길이 초과 |
| 401 | 없음 | 로그인 세션이 없음 |
| 401 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | 세션 사용자가 `TB_USER` 에 없거나 사용 중지됨 |
| 403 | 기본 오류 JSON(`timestamp`, `status`, `error`, `path`) | CSRF 토큰 누락 또는 불일치 |
| 409 | `{"code":"SURVEY_ALREADY_SUBMITTED"}` | 제출 기간(공통코드 `AI_SRVY_SBMSN_PRD_CD`, 기본 14일) 안에 다시 제출 |
