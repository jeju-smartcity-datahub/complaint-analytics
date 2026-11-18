import { useEffect, useRef } from 'react'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

/*
 * 정책 효과 시뮬레이션 — 정책 전·후 민원 추이 차트 (TSK-4037 Step 7-1 · 명세 2.2·7.3).
 *
 * 시안 `$Ce`(정책 전 점선 · 정책 후 실선+영역, smooth 0.4, 범례 우상단)를 옮겼다. 시안과 다른 점:
 *   - 🔴 **가로축 = 예측 기간**(명세 7.3) — 시안은 6개월 고정 목업이다. `현재`(t=0) ~ `{predcMm}개월`.
 *   - 정책 전 `base(t)` 는 기준선 `C0`(월평균 민원) **수평선**이다(시안의 들쭉날쭉한 목업 값이 아니다).
 *   - 정책 후 `pol(t) = base(t) × (1 + R × min(1, t ÷ lag))` — 효과 발현 시차 lag(확충·이면도로 3개월 — `lagMm`)를 반영한다.
 *     🔴 `R` 은 **비율**이다 — 응답 `cvlcptRdctRt` 는 퍼센트라 `÷ 100` 해서 받는다(부모가 아니라 여기서 한다).
 *
 * ECharts 는 트리셰이킹 임포트만 쓴다(`TrendChart` 와 같은 관례).
 * 🔴 `type: 'line'` 을 쓰므로 `LineChart` 등록을 빼면 브라우저에서만 죽는다.
 */
echarts.use([LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

/** lag 를 못 받았을 때의 기본값 — 주차장 확충(명세 7.3). 유형별 값은 `policySimView.LAG_MM` 이 정본이다. */
const DEFAULT_LAG_MM = 3

interface EffectChartProps {
  /** 기준 월평균 민원 C0(건/월). */
  crtrCvlcptCnt: number
  /** 민원 감소율(**%** — 응답 그대로). null 이면 정책 후 선을 그리지 않는다. */
  cvlcptRdctRt: number | null
  /** 예측 기간(개월) = 가로축 길이. */
  predcMm: number
  /** 효과 발현 시차(개월) — 정책 유형별(`LAG_MM`: 확충 3 · 이면도로 3 · 요금제 1). */
  lagMm?: number
}

/** CSS 변수 → 실제 색 문자열(캔버스는 `var()` 를 못 읽는다). 시안 `d9` 와 같은 방식. */
function readCssColor(token: string, fallback: string): string {
  const probe = document.createElement('span')
  probe.style.cssText = 'position:absolute;left:-9999px;top:-9999px'
  probe.style.color = `var(${token})`
  document.body.appendChild(probe)
  const c = getComputedStyle(probe).color
  probe.remove()
  return c && c !== 'rgba(0, 0, 0, 0)' ? c : fallback
}

/** `rgb(r, g, b)` → `rgba(r, g, b, a)` (영역 그라데이션용). */
function withAlpha(rgb: string, a: number): string {
  const m = rgb.match(/rgba?\(([^)]+)\)/)
  if (!m) return rgb
  return `rgba(${m[1].split(',').slice(0, 3).join(',')},${a})`
}

const round1 = (v: number) => Math.round(v * 10) / 10

export default function EffectChart({
  crtrCvlcptCnt,
  cvlcptRdctRt,
  predcMm,
  lagMm = DEFAULT_LAG_MM,
}: EffectChartProps) {
  const boxRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const box = boxRef.current
    if (!box) return

    const ts = Array.from({ length: predcMm + 1 }, (_, t) => t)
    const labels = ts.map((t) => (t === 0 ? '현재' : `${t}개월`))
    const base = ts.map(() => round1(crtrCvlcptCnt))
    const r = cvlcptRdctRt === null ? null : cvlcptRdctRt / 100 // 🔴 퍼센트 → 비율
    const pol =
      r === null ? [] : ts.map((t) => round1(crtrCvlcptCnt * (1 + r * Math.min(1, t / lagMm))))

    const font = getComputedStyle(document.documentElement).getPropertyValue('--font-body').trim()
    const axisText = readCssColor('--text-assistive', '#8b8d92')
    const line = readCssColor('--line-alternative', '#e5e7eb')
    const before = readCssColor('--cool-neutral-60', '#94a3b8')
    const after = readCssColor('--primary', '#0066ff')

    const chart = echarts.init(box)
    chart.setOption({
      color: [before, after],
      textStyle: { fontFamily: font },
      grid: { left: 8, right: 16, top: 36, bottom: 8, containLabel: true },
      legend: {
        top: 0,
        right: 0,
        itemWidth: 10,
        itemHeight: 10,
        icon: 'roundRect',
        textStyle: { color: axisText, fontFamily: font, fontSize: 12 },
      },
      tooltip: {
        trigger: 'axis',
        backgroundColor: readCssColor('--cool-neutral-15', '#1b1c1e'),
        borderWidth: 0,
        padding: [10, 14],
        textStyle: { color: '#fff', fontFamily: font, fontSize: 13 },
        axisPointer: { type: 'line', lineStyle: { color: line, width: 1 } },
        valueFormatter: (v: number) => `${v.toLocaleString('ko-KR')}건/월`,
      },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: labels,
        axisLine: { lineStyle: { color: line } },
        axisTick: { show: false },
        // 24개월이면 25칸 — 겹치는 라벨은 ECharts 가 솎는다.
        axisLabel: {
          color: axisText,
          fontSize: 12,
          fontFamily: font,
          margin: 12,
          hideOverlap: true,
        },
      },
      yAxis: {
        type: 'value',
        min: 0,
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { lineStyle: { color: line, type: 'solid' } },
        axisLabel: { color: axisText, fontSize: 12, fontFamily: font },
      },
      series: [
        {
          name: '정책 전',
          type: 'line',
          smooth: 0.4,
          symbol: 'circle',
          symbolSize: 6,
          showSymbol: false,
          lineStyle: { width: 2.5, color: before, type: 'dashed' },
          itemStyle: { color: before, borderColor: '#fff', borderWidth: 2 },
          emphasis: { focus: 'series' },
          data: base,
          z: 2,
        },
        {
          name: '정책 후',
          type: 'line',
          smooth: 0.4,
          symbol: 'circle',
          symbolSize: 7,
          showSymbol: false,
          lineStyle: { width: 2.5, color: after },
          itemStyle: { color: after, borderColor: '#fff', borderWidth: 2 },
          emphasis: { focus: 'series' },
          data: pol,
          z: 3,
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: withAlpha(after, 0.22) },
              { offset: 1, color: withAlpha(after, 0) },
            ]),
          },
        },
      ],
    })

    // 2열 그리드 카드라 창 폭에 따라 변한다 — 컨테이너 관찰로 리사이즈한다.
    const ro = new ResizeObserver(() => chart.resize())
    ro.observe(box)
    return () => {
      ro.disconnect()
      chart.dispose()
    }
  }, [crtrCvlcptCnt, cvlcptRdctRt, predcMm, lagMm])

  return <div ref={boxRef} style={{ width: '100%', height: '100%', minHeight: 180 }} />
}
