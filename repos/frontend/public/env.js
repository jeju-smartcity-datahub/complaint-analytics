// 런타임 환경값 — 🔴 컨테이너는 시작할 때 이 파일을 docker-entrypoint.sh 가 환경변수로 덮어쓴다.
// 로컬 개발(vite dev)은 이 빈 값이 그대로 쓰여 빌드 시점 값(import.meta.env · .env)으로 폴백한다.
window.__APP_ENV__ = {};
