package jeju.datahub.lifezone.service;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 생활권 시뮬레이션 보고서 생성 요청. (POST /api/analysis/lifezone/reports)
 *
 * <p>조회 조건은 {@link LifezoneAnalysisRequest} 와 <b>같은 필드</b>이고 {@link #fileFmt} 한 칸만
 * 더한다. 🔴 <b>점수·건수를 받지 않는다</b> — 화면이 계산한 값을 그대로 굳히면 보고서가
 * 화면의 오류까지 영구 보존한다. 저장 직전에 서버가 같은 조건으로 <b>다시 분석</b>한다.
 *
 * <p>🔴 <b>{@link LifezoneAnalysisRequest} 를 상속하지 않고 필드를 나열</b>한다 — 상속하면 springdoc 이
 * 부모/자식 스키마를 {@code allOf} 로 엮어 조회 API 의 파라미터 문서가 생성 API 의 요청 바디 문서에
 * 끌려 들어간다({@code HotspotReportCreateRequest} 와 같은 판단).
 * 대신 두 요청을 잇는 변환은 {@link #toAnalysisRequest()} 한 곳에만 둔다.
 *
 * <p>지역 조건({@link #sggNm}·{@link #emdCd})은 <b>키를 아예 넣지 않는 것</b>이 "전체"다 —
 * 빈 문자열은 400 이다(서비스 판정). 격자크기·분석유형·분류방법의 값 검증도 서비스가 한다.
 */
@Schema(description = "생활권 시뮬레이션 보고서 생성 요청")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class LifezoneReportCreateRequest {

    /** 분석내용코드. 기본 {@code CVLCPT_CNT}. 미구현 유형({@code SPLY_DMND}·{@code CNGST})은 400 */
    @Schema(description = "분석내용코드 (CVLCPT_CNT | SPLY_DMND | CNGST)",
            example = "CVLCPT_CNT", defaultValue = "CVLCPT_CNT")
    private String anlsTrgtCd = LifezoneTarget.CVLCPT_CNT.name();

    /** 격자크기(m). {@code 1000}·{@code 500}·{@code 250}·{@code 100} 중 하나, 기본 500 */
    @Schema(description = "격자크기(m) — 1000 | 500 | 250 | 100", example = "500", defaultValue = "500")
    private int gridSz = 500;

    /**
     * 등급 분류방법. 생략하면 <b>유형 기본값</b>이다({@code LifezoneClsfMthdResolver} 가 정한다).
     *
     * <p>🔴 <b>초기값이 {@code null} 이다</b> — 특정 상수로 두면 유형 기본값이 적용될 틈이 없고, 허용 목록
     * 밖 값으로 재조회돼 생략한 저장 요청이 400 이 된다(유형마다 1종 확정).
     *
     * <p>🔴 <b>보고서에 반드시 함께 굳힌다</b>({@code anls_param_json.clsfMthd}). 경계가 조회
     * 결과에서 산출되므로, 어느 방식으로 낸 경계인지 모르면 스냅샷을 다시 열 때 재현할 수 없다.
     */
    @Schema(description = "등급 분류방법 — 생략 시 유형 기본값(민원건수 EQUAL_INTERVAL). 허용 목록은 /targets 의 clsfMthds",
            example = "EQUAL_INTERVAL", nullable = true)
    private String clsfMthd;

    /** 분석 시작일 (YYYYMMDD, 필수) */
    @NotBlank(message = "bgngYmd 는 필수입니다")
    @Pattern(regexp = "\\d{8}", message = "bgngYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "분석 시작일 (YYYYMMDD)", example = "20251201",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String bgngYmd;

    /** 분석 종료일 (YYYYMMDD, 필수 · 당일 포함) */
    @NotBlank(message = "endYmd 는 필수입니다")
    @Pattern(regexp = "\\d{8}", message = "endYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "분석 종료일 (YYYYMMDD, 당일 포함)", example = "20251231",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String endYmd;

    /** 시군구명(선택). 생략하면 전 지역. 빈 문자열은 400 */
    @Schema(description = "시군구명 (생략 = 전 지역)", example = "제주시")
    private String sggNm;

    /** 읍면동코드(선택). 생략하면 선택 시군구 전체. 빈 문자열은 400 */
    @Schema(description = "읍면동코드 (생략 = 시군구 전체)", example = "50110137")
    private String emdCd;

    /**
     * 순위 목록 개수(선택). <b>생략하면 상한 없음</b>(심각·경고·주의 셀 전부),
     * 지정하면 그 값까지(1 이상). 지도 셀은 이 값과 무관하게 값&gt;0 전량이다
     */
    @Min(value = 1, message = "limit 은 1 이상이어야 합니다")
    @Schema(description = "순위 개수 (생략 = 심각·경고·주의 전부, 지정 시 1 이상)", example = "15", nullable = true)
    private Integer limit;

    /**
     * 파일형식 (필수). {@code PDF}·{@code DOCX}·{@code XLSX} 중 하나 — 그 밖의 값이면 <b>400</b>.
     *
     * <p>🔴 화이트리스트를 <b>요청 VO 의 제약으로</b> 건다 — 서비스에서 if 로 거르면 컨트롤러의
     * {@code @Valid} 와 검증 지점이 갈려 Swagger 문서에도 값 목록이 드러나지 않는다.
     * 공통코드 {@code FILE_FMT_CD} 의 값과 같다(🆕 {@code XLSX} 는 코드표는 마이그레이션 102). 기본값을 두지 않는 이유는 화면의 내보내기 메뉴가
     * PDF · Word(DOCX) · Excel(XLSX) 세 항목으로 갈라져 있어 생략이 곧 프론트의 버그이기 때문이다.
     */
    @NotBlank(message = "fileFmt 는 필수입니다")
    // XLSX 허용(민원건수는 wbk 없이 기존 스냅샷으로 엑셀을 만든다)
    @Pattern(regexp = "^(PDF|DOCX|XLSX)$", message = "fileFmt 는 PDF, DOCX, XLSX 중 하나여야 합니다")
    @Schema(description = "파일형식 (PDF | DOCX | XLSX)", example = "PDF",
            allowableValues = {"PDF", "DOCX", "XLSX"}, requiredMode = Schema.RequiredMode.REQUIRED)
    private String fileFmt;

    /**
     * 조회 조건만 떼어 분석 요청으로 옮긴다 — {@link #fileFmt} 는 분석과 무관하므로 싣지 않는다.
     *
     * <p>보고서는 화면이 보낸 숫자를 믿지 않고 <b>같은 조건으로 다시 분석</b>한 결과를 저장한다.
     * 그 재분석의 입력이 이 메서드다. 🔴 여기서 칸을 빠뜨리면 <b>조건이 조용히 기본값으로 떨어져</b>
     * 사용자가 본 화면과 다른 보고서가 저장된다.
     */
    public LifezoneAnalysisRequest toAnalysisRequest() {
        LifezoneAnalysisRequest req = new LifezoneAnalysisRequest();
        req.setAnlsTrgtCd(anlsTrgtCd);
        req.setGridSz(gridSz);
        req.setClsfMthd(clsfMthd);
        req.setBgngYmd(bgngYmd);
        req.setEndYmd(endYmd);
        req.setSggNm(sggNm);
        req.setEmdCd(emdCd);
        req.setLimit(limit);
        return req;
    }
}
