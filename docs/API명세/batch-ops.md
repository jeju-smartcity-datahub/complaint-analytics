# 배치 운영 API

## 1. GET /api/ops/batch/jobs

### API 개요
잡별 마지막 실행, 마지막 성공 시각, 스케줄을 조회한다. 실행 정보는 백엔드가 `batch` 스키마에서 직접 읽고, 잡 설명·파라미터 명세·스케줄 등록 상태는 배치 앱 내부 API `GET /api/batch/ops/jobs`(9번)의 카탈로그를 합친다.

로그인 세션과 `M0604`(배치 운영) 메뉴 권한이 필요하다. 배치 앱에 연결할 수 없어도 200으로 응답하며, 이때 `catalogAvailable=false` 이고 DB에 실행 기록이 있는 잡만 내려간다. 카탈로그는 성공·실패 모두 기본 5초 캐시하고, 명령 API가 성공하면 캐시를 비운다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | 세션 쿠키 | string | Y | - | 로그인 세션 |

### 호출 예시
```bash
curl 'http://localhost:8180/api/ops/batch/jobs' -b cookies.txt
```

### 응답
`200 OK`, `application/json`. 카탈로그를 받으면 카탈로그 순서로 10개 잡을 내려주고, 받지 못하면 잡 이름 오름차순이다.

