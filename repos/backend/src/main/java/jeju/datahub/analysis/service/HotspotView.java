package jeju.datahub.analysis.service;

import jeju.datahub.analysis.service.impl.HotspotRankingServiceImpl;

/**
 * 분석레이어 view enum.
 *
 * <ul>
 *   <li>{@link #MOST_COMPLAINT} — 민원다발 (parking.tb_cvlcpt 직접 집계, COUNT(*) DESC) — 기본값
 *       <br>2026-09-04 갱신: 데이터 소스를 TB_HTSPT_ANLS_RSLT (view 부재) → tb_cvlcpt 로 변경.
 *       <br>🔁 2026-10-02: 종전 "중복 적재분을 1건으로" (DISTINCT cvlcpt_cn) 기준은 폐기됐다 —
 *       같은 문구가 중복이 아니라 정형화된 신고 문구였다. 근거는
 *       {@code mapper/complaint/ComplaintMapper.xml} 상단 「민원 집계 기준」 주석.</li>
 *   <li>{@link #ENFORCEMENT} — 집중단속 (parking.tb_crdn_dsctn 직접 집계)
 *       <br>2026-09-03 갱신: 데이터 소스를 TB_HTSPT_ANLS_RSLT → tb_crdn_dsctn 로 변경.</li>
 *   <li>{@link #SHORTAGE} — 수요 부족 (parking.fn_jenks_shortage DB 즉시 계산, k=3 Jenks "심각" 등급)
 *       <br>2026-09-04 갱신 (복원): 사용자 명시 "폐기" 가 아니라 "옛 정의를 적용하지 않는다" 였음.
 *       Jenks Natural Breaks 통계적 기법을 DB 에서 즉시 계산 — §3 KPI #4 shortageCount 와 동일 베이스.
 *       queryValue = "shortage", 화면 라벨은 "수요 부족 지역" 그대로 유지.</li>
 * </ul>
 *
 * <p>기존 군집 분석 ({@link HotspotAnalysisService}) 과 다른 화면용. 이 enum 은 <b>§6 GIS 지도</b>의
 * 분석레이어 polygon/marker 를 위한 순위 조회 전용이며 가중치·등급·DBSCAN 군집등 분석 화면
 * (PR-PAR-003-02) 의 응답을 <b>갱신하지 않는다</b>. SQL 인젝션 방어: enum 만 받고 SQL 에서 분기.
 */
public enum HotspotView {

    MOST_COMPLAINT("most-complaint"),
    ENFORCEMENT("enforcement"),
    SHORTAGE("shortage");

    /** API query string 에 쓰는 값 (kebab-case) */
    private final String queryValue;

    HotspotView(String queryValue) {
        this.queryValue = queryValue;
    }

    public String queryValue() {
        return queryValue;
    }

    /**
     * query string 의 kebab-case 값 → enum 변환. null/blank 면 default {@link #MOST_COMPLAINT}.
     * 화이트리스트 밖 값이면 {@link IllegalArgumentException} — Spring 의 400 으로 매핑됨
     * (호출부 {@link HotspotRankingServiceImpl#rank} 에서 catch).
     *
     * <p>Spring 의 기본 enum 바인딩은 {@code MOST_COMPLAINT} 같은 enum 상수명 그대로만
     * 받아서, API 컨벤션의 {@code most-complaint} 와 어긋난다. 그래서 request 의 view 는
     * String 으로 받고 여기서 enum 으로 변환한다 (직전 라운드의 unit 처리 패턴과 동일).
     */
    public static HotspotView fromQueryValue(String value) {
        if (value == null || value.isBlank()) {
            return MOST_COMPLAINT;
        }
        for (HotspotView v : values()) {
            if (v.queryValue.equals(value)) {
                return v;
            }
        }
        throw new IllegalArgumentException(
                "view 는 most-complaint/enforcement/shortage 중 하나여야 합니다: " + value);
    }
}
