package jeju.datahub.analysis.service;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jeju.datahub.complaint.service.ComplaintUnit;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 분석레이어 순위 조회 요청. ({@code GET /api/hotspots/ranking})
 *
 * <p>가이드 §4 결정: {@code view=most-complaint|enforcement} (2종).
 * 기본값은 {@link HotspotView#MOST_COMPLAINT}.
 *
 * <p>가이드 §2 결정 (2026-08-21): §2 필터(단위/기간, 행정구역, 민원유형)에 동적으로 반응 —
 * 핵심 API 와 동일한 파라미터를 받는다.
 *
 * <p><b>2026-09-04 갱신 — view=least-complaint 폐기 + view=most-complaint 갱신</b>:
 * <ul>
 *   <li>{@code view=least-complaint} 폐기 — 화면 라디오 라벨/매퍼/API 응답에서 제거.
 *       화면 라디오 2종만 지원.</li>
 *   <li>{@code view=most-complaint} 데이터 소스가 {@code TB_HTSPT_ANLS_RSLT} view 에서
 *       {@code parking.tb_cvlcpt} 직접 집계로 변경 (가이드 §4 갱신). SQL 매개변수에
 *       {@code startDt}/{@code endDt} 가 들어간다 — 핵심 API 와 동일한 enum 으로 통일.
 *       🔁 2026-10-02 TSK-4197 — 건수는 <b>행 단위 {@code COUNT(*)}</b> 다. 종전의
 *       "같은 민원의 중복 적재분은 1건"({@code COUNT(DISTINCT cvlcpt_cn)}) 기준은 폐기됐다 —
 *       같은 문구가 중복이 아니라 정형화된 신고 문구였다. 근거는
 *       {@code mapper/complaint/ComplaintMapper.xml} 상단 「민원 집계 기준」 주석.</li>
 *   <li>enforcement view (2026-09-03 갱신) — §2 필터 SQL 반영 상태 유지.</li>
 * </ul>
 */
@Schema(description = "분석레이어 순위 조회 요청")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class HotspotRankingRequest {

    /**
     * view 식별자. kebab-case 문자열로 받아 서비스에서 {@link HotspotView} 로 변환한다 —
     * Spring 의 기본 enum 바인딩은 enum 상수명(예: {@code MOST_COMPLAINT}) 만 받아서
     * API 컨벤션의 kebab-case({@code most-complaint}) 와 어긋난다. 그래서 String 으로 받아
     * 서비스의 {@link HotspotView#fromQueryValue(String)} 로 변환한다.
     *
     * <p>화이트리스트 밖 값이면 서비스에서 400 — SQL 인젝션 방어: enum 만 SQL 분기에 닿는다.
     * 2026-09-04 갱신: 화이트리스트 = {most-complaint, enforcement}. least-complaint 요청 시 400.
     */
    @Schema(description = "view (most-complaint/enforcement). 생략 시 most-complaint",
            defaultValue = "most-complaint")
    private String view = HotspotView.MOST_COMPLAINT.queryValue();

    /**
     * 조회 단위. enum 변환은 서비스에서 {@link ComplaintUnit#resolveRange(LocalDate)} 로 한다 —
     * 핵심 API 와 동일 패턴.
     *
     * <p>✅ <b>2026-09-04 갱신</b> — 모든 view 가 SQL 매개변수로 사용. 응답 envelope 에 그대로 echo 됨.
     */
    @Schema(description = "조회 단위 (YESTERDAY/WEEK/MONTH). 모든 view 의 SQL 매개변수로 반영",
            defaultValue = "YESTERDAY")
    private ComplaintUnit unit = ComplaintUnit.YESTERDAY;

    /**
     * 2026-09-04 갱신 — 기간 직접 지정(시작일). {@code endDt} 와 함께 주어지면
     * {@code unit} 보다 우선한다 (가이드 §1 — 핵심 API ComplaintRequest 와 동일 패턴).
     * 둘 다 주어지지 않으면 {@code unit.resolveRange(now)} 로 폴백.
     */
    @Schema(description = "기간 시작일(yyyy-MM-dd). endDt 와 함께 주면 unit 보다 우선")
    private LocalDate startDt;

    /**
     * 2026-09-04 갱신 — 기간 직접 지정(종료일). {@code startDt} 와 함께 주어지면
     * {@code unit} 보다 우선.
     */
    @Schema(description = "기간 종료일(yyyy-MM-dd). startDt 와 함께 주면 unit 보다 우선")
    private LocalDate endDt;

    /** {@code startDt}/{@code endDt} 가 둘 다 주어졌는지 — 기간 직접 지정 모드 판정. */
    public boolean hasExplicitRange() {
        return startDt != null && endDt != null;
    }

    /** 시군구 코드 (5자리, optional). */
    @Schema(description = "시군구 코드(50110=제주시, 50130=서귀포시)")
    private String sggCd;

    /** 읍면동코드 (8자리, optional). */
    @Schema(description = "읍면동코드 (8자리)")
    private String emdCd;

    /**
     * 민원유형코드. view별 매핑은 매퍼 SQL 에서 결정 —
     * {@code most-complaint} 는 {@code parking.tb_cvlcpt.cvlcpt_type_cd} 와 직접 매칭,
     * {@code enforcement} 는 {@code parking.tb_crdn_dsctn.crdn_type_cd} 와 동일 enum 가정.
     */
    @Schema(description = "민원유형코드(01~05) — 모든 view 의 SQL 매개변수로 반영")
    private String cvlcptTypeCd;
}
