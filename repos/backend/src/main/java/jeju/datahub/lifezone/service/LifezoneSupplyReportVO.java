package jeju.datahub.lifezone.service;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 생활권 <b>수급</b> 보고서 = 분석 응답 + 보고서 헤더 7칸. (TSK-4089)
 *
 * <p>민원건수 보고서({@link LifezoneReportVO})와 <b>헤더는 같고 본문이 다르다</b> — 본문이
 * {@link LifezoneSupplyResultVO}(🔁 TSK-4258 — 부족률 등간격 5단계 · 구 스냅샷은 4사분면)라 상속 대상이 갈린다.
 */
@Schema(description = "생활권 주차 수급 보고서 (분석 응답 + 보고서 헤더)")
@Getter
@Setter
@ToString(callSuper = true)
@NoArgsConstructor
public class LifezoneSupplyReportVO extends LifezoneSupplyResultVO {

    @Schema(description = "보고서아이디", example = "101")
    private Long rptpId;

    @Schema(description = "분석실행아이디", example = "101")
    private Long excnId;

    @Schema(description = "보고서명", example = "생활권 주차 수급 보고서 전체 지역 500m")
    private String rptpNm;

    @Schema(description = "지역명 (전체 지역 | {시군구} 전체 | {시군구} {읍면동})", example = "전체 지역")
    private String regionNm;

    @Schema(description = "분석기간설명", example = "2025.01.01 ~ 2025.08.31")
    private String anlsPrdExpln;

    @Schema(description = "파일형식 (PDF | DOCX | XLSX)", example = "PDF")
    private String fileFmt;

    /**
     * 보고서 표에 실은 등급 (🔁 TSK-4258). 🔴 {@code null} = 전체. 셀({@code cells})은 등급과 무관하게 <b>전량</b>이다.
     * 신규 스냅샷({@code anlsMthd = SHRT_RT})만 이 값을 읽는다.
     */
    @Schema(description = "보고서 표에 실은 등급 (SEVERE | WARN | CAUTION · null = 전체). 셀 목록은 이 값과 무관하게 전량",
            example = "SEVERE", nullable = true)
    private String grade;

    /**
     * 보고서 표에 실은 사분면 (TSK-4227 D17). 🔴 {@code null} = 전체 — 이 값이 없던 과거 스냅샷도 {@code null}
     * 이라 종전대로 전체를 그린다. 셀({@code cells})은 사분면과 무관하게 <b>전량</b>이다.
     *
     * @deprecated TSK-4258 — 구 스냅샷(사분면) 전용(D10·D14). 신규 스냅샷에는 쓰지 않아 키가 생략된다.
     *             🔴 NON_NULL 은 필드 단위다 — 클래스 단위로 걸면 "null = 전체" 인 {@link #grade} 까지 사라진다.
     */
    @Deprecated
    @Schema(description = "(구 스냅샷 전용) 보고서 표에 실은 사분면 (Q1 | Q2 | Q3 | Q4 · null = 전체)",
            example = "Q1", nullable = true, deprecated = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String qdrn;

    @Schema(description = "생성일시", example = "2026-09-13T01:03:11")
    private LocalDateTime crtYmd;

    /**
     * 엑셀 재계산 데이터 (TSK-4340 D7) — 후보 격자 + 계수. 형식과 무관하게 새 보고서마다 저장된다.
     * 🔴 <b>필드 초기값을 두지 않는다</b> — 이 값을 저장하기 전 보고서는 {@code null} 이다(D7-1 — 프론트가 안내 문구를 띄운다).
     */
    @Schema(description = "엑셀 재계산 데이터 (후보 격자 + 계수). 이 값을 저장하기 전 보고서는 null", nullable = true)
    private LifezoneSupplyWorkbookVO wbk;
}