```json
{
  "catalogAvailable": true,
  "jobs": [
    {
      "jobNm": "complaintFetchJob",
      "jobKorNm": "민원 수집",
      "description": "D-Hub 민원 원천을 수집해 분류·지오코딩 후 parking.tb_cvlcpt 에 적재한다",
      "countLabel": "적재 민원",
      "schedulable": true,
      "chainNext": "riskAlertJudgeJob",
      "chainCondition": "스케줄 실행이 COMPLETED 로 끝났을 때만 이어서 실행(중지·실패·운영 화면 수동 실행에는 연쇄가 없다) · risknoti.sync.scheduled=true 일 때",
      "runningJobExecutionIds": [],
      "params": [
        {
          "name": "mode",
          "type": "ENUM",
          "required": false,
          "defaultDesc": "증분: 최대 원천 load_dt부터 오늘까지",
          "options": ["incremental", "daily", "backfill"],
          "description": "수집 방식"
        }
      ],
      "steps": [{ "stepNm": "complaintFetchStep", "stepKorNm": "수집·지오코딩·적재" }],
      "lastExcn": {
        "jobExecutionId": 4521,
        "jobNm": "complaintFetchJob",
        "status": "COMPLETED",
        "exitCode": "COMPLETED",
        "exitMessage": "",
        "createTime": "2026-11-18T02:00:00",
        "startTime": "2026-11-18T02:00:00",
        "endTime": "2026-11-18T02:05:00",
        "lastUpdated": "2026-11-18T02:05:00",
        "durationMs": 300000,
        "hstryYn": "Y",
        "readCnt": 1200,
        "writeCnt": 1180,
        "excnSeCd": "SCHDL",
        "excutrId": null,
        "upExcnId": null,
        "runMode": "incremental",
        "targetRange": "2026-11-17 ~ 2026-11-18",
        "failureMessage": null,
        "subRslts": []
      },
      "lastSuccessDt": "2026-11-18T02:05:00",
      "lastSuccessExcnId": 4521,
      "schedule": {
        "cronExpr": "0 0 2 * * *",
        "useYn": "Y",
        "mdfrId": null,
        "mdfcnDt": null,
        "registered": true,
        "blockedByEnv": false,
        "envKey": "complaint.sync.scheduled",
        "nextRuns": ["2026-11-19T02:00:00+09:00"]
      }
    }
  ]
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `catalogAvailable` | boolean | N | 배치 카탈로그 병합 여부 |
| `jobs[].jobNm` | string | N | Spring Batch Job 이름 |
| `jobs[].jobKorNm` | string | Y | 한글명. 카탈로그를 못 받으면 null |
| `jobs[].description` | string | Y | 설명. 카탈로그를 못 받으면 null |
| `jobs[].countLabel` | string | Y | `writeCnt` 의 화면 문구. 카탈로그를 못 받으면 null |
| `jobs[].schedulable` | boolean | Y | 스케줄 대상 여부. 카탈로그를 못 받으면 null |
| `jobs[].chainNext` | string | Y | 스케줄 실행 성공 후 이어서 도는 잡 |
| `jobs[].chainCondition` | string | Y | 연쇄 조건 문구 |
| `jobs[].runningJobExecutionIds[]` | number | Y | 배치 프로세스에서 실행 중인 실행 ID. 카탈로그를 못 받으면 배열 자체가 null |
| `jobs[].params[].name` | string | N | 실행 요청 `params` 의 키 |
| `jobs[].params[].type` | string | N | `DATE`(yyyy-MM-dd), `ENUM` |
| `jobs[].params[].required` | boolean | N | 필수 여부. 현재 전부 false |
| `jobs[].params[].defaultDesc` | string | Y | 값을 비웠을 때의 동작 |
| `jobs[].params[].options[]` | string | N | ENUM 허용값. 그 밖의 형식은 빈 배열 |
| `jobs[].params[].description` | string | Y | 화면 라벨 |
| `jobs[].steps[].stepNm` | string | N | Step 이름. 배열 순서가 실행 순서 |
| `jobs[].steps[].stepKorNm` | string | Y | Step 한글명 |
| `jobs[].lastExcn` | object | Y | 마지막 실행. 필드는 2번 `content[]` 와 같다. 실행 기록이 없으면 null |
| `jobs[].lastSuccessDt` | string | Y | 마지막 `COMPLETED` 실행의 종료 시각 |
| `jobs[].lastSuccessExcnId` | number | Y | 마지막 `COMPLETED` 실행 ID |
| `jobs[].schedule` | object | Y | 스케줄. 행과 카탈로그 값이 모두 없으면 null |
| `jobs[].schedule.cronExpr` | string | Y | Spring 6필드 cron |
| `jobs[].schedule.useYn` | string | Y | `Y`, `N` |
| `jobs[].schedule.mdfrId` | string | Y | 마지막 변경자 |
| `jobs[].schedule.mdfcnDt` | string | Y | 마지막 변경 시각 |
| `jobs[].schedule.registered` | boolean | Y | 배치 스케줄러 등록 여부. 카탈로그를 못 받으면 null |
| `jobs[].schedule.blockedByEnv` | boolean | Y | `*.sync.scheduled=false` 로 차단됐는지 여부 |
| `jobs[].schedule.envKey` | string | Y | 차단 스위치 키. `crackdownFetchJob` 은 스위치가 없어 null |
| `jobs[].schedule.nextRuns[]` | string | Y | 다음 실행 시각 최대 3회(ISO-8601, 오프셋 포함) |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 401 | 없음 | 로그인 세션 없음 |
| 403 | `{"timestamp":"…","status":403,"error":"Forbidden","path":"/api/ops/batch/jobs"}` | `M0604` 권한 없음 |

## 2. GET /api/ops/batch/executions

### API 개요
배치 실행 이력을 페이지 단위로 조회한다. 백엔드가 `batch` 스키마를 직접 읽으므로 배치 앱이 꺼져 있어도 동작한다.

로그인 세션과 `M0604` 메뉴 권한이 필요하다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | 세션 쿠키 | string | Y | - | 로그인 세션 |
| query | `jobNm` | string | N | 전체 | Job 이름 정확 일치. 최대 100자, 공백만 있으면 전체 |
| query | `status` | string | N | 전체 | `STARTING`, `STARTED`, `STOPPING`, `STOPPED`, `COMPLETED`, `FAILED`, `ABANDONED`, `UNKNOWN` |
| query | `page` | integer | N | `0` | 0부터 시작하는 페이지 번호. 0 이상 |
| query | `size` | integer | N | `10` | 페이지 크기. 1~100 |

### 호출 예시
```bash
curl 'http://localhost:8180/api/ops/batch/executions?jobNm=complaintFetchJob&status=FAILED&page=0&size=10' -b cookies.txt
```

### 응답
`200 OK`, `application/json`. `jobExecutionId` 내림차순으로 정렬한다.

```json
{
  "content": [
    {
      "jobExecutionId": 4521,
      "jobNm": "complaintFetchJob",
      "status": "COMPLETED",
      "exitCode": "COMPLETED",
      "exitMessage": "",
      "createTime": "2026-11-18T02:00:00",
      "startTime": "2026-11-18T02:00:00",
      "endTime": "2026-11-18T02:05:00",
      "lastUpdated": "2026-11-18T02:05:00",
      "durationMs": 300000,
      "hstryYn": "Y",
      "readCnt": 1200,
      "writeCnt": 1180,
      "excnSeCd": "SCHDL",
      "excutrId": null,
      "upExcnId": null,
      "runMode": "incremental",
      "targetRange": "2026-11-17 ~ 2026-11-18",
      "failureMessage": null,
      "subRslts": [
        {
          "subSeCd": "CHAIN",
          "subNm": "riskAlertJudgeJob",
          "jobExecutionId": 4522,
          "status": "COMPLETED",
          "startTime": "2026-11-18T02:05:01",
          "endTime": "2026-11-18T02:05:03",
          "durationMs": 2000,
          "readCnt": 43,
          "writeCnt": 2,
          "message": null
        }
      ]
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 1,
  "totalPages": 1
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `content[].jobExecutionId` | number | N | 실행 ID |
| `content[].jobNm` | string | N | Job 이름 |
| `content[].status` | string | N | Spring Batch 실행 상태 |
| `content[].exitCode` | string | Y | 종료 코드 |
| `content[].exitMessage` | string | Y | 종료 메시지. 비밀값 마스킹 후 500자로 자른다 |
| `content[].createTime` | string | Y | 실행 생성 시각 |
| `content[].startTime` | string | Y | 시작 시각 |
| `content[].endTime` | string | Y | 종료 시각. 실행 중이면 null |
| `content[].lastUpdated` | string | Y | 메타 마지막 갱신 시각 |
| `content[].durationMs` | number | Y | 소요 시간(ms). 이력값이 없으면 종료-시작으로 계산 |
| `content[].hstryYn` | string | N | 실행 이력 행 존재 여부. `N` 이면 이력 필드가 null |
| `content[].readCnt` | number | Y | 읽기 건수 |
| `content[].writeCnt` | number | Y | 처리 건수. 의미는 잡의 `countLabel` |
| `content[].excnSeCd` | string | Y | `SCHDL`, `MANUAL`, `CHAIN`, `API` |
| `content[].excutrId` | string | Y | 실행자. 운영 화면 실행만 값이 있다 |
| `content[].upExcnId` | number | Y | 연쇄를 일으킨 앞 실행 ID |
| `content[].runMode` | string | Y | 실행 방식 |
| `content[].targetRange` | string | Y | 대상 구간 |
| `content[].failureMessage` | string | Y | 실패 메시지. 마스킹 후 500자로 자른다 |
| `content[].subRslts[].subSeCd` | string | N | 하위 구분. 현재 `CHAIN` 뿐 |
| `content[].subRslts[].subNm` | string | N | 연쇄 실행된 Job 이름 |
| `content[].subRslts[].jobExecutionId` | number | Y | 하위 실행 ID |
| `content[].subRslts[].status` | string | N | 하위 실행 상태 |
| `content[].subRslts[].startTime` | string | Y | 시작 시각 |
| `content[].subRslts[].endTime` | string | Y | 종료 시각 |
| `content[].subRslts[].durationMs` | number | Y | 소요 시간(ms) |
| `content[].subRslts[].readCnt` | number | Y | 읽기 건수 |
| `content[].subRslts[].writeCnt` | number | Y | 처리 건수 |
| `content[].subRslts[].message` | string | Y | 실패 메시지, 없으면 종료 메시지. 마스킹 후 500자로 자른다 |
| `page` | number | N | 0부터 시작하는 페이지 번호 |
| `size` | number | N | 페이지 크기 |
| `totalElements` | number | N | 조건에 맞는 전체 건수 |
| `totalPages` | number | N | 전체 페이지 수 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"timestamp":"…","status":400,"error":"Bad Request","path":"/api/ops/batch/executions"}` | `page` 음수, `size` 범위 밖, `jobNm` 100자 초과, 허용되지 않은 `status`, `page`·`size` 숫자 아님 |
| 401 | 없음 | 로그인 세션 없음 |
| 403 | `{"timestamp":"…","status":403,"error":"Forbidden","path":"/api/ops/batch/executions"}` | `M0604` 권한 없음 |

## 3. GET /api/ops/batch/executions/{id}

### API 개요
실행 1건의 이력 지표, 파라미터, Step별 카운트, 진행 단계를 조회한다. 메시지는 마스킹만 하고 자르지 않는다. Step 한글명과 진행 수치는 배치 앱에서 받아 채우며, 받지 못하면 해당 필드만 null 이다.

로그인 세션과 `M0604` 메뉴 권한이 필요하다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | 세션 쿠키 | string | Y | - | 로그인 세션 |
| path | `id` | long | Y | - | 실행 ID |

### 호출 예시
```bash
curl 'http://localhost:8180/api/ops/batch/executions/4521' -b cookies.txt
```

### 응답
`200 OK`, `application/json`. 2번 `content[]` 의 필드에 아래 필드를 더한다.

```json
{
  "jobExecutionId": 4521,
  "jobNm": "complaintFetchJob",
  "status": "STARTED",
  "exitCode": "UNKNOWN",
  "exitMessage": "",
  "createTime": "2026-11-18T02:00:00",
  "startTime": "2026-11-18T02:00:00",
  "endTime": null,
  "lastUpdated": "2026-11-18T02:01:00",
  "durationMs": null,
  "hstryYn": "N",
  "readCnt": null,
  "writeCnt": null,
  "excnSeCd": null,
  "excutrId": null,
  "upExcnId": null,
  "runMode": null,
  "targetRange": null,
  "failureMessage": null,
  "subRslts": [],
  "skipGeocodeFailed": null,
  "skipEmdUnresolved": null,
  "filteredTransient": null,
  "geocodeApiLookups": null,
  "geocodeCacheHits": null,
  "geocodePlaceRecoveries": null,
  "geocodeStripped": null,
  "emdContains": null,
  "emdBuffer": null,
  "emdNameFallback": null,
  "unclsfCnt": null,
  "unknownTypes": null,
  "params": [
    {
      "parameterName": "runAt",
      "parameterType": "java.lang.Long",
      "parameterValue": "1795000000000",
      "identifying": "Y"
    }
  ],
  "steps": [
    {
      "stepExecutionId": 9001,
      "stepName": "complaintFetchStep",
      "stepKorNm": "수집·지오코딩·적재",
      "status": "STARTED",
      "startTime": "2026-11-18T02:00:00",
      "endTime": null,
      "readCount": 500,
      "writeCount": 480,
      "filterCount": 0,
      "readSkipCount": 0,
      "writeSkipCount": 0,
      "processSkipCount": 2,
      "commitCount": 5,
      "rollbackCount": 0,
      "exitCode": "EXECUTING",
      "exitMessage": ""
    }
  ],
  "progress": {
    "currentStep": "complaintFetchStep",
    "currentStepKorNm": "수집·지오코딩·적재",
    "cmptnStepCnt": 0,
    "totalStepCnt": 1,
    "readCount": 500,
    "writeCount": 480,
    "live": {
      "dayIdx": 1,
      "dayCnt": 1,
      "crntYmd": "2026-11-17",
      "fetchCnt": 1000,
      "prcsCnt": 520,
      "geocodedCnt": 490,
      "unclsfCnt": 20,
      "filteredTransient": 0,
      "geocodeCacheHits": 400,
      "geocodeApiLookups": 90,
      "skipGeocodeFailed": 2,
      "skipEmdUnresolved": 0
    }
  }
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `skipGeocodeFailed` | number | Y | 지오코딩 실패 스킵 수 |
| `skipEmdUnresolved` | number | Y | 읍면동 미해석 스킵 수 |
| `filteredTransient` | number | Y | 일시 실패로 이번 실행에서 제외한 수 |
| `geocodeApiLookups` | number | Y | 지오코딩 API 호출 수 |
| `geocodeCacheHits` | number | Y | 지오코딩 캐시 히트 수 |
| `geocodePlaceRecoveries` | number | Y | 장소검색 폴백 복구 수 |
| `geocodeStripped` | number | Y | 주소 정제 후 재시도 수 |
| `emdContains` | number | Y | 공간매칭 포함 건수 |
| `emdBuffer` | number | Y | 공간매칭 버퍼 건수 |
| `emdNameFallback` | number | Y | 읍면동명 폴백 건수 |
| `unclsfCnt` | number | Y | 미분류 수 |
| `unknownTypes` | object | Y | 미분류 유형별 건수 |
| `params[].parameterName` | string | N | 파라미터 이름. 이름 오름차순 |
| `params[].parameterType` | string | N | Java 클래스명 |
| `params[].parameterValue` | string | Y | 값(마스킹) |
| `params[].identifying` | string | N | 식별 파라미터 여부 `Y`, `N` |
| `steps[].stepExecutionId` | number | N | Step 실행 ID. 오름차순 |
| `steps[].stepName` | string | N | Step 이름 |
| `steps[].stepKorNm` | string | Y | Step 한글명 |
| `steps[].status` | string | N | Step 상태 |
| `steps[].startTime` | string | Y | 시작 시각 |
| `steps[].endTime` | string | Y | 종료 시각 |
| `steps[].readCount` | number | Y | 읽기 수 |
| `steps[].writeCount` | number | Y | 쓰기 수 |
| `steps[].filterCount` | number | Y | 필터 수 |
| `steps[].readSkipCount` | number | Y | 읽기 스킵 수 |
| `steps[].writeSkipCount` | number | Y | 쓰기 스킵 수 |
| `steps[].processSkipCount` | number | Y | 처리 스킵 수 |
| `steps[].commitCount` | number | Y | 커밋 수 |
| `steps[].rollbackCount` | number | Y | 롤백 수 |
| `steps[].exitCode` | string | Y | 종료 코드 |
| `steps[].exitMessage` | string | Y | 종료 메시지(마스킹, 전체) |
| `progress` | object | Y | `STARTING`, `STARTED`, `STOPPING` 일 때만 값이 있다 |
| `progress.currentStep` | string | Y | 현재 Step. Step 전환 중이면 null |
| `progress.currentStepKorNm` | string | Y | 현재 Step 한글명 |
| `progress.cmptnStepCnt` | number | N | 완료 Step 수 |
| `progress.totalStepCnt` | number | Y | 전체 Step 수. 카탈로그를 못 받으면 null |
| `progress.readCount` | number | Y | 현재 Step 읽기 수 |
| `progress.writeCount` | number | Y | 현재 Step 쓰기 수 |
| `progress.live` | object | Y | 단계별 수치. `complaintFetchJob` 실행 중에만 값이 있다. 필드는 15번 `metrics` 와 같다 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"timestamp":"…","status":400,"error":"Bad Request","path":"/api/ops/batch/executions/abc"}` | `id` 가 숫자가 아님 |
| 401 | 없음 | 로그인 세션 없음 |
| 403 | `{"timestamp":"…","status":403,"error":"Forbidden","path":"/api/ops/batch/executions/4521"}` | `M0604` 권한 없음 |
| 404 | `{"timestamp":"…","status":404,"error":"Not Found","path":"/api/ops/batch/executions/4521"}` | 실행이 없음 |

## 4. POST /api/ops/batch/jobs/{jobNm}/executions

### API 개요
잡을 비동기로 실행한다. 백엔드는 로그인 사용자의 `user_id` 를 실행자로 넣어 배치 앱 `POST /api/batch/ops/jobs/{jobNm}/executions`(10번)로 전달하고, 배치 앱의 2xx·400·404·409 응답을 상태와 본문 그대로 돌려준다.

로그인 세션, `M0604` 메뉴 권한, CSRF 토큰이 필요하다. 실행 이력은 `excnSeCd=MANUAL` 로 남고 연쇄 잡은 실행되지 않는다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | 세션 쿠키 | string | Y | - | 로그인 세션 |
| header | `X-XSRF-TOKEN` | string | Y | - | `XSRF-TOKEN` 쿠키 값 |
| path | `jobNm` | string | Y | - | 카탈로그의 Job 이름. 영문으로 시작하는 영숫자 최대 100자 |
| body | `params` | object | N | `{}` | Job 파라미터. 빈 값은 생략으로 처리한다. 잡에 없는 키는 400 |
| body | `params.mode` | string | N | 증분 수집 | `complaintFetchJob`. `incremental`, `daily`, `backfill` |
| body | `params.targetDate` | string | N | 잡별 | `complaintFetchJob` 은 daily 기준일(기본 전일)로 `mode=daily` 와 함께 쓴다. `riskAlertJudgeJob` 은 판정일(기본 어제, Asia/Seoul). `yyyy-MM-dd` |
| body | `params.from` | string | N | 저장된 마지막 시각 | `complaintFetchJob`, `crackdownFetchJob`, `pkltAcdntFetchJob` 의 시작일(포함). `yyyy-MM-dd` |
| body | `params.to` | string | N | 오늘 포함 | 위 3개 잡의 끝일(미포함). `yyyy-MM-dd`. 증분 수집에서는 `from` 과 함께 지정한다 |
| body | `params.fromDt` | string | N | 하한 없음 | `riskAlertReceiverBackfillJob` 시작일(포함). `yyyy-MM-dd` |
| body | `params.toDt` | string | N | 상한 없음 | `riskAlertReceiverBackfillJob` 끝일(미포함). `yyyy-MM-dd` |
| body | `params.baseYmd` | string | N | 오늘(Asia/Seoul) | `reportAutoCrtJob` 기준일. `yyyy-MM-dd` |
| body | `params.gridSz` | string | N | 전 크기 | `zoneNmPrefillJob` 격자 크기. `1000`, `500`, `250`, `100` |

`pblpkltFetchJob`, `pkltUtztnFetchJob`, `pkltSftyChckFetchJob` 은 파라미터가 없다. 본문의 `excutrId` 는 무시한다.

### 호출 예시
```bash
curl -X POST 'http://localhost:8180/api/ops/batch/jobs/reportAutoCrtJob/executions' \
  -b cookies.txt \
  -H 'X-XSRF-TOKEN: {XSRF-TOKEN 쿠키 값}' \
  -H 'Content-Type: application/json' \
  -d '{"params":{"baseYmd":"2026-11-01"}}'
```

### 응답
`202 Accepted`, `application/json`. 실행 접수 결과이며 잡의 완료를 뜻하지 않는다.

```json
{
  "jobExecutionId": 4530,
  "jobNm": "reportAutoCrtJob",
  "status": "STARTING"
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `jobExecutionId` | number | N | 생성된 실행 ID |
| `jobNm` | string | N | Job 이름 |
| `status` | string | N | 접수 시점의 실행 상태 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"code":"INVALID_PATH","message":"잡 이름 형식이 잘못됐습니다"}` | `jobNm` 형식 오류 |
| 400 | `{"code":"INVALID_PARAM","message":"…"}` | 없는 파라미터, 날짜·ENUM 형식 오류, 구간 조합 오류 |
| 400 | `{"code":"INVALID_EXCUTRID","message":"…"}` | 실행자 ID가 20자 초과 |
| 400 | `{"timestamp":"…","status":400,"error":"Bad Request","path":"…"}` | 요청 본문이 올바른 JSON이 아님 |
| 401 | 없음 또는 `{"timestamp":"…","status":401,"error":"Unauthorized","path":"…"}` | 로그인 세션 없음, 사용자 없음 또는 정지 |
| 403 | `{"timestamp":"…","status":403,"error":"Forbidden","path":"…"}` | `M0604` 권한 없음, CSRF 토큰 누락·불일치 |
| 404 | `{"code":"JOB_NOT_FOUND","message":"카탈로그에 없는 잡입니다: …"}` | 카탈로그에 없는 잡 |
| 409 | `{"code":"JOB_ALREADY_RUNNING","message":"…","jobExecutionId":4521,"status":"STARTED","zombie":false}` | 같은 잡이 실행 중. `zombie=true` 면 프로세스에서 돌지 않는 실행으로 실패 처리(7번) 대상이다 |
| 503 | `{"code":"BATCH_OPS_NOT_CONFIGURED","message":"배치 운영 연결이 설정되지 않았습니다"}` | `app.batch-ops.base-url` 미설정 |
| 503 | `{"code":"BATCH_UNAVAILABLE","message":"배치 서버에 연결할 수 없습니다"}` | 연결 실패, 타임아웃, 배치 앱의 401·5xx, 예상 밖 응답 |

## 5. PUT /api/ops/batch/schedules/{jobNm}

### API 개요
잡의 cron과 사용 여부를 변경한다. 백엔드는 로그인 사용자의 `user_id` 를 수정자로 넣어 배치 앱 `PUT /api/batch/ops/schedules/{jobNm}`(11번)로 전달한다. 배치 앱은 `batch.tb_btch_schdl` 에 저장한 뒤 스케줄을 즉시 다시 등록한다.

로그인 세션, `M0604` 메뉴 권한, CSRF 토큰이 필요하다. env 스위치로 차단된 잡도 값은 저장되지만 스케줄러에는 등록되지 않는다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | 세션 쿠키 | string | Y | - | 로그인 세션 |
| header | `X-XSRF-TOKEN` | string | Y | - | `XSRF-TOKEN` 쿠키 값 |
| path | `jobNm` | string | Y | - | 스케줄 대상 Job 이름 |
| body | `cronExpr` | string | Y | - | Spring 6필드 cron(초 분 시 일 월 요일). 최대 100자 |
| body | `useYn` | string | Y | - | `Y`, `N` |

### 호출 예시
```bash
curl -X PUT 'http://localhost:8180/api/ops/batch/schedules/complaintFetchJob' \
  -b cookies.txt \
  -H 'X-XSRF-TOKEN: {XSRF-TOKEN 쿠키 값}' \
  -H 'Content-Type: application/json' \
  -d '{"cronExpr":"0 30 2 * * *","useYn":"Y"}'
```

### 응답
`200 OK`, `application/json`

```json
{
  "jobNm": "complaintFetchJob",
  "cronExpr": "0 30 2 * * *",
  "useYn": "Y",
  "mdfrId": "user01",
  "state": "REGISTERED",
  "registered": true,
  "blockedByEnv": false,
  "nextRuns": [
    "2026-11-19T02:30:00+09:00",
    "2026-11-20T02:30:00+09:00",
    "2026-11-21T02:30:00+09:00"
  ]
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `jobNm` | string | N | Job 이름 |
| `cronExpr` | string | N | 저장된 cron |
| `useYn` | string | N | 저장된 사용 여부 |
| `mdfrId` | string | Y | 수정자 |
| `state` | string | N | `REGISTERED`, `DISABLED`, `BLOCKED`, `INVALID_CRON`, `NO_TARGET`, `NO_ROW` |
| `registered` | boolean | N | `state=REGISTERED` 여부 |
| `blockedByEnv` | boolean | N | env 스위치 차단 여부 |
| `nextRuns[]` | string | N | 다음 실행 시각 최대 3회(Asia/Seoul). 등록되지 않았으면 빈 배열 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"code":"INVALID_PATH","message":"잡 이름 형식이 잘못됐습니다"}` | `jobNm` 형식 오류 |
| 400 | `{"code":"SCHEDULE_NOT_SUPPORTED","message":"…"}` | 스케줄을 둘 수 없는 잡 |
| 400 | `{"code":"INVALID_USE_YN","message":"…"}` | `useYn` 이 `Y`, `N` 이 아님 |
| 400 | `{"code":"INVALID_CRON","message":"…"}` | cron이 비었거나 100자 초과, 형식 오류 |
| 400 | `{"code":"INVALID_MDFRID","message":"…"}` | 수정자 ID가 20자 초과 |
| 401 | 없음 또는 `{"timestamp":"…","status":401,"error":"Unauthorized","path":"…"}` | 로그인 세션 없음, 사용자 없음 또는 정지 |
| 403 | `{"timestamp":"…","status":403,"error":"Forbidden","path":"…"}` | `M0604` 권한 없음, CSRF 토큰 누락·불일치 |
| 404 | `{"code":"JOB_NOT_FOUND","message":"카탈로그에 없는 잡입니다: …"}` | 카탈로그에 없는 잡 |
| 503 | `{"code":"BATCH_OPS_NOT_CONFIGURED","message":"배치 운영 연결이 설정되지 않았습니다"}` | `app.batch-ops.base-url` 미설정 |
| 503 | `{"code":"BATCH_UNAVAILABLE","message":"배치 서버에 연결할 수 없습니다"}` | 연결 실패, 타임아웃, 배치 앱의 401·5xx, 예상 밖 응답 |

## 6. POST /api/ops/batch/executions/{id}/stop

### API 개요
실행 중인 잡에 중지를 요청한다. 백엔드가 배치 앱 `POST /api/batch/ops/executions/{id}/stop`(12번)로 전달하며, 실제 중지는 현재 청크를 커밋한 뒤 이뤄진다.

로그인 세션, `M0604` 메뉴 권한, CSRF 토큰이 필요하다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | 세션 쿠키 | string | Y | - | 로그인 세션 |
| header | `X-XSRF-TOKEN` | string | Y | - | `XSRF-TOKEN` 쿠키 값 |
| path | `id` | long | Y | - | 실행 ID |

### 호출 예시
```bash
curl -X POST 'http://localhost:8180/api/ops/batch/executions/4521/stop' \
  -b cookies.txt \
  -H 'X-XSRF-TOKEN: {XSRF-TOKEN 쿠키 값}'
```

### 응답
`202 Accepted`, `application/json`

```json
{
  "jobExecutionId": 4521,
  "status": "STOPPING",
  "zombie": false
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `jobExecutionId` | number | N | 실행 ID |
| `status` | string | N | 항상 `STOPPING` |
| `zombie` | boolean | N | DB에는 실행 중이지만 배치 프로세스에서 돌고 있지 않으면 true. 이 경우 실패 처리(7번)로 정리한다 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"timestamp":"…","status":400,"error":"Bad Request","path":"…"}` | `id` 가 숫자가 아님 |
| 400 | `{"code":"INVALID_EXCUTRID","message":"…"}` | 요청자 ID가 20자 초과 |
| 401 | 없음 또는 `{"timestamp":"…","status":401,"error":"Unauthorized","path":"…"}` | 로그인 세션 없음, 사용자 없음 또는 정지 |
| 403 | `{"timestamp":"…","status":403,"error":"Forbidden","path":"…"}` | `M0604` 권한 없음, CSRF 토큰 누락·불일치 |
| 404 | `{"code":"EXECUTION_NOT_FOUND","message":"없는 실행입니다: 4521"}` | 실행이 없음 |
| 409 | `{"code":"NOT_RUNNING","message":"…","jobExecutionId":4521,"status":"COMPLETED"}` | 이미 끝난 실행 |
| 409 | `{"code":"CONCURRENT_UPDATE","message":"같은 실행이 동시에 갱신됐습니다 — 상태를 다시 조회한 뒤 시도하세요"}` | 같은 실행 행을 동시에 갱신 |
| 503 | `{"code":"BATCH_OPS_NOT_CONFIGURED","message":"배치 운영 연결이 설정되지 않았습니다"}` | `app.batch-ops.base-url` 미설정 |
| 503 | `{"code":"BATCH_UNAVAILABLE","message":"배치 서버에 연결할 수 없습니다"}` | 연결 실패, 타임아웃, 배치 앱의 401·5xx, 예상 밖 응답 |

## 7. POST /api/ops/batch/executions/{id}/abandon

### API 개요
DB에는 실행 중이지만 배치 프로세스에서 돌고 있지 않은 실행을 `FAILED` 로 정리한다. 백엔드가 배치 앱 `POST /api/batch/ops/executions/{id}/abandon`(14번)로 전달한다.

로그인 세션, `M0604` 메뉴 권한, CSRF 토큰이 필요하다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | 세션 쿠키 | string | Y | - | 로그인 세션 |
| header | `X-XSRF-TOKEN` | string | Y | - | `XSRF-TOKEN` 쿠키 값 |
| path | `id` | long | Y | - | 실행 ID |

### 호출 예시
```bash
curl -X POST 'http://localhost:8180/api/ops/batch/executions/4521/abandon' \
  -b cookies.txt \
  -H 'X-XSRF-TOKEN: {XSRF-TOKEN 쿠키 값}'
```

### 응답
`200 OK`, `application/json`

```json
{
  "jobExecutionId": 4521,
  "jobNm": "complaintFetchJob",
  "status": "FAILED",
  "exitMessage": "운영 화면에서 실패 처리 — user01"
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `jobExecutionId` | number | N | 실행 ID |
| `jobNm` | string | N | Job 이름 |
| `status` | string | N | 항상 `FAILED` |
| `exitMessage` | string | N | 실패 처리 메시지 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"timestamp":"…","status":400,"error":"Bad Request","path":"…"}` | `id` 가 숫자가 아님 |
| 400 | `{"code":"INVALID_EXCUTRID","message":"…"}` | 요청자 ID가 20자 초과 |
| 401 | 없음 또는 `{"timestamp":"…","status":401,"error":"Unauthorized","path":"…"}` | 로그인 세션 없음, 사용자 없음 또는 정지 |
| 403 | `{"timestamp":"…","status":403,"error":"Forbidden","path":"…"}` | `M0604` 권한 없음, CSRF 토큰 누락·불일치 |
| 404 | `{"code":"EXECUTION_NOT_FOUND","message":"없는 실행입니다: 4521"}` | 실행이 없음 |
| 409 | `{"code":"STILL_RUNNING","message":"…","jobExecutionId":4521,"status":"STARTED"}` | 배치 프로세스에서 실제로 실행 중 |
| 409 | `{"code":"NOT_RUNNING","message":"…","jobExecutionId":4521,"status":"COMPLETED"}` | 이미 끝난 실행 |
| 409 | `{"code":"CONCURRENT_UPDATE","message":"같은 실행이 동시에 갱신됐습니다 — 상태를 다시 조회한 뒤 시도하세요"}` | 같은 실행 행을 동시에 갱신 |
| 503 | `{"code":"BATCH_OPS_NOT_CONFIGURED","message":"배치 운영 연결이 설정되지 않았습니다"}` | `app.batch-ops.base-url` 미설정 |
| 503 | `{"code":"BATCH_UNAVAILABLE","message":"배치 서버에 연결할 수 없습니다"}` | 연결 실패, 타임아웃, 배치 앱의 401·5xx, 예상 밖 응답 |

## 8. GET /api/ops/batch/executions/{id}/log

### API 개요
실행별 텍스트 로그를 조회한다. 백엔드가 배치 앱 `GET /api/batch/ops/executions/{id}/log`(13번)의 `text/plain` 본문과 응답 헤더를 JSON으로 감싸 돌려준다.

로그인 세션과 `M0604` 메뉴 권한이 필요하다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| cookie | 세션 쿠키 | string | Y | - | 로그인 세션 |
| path | `id` | long | Y | - | 실행 ID |
| query | `tailKb` | integer | N | `200` | 마지막 N KB. 1~1024. `fromByte` 와 함께 쓸 수 없다 |
| query | `fromByte` | long | N | - | 이 바이트 위치 뒤의 증분. 0 이상, 1회 최대 256KB |

### 호출 예시
```bash
curl 'http://localhost:8180/api/ops/batch/executions/4521/log?tailKb=200' -b cookies.txt
```

### 응답
`200 OK`, `application/json`

```json
{
  "content": "2026-11-18 02:00:00 INFO 수집 시작\n",
  "nextByte": 20480,
  "jobRunning": true
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `content` | string | N | 로그 본문(마스킹, UTF-8). 줄 단위로 끊어 내려준다 |
| `nextByte` | number | Y | 다음 조회의 `fromByte`. 원본 파일 바이트 기준이라 `content` 길이와 다를 수 있다 |
| `jobRunning` | boolean | Y | 배치 프로세스에서 실행 중인지 여부. false 면 폴링을 멈춘다 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"code":"INVALID_PARAM","message":"…"}` | `tailKb` 범위 밖, `fromByte` 음수 또는 파일 크기 초과, 두 파라미터 동시 지정 |
| 400 | `{"timestamp":"…","status":400,"error":"Bad Request","path":"…"}` | `id`, `tailKb`, `fromByte` 가 숫자가 아님 |
| 401 | 없음 | 로그인 세션 없음 |
| 403 | `{"timestamp":"…","status":403,"error":"Forbidden","path":"…"}` | `M0604` 권한 없음 |
| 404 | `{"code":"EXECUTION_NOT_FOUND","message":"없는 실행입니다: 4521"}` | 실행이 없음 |
| 404 | `{"code":"LOG_NOT_FOUND","message":"로그 없음 — 로그 기능 도입 전 실행이거나 보존기간이 지나 정리됐습니다(실행 #4521)"}` | 로그 파일이 없음 |
| 503 | `{"code":"BATCH_OPS_NOT_CONFIGURED","message":"배치 운영 연결이 설정되지 않았습니다"}` | `app.batch-ops.base-url` 미설정 |
| 503 | `{"code":"BATCH_UNAVAILABLE","message":"배치 서버에 연결할 수 없습니다"}` | 연결 실패, 타임아웃, 배치 앱의 401·5xx, 예상 밖 응답 |

## 9. GET /api/batch/ops/jobs

### API 개요
배치 앱 내부 API로, 카탈로그에 등록된 10개 잡의 명세와 스케줄 상태를 돌려준다. 백엔드 1번 API가 호출한다.

`X-API-Key` 인증은 `batch.api.auth.enabled` 로 켠다. local 은 기본 꺼짐, dev 는 기본 켜짐, prod 는 항상 켜짐이다. Docker 구성에서는 배치 포트가 외부에 공개되지 않고 nginx 도 이 경로를 배치 앱으로 전달하지 않는다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `X-API-Key` | string | 인증 활성 시 Y | - | `BATCH_API_KEY` 와 같은 값 |

### 호출 예시
```bash
curl 'http://localhost:8280/api/batch/ops/jobs' -H 'X-API-Key: {BATCH_API_KEY}'
```

### 응답
`200 OK`, `application/json`. 배열이며 카탈로그 순서로 내려준다.

```json
[
  {
    "jobNm": "riskAlertJudgeJob",
    "jobKorNm": "위험단계 판정",
    "description": "읍면동별 민원 증가로 위험단계를 판정해 알림 이력에 적재하고 수신자를 매핑한다",
    "countLabel": "알림",
    "params": [
      {
        "name": "targetDate",
        "type": "DATE",
        "required": false,
        "defaultDesc": "어제(Asia/Seoul)",
        "options": [],
        "description": "판정 대상일"
      }
    ],
    "schedulable": false,
    "schedule": null,
    "chainNext": null,
    "chainCondition": null,
    "steps": [
      { "stepNm": "riskAlertJudgeStep", "stepKorNm": "판정" },
      { "stepNm": "riskAlertReceiverMappingStep", "stepKorNm": "수신자 매핑" }
    ],
    "runningJobExecutionIds": []
  }
]
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `[].jobNm` | string | N | Job 이름 |
| `[].jobKorNm` | string | N | 한글명 |
| `[].description` | string | N | 설명 |
| `[].countLabel` | string | N | 처리 건수 문구 |
| `[].params[]` | array | N | 파라미터 명세. 필드는 1번 `jobs[].params[]` 와 같다 |
| `[].schedulable` | boolean | N | 스케줄 대상 여부 |
| `[].schedule` | object | Y | 스케줄 상태. 스케줄 대상이 아니면 null. 필드는 1번 `jobs[].schedule` 과 같다 |
| `[].chainNext` | string | Y | 이어서 도는 잡 |
| `[].chainCondition` | string | Y | 연쇄 조건 문구 |
| `[].steps[]` | array | N | Step 명세. 필드는 1번 `jobs[].steps[]` 와 같다 |
| `[].runningJobExecutionIds[]` | number | N | 이 프로세스에서 실행 중인 실행 ID, 오름차순 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 401 | `{"error":"missing or invalid X-API-Key"}` | 인증 활성 상태에서 키 누락 또는 불일치 |
| 503 | `{"error":"batch API key not configured (BATCH_API_KEY missing)"}` | 인증 활성 상태에서 서버 키가 비어 있음 |
| 500 | `{"timestamp":"…","status":500,"error":"Internal Server Error","path":"/api/batch/ops/jobs"}` | DB 조회 실패 등 처리되지 않은 오류 |

## 10. POST /api/batch/ops/jobs/{jobNm}/executions

### API 개요
배치 앱 내부 API로, 잡을 별도 스레드에서 비동기로 실행한다. 백엔드 4번 API가 호출한다. 실행 이력에는 `excnSeCd=MANUAL` 과 `excutrId` 가 남는다.

`X-API-Key` 인증은 local 기본 꺼짐, dev 기본 켜짐, prod 항상 켜짐이다. 같은 잡이 DB 기준으로 실행 중이면 409로 거절한다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `X-API-Key` | string | 인증 활성 시 Y | - | `BATCH_API_KEY` 와 같은 값 |
| path | `jobNm` | string | Y | - | 카탈로그의 Job 이름 |
| body | `params` | object | N | - | Job 파라미터. 허용 키와 형식은 4번과 같다 |
| body | `excutrId` | string | N | - | 실행자 ID. 최대 20자 |

### 호출 예시
```bash
curl -X POST 'http://localhost:8280/api/batch/ops/jobs/reportAutoCrtJob/executions' \
  -H 'X-API-Key: {BATCH_API_KEY}' \
  -H 'Content-Type: application/json' \
  -d '{"params":{"baseYmd":"2026-11-01"},"excutrId":"user01"}'
```

### 응답
`202 Accepted`, `application/json`

```json
{
  "jobExecutionId": 4530,
  "jobNm": "reportAutoCrtJob",
  "status": "STARTING"
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `jobExecutionId` | number | N | 생성된 실행 ID |
| `jobNm` | string | N | Job 이름 |
| `status` | string | N | 접수 시점의 실행 상태 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"code":"INVALID_PARAM","message":"…"}` | 없는 파라미터, 날짜·ENUM 형식 오류, 구간 조합 오류 |
| 400 | `{"code":"INVALID_EXCUTRID","message":"excutrId 는 20자 이하여야 합니다"}` | `excutrId` 20자 초과 |
| 400 | `{"code":"INVALID_BODY","message":"요청 본문을 읽을 수 없습니다(JSON 형식 확인)"}` | 본문이 올바른 JSON이 아님 |
| 401 | `{"error":"missing or invalid X-API-Key"}` | 키 누락 또는 불일치 |
| 404 | `{"code":"JOB_NOT_FOUND","message":"카탈로그에 없는 잡입니다: …"}` | 카탈로그에 없는 잡 |
| 409 | `{"code":"JOB_ALREADY_RUNNING","message":"…","jobExecutionId":4521,"status":"STARTED","zombie":false}` | 같은 잡이 실행 중 |
| 500 | `{"code":"JOB_BEAN_MISSING","message":"카탈로그에는 있는데 Job 빈이 없습니다: …"}` | 카탈로그와 Job 빈 불일치 |
| 500 | `{"code":"LAUNCH_FAILED","message":"실행을 시작하지 못했습니다: …"}` | Job 실행 시작 실패 |
| 503 | `{"error":"batch API key not configured (BATCH_API_KEY missing)"}` | 인증 활성 상태에서 서버 키가 비어 있음 |

## 11. PUT /api/batch/ops/schedules/{jobNm}

### API 개요
배치 앱 내부 API로, `batch.tb_btch_schdl` 에 cron과 사용 여부를 저장하고 스케줄을 즉시 다시 등록한다. 백엔드 5번 API가 호출한다.

`X-API-Key` 인증은 local 기본 꺼짐, dev 기본 켜짐, prod 항상 켜짐이다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `X-API-Key` | string | 인증 활성 시 Y | - | `BATCH_API_KEY` 와 같은 값 |
| path | `jobNm` | string | Y | - | 스케줄 대상 Job 이름 |
| body | `cronExpr` | string | Y | - | Spring 6필드 cron. 최대 100자 |
| body | `useYn` | string | Y | - | `Y`, `N` |
| body | `mdfrId` | string | N | - | 수정자 ID. 최대 20자 |

### 호출 예시
```bash
curl -X PUT 'http://localhost:8280/api/batch/ops/schedules/complaintFetchJob' \
  -H 'X-API-Key: {BATCH_API_KEY}' \
  -H 'Content-Type: application/json' \
  -d '{"cronExpr":"0 30 2 * * *","useYn":"Y","mdfrId":"user01"}'
```

### 응답
`200 OK`, `application/json`. 본문과 필드는 5번 응답과 같다.

```json
{
  "jobNm": "complaintFetchJob",
  "cronExpr": "0 30 2 * * *",
  "useYn": "Y",
  "mdfrId": "user01",
  "state": "REGISTERED",
  "registered": true,
  "blockedByEnv": false,
  "nextRuns": ["2026-11-19T02:30:00+09:00"]
}
```

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"code":"SCHEDULE_NOT_SUPPORTED","message":"…"}` | 스케줄을 둘 수 없는 잡 |
| 400 | `{"code":"INVALID_USE_YN","message":"…"}` | `useYn` 이 `Y`, `N` 이 아님 |
| 400 | `{"code":"INVALID_CRON","message":"…"}` | cron이 비었거나 100자 초과, 형식 오류 |
| 400 | `{"code":"INVALID_MDFRID","message":"mdfrId 는 20자 이하여야 합니다"}` | `mdfrId` 20자 초과 |
| 400 | `{"code":"INVALID_BODY","message":"요청 본문을 읽을 수 없습니다(JSON 형식 확인)"}` | 본문 누락 또는 JSON 형식 오류 |
| 401 | `{"error":"missing or invalid X-API-Key"}` | 키 누락 또는 불일치 |
| 404 | `{"code":"JOB_NOT_FOUND","message":"카탈로그에 없는 잡입니다: …"}` | 카탈로그에 없는 잡 |
| 503 | `{"error":"batch API key not configured (BATCH_API_KEY missing)"}` | 인증 활성 상태에서 서버 키가 비어 있음 |

## 12. POST /api/batch/ops/executions/{id}/stop

### API 개요
배치 앱 내부 API로, 실행 중인 잡에 중지를 요청한다. 백엔드 6번 API가 호출한다.

`X-API-Key` 인증은 local 기본 꺼짐, dev 기본 켜짐, prod 항상 켜짐이다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `X-API-Key` | string | 인증 활성 시 Y | - | `BATCH_API_KEY` 와 같은 값 |
| path | `id` | long | Y | - | 실행 ID |
| body | `excutrId` | string | N | - | 요청자 ID. 최대 20자 |

### 호출 예시
```bash
curl -X POST 'http://localhost:8280/api/batch/ops/executions/4521/stop' \
  -H 'X-API-Key: {BATCH_API_KEY}' \
  -H 'Content-Type: application/json' \
  -d '{"excutrId":"user01"}'
```

### 응답
`202 Accepted`, `application/json`. 필드는 6번 응답과 같다.

```json
{
  "jobExecutionId": 4521,
  "status": "STOPPING",
  "zombie": false
}
```

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"code":"INVALID_PATH","message":"경로 값 형식이 잘못됐습니다: id"}` | `id` 가 숫자가 아님 |
| 400 | `{"code":"INVALID_EXCUTRID","message":"excutrId 는 20자 이하여야 합니다"}` | `excutrId` 20자 초과 |
| 400 | `{"code":"INVALID_BODY","message":"요청 본문을 읽을 수 없습니다(JSON 형식 확인)"}` | 본문 JSON 형식 오류 |
| 401 | `{"error":"missing or invalid X-API-Key"}` | 키 누락 또는 불일치 |
| 404 | `{"code":"EXECUTION_NOT_FOUND","message":"없는 실행입니다: 4521"}` | 실행이 없음 |
| 409 | `{"code":"NOT_RUNNING","message":"…","jobExecutionId":4521,"status":"COMPLETED"}` | 이미 끝난 실행 |
| 409 | `{"code":"CONCURRENT_UPDATE","message":"같은 실행이 동시에 갱신됐습니다 — 상태를 다시 조회한 뒤 시도하세요"}` | 같은 실행 행을 동시에 갱신 |
| 503 | `{"error":"batch API key not configured (BATCH_API_KEY missing)"}` | 인증 활성 상태에서 서버 키가 비어 있음 |

## 13. GET /api/batch/ops/executions/{id}/log

### API 개요
배치 앱 내부 API로, `{batch.ops.log-dir}/batch-jobs/{jobNm}/{id}.log` 파일을 읽어 텍스트로 돌려준다. 백엔드 8번 API가 호출한다. 비밀값은 응답 직전에 마스킹한다.

`X-API-Key` 인증은 local 기본 꺼짐, dev 기본 켜짐, prod 항상 켜짐이다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `X-API-Key` | string | 인증 활성 시 Y | - | `BATCH_API_KEY` 와 같은 값 |
| path | `id` | long | Y | - | 실행 ID |
| query | `tailKb` | integer | N | `200` | 마지막 N KB. 1~1024. `fromByte` 와 함께 쓸 수 없다 |
| query | `fromByte` | long | N | - | 이 바이트 위치 뒤의 증분. 0 이상, 1회 최대 256KB |

### 호출 예시
```bash
curl -i 'http://localhost:8280/api/batch/ops/executions/4521/log?fromByte=20480' -H 'X-API-Key: {BATCH_API_KEY}'
```

### 응답
`200 OK`, `text/plain;charset=UTF-8`. 본문은 로그 텍스트이며 줄 경계까지만 내려준다.

```text
2026-11-18 02:00:00 INFO 수집 시작
```

| 응답 헤더 | 타입 | 설명 |
| --- | --- | --- |
| `X-Next-Byte` | long | 다음 조회의 `fromByte`(원본 파일 바이트 기준) |
| `X-Job-Running` | boolean | 이 프로세스에서 실행 중인지 여부 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"code":"INVALID_PARAM","message":"…"}` | `tailKb` 범위 밖, `fromByte` 음수 또는 파일 크기 초과, 두 파라미터 동시 지정, 숫자 형식 오류 |
| 400 | `{"code":"INVALID_PATH","message":"경로 값 형식이 잘못됐습니다: id"}` | `id` 가 숫자가 아님 |
| 401 | `{"error":"missing or invalid X-API-Key"}` | 키 누락 또는 불일치 |
| 404 | `{"code":"EXECUTION_NOT_FOUND","message":"없는 실행입니다: 4521"}` | 실행이 없음 |
| 404 | `{"code":"LOG_NOT_FOUND","message":"로그 없음 — 로그 기능 도입 전 실행이거나 보존기간이 지나 정리됐습니다(실행 #4521)"}` | 로그 파일이 없음 |
| 500 | `{"code":"LOG_READ_FAILED","message":"로그 파일을 읽지 못했습니다(실행 #4521): …"}` | 파일 읽기 실패 |
| 503 | `{"error":"batch API key not configured (BATCH_API_KEY missing)"}` | 인증 활성 상태에서 서버 키가 비어 있음 |

## 14. POST /api/batch/ops/executions/{id}/abandon

### API 개요
배치 앱 내부 API로, 이 프로세스에서 돌고 있지 않은 실행과 실행 중 상태의 Step을 `FAILED` 로 갱신한다. 백엔드 7번 API가 호출한다.

`X-API-Key` 인증은 local 기본 꺼짐, dev 기본 켜짐, prod 항상 켜짐이다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `X-API-Key` | string | 인증 활성 시 Y | - | `BATCH_API_KEY` 와 같은 값 |
| path | `id` | long | Y | - | 실행 ID |
| body | `excutrId` | string | N | `unknown` | 요청자 ID. 최대 20자. 종료 메시지에 들어간다 |

### 호출 예시
```bash
curl -X POST 'http://localhost:8280/api/batch/ops/executions/4521/abandon' \
  -H 'X-API-Key: {BATCH_API_KEY}' \
  -H 'Content-Type: application/json' \
  -d '{"excutrId":"user01"}'
