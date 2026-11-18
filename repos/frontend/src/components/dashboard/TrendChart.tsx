import { useEffect, useRef } from 'react'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { TYPE_META, TYPE_ORDER, readSeriesColor } from './complaintTypes'
import type { TrendPoint, TrendResponse } from '../../api/dashboard'

/*
 * 민원 발생 추이 카드 (TSK-4059).
 *
 * 시간대 버킷(HOUR)은 x축 24칸이며 구간 내 같은 시각끼리 합산된 값이다 — 07시 칸 =
 * (1일 07시 + 2일 07시 + …). 따라서 "언제 늘고 줄었나"가 아니라 "몇 시에 몰리나"를 보는 그래프다(D6).
 * 기간을 직접 지정하면 서버가 DAY 로 내려주고 그때는 날짜순 시계열이 된다(D7).
 *
 * ECharts 는 트리셰이킹 임포트로만 쓴다(전체 번들 금지). React 래퍼는 두지 않는다 — 차트가 하나뿐이라
 * 래퍼 의존성이 주는 이득보다 버전 호환을 따로 챙기는 비용이 크다(D2).
 */
// 🔴 트리셰이킹 임포트는 여기 등록한 계열만 그릴 수 있다 — `type: 'line'` 을 쓰면서 `LineChart`
//    를 빠뜨리면 tsc·lint·build 는 전부 통과하고 **브라우저에서만** "Series line is used but not
//    imported" 로 죽는다. series.type 을 바꾸면 이 줄도 같이 바꾼다.
echarts.use([LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

/** 미수신일 때 쓰는 고정 빈 배열. 렌더마다 `[]` 를 새로 만들면 useEffect 가 매번 다시 돈다. */
const NO_POINTS: TrendPoint[] = []

interface TrendChartProps {
  /** /api/dashboard/trend 응답. null 이면 미수신(로딩) 또는 실패. */
  data: TrendResponse | null
  error: boolean
  /** 카드 헤더 표기용 — '어제' | '주간' | '월간' | '2025-11-01 ~ 2025-12-31'. */
  periodLabel: string
  /** 카드 헤더 표기용 — '전체 유형' 또는 선택된 민원유형 라벨. */
  typeLabel: string
}

export default function TrendChart({ data, error, periodLabel, typeLabel }: TrendChartProps) {
  const boxRef = useRef<HTMLDivElement>(null)

  const points = data?.points ?? NO_POINTS
  const grand = points.reduce((s, p) => s + p.total, 0)
  const hasData = !error && data != null && grand > 0

  useEffect(() => {
    // `data` 를 함께 검사한다 — `hasData` 는 boolean 이라 TS 가 data 의 null 을 좁혀 주지 않는다.
    if (!data || !hasData || !boxRef.current) return

    const labels = points.map((p) =>
      data.bucket === 'HOUR' ? `${p.bucketKey}시` : p.bucketKey.slice(5).replace('-', '/'),
    )

    const chart = echarts.init(boxRef.current)
    chart.setOption({
      // ⚠️ `containLabel` 은 **축 라벨만** 감싼다 — legend 공간은 자동 확보되지 않는다.
      //    `legend.bottom: 0` 이 차지하는 높이(아이콘 8px + 12px 라벨 ≈ 24px)를 grid.bottom 으로
      //    직접 비워 두지 않으면 x축 라벨("00시"…)과 범례가 겹친다.
      grid: { left: 4, right: 8, top: 16, bottom: 36, containLabel: true },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'line' },   // 막대가 아니라 선이라 그림자 대신 세로 기준선
        // 0 인 계열은 툴팁에서 숨겨 24칸 × 5종 목록이 화면을 덮는 것을 막는다.
        formatter: (ps: { name: string; seriesName: string; value: number; marker: string }[]) => {
          const rows = ps.filter((p) => p.value > 0)
          const sum = rows.reduce((s, p) => s + p.value, 0)
          if (rows.length === 0) return `${ps[0]?.name ?? ''}<br/>0건`
          return [
            `<b>${rows[0].name}</b> 합계 ${sum}건`,
            ...rows.map((p) => `${p.marker} ${p.seriesName} <b>${p.value}건</b>`),
          ].join('<br/>')
        },
      },
      legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8, itemGap: 14 },
      xAxis: {
        type: 'category',
        data: labels,
        axisTick: { show: false },
        // 24칸/30칸이 겹치지 않게 ECharts 가 알아서 솎아내게 둔다.
        axisLabel: { hideOverlap: true },
      },
      yAxis: { type: 'value', splitLine: { lineStyle: { type: 'dashed' } } },
      // 유형별 독립 꺾은선 5개 — 누적(stack)하지 않는다. 누적하면 각 선이 "그 유형의 건수"가
      // 아니라 "그 유형까지의 합"이 돼 y값을 눈으로 읽을 수 없다.
      series: TYPE_ORDER.map((code) => {
        const color = readSeriesColor(TYPE_META[code].token, TYPE_META[code].fallback)
        return {
          name: TYPE_META[code].label,
          type: 'line',
          symbol: 'circle',
          symbolSize: 5,
          // line 계열은 선(lineStyle)과 꼭짓점(itemStyle)의 색이 따로 논다 — 둘 다 지정한다.
          lineStyle: { width: 2, color },
          itemStyle: { color },
          data: points.map((p) => p.breakdown.find((b) => b.typeCd === code)?.count ?? 0),
        }
      }),
    })

    // 카드가 그리드(1.62fr)라 창 크기에 따라 폭이 변한다 — 컨테이너 관찰로 리사이즈한다.
    const ro = new ResizeObserver(() => chart.resize())
    ro.observe(boxRef.current)

    return () => {
      ro.disconnect()
      chart.dispose()
    }
    // 데이터가 바뀌면 옵션을 갱신하는 대신 다시 만든다 — 24칸 × 5계열이라 비용이 무시할 만하고,
    // setOption 병합 규칙(계열 수가 줄 때 잔상)을 신경 쓰지 않아도 된다.
  }, [data, hasData, points])

  return (
    <div className="card trend">
      <div className="card-head">
        <div>
          <h2 className="card-head__title">
            민원 발생 추이{' '}
            <span className="card-head__note">
              {/* 🔄 TSK-4169 — 인자 순서를 시안에 맞춘다: (기간 · 버킷 · 유형). */}
              ({periodLabel}
              {data ? (data.bucket === 'HOUR' ? ' · 시간대별' : ' · 일자별') : ''} · {typeLabel})
            </span>
          </h2>
          {/* 🔄 TSK-4169 — 부제는 버킷과 무관하게 한 줄 고정이다(시안).
              ⚠️ 종전의 `00시 집계` 안내(`midnightHeavy`)는 **요구로 걷었다**(2026-10-01) —
              원천 발생일시가 연동돼 더는 자정에 몰리지 않는다. 되살릴 일이 생기면 git log 를 본다. */}
          <p className="card-head__sub">기간별 민원 건수 및 유형별 분포</p>
        </div>
      </div>

      {error ? (
        <div className="trend__chart panel-state">추이를 불러오지 못했습니다.</div>
      ) : data == null ? (
        <div className="trend__chart panel-state">불러오는 중…</div>
      ) : !hasData ? (
        <div className="trend__chart panel-state">표시할 데이터가 없습니다.</div>
      ) : (
        <div className="trend__chart" ref={boxRef} />
      )}
    </div>
  )
}
