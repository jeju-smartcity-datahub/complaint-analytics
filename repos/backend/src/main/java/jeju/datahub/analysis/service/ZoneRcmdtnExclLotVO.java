package jeju.datahub.analysis.service;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 구역추천 <b>후보 밖</b> 주차장 1건 — 지도 회색 핀 전용 (TSK-4276). (MyBatis 조회 전용 VO)
 *
 * <p>컬럼(스네이크) → 필드(카멜) 매핑은 mybatis.configuration.map-underscore-to-camel-case=true 로
 * 자동 처리한다. <b>JPA 엔티티가 아니다</b> — 조회 전용이다.
 *
 * <p>🔴 순위·배정 건수가 없다 — 후보가 아니라 배정 대상이 아니다. 결과 패널 목록에도 나오지 않는다.
 *
 * <p>🔴 사유({@link #exclRsnCd})는 <b>하나만</b>이다 — 여러 사유에 걸려도 우선순위
 * {@code PAID > CPCTY_LACK > PRVT > TYPE_EXCL} 의 첫 것(D2). 이름은 데이터표준사전 표준용어
 * {@code 제외사유 EXCL_RSN} + 형식어 {@code 코드 CD}.
 */
@Schema(description = "구역추천 후보 밖 주차장 1건 (지도 참고용 · 순위 없음)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ZoneRcmdtnExclLotVO {

    /** 주차장 ID. {@code TB_PBLPKLT.pklt_id}(IDENTITY) — 🔴 값에 업무적 의미가 없다 */
    @Schema(description = "주차장 ID", example = "11513")
    private Long pkltId;

    /** 주차장명 */
    @Schema(description = "주차장명", example = "이도이동 1994-1")
    private String pkltNm;

    /**
     * 주차면수. 🔴 <b>{@code null} 일 수 있다</b>(원천 면수 미상) — 그 행의 사유는 {@code CPCTY_LACK} 이고
     * 화면 문구는 {@code 면수 미상} 이다.
     */
    @Schema(description = "주차면수 (원천 미상이면 null)", example = "14", nullable = true)
    private Integer pkltCpcty;

    /** 위도 */
    @Schema(description = "위도", example = "33.49")
    private BigDecimal lat;

    /** 경도 */
    @Schema(description = "경도", example = "126.53")
    private BigDecimal lot;

    /**
     * 제외 사유 코드 — {@code PAID}(유료) · {@code CPCTY_LACK}(최소 면수 미만 · 면수 미상 포함) ·
     * {@code PRVT}(민영) · {@code TYPE_EXCL}(대상 유형 범위 밖). 우선순위는 이 나열 순서다(D2).
     */
    @Schema(description = "제외 사유 코드 (우선순위 PAID > CPCTY_LACK > PRVT > TYPE_EXCL)",
            example = "PAID", allowableValues = {"PAID", "CPCTY_LACK", "PRVT", "TYPE_EXCL"})
    private String exclRsnCd;
}
