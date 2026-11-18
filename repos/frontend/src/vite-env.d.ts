/// <reference types="vite/client" />

/**
 * Vite 가 빌드 시점에 번들로 인라인하는 환경변수 선언 (TSK-4032 Step 8).
 * 값은 로컬 `.env`(키 이름은 `.env.example` 참고), dev 이미지는 Dockerfile 의 build ARG 로 들어온다.
 */
interface ImportMetaEnv {
  /** VWorld 배경지도(WMTS) 키. 비어 있으면 지도 대신 안내 문구를 깐다. */
  readonly VITE_VWORLD_API_KEY: string
  /** VWorld 타일 호스트(프록시 경유 시). 비어 있으면 `https://api.vworld.kr`. */
  readonly VITE_VWORLD_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

/** 런타임 환경값 (TSK-4349) — `/env.js` 가 채운다. 비면 빌드 시점 값으로 폴백한다(`mapTiles.ts`). */
interface AppRuntimeEnv {
  VITE_VWORLD_API_KEY?: string
  VITE_VWORLD_BASE_URL?: string
}

interface Window {
  __APP_ENV__?: AppRuntimeEnv
}
