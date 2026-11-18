package jeju.datahub.complaint.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 민원현황 보고서 응답 루트. (POST /api/complaints/reports 201 · GET 단건 스냅샷 — TSK-4164)
 *
 * <p><b>저장된 스냅샷을 그대로 돌려준다</b> — 프론트는 이 응답 하나로 문서를 그린다.
 * 그래서 화면이 다시 조립하지 않도록 서버 산출 헤더 4칸({@link #rptpNm}·{@link #regionNm}·
 * {@link #anlsPrdExpln}·{@link #crtYmd})을 값으로 내린다 — 지역 3단 규칙·기간 표기가 화면에
 * 복제되면 서버와 조용히 갈린다({@code ComplaintReportNaming} 이 그 규칙의 유일한 출처다 — D8).
 *
 * <p>🔴 {@link #kpi} 는 <b>이 클래스 안의 중첩 record {@link Kpi}</b> 다 — {@link ComplaintKpiVO} 를
 * 재사용할 수 <b>없다</b>. Step 1 이후 10컴포넌트({@code lastSyncDt}·{@code emdCompare}·
 * {@code negEmdCompare}·{@code bgngYmd}·{@code endYmd} 포함)가 되어, {@code rslt_json} 에 담지
 * 않기로 한 값({@code lastSyncDt} — D9)과 배열 2개까지 kpi 안에 중복으로 끌고 들어온다.
 *
 * <p>{@link #emdCompare}·{@link #negEmdCompare} 의 원소 타입은 <b>타입을 새로 만들지 않고</b>
 * 조회 응답의 것({@link EmdCompareVO}·{@link NegEmdCompareVO})을 그대로 싣는다 —
 * {@code HotspotReportVO} 가 {@code AnalysisWeightVO}·{@code HotspotZoneVO} 를 재사용하는 것과 같다.
 * 보고서 전용 타입을 따로 두면 조회 화면과 보고서 화면이 같은 값을 서로 다른 모양으로 받게 된다.
 *
 * <p>{@code rslt_json} 의 키는 조회 응답 필드명 그대로다(D9) — 조회할 때 변환이 없다.
 */
@Schema(description = "민원현황 보고서")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ComplaintReportVO {

    /** 보고서아이디 (rptp_id — TB_RPTP PK) */
    @Schema(description = "보고서아이디", example = "100")
    private Long rptpId;

    /** 실행아이디 (excn_id — TB_ANLS_EXCN PK) */
    @Schema(description = "분석실행아이디", example = "100")
    private Long excnId;

    /** 보고서명. {@code 민원 현황 보고서 {regionNm}} ({@code ComplaintReportNaming} 이 조립 — D8) */
    @Schema(description = "보고서명", example = "민원 현황 보고서 제주시 연동")
    private String rptpNm;

    /**
     * 지역명. 서버 3단 규칙 — {@code 전체 지역} / {@code 제주시 전체} / {@code 제주시 연동} (D4·D8).
     *
     * <p>DB 컬럼이 아니다 — 생성 경로는 {@code ComplaintReportNaming} 이, 스냅샷 조회 경로는
     * 매퍼의 CASE 가 만든다. <b>두 경로가 같은 문자열을 내야 한다.</b>
     */
    @Schema(description = "지역명 (전체 지역 | {시군구} 전체 | {시군구} {읍면동})", example = "제주시 연동")
    private String regionNm;

    /** 분석기간설명 (anls_prd_expln — {@code yyyy.MM.dd ~ yyyy.MM.dd}, 시작=종료면 한 번만) */
    @Schema(description = "분석기간설명", example = "2026.09.01 ~ 2026.09.17")
    private String anlsPrdExpln;

    /** 파일형식 (file_fmt — PDF | DOCX. 요청값을 그대로 저장·반환한다) */
    @Schema(description = "파일형식 (PDF | DOCX)", example = "PDF")
    private String fileFmt;

    /** 생성일시 (crt_ymd — 보고서 생성 시각. 문서 부제의 생성일 출처) */
    @Schema(description = "생성일시", example = "2026-09-18T14:03:11")
    private LocalDateTime crtYmd;

    /** 핵심 지표 4종 + 감성 분포 + 전기 2칸. 조회 응답 KPI 중 보고서가 쓰는 5칸에 전기 값을 더했다. */
    @Schema(description = "핵심 지표 (총 민원 · 부정 비율 · 감성 분포 · 단속 집중 · 수요 부족 · 전기 기간 · 전기 민원 건수)")
    private Kpi kpi;

    /** 지역별 민원 현황 카드 목록. 조회 응답의 {@code emdCompare[]} 를 그대로 싣는다. */
    @Schema(description = "지역별 민원 현황 카드 목록")
    private List<EmdCompareVO> emdCompare = new ArrayList<>();

    /** 부정 민원 상위 지역 카드 목록. 조회 응답의 {@code negEmdCompare[]} 를 그대로 싣는다. */
    @Schema(description = "부정 민원 상위 지역 카드 목록")
    private List<NegEmdCompareVO> negEmdCompare = new ArrayList<>();

    /**
     * 단속 집중 지역 목록 (🆕 TSK-4197 — 보고서 §4). 서버가 같은 조건으로
     * {@code HotspotRankingService.rank(view=enforcement)} 를 다시 돌려 담는다(D8 —
     * 화면이 보낸 숫자를 쓰지 않는다).
     *
     * <p>🔴 원소 타입이 {@code HotspotRankingZoneVO} 가 <b>아니다</b> — 그쪽 {@code bndryGeoJson}
     * (수 MB)이 {@code rslt_json} 한 칸에 들어가면 보고서 1건이 수 MB 가 된다(D7).
     * <p>🔴 <b>전량</b>이다 — 자르는 것은 프론트 템플릿의 몫이다(D6).
     */
    @Schema(description = "단속 집중 지역 목록 (view=enforcement 재집계, 전량)")
    private List<CrdnCompareVO> crdnCompare = new ArrayList<>();

    /**
     * 주차 수요 부족 지역 목록 (🆕 TSK-4197 — 보고서 §5). 서버가 같은 조건으로
     * {@code HotspotRankingService.rank(view=shortage)} 를 다시 돌려 담는다(D8).
     *
     * <p>🔴 원소 타입이 {@code HotspotRankingZoneVO} 가 <b>아니다</b>(D7 — 경계 polygon 제외).
     * <p>🔴 <b>전량</b>이다(D6). Jenks 등급 필터를 쓰지 않으므로 모집단은
     * {@code out_cvlcpt_sum > 0} 인 zone 전체다(TSK-4197 Step 1).
     */
    @Schema(description = "주차 수요 부족 지역 목록 (view=shortage 재집계, 전량)")
    private List<ShortageCompareVO> shortageCompare = new ArrayList<>();

    /**
     * 보고서가 담는 KPI 7칸(🔁 TSK-4311 — 전기 2칸 추가). 🔴 {@link ComplaintKpiVO} 와 <b>일부러 다른 타입</b>이다 —
     * 그쪽은 {@code lastSyncDt}·배열 2개·적용 기간 2칸까지 갖고 있어 kpi 안에 중복으로 들어온다.
     *
     * <p>Jackson 기본 직렬화는 record 컴포넌트명을 JSON 키로 쓰므로, 여기 7칸이 곧
     * {@code rslt_json.kpi} 의 키이자 프론트 {@code ComplaintReportDoc.kpi} 의 모양이다.
     */
    @Schema(description = "민원현황 보고서 KPI")
    public record Kpi(
            /** 총 민원 건수 — 행 단위 {@code count(*)} (조회 응답의 totalCount 와 같은 베이스.
             *  🔁 2026-10-02 TSK-4197 로 DISTINCT 기준 폐기) */
            long totalCount,
            /** 부정 비율 (0.0~1.0) */
            double negativityRatio,
            /** 감성 분포 (긍정/보통/부정) */
            SentimentDistVO sentimentDist,
            /**
             * 단속 집중 지역 중 <b>"심각" 등급 동 수</b> (2026-09-30 TSK-4167 D16 갱신).
             * {@code FLOOR(COUNT(DISTINCT tb_crdn_dsctn.emd_cd) * 0.1)} — 종전 "단속이 있는 모든 동 수" 폐기.
             * 보고서는 조회 화면과 <b>같은 KPI 서비스</b>를 쓰므로 이 값도 함께 바뀐다 (D15 ⚠️).
             * 상세는 {@link ComplaintKpiVO}.
             */
            long enforcementCount,
            /**
             * <b>주차민원이 있는 zone 수의 상위 10%</b> (🔁 2026-10-02 TSK-4197 갱신).
             * {@code FLOOR(count(*) * 0.1)} — 모집단은 {@code fn_jenks_shortage(…, NULL::text)} 중
             * {@code out_cvlcpt_sum > 0} 인 zone 이다.
             * 🔴 <b>Jenks 등급 분류를 쓰지 않는다</b> — 종전 설명(Jenks "심각" 등급 zone 수 →
             * 심각 안에서 다시 상위 10%)은 이제 틀렸다. 9월 실측 모집단 67 → KPI 6.
             * 보고서는 조회 화면과 <b>같은 KPI 서비스</b>를 쓰므로 이 값도 함께 바뀐다 (D15 ⚠️).
             * 상세는 {@link ComplaintKpiVO}.
             */
            long shortageCount,
            /**
             * 전기 기간 표기 (🆕 TSK-4311) — {@code anlsPrdExpln} 과 같은 형식.
             * 전기 = 분석 기간 바로 앞의 같은 일수 구간({@code ComplaintUnit.prevStart}).
             * 이 키가 없는 구 스냅샷은 {@code null} 이다(소급하지 않는다).
             */
            String prevAnlsPrdExpln,
            /**
             * 전기 민원 건수 (🆕 TSK-4311) — {@link #totalCount} 와 같은 SQL 이다.
             * 🔴 {@code emdCompare[].prevCnt} 의 합이 아니다 — 그쪽은 이번 기간 0건인 동이 빠져 더 작을 수 있다.
             * 🔴 boxed 다 — 키가 없는 구 스냅샷이 {@code 0} 으로 읽히면 거짓 {@code 0건} 이 찍힌다.
             */
            Long prevTotalCount
    ) {
    }
}
