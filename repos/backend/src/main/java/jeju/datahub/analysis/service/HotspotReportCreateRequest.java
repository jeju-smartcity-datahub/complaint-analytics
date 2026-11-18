package jeju.datahub.analysis.service;

import java.math.BigDecimal;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 집중구역 분석 보고서 생성 요청. (POST /api/analysis/hotspot/reports)
 *
 * <p>조회 조건은 {@link HotspotAnalysisRequest} 와 <b>같은 필드</b>이고 {@link #fileFmt} 한 칸만 더한다(D3).
 * 🔴 <b>{@link HotspotAnalysisRequest} 를 상속하지 않고 필드를 나열</b>한다 — 상속하면 springdoc 이
 * 부모/자식 스키마를 {@code allOf} 로 엮어 Swagger 문서가 갈리고, 조회 API 의 파라미터 문서가
 * 생성 API 의 요청 바디 문서에 끌려 들어간다(TSK-4078 Step 4).
 *
 * <p>대신 필드가 두 곳에 존재하는 값을 치르므로, 두 요청을 잇는 변환은 {@link #toAnalysisRequest()}
 * 한 곳에만 둔다 — Step 5 의 서비스가 필드를 손으로 옮겨 담으면 칸이 하나 늘 때 조용히 누락된다.
 *
 * <p>지역 조건({@link #sggNm}·{@link #emdCd})은 <b>키를 아예 넣지 않는 것</b>이 "전체"다 —
 * 빈 문자열은 400 이다. 값이 실제로 존재하는 지역인지(미등록 시군구·미존재 읍면동)는
 * 여기서 판정하지 않고 서비스 계층이 검증한다({@code HotspotAnalysisServiceImpl#resolveRegion}).
 */
@Schema(description = "집중구역 분석 보고서 생성 요청")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class HotspotReportCreateRequest {

    /** 시군구명(선택). 생략하면 전 지역. 예: {@code 제주시} */
    @Schema(description = "시군구명 (생략 = 전 지역)", example = "제주시")
    private String sggNm;

    /**
     * 읍면동코드(선택). 생략하면 선택 시군구 전체.
     *
     * <p>지역 필터는 원천 테이블의 {@code emd_cd} 로 <b>군집화 전에</b> 거른다(D19).
     */
    @Schema(description = "읍면동코드 (생략 = 시군구 전체)", example = "50110137")
    private String emdCd;

    /** 분석 시작일 (YYYYMMDD, 필수) */
    @NotBlank(message = "bgngYmd 는 필수입니다")
    @Pattern(regexp = "\\d{8}", message = "bgngYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "분석 시작일 (YYYYMMDD)", example = "20251201", requiredMode = Schema.RequiredMode.REQUIRED)
    private String bgngYmd;

    /** 분석 종료일 (YYYYMMDD, 필수 · 당일 포함) */
    @NotBlank(message = "endYmd 는 필수입니다")
    @Pattern(regexp = "\\d{8}", message = "endYmd 는 YYYYMMDD 형식이어야 합니다")
    @Schema(description = "분석 종료일 (YYYYMMDD, 당일 포함)", example = "20251231", requiredMode = Schema.RequiredMode.REQUIRED)
    private String endYmd;

    /**
     * 결과 구역 개수. <b>미지정이면 전량</b> — 상한이 없다 (TSK-4172 D8).
     *
     * <p>순위 목록과 지도 마커는 <b>같은 집합</b>이라 이 값 하나가 둘 다를 정한다.
     * 🔁 종전의 상한 30 을 걷었다 — "주의 이상 전부" 를 보여줘야 하므로 개수를 묶지 않고,
     * 대신 표시 하한({@link HotspotGrade#MIN_DISPLAYED})이 결과 수를 줄인다.
     *
     * <p>🔴 기본값을 두지 않는다({@code null} = 전량). {@code @Min(1)} 은 남긴다 —
     * {@code null} 은 통과하고 0·음수만 400 이다.
     */
    @Min(value = 1, message = "limit 은 1 이상이어야 합니다")
    @Schema(description = "결과 구역 개수 (미지정이면 전량 — 표시 하한 이상 모두)", example = "15")
    private Integer limit;

    /**
     * 민원 가중치(%, 선택) — 분석 화면에서 일시 조정한 값이다 (TSK-4306).
     *
     * <p>{@link #crdnWgvl} 과 <b>함께</b> 보내거나 함께 생략한다. 둘 다 생략하면 설정값으로 분석한다(D1).
     * 한쪽만 · 합계가 100 이 아님 · 설정 범위 밖 · 소수 셋째 자리 이상 · 꺼진 지표가 있음은 400 이다(D2·D3) —
     * 판정은 {@code HotspotWeightResolver} 한 곳이다. 설정 표에는 쓰지 않는다.
     */
    @Schema(description = "민원 가중치 (%, 선택 — crdnWgvl 과 함께 보내며 합계 100. 둘 다 생략 = 설정값)", example = "80")
    private BigDecimal cvlcptWgvl;

    /** 단속 가중치(%, 선택). 규칙은 {@link #cvlcptWgvl} 과 같다 (TSK-4306). */
    @Schema(description = "단속 가중치 (%, 선택 — cvlcptWgvl 과 함께 보내며 합계 100. 둘 다 생략 = 설정값)", example = "20")
    private BigDecimal crdnWgvl;

    /**
     * 분석 계수 일시 입력(선택, TSK-4325 D1). 조회의 JSON 문자열과 달리 바디 객체다.
     *
     * <p>🔴 집중구역은 등급 비율 {@code GRD_BNDRY1~3} 만 받는다 — {@code COMPLAINT} 등은 400 이다(D2).
     * 화면은 마지막으로 성공한 조회의 값을 보낸다(D7). 생략하면 설정값이다.
     */
    @Schema(description = "분석 계수 일시 입력 (선택 — GRD_BNDRY1~3 만. 생략 = 설정값)",
            example = "{\"GRD_BNDRY1\":3,\"GRD_BNDRY2\":7,\"GRD_BNDRY3\":12}")
    private Map<String, BigDecimal> vrblVls;

    /**
     * 파일형식 (필수). {@code PDF}·{@code DOCX}·{@code XLSX} 중 하나 — 그 밖의 값이면 <b>400</b>.
     *
     * <p>🔴 화이트리스트를 <b>요청 VO 의 제약으로</b> 건다 — 서비스에서 if 로 거르면 컨트롤러의
     * {@code @Valid} 와 검증 지점이 갈려 Swagger 문서에도 값 목록이 드러나지 않는다.
     * 공통코드 {@code FILE_FMT_CD} 중 보고서가 받는 세 값이다({@code HWP} 는 받지 않는다).
     * 🆕 {@code XLSX} 는 TSK-4338 D7 — 형식과 무관하게 스냅샷에 엑셀 데이터({@code wbk})가 실린다(D7-1).
     *
     * <p>기본값을 두지 않는다 — 화면이 "PDF / DOCX / Excel 로 저장" 버튼으로 갈라져 있어
     * 생략은 곧 프론트의 버그다. 조용히 PDF 로 떨어뜨리면 그 버그가 드러나지 않는다.
     */
    @NotBlank(message = "fileFmt 는 필수입니다")
    @Pattern(regexp = "^(PDF|DOCX|XLSX)$", message = "fileFmt 는 PDF, DOCX, XLSX 중 하나여야 합니다")
    @Schema(description = "파일형식 (PDF | DOCX | XLSX)", example = "PDF",
            allowableValues = {"PDF", "DOCX", "XLSX"}, requiredMode = Schema.RequiredMode.REQUIRED)
    private String fileFmt;

    /**
     * 조회 조건만 떼어 분석 요청으로 옮긴다 — {@link #fileFmt} 는 분석과 무관하므로 싣지 않는다.
     *
     * <p>보고서는 화면이 보낸 숫자를 믿지 않고 <b>같은 조건으로 다시 분석</b>한 결과를 저장한다(D3).
     * 그 재분석의 입력이 이 메서드다.
     */
    public HotspotAnalysisRequest toAnalysisRequest() {
        HotspotAnalysisRequest req = new HotspotAnalysisRequest();
        req.setSggNm(sggNm);
        req.setEmdCd(emdCd);
        req.setBgngYmd(bgngYmd);
        req.setEndYmd(endYmd);
        req.setLimit(limit);
        // 🔴 빠뜨리면 컴파일 오류 없이 보고서만 설정값으로 분석된다 — 화면과 순위가 갈린다 (TSK-4306)
        req.setCvlcptWgvl(cvlcptWgvl);
        req.setCrdnWgvl(crdnWgvl);
        // 🔴 쿼리 칸(String vrblVls)이 아니라 보고서 경로 전용 칸으로 옮긴다 (TSK-4325)
        req.setVrblVlsMap(vrblVls);
        return req;
    }
}