```

### 응답
`200 OK`, `application/json`. 필드는 7번 응답과 같다.

```json
{
  "jobExecutionId": 4521,
  "jobNm": "complaintFetchJob",
  "status": "FAILED",
  "exitMessage": "운영 화면에서 실패 처리 — user01"
}
```

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"code":"INVALID_PATH","message":"경로 값 형식이 잘못됐습니다: id"}` | `id` 가 숫자가 아님 |
| 400 | `{"code":"INVALID_EXCUTRID","message":"excutrId 는 20자 이하여야 합니다"}` | `excutrId` 20자 초과 |
| 400 | `{"code":"INVALID_BODY","message":"요청 본문을 읽을 수 없습니다(JSON 형식 확인)"}` | 본문 JSON 형식 오류 |
| 401 | `{"error":"missing or invalid X-API-Key"}` | 키 누락 또는 불일치 |
| 404 | `{"code":"EXECUTION_NOT_FOUND","message":"없는 실행입니다: 4521"}` | 실행이 없음 |
| 409 | `{"code":"STILL_RUNNING","message":"…","jobExecutionId":4521,"status":"STARTED"}` | 이 프로세스에서 실제로 실행 중 |
| 409 | `{"code":"NOT_RUNNING","message":"…","jobExecutionId":4521,"status":"COMPLETED"}` | 이미 끝난 실행 |
| 409 | `{"code":"CONCURRENT_UPDATE","message":"같은 실행이 동시에 갱신됐습니다 — 상태를 다시 조회한 뒤 시도하세요"}` | 같은 실행 행을 동시에 갱신 |
| 503 | `{"error":"batch API key not configured (BATCH_API_KEY missing)"}` | 인증 활성 상태에서 서버 키가 비어 있음 |

