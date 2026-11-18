package jeju.datahub.report.service;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 보고서 자동 생성 설정 등록·수정 요청 바디.
 * (POST /api/report-settings · PUT /api/report-settings/{stngId})
 *
 * <p>여기 애노테이션은 <b>형식</b>만 본다. 값이 실제로 존재하는 코드인지
 * (부서는 {@code TB_DEPT} · {@code CRLTN_CYCL_CD} · {@code ANLS_TYPE_CD} 중 {@code addl_atrb1 IN ('RPTP','BOTH')}),
 * 주기에 따라 {@code crltnMm} 이 필수인지, 지역이 {@code TB_ADMDST} 에 있는지는
 * DB 를 봐야 하므로 서비스가 판단한다 — 어느 쪽이든 응답은 400 이다.
 *
 * <p>컨트롤러에서 {@code @Valid} 를 빠뜨리면 이 검증이 통째로 발동하지 않는다.
 *
 * <p><b>DB NOT NULL 4종({@code crltn_cycl_cd}·{@code actvtn_yn}·{@code creatr_id}·{@code crt_dt})에
 * 대응하는 필드는 일부러 두지 않았다.</b> 다음 배포 예정일은 서버 계산(D2), 활성여부·생성자·생성일시는
 * 서버 고정값(D5)이라 클라이언트가 정할 값이 아니다.
 */
@Schema(description = "보고서 자동 생성 설정 등록·수정 요청")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ReportSettingRequest {

    /** 보고서종류 코드. ANLS_TYPE_CD 중 addl_atrb1 IN ('RPTP','BOTH') 인 2종만 허용 (D13) */
    @NotBlank(message = "rptpTypeCd 는 필수입니다")
    @Schema(description = "보고서종류 코드 (CVLCPT | HOTSPOT)", example = "CVLCPT")
    private String rptpTypeCd;

    /** 배포부서 코드 (TB_DEPT.dept_cd) */
    @NotBlank(message = "crltnDeptCd 는 필수입니다")
    @Schema(description = "배포부서 코드", example = "TRFC")
    private String crltnDeptCd;

    /** 배포주기 코드 (CRLTN_CYCL_CD) */
    @NotBlank(message = "crltnCyclCd 는 필수입니다")
    @Schema(description = "배포주기 코드 (MONTH | QUARTER | HALF | YEAR)", example = "MONTH")
    private String crltnCyclCd;

    /**
     * 배포기준월 (1~12).
     *
     * <p>주기가 {@code MONTH} 면 보내도 무시하고 NULL 로 저장하고,
     * {@code QUARTER}/{@code HALF}/{@code YEAR} 면 <b>필수</b>다(없으면 서비스가 400).
     * 조건부 필수라 {@code @NotNull} 을 걸지 않는다.
     */
    @Min(value = 1, message = "crltnMm 은 1 ~ 12 범위여야 합니다")
    @Max(value = 12, message = "crltnMm 은 1 ~ 12 범위여야 합니다")
    @Schema(description = "배포기준월 (1~12. 월간이면 무시)", example = "1")
    private Integer crltnMm;

    /** 배포기준일 (1~28). 말일 편차를 피해 28 로 제한한다 (DB CHECK 와 동일) */
    @NotNull(message = "crltnDd 는 필수입니다")
    @Min(value = 1, message = "crltnDd 는 1 ~ 28 범위여야 합니다")
    @Max(value = 28, message = "crltnDd 는 1 ~ 28 범위여야 합니다")
    @Schema(description = "배포기준일 (1~28)", example = "5")
    private Integer crltnDd;

    /**
     * 시군구명 (선택). 비우면 전체 지역 (D12).
     *
     * <p>{@code emdCd} 를 함께 보내면 <b>이 값은 무시된다</b> — 서버가 {@code TB_ADMDST} 에서
     * 다시 채운다. '제주시 + 성산읍' 같은 불일치 조합을 원천 차단하기 위함이다.
     */
    @Schema(description = "시군구명 (선택. emdCd 를 함께 보내면 무시된다)", example = "제주시")
    private String sggNm;

    /** 읍면동코드 (선택). 비우면 시군구 전체, {@code sggNm} 도 비우면 전체 지역 (D12) */
    @Schema(description = "읍면동코드 (선택. 비우면 시군구 전체)", example = "50110560")
    private String emdCd;
}
