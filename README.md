# complaint-analytics

제주 데이터허브의 **주차 민원 분석 서비스**입니다. 민원·단속·공영주차장 데이터를 수집해 불법 주차 집중 구역을 분석하고, 정책 효과를 시뮬레이션하며, 분석 결과를 보고서와 위험단계 알림으로 제공합니다.

## 주요 기능

| 분류 | 기능 |
| --- | --- |
| 현황 | 대시보드, 민원 현황 |
| 분석·시뮬레이션 | 불법 주차 집중 구역 분석, 정책 효과 시뮬레이션, 생활권 시뮬레이션, 구역 추천, 투자우선순위 보드 |
| 보고서·알림 | 보고서 목록·관리, 위험단계 알림 조회·관리 |
| AI 어시스턴트 | 외부 에이전트 API와 연동한 대화형 분석 지원 |
| 설정 | 메뉴 권한 관리, 분석 지표 가중치 설정, 배치 운영 |

## 구성

```
브라우저 ─▶ nginx ─┬─ /      ─▶ frontend (React 정적 파일)
                   └─ /api/  ─▶ backend (Spring Boot) ─▶ PostgreSQL + PostGIS
                                   ▲                        ▲
                                   └────── batch ───────────┘
                                     (Spring Batch, 외부 원천 수집)
```

| 디렉터리 | 역할 | 기술 스택 |
| --- | --- | --- |
| [`repos/frontend/`](repos/frontend/) | 웹 화면 | React 18, Vite 5, TypeScript |
| [`repos/backend/`](repos/backend/) | 업무 API, 인증 | Java 17, Spring Boot 3.5, 전자정부표준프레임워크 5.0, MyBatis, JPA |
| [`repos/batch/`](repos/batch/) | 데이터 수집·가공, 스케줄 실행 | Java 17, Spring Boot 3.5, Spring Batch 5 |
| [`database/`](database/) | DB 실행 환경, DDL, 기준데이터 | PostgreSQL 15, PostGIS 3.4 |
| [`nginx/`](nginx/) | 리버스 프록시 설정 | nginx |
| [`docs/`](docs/) | 설치 가이드, API 명세 | |
| [`sbom/`](sbom/) | 소프트웨어 구성 명세 | CycloneDX 1.6 |

## 빠른 시작

Docker(Compose v2)와 OIDC 클라이언트 자격증명이 필요합니다.

**1. DB 실행** — 첫 기동 때 스키마와 기준데이터가 자동으로 적재됩니다.

```bash
cd database
cp .env.example .env
docker compose up -d
cd ..
```

**2. 환경변수 설정**

```bash
cp .env.example .env
```

`.env`에서 아래 4개 값을 채웁니다.

| 변수 | 값 |
| --- | --- |
| `OIDC_CLIENT_ID` | IdP에서 발급받은 client-id |
| `OIDC_CLIENT_SECRET` | IdP에서 발급받은 client-secret |
| `INTERNAL_API_KEY` | 임의 문자열 (예: `openssl rand -hex 32`) |
| `BATCH_API_KEY` | 임의 문자열 (예: `openssl rand -hex 32`) |

**3. 애플리케이션 실행**

```bash
docker compose up -d --build
docker compose ps
```

브라우저에서 <http://localhost:8080> 을 엽니다. IdP에는 이 주소가 redirect URI로 등록되어 있어야 합니다.

소스에서 직접 실행하는 방법, 전체 환경변수, 문제 해결은 [설치 가이드](docs/설치가이드.md)를 참고하세요.

## 실행 전 알아 둘 점

- **로그인에 OIDC IdP가 필요합니다.** `OIDC_CLIENT_ID`가 비어 있으면 백엔드가 기동하지 않습니다.
- **기준데이터만 포함되어 있습니다.** 코드, 권한·메뉴, 행정구역, 격자, 배치 스케줄은 자동으로 적재되지만 민원·단속 같은 업무 데이터는 없어서 처음에는 대시보드와 분석 화면이 비어 있습니다.
- **업무 데이터 수집에는 외부 자격증명이 필요합니다.** 배치 잡을 실행하려면 D-Hub 계정과 VWorld API 키가 필요합니다.
- **배치 스케줄은 기본으로 꺼져 있습니다.** 자동 실행하려면 `.env`에서 `BATCH_SCHEDULED=true`로 설정합니다.

## 문서

- [설치 가이드](docs/설치가이드.md)
- [API 명세](docs/API명세/index.md)
- 실행 중인 백엔드의 Swagger UI: 소스 실행 시 <http://localhost:8180/swagger-ui.html>

## 라이선스

이 프로젝트는 [Apache License 2.0](LICENSE)으로 배포됩니다.

포함된 오픈소스 구성요소의 저작권과 라이선스는 [NOTICE](NOTICE)에 있습니다.

## SBOM

[`sbom/`](sbom/) 디렉터리에 [CycloneDX](https://cyclonedx.org/) 1.6 JSON 형식의 구성 명세가 있습니다. 실행 JAR과 `package-lock.json`의 운영 의존성 기준이며, 테스트·개발 전용 의존성과 컨테이너 베이스 이미지는 포함하지 않습니다.

| 파일 | 범위 | 구성요소 수 |
| --- | --- | --- |
| [`complaint-analytics.cdx.json`](sbom/complaint-analytics.cdx.json) | 전체 통합 (중복 제거) | 470 |
| [`backend.cdx.json`](sbom/backend.cdx.json) | 백엔드 | 119 |
| [`batch.cdx.json`](sbom/batch.cdx.json) | 배치 | 48 |
| [`frontend.cdx.json`](sbom/frontend.cdx.json) | 프론트엔드 | 348 |
