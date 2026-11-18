#!/bin/sh
# 런타임 환경값 주입 — 컨테이너 시작마다 dist/env.js 를 환경변수로 다시 쓴다.
# 🔴 비어 있는 값은 쓰지 않는다 → 프론트가 빌드 시점 값(import.meta.env)으로 폴백한다.
# 🔴 공개 값만 넣는다 — 이 파일은 브라우저로 그대로 내려간다(비밀·토큰 금지).
set -e
node -e '
const keys = ["VITE_VWORLD_API_KEY", "VITE_VWORLD_BASE_URL"];
const env = {};
for (const k of keys) {
  const v = (process.env[k] || "").trim();
  if (v) env[k] = v;
}
require("fs").writeFileSync("/app/dist/env.js", "window.__APP_ENV__ = " + JSON.stringify(env) + ";\n");
console.log("[env.js] runtime keys=" + JSON.stringify(Object.keys(env)));
'
exec "$@"
