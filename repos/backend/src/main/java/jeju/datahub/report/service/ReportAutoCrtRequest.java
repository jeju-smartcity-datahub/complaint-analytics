package jeju.datahub.report.service;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 보고서 자동 생성 요청. (POST /api/internal/reports/auto-crt)
 *
 * <p>🔴 <b>배치({@code reportAutoCrtJob})만 부른다.</b> 화면이 부르는 수동 생성
 * ({@code ComplaintReportCreateRequest}·{@code HotspotReportCreateRequest})과 계약이 다르다 —
 * 이쪽은 <b>설정({@code TB_RPTP_AUTO_CRT_STNG}) 원문</b>과 <b>배치가 산출한 대상기간</b>을 싣는다.
 *
 * <h2>🔴 받지 않는 것 둘</h2>
 *
 * <p><b>① {@code fileFmt} 를 받지 않는다</b> — 자동 생성분은 {@code file_fmt} 를 <b>NULL</b> 로 둔다
 * (마이그레이션 72 가 NOT NULL 제약을 풀었다). 배치가 보낼 값이 없다(설정에 파일형식
 * 칸이 없다). ⚠️ {@code ComplaintReportCreateRequest}·{@code HotspotReportCreateRequest} 의
 * {@code fileFmt} 에는 {@code @NotBlank}+{@code @Pattern} 이 붙어 있지만 <b>그 검증은 컨트롤러의
 * {@code @Valid} 가 발동시킨다</b> — 두 서비스 인터페이스·구현에 {@code @Validated} 가 없어
 * 자동 생성 서비스가 {@code createReport(...)} 를 직접 호출하면 {@code fileFmt=null} 이 그대로 통과한다.
 * 🔴 나중에 그 서비스에 {@code @Validated} 를 붙이면 이 경로가 조용히 400 이 된다.
 *
 * <p><b>② 집계 결과(건수·비율)를 받지 않는다</b> — 배치가 숫자를 보내면 그 값이 영구 보존되어,
 * 집계 규칙이 바뀌어도 되돌아보지 못한다. 집계는 백엔드가 기존 생성
 * 서비스를 <b>다시 불러</b> 수행한다.
 */
@Schema(description = "보고서 자동 생성 요청 (배치 전용)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ReportAutoCrtRequest {

    /** 설정아이디 (TB_RPTP_AUTO_CRT_STNG.stng_id, 필수) */
    @NotNull(message = "stngId 는 필수입니다")
    @Schema(description = "설정아이디", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long stngId;

    /**
     * 보고서종류 코드 (필수). {@code CVLCPT} 또는 {@code HOTSPOT} — 그 밖이면 <b>400</b>.
     *
     * <p>🔴 값 검증은 <b>서비스</b>가 한다 — 설정 원문과 대조해야 하기 때문이다
     * (요청의 종류·주기가 설정과 다르면 배치가 낡은 값을 들고 있는 것이라 400).
     * 여기에 {@code @Pattern} 을 걸면 "설정과 다름" 과 "값 자체가 이상함" 이 같은 400 으로 뭉개진다.
     */
    @NotBlank(message = "rptpTypeCd 는 필수입니다")
    @Schema(description = "보고서종류 코드 (CVLCPT | HOTSPOT)", example = "CVLCPT",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String rptpTypeCd;

    /**
     * 대상기간 코드 (필수, 최대 20자). <b>멱등키</b>다 — 배치가
     * {@code ReportAutoCrtNaming.targetPeriod(...)} 와 같은 규칙으로 만들어 보낸다.
     *
     * <p>{@code (stng_id, trgt_prd_cd)} 에 유니크 제약({@code uq_rptp_stng_prd})이 걸려 있어
     * 같은 주기를 두 번 보내도 한 행만 남는다.
     */
    @NotBlank(message = "trgtPrdCd 는 필수입니다")
    @Size(max = 20, message = "trgtPrdCd 는 20자 이하여야 합니다")
    @Schema(description = "대상기간 코드 (멱등키)", example = "2026-08",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String trgtPrdCd;

    /** 대상기간 시작일 (YYYYMMDD) */
    @Pattern(regexp = "\\d{8}", message = "bgngYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "대상기간 시작일 (YYYYMMDD)", example = "20260801")
    private String bgngYmd;

    /** 대상기간 종료일 (YYYYMMDD, 당일 포함) */
    @Pattern(regexp = "\\d{8}", message = "endYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "대상기간 종료일 (YYYYMMDD, 당일 포함)", example = "20260831")
    private String endYmd;

    /**
     * 시군구명(선택) — <b>설정 원문 그대로</b>다. NULL 이면 시군구 조건 없음.
     *
     * <p>🔴 코드가 아니라 <b>이름</b>이다({@code TB_RPTP_AUTO_CRT_STNG.sgg_nm}). 민원 생성 서비스는
     * 코드({@code sggCd})를 받으므로 구현이 변환한다 — 배치가 변환하지 않는다.
     */
    @Schema(description = "시군구명 (설정 원문, 생략 = 전체 지역)", example = "제주시")
    private String sggNm;

    /** 읍면동코드(선택, 8자리) — 설정 원문 그대로. NULL 이면 시군구 전체. */
    @Schema(description = "읍면동코드(8자리, 설정 원문)", example = "50110137")
    private String emdCd;

    /** 배포부서 코드 (필수) — {@code TB_RPTP} 스냅샷 칸. */
    @NotBlank(message = "crltnDeptCd 는 필수입니다")
    @Schema(description = "배포부서 코드", example = "CIVIL",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String crltnDeptCd;

    /** 배포주기 코드 (필수) — {@code TB_RPTP} 스냅샷 칸. {@code MONTH|QUARTER|HALF|YEAR}. */
    @NotBlank(message = "crltnCyclCd 는 필수입니다")
    @Schema(description = "배포주기 코드 (MONTH | QUARTER | HALF | YEAR)", example = "MONTH",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String crltnCyclCd;
}
