# =============================================================================
# 제주 데이터허브 프론트 (React 18 + Vite 5 + TypeScript)
#
# 2단 빌드: vite 빌드 산출 dist/ 만 런타임 이미지로 옮겨 serve 로 정적 서빙한다(3000).
# nginx 는 이미지에 넣지 않는다 — compose 의 별도 nginx 서비스가 / 는 이 컨테이너로,
# /api 는 backend 컨테이너로 리버스 프록시한다.
# =============================================================================
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# VWorld 배경지도 키 — 빌드 시점 기본값. 🔁 TSK-4349 부터 실행 시 환경변수가 있으면 그 값이 우선한다(docker-entrypoint.sh).
# 루트 docker-compose.yml 의 frontend build args 로 ${DEV_VWORLD_MAP_KEY} 가 들어온다.
ARG VITE_VWORLD_API_KEY=
ENV VITE_VWORLD_API_KEY=$VITE_VWORLD_API_KEY
# VWorld 타일 호스트(프록시 경유 시) — ${DEV_VWORLD_MAP_BASE_URL}. 비면 공식 호스트.
ARG VITE_VWORLD_BASE_URL=
ENV VITE_VWORLD_BASE_URL=$VITE_VWORLD_BASE_URL
# tsc -b (타입체크) + vite build → dist/
RUN npm run build

FROM node:20-alpine
WORKDIR /app
RUN npm install -g serve@14.2.4
COPY --from=build /app/dist ./dist
# 🔁 TSK-4349 — 시작할 때 환경변수(VITE_VWORLD_*)로 dist/env.js 를 다시 쓴다 → compose .env 만 바꿔도 반영된다.
#    비어 있으면 빌드 ARG 값으로 폴백한다(아래 ARG 는 그대로 둔다 — 하위 호환).
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh
EXPOSE 3000
# -s: SPA 폴백 — react-router 경로를 직접 치거나 새로고침해도 index.html 을 준다.
ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["serve", "-s", "dist", "-l", "3000"]
