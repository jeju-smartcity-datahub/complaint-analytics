import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // 백엔드(eGovFrame) API 프록시. /api 요청을 로컬 백엔드로 전달한다.
    // local.sh 로 띄우는 로컬 백엔드는 8180 포트다.
    proxy: {
      '/api': {
        target: 'http://localhost:8180',
        changeOrigin: true,
        xfwd: true,            // 🔴 추가 — X-Forwarded-For/Host/Proto 를 실어 보낸다
      },
    },
  },
  build: {
    rollupOptions: {
      output: {
        /*
         * ECharts 를 앱 코드와 분리한다 (TSK-4059).
         *
         * 기본 설정이면 ECharts(약 500KB)가 메인 청크에 통째로 섞여, 앱 코드를 한 줄만 고쳐도
         * 파일 해시가 바뀌어 사용자가 안 바뀐 라이브러리까지 매번 다시 받는다. 별도 청크로 빼면
         * 그 파일은 ECharts 버전이 바뀔 때만 갱신되므로 캐시가 살아남는다.
         *
         * 같은 번들의 jspdf·html-docx·html2canvas 는 보고서 내보내기가 동적 import 를 쓰고 있어
         * Vite 가 이미 자동으로 갈라 놓았다 — ECharts 만 정적 import 라 여기서 명시한다.
         *
         * ⚠️ 초기 다운로드 총량이 줄지는 않는다(대시보드가 첫 화면이라 결국 받는다).
         *    그건 TrendChart 를 React.lazy 로 감싸야 해결되며, 로딩 UI 설계가 얽혀 별건으로 둔다.
         */
        manualChunks: {
          echarts: ['echarts/core', 'echarts/charts', 'echarts/components', 'echarts/renderers'],
        },
      },
    },
  },
})
