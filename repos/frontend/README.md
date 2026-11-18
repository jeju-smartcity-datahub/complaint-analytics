# jeju-datahub-front

제주 스마트시티 데이터 허브 프론트엔드. **React 18.3 + Vite + TypeScript** 기반이다.

## 기술 스택

| 항목 | 버전 |
| --- | --- |
| React | 18.2.0 |
| 빌드 도구 | Vite 5 |
| 언어 | TypeScript 5 |
| HTTP | axios |
| 라우팅 | react-router-dom 6 |

## 실행

```bash
npm install
npm run dev      # http://localhost:5173
```

`/api` 요청은 Vite 프록시를 통해 백엔드(eGovFrame, http://localhost:8080)로 전달된다.
백엔드(jeju-datahub-back)를 먼저 실행해야 샘플 목록이 정상 표시된다.

## 빌드

```bash
npm run build    # 타입체크(tsc -b) + vite build → dist/
npm run preview  # 빌드 결과 미리보기
```

## 구조

```
src/
├─ main.tsx          # 진입점
├─ App.tsx           # 루트 컴포넌트 (샘플 API 연동 예시)
├─ index.css         # 전역 스타일
└─ api/client.ts     # 백엔드 API 클라이언트 (axios)
```
