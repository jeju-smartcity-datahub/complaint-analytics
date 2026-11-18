package jeju.datahub.complaint.service;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 민원현황 보고서 생성 요청. (POST /api/complaints/reports — TSK-4164)
 *
 * <p>조회 조건은 {@link ComplaintRequest} 와 겹치고 {@link #fileFmt} 한 칸을 더한다.
 * 🔴 <b>{@link ComplaintRequest} 를 상속하지 않고 필드를 나열</b>한다 — 상속하면 springdoc 이
 * 부모/자식 스키마를 {@code allOf} 로 엮어 Swagger 문서가 갈리고, 조회 API 의 파라미터 문서가
 * 생성 API 의 요청 바디 문서에 끌려 들어간다({@code HotspotReportCreateRequest} 가 같은 이유로
 * {@code HotspotAnalysisRequest} 를 상속하지 않는다 — TSK-4078 Step 4).
 *
 * <p>대신 필드가 두 곳에 존재하는 값을 치르므로, 두 요청을 잇는 변환은
 * {@link #toComplaintRequest(LocalDate, LocalDate)} 한 곳에만 둔다 — 서비스가 필드를 손으로
 * 옮겨 담으면 칸이 하나 늘 때 조용히 누락된다.
 *
 * <p>🔴 <b>{@code unit} 을 받지 않는다</b> — 상대 기간({@code YESTERDAY}/{@code WEEK}/{@code MONTH})을
 * 저장할 칸이 없다(D3). 화면은 조회 응답이 되비춘 실제 날짜({@code bgngYmd}·{@code endYmd})를 보낸다.
 *
 * <p>지역 조건({@link #sggCd}·{@link #emdCd})은 <b>키를 아예 넣지 않는 것</b>이 "전체"다 —
 * 빈 문자열을 보내지 않는다. 실제로 존재하는 지역인지는 여기서 판정하지 않고 서비스 계층이 검증한다.
 */
@Schema(description = "민원현황 보고서 생성 요청")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ComplaintReportCreateRequest {

    /** 조회 시작일 (YYYYMMDD, 필수) */
    @NotBlank(message = "bgngYmd 는 필수입니다")
    @Pattern(regexp = "\\d{8}", message = "bgngYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "조회 시작일 (YYYYMMDD)", example = "20260901",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String bgngYmd;

    /** 조회 종료일 (YYYYMMDD, 필수 · 당일 포함) */
    @NotBlank(message = "endYmd 는 필수입니다")
    @Pattern(regexp = "\\d{8}", message = "endYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "조회 종료일 (YYYYMMDD, 당일 포함)", example = "20260917",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String endYmd;

    /** 시군구 코드(선택). 5자리 — 50110=제주시, 50130=서귀포시. 빈 문자열 금지. */
    @Schema(description = "시군구 코드 (생략 = 전 지역, 50110=제주시 · 50130=서귀포시)", example = "50110")
    private String sggCd;

    /**
     * 읍면동코드(선택). 8자리.
     *
     * <p>🔴 {@link #sggCd} 와 함께 오면 <b>AND 로 좁혀진다(우선 아님)</b> — 집중구역의
     * {@code resolveRegion}(emdCd 우선)과 동작이 다르다(D4).
     */
    @Schema(description = "읍면동코드(8자리) — sggCd 와 함께 오면 AND 로 좁혀진다(우선 아님)",
            example = "50110137")
    private String emdCd;

    /** 민원유형코드(선택). 01~05. */
    @Schema(description = "민원유형코드(01~05)", example = "01")
    private String cvlcptTypeCd;

    /**
     * 파일형식 (필수). {@code PDF} 또는 {@code DOCX} — 그 밖의 값이면 <b>400</b>.
     *
     * <p>🔴 화이트리스트를 <b>요청 VO 의 제약으로</b> 건다 — 서비스에서 if 로 거르면 컨트롤러의
     * {@code @Valid} 와 검증 지점이 갈려 Swagger 문서에도 값 목록이 드러나지 않는다.
     * 기본값을 두지 않는다 — 화면이 두 버튼으로 갈라져 있어 생략은 곧 프론트의 버그다.
     */
    @NotBlank(message = "fileFmt 는 필수입니다")
    @Pattern(regexp = "^(PDF|DOCX)$", message = "fileFmt 는 PDF 또는 DOCX 여야 합니다")
    @Schema(description = "파일형식 (PDF | DOCX)", example = "PDF",
            allowableValues = {"PDF", "DOCX"}, requiredMode = Schema.RequiredMode.REQUIRED)
    private String fileFmt;

    /**
     * 조회 조건만 떼어 민원현황 조회 요청으로 옮긴다 — {@link #fileFmt} 는 집계와 무관해 싣지 않는다.
     *
     * <p>🔴 <b>파싱·검증은 서비스가 이미 끝냈다</b> — 파싱한 값을 받는다. 여기서
     * {@code LocalDate.parse} 를 하면 {@code 20261345} 가 {@code DateTimeParseException} 으로 터져
     * <b>400 이 아니라 500</b> 이 된다(이 경로를 덮는 {@code @ControllerAdvice} 가 레포에 없다).
     *
     * @param bgng 서비스가 파싱·검증을 끝낸 시작일
     * @param end  서비스가 파싱·검증을 끝낸 종료일 (당일 포함)
     */
    public ComplaintRequest toComplaintRequest(LocalDate bgng, LocalDate end) {
        ComplaintRequest r = new ComplaintRequest();
        r.setStartDt(bgng);
        r.setEndDt(end);
        r.setSggCd(sggCd);
        r.setEmdCd(emdCd);
        r.setCvlcptTypeCd(cvlcptTypeCd);
        // unit 은 건드리지 않는다 — startDt·endDt 가 둘 다 있으면 hasExplicitRange()==true 라 무시된다.
        return r;
    }
}
