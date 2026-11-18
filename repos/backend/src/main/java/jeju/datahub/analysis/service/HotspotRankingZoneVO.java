package jeju.datahub.analysis.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 분석레이어 hotspot zone 1건. ({@code GET /api/hotspots/ranking} 응답 원소)
 *
 * < 가이드 §4 결정: {@code TB_HTSPT_ANLS_RSLT} 정렬 결과 1행.
 *
 * * 기존 군집 분석 ({@link HotspotZoneVO}) 의 snthsScr · grade · gradeNm · zoneNmSrc ·
 *   cvlcptCnt/crdnCnt (raw 합) 와 의미가 다르다 — 이 VO 는 이미 가중치·등급이 적용된 view 순위.
 * * 컬럼(스네이크) → 필드(카멜) 매핑은 mybatis.configuration.map-underscore-to-camel-case=true 로 자동 처리.
 *
 * * 가이드 §6 view별 표시 표: 라벨 N 값은 view 가 {@code enforcement} 일 때만 {@code crdnCnt},
 *   그 외는 {@code cvlcptCnt}. 화면이 zone 정렬 기준을 알고 있어 그대로 보여줄 수 있으므로
 *   별도 {@code countKey} 필드는 두지 않는다.
 *
 * <p><b>2026-09-04 갱신 — 모든 view 가 rank 를 채움 (PR #24 Copilot review)</b>:
 * most-complaint / enforcement / shortage 모두 SQL 의 {@code RANK() OVER (ORDER BY ...)} 가
 * 1-base 순위를 매긴다 (view 별 정렬 기준은 §4 결정). 이전 주석의 '다른 view 는 rank=0 일 수 있음' 은
 * 옛 단계의 잔재 — 현재 매퍼 SQL 세 view 가 모두 RANK 를 둔다
 * (🔁 2026-10-02 TSK-4197 D13 — ROW_NUMBER 에서 전환, 동률은 같은 rank).
 */
@Schema(description = "분석레이어 hotspot zone 1건 (TB_HTSPT_ANLS_RSLT 또는 TB_CRDN_DSCTN 직접 집계)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class HotspotRankingZoneVO {

    /**
     * 1-base 순위. view 별 SQL 이 {@code RANK()} 로 채운다 (정렬 기준은 view별 정의를 따른다).
     * 2026-09-04 갱신 — 모든 view 가 rank 를 채움 (PR #24 Copilot review).
     * 🔁 2026-10-02 TSK-4197 D13 — {@code ROW_NUMBER()} → {@code RANK()}. <b>같은 값이면 같은 순위</b>이고
     * 그만큼 다음 순위가 건너뛴다(1,1,3). 출력 순서만 쿼리 끝 {@code ORDER BY} 의 tie-break 가 정한다.
     */
    @Schema(description = "1-base 순위", example = "1")
    private long rank;

    /** 지역명 */
    @Schema(description = "지역명", example = "연동")
    private String zoneNm;

    /** 읍면동코드 (TB_ADMDST.emd_cd 매칭 또는 TB_HTSPT_ANLS_RSLT 자체 보유) */
    @Schema(description = "읍면동코드 (8자리)", example = "50110137")
    private String emdCd;

    /**
     * 민원건수. parking.tb_cvlcpt 직접 집계 — <b>행 단위 {@code COUNT(*)}</b>
     * (🔁 2026-10-02 TSK-4197 — 2026-09-21 D2-1 의 DISTINCT 기준 폐기. 근거는
     * {@code mapper/complaint/ComplaintMapper.xml} 상단 「민원 집계 기준」 주석).
     * most-complaint · enforcement 모두 같은 정의. shortage 는 0(집계 컬럼 아님).
     */
    @Schema(description = "민원건수 — COUNT(*) (행 단위). "
            + "most-complaint·enforcement 는 같은 동·기간의 민원 수, shortage 는 0", example = "52")
    private long cvlcptCnt;

    /** 단속건수. view=most-complaint 면 0 일 수 있음. */
    @Schema(description = "단속건수", example = "0")
    private long crdnCnt;

    /** 마커 위도 (zone 중심점). */
    @Schema(description = "마커 위도 (EPSG:4326)", example = "33.4869")
    private BigDecimal mrkLat;

    /** 마커 경도 (zone 중심점). */
    @Schema(description = "마커 경도 (EPSG:4326)", example = "126.4903")
    private BigDecimal mrkLot;

    /**
     * 행정동 경계 polygon. PostGIS {@code ST_AsGeoJSON(tb_admdst.bndry_geom)} 결과 (JSON 문자열).
     *
     * <p>2026-09-08 추가 (TSK-4125) — {@code GET /api/hotspots/ranking} 응답에 polygon 동봉.
     * 매핑 책임은 백엔드 service 단 — {@code most-complaint} / {@code enforcement} 는 매퍼 SQL 에서
     * {@code tb_admdst} LEFT JOIN 으로 동봉, {@code shortage} 는 {@code fn_jenks_shortage} 함수
     * 시그니처를 유지하기 위해 service 단에서 admdst 매퍼 일괄 조회 후 in-memory 매핑. 매핑 실패
     * zone ({@code tb_admdst.bndry_geom IS NULL} 또는 admdst 미등록) 은 {@code null}.
     */
    @Schema(description = "행정동 경계 polygon (ST_AsGeoJSON 결과 JSON 문자열, 매핑 실패 시 null)",
            example = "{\"type\":\"Polygon\",\"coordinates\":[[[126.5,33.4],...]]}")
    private String bndryGeoJson;

    /**
     * 민원유형별 건수 목록 (TSK-4165 D2). {@code most-complaint} 만 채우고 다른 view 는 빈 목록 —
     * 🔴 {@code null} 이 아니다. 유형 순서는 {@code TB_COM_CD.sort_seq}. 건수 0 인 유형은 행이 없다.
     * 합은 항상 {@link #cvlcptCnt} 와 같다(D2-1 기준 통일).
     */
    @Schema(description = "민원유형별 건수 (most-complaint 만 채움, 그 외 view 는 빈 목록). 합 == cvlcptCnt")
    private List<CvlcptTypeCntVO> typeCnts = new ArrayList<>();

    /** 공급 면수 — fn_jenks_shortage.out_pklt_sum. shortage 만 채움, 그 외 null. */
    @Schema(description = "공급 면수 (shortage 만, 그 외 null). 0 은 정상값(공영주차장 없음)",
            example = "16", nullable = true)
    private BigDecimal pkltCpctySum;

    /**
     * 주차 수요 = 공급 면수 + 주차 관련 민원 수 (TSK-4165 D3). shortage 만 채움, 그 외 null.
     * 면(面)과 건(件)을 더한 지수라 단위가 없다.
     */
    @Schema(description = "주차 수요 지수 = 공급 면수 + 주차 관련 민원 수 (단위 없음, shortage 만, 그 외 null)",
            example = "74", nullable = true)
    private BigDecimal demandIndex;

    /**
     * 부족률 = 주차 관련 민원 수 ÷ 주차 수요 (0~1, 소수 4자리, TSK-4165 D3). shortage 만 채움, 그 외 null.
     * PSDR 의 단조 감소 변환({@code 1/(1+PSDR)})이라 rank 순으로 단조 비증가다. 분모 0 이면 null.
     */
    @Schema(description = "부족률 = 주차 관련 민원 수 ÷ 주차 수요 (0~1, 소수 4자리, shortage 만, 그 외 null)",
            example = "0.7838", nullable = true)
    private BigDecimal shortageRatio;

    /**
     * 단속 건수 증감률(%) — {@code view=enforcement} 만 채운다. 다른 view 는 null (TSK-4197 D2).
     * 직전 동일 길이 구간({@code ComplaintUnit.prevStart}) 대비이며, 직전 0건이면 분모 1 치환
     * + {@link #deltaPctInferred} = true (민원 §7 {@code emdCompare[].deltaPct} 와 같은 규칙).
     */
    @Schema(description = "단속 건수 증감률(%) — enforcement 만, 그 외 null. 직전 0건이면 분모 1 치환",
            example = "-12", nullable = true)
    private Integer deltaPct;

    /**
     * 부족률 %p 차이 = (현재 부족률 − 직전 부족률) × 100, 소수 1자리.
     * {@code view=shortage} 만 채운다. 다른 view 는 null (TSK-4197 D2).
     *
     * <p>⚠️ 직전 구간의 공급 면수는 현재와 같은 값이다({@code tb_pblpklt} 에 기간 개념이 없다) —
     * 따라서 이 값은 실질적으로 <b>민원 증감만</b> 반영한다.
     * 직전 분모(공급 + 민원)가 0 이면 {@code null} 이고 {@link #deltaPctInferred} = true (D12).
     */
    @Schema(description = "부족률 %p 차이 (현재 − 직전, 소수 1자리) — shortage 만, 그 외 null. "
            + "직전 분모 0 이면 null", example = "3.4", nullable = true)
    private BigDecimal shortageDeltaPp;

    /**
     * 직전 구간 값이 없어 치환·추론된 값인가.
     * 🔴 민원 카드({@code emdCompare[].deltaPctInferred})의 같은 이름과 의미가 다르다(TSK-4197 D12) —
     * enforcement 는 "직전 0건이라 분모를 1 로 치환했다", shortage 는 "직전 분모가 0 이라
     * {@code shortageDeltaPp} 를 낼 수 없었다" 다. most-complaint 는 항상 false.
     */
    @Schema(description = "직전 구간 값이 없어 치환·추론된 값인가 (enforcement=분모 1 치환, "
            + "shortage=직전 분모 0 으로 산출 불가)", example = "false")
    private boolean deltaPctInferred;

    /**
     * 그 동에서 가장 많은 단속유형 ({@code crdn_mthd} 원문) — {@code view=enforcement} 만 채운다 (TSK-4322).
     * 다른 view 는 null. 동률은 이름 오름차순.
     */
    @Schema(description = "최다 단속유형(crdn_mthd 원문) — enforcement 만, 그 외 null",
            example = "고정형CCTV", nullable = true)
    private String topCrdnMthd;

    /** {@link #topCrdnMthd} 건수 ÷ {@link #crdnCnt} (0~1). {@code view=enforcement} 만 채운다 (TSK-4322). */
    @Schema(description = "최다 단속유형 비율 = 그 유형 건수 ÷ crdnCnt (0~1) — enforcement 만, 그 외 null",
            example = "0.4985", nullable = true)
    private Double topCrdnMthdRatio;

    /**
     * 직전 구간 단속 건수 — {@code view=enforcement} 전용 <b>내부 계산 입력</b>.
     * 🔴 {@code /api/hotspots/ranking} 응답에 내보내지 않는다. 보고서는 {@code CrdnCompareVO} 로 옮겨 담는다(TSK-4311).
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Schema(hidden = true)
    private Long prevCrdnCnt;

    /**
     * 직전 구간 부족률(0~1 소수 4자리) — {@code view=shortage} 전용. 직전 분모 0 이면 null (TSK-4311).
     * 🔴 {@code /api/hotspots/ranking} 응답에 내보내지 않는다. 보고서는 {@code ShortageCompareVO} 로 옮겨 담는다.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Schema(hidden = true)
    private BigDecimal prevShortageRatio;
}