## 15. GET /api/batch/ops/executions/{id}/live

### API 개요
배치 앱 내부 API로, 실행 중인 잡의 단계별 수치를 메모리에서 읽어 돌려준다. 현재 `complaintFetchJob` 만 지원하며, 백엔드 3번 API가 `progress.live` 를 채울 때 호출한다.

`X-API-Key` 인증은 local 기본 꺼짐, dev 기본 켜짐, prod 항상 켜짐이다.

### 파라미터
| 위치 | 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- | --- |
| header | `X-API-Key` | string | 인증 활성 시 Y | - | `BATCH_API_KEY` 와 같은 값 |
| path | `id` | long | Y | - | 실행 ID |

### 호출 예시
```bash
curl 'http://localhost:8280/api/batch/ops/executions/4521/live' -H 'X-API-Key: {BATCH_API_KEY}'
```

### 응답
`200 OK`, `application/json`

```json
{
  "jobExecutionId": 4521,
  "metrics": {
    "dayIdx": 1,
    "dayCnt": 1,
    "crntYmd": "2026-11-17",
    "fetchCnt": 1000,
    "prcsCnt": 520,
    "geocodedCnt": 490,
    "unclsfCnt": 20,
    "filteredTransient": 0,
    "geocodeCacheHits": 400,
    "geocodeApiLookups": 90,
    "skipGeocodeFailed": 2,
    "skipEmdUnresolved": 0
  }
}
```

