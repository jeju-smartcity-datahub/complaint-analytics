package jeju.datahub.lifezone.service;

import java.math.BigDecimal;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 생활권 <b>수급</b> 보고서 생성 요청. (POST /api/analysis/lifezone/supply/reports — TSK-4089)
 *
 * <p>🔴 <b>조회 조건만 받는다 — 화면이 들고 있던 숫자는 받지 않는다.</b> 서버가 같은 조건으로
 * <b>다시 분석해</b> 그 결과를 저장한다. 화면이 보낸 결과를 그대로 저장하면 화면 조작이 곧
 * 보고서 위조가 된다(민원건수 보고서와 같은 방침).
 */
@Schema(description = "생활권 주차 수급 보고서 생성 요청")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class LifezoneSupplyReportCreateRequest {

    /** 격자크기(m). 기본 {@code 500}. {@code 1000|500|250|100} 외는 서비스가 400 */
    @Schema(description = "격자크기(m) — 1000 | 500 | 250 | 100", example = "500", defaultValue = "500")
    private int gridSz = LifezoneSupply.DEFAULT_GRID_SZ;

    /** 분석 시작일 (YYYYMMDD, 필수) */
    @NotBlank(message = "bgngYmd 는 필수입니다")
    @Pattern(regexp = "\\d{8}", message = "bgngYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "분석 시작일 (YYYYMMDD)", example = "20250101",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String bgngYmd;

    /** 분석 종료일 (YYYYMMDD, 필수 · 당일 포함) */
    @NotBlank(message = "endYmd 는 필수입니다")
    @Pattern(regexp = "\\d{8}", message = "endYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "분석 종료일 (YYYYMMDD, 당일 포함)", example = "20250831",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String endYmd;

    /** 시군구명(선택). 생략·{@code null} 이면 전 지역 */
    @Schema(description = "시군구명 (생략 = 전 지역)", example = "제주시")
    private String sggNm;

    /** 읍면동코드(선택) */
    @Schema(description = "읍면동코드 (생략 = 시군구 전체)", example = "50110137")
    private String emdCd;

    /**
     * 파일형식 ({@code PDF} | {@code DOCX}).
     *
     * <p>🔴 <b>서버는 파일을 굽지 않는다</b> — 문서를 만드는 것은 브라우저다. 이 값은 "무엇으로
     * 내보냈는지" 를 보고서 행에 남길 뿐이다({@code TB_RPTP.file_fmt}).
     */
    @NotBlank(message = "fileFmt 는 필수입니다")
    // 🆕 TSK-4340 D7 — XLSX 허용. 형식과 무관하게 스냅샷에 엑셀 데이터(wbk)가 실린다
    @Pattern(regexp = "^(PDF|DOCX|XLSX)$", message = "fileFmt 는 PDF, DOCX, XLSX 중 하나여야 합니다")
    @Schema(description = "파일형식 (PDF | DOCX | XLSX)", example = "PDF",
            allowableValues = {"PDF", "DOCX", "XLSX"}, requiredMode = Schema.RequiredMode.REQUIRED)
    private String fileFmt;

    /**
     * 보고서 표에 실을 등급 ({@code SEVERE} | {@code WARN} | {@code CAUTION}, 선택 · 🔁 TSK-4258 — 종전 {@code qdrn}).
     * 생략·{@code null}·공백이면 전체.
     *
     * <p>🔴 <b>표의 행만 거른다 — 분석·저장 대상은 그대로 셀 전량이다.</b> 화면에서 고른 등급을 보고서도
     * 그대로 따르게 하려는 값이라 스냅샷 {@code anls_param_json.grade} 에 굳힌다.
     * 🔴 허용은 리스트 대상 3종({@link LifezoneGrade#isListed()})뿐이다 — {@code NORMAL}·{@code LOW} 는
     * 표가 0행이 되므로 미지원 값과 같이 서비스가 400.
     */
    @Schema(description = "보고서 표에 실을 등급 (SEVERE | WARN | CAUTION). 생략 = 전체",
            example = "SEVERE", nullable = true)
    private String grade;

    /**
     * 분석 계수 일시 입력(선택, TSK-4325 D1). 조회의 JSON 문자열과 달리 바디 객체다.
     *
     * <p>🔴 SPLY_MIN_MNTH_AVG_CVLCPT·SPLY_GRD_BNDRY1~4 만 받는다 — 규칙은 조회와 같다. 화면은 마지막으로 성공한 조회의 값을 보낸다(D7).
     * 생략하면 설정값이다.
     */
    @Schema(description = "분석 계수 일시 입력 (선택 — SPLY_MIN_MNTH_AVG_CVLCPT·SPLY_GRD_BNDRY1~4 만. 생략 = 설정값)",
            example = "{\"SPLY_MIN_MNTH_AVG_CVLCPT\":3,\"SPLY_GRD_BNDRY4\":85}")
    private Map<String, BigDecimal> vrblVls;

    /** 조회 요청으로 옮긴다 — 분석은 조회와 <b>같은 서비스</b>가 한다(두 경로의 결과가 갈리지 않게) */
    public LifezoneSupplyRequest toAnalysisRequest() {
        LifezoneSupplyRequest request = new LifezoneSupplyRequest();
        request.setGridSz(gridSz);
        request.setBgngYmd(bgngYmd);
        request.setEndYmd(endYmd);
        request.setSggNm(sggNm);
        request.setEmdCd(emdCd);
        // 🔴 쿼리 칸(String vrblVls)이 아니라 보고서 경로 전용 칸으로 옮긴다 — 빠뜨리면 컴파일 오류 없이
        //    보고서만 설정값으로 분석된다 (TSK-4325)
        request.setVrblVlsMap(vrblVls);
        return request;
    }
}