| 필드 | 타입 | Null | 설명 |
| --- | --- | --- | --- |
| `jobExecutionId` | number | N | 실행 ID |
| `metrics.dayIdx` | number | N | 현재 읽는 구간 순번 |
| `metrics.dayCnt` | number | N | 전체 구간 수 |
| `metrics.crntYmd` | string | Y | 현재 읽는 일자(`yyyy-MM-dd`) |
| `metrics.fetchCnt` | number | N | 원천에서 받은 행 수 |
| `metrics.prcsCnt` | number | N | 가공 처리 행 수 |
| `metrics.geocodedCnt` | number | N | 좌표·행정동 확보 행 수 |
| `metrics.unclsfCnt` | number | N | 미분류 행 수 |
| `metrics.filteredTransient` | number | N | 일시 장애로 제외한 행 수 |
| `metrics.geocodeCacheHits` | number | N | 지오코딩 캐시 히트 수 |
| `metrics.geocodeApiLookups` | number | N | 지오코딩 API 호출 수 |
| `metrics.skipGeocodeFailed` | number | N | 지오코딩 실패 스킵 수 |
| `metrics.skipEmdUnresolved` | number | N | 행정동 미해석 스킵 수 |

### 예외
| 상태 | 본문 | 발생 조건 |
| --- | --- | --- |
| 400 | `{"code":"INVALID_PATH","message":"경로 값 형식이 잘못됐습니다: id"}` | `id` 가 숫자가 아님 |
| 401 | `{"error":"missing or invalid X-API-Key"}` | 키 누락 또는 불일치 |
| 404 | `{"code":"LIVE_NOT_AVAILABLE","message":"실행 중 단계별 수치가 없습니다(실행 #4521)"}` | 지원하지 않는 잡이거나 이미 끝난 실행 |
| 503 | `{"error":"batch API key not configured (BATCH_API_KEY missing)"}` | 인증 활성 상태에서 서버 키가 비어 있음 |
