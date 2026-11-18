package jeju.datahub.lifezone.service;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 생활권 시뮬레이션 보고서 응답 루트.
 * (POST /api/analysis/lifezone/reports · GET /api/reports/&#123;rptpId&#125;/lifezone — TSK-4088 Step 11)
 *
 * <p>🔴 <b>{@link LifezoneAnalysisResultVO} 를 상속한다</b> — 스냅샷 조회가 "Step 4 GET 과 같은 모양"
 * 이어야 하기 때문이다(계약). 필드를 손으로 베껴 두면 조회 응답에 칸이 하나 늘 때 보고서 응답만
 * 조용히 뒤처져, <b>같은 화면 코드가 두 경로에서 다르게 동작</b>한다. 여기 더하는 것은 보고서
 * 헤더 7칸뿐이며 본문(조건 echo · 등급 기준 · 요약 · 셀 · 순위)은 부모가 그대로 소유한다.
 *
 * <p>⚠️ 상속은 springdoc 스키마를 {@code allOf} 로 엮지만, <b>응답</b> 스키마라 요청 파라미터
 * 문서를 오염시키지 않는다({@code LifezoneReportCreateRequest} 가 상속을 피한 이유와 다르다).
 *
 * <h2>POST 와 GET 의 비대칭 (의도된 것)</h2>
 * <table border="1">
 *   <caption>스냅샷 조회에서 값이 달라지는 칸</caption>
 *   <tr><th>칸</th><th>GET(스냅샷)</th><th>이유</th></tr>
 *   <tr><td>{@code dataBgngYmd}·{@code dataEndYmd}</td><td>{@code null}</td>
 *       <td>원천 보유 구간은 <b>지금</b>의 값이라 스냅샷이 아니다 — 굳혀 두면 옛 값이 사실인 척한다</td></tr>
 *   <tr><td>{@code cells[].grade}</td><td>저장 경계로 <b>재판정</b></td>
 *       <td>셀 전량이라 등급을 저장하지 않는다(TSK-4078 D9 관례). 경계는 {@code gradeCriteria} 가 굳혔다</td></tr>
 *   <tr><td>{@code ranking[].grade}</td><td>저장값 그대로</td>
 *       <td>순위 행(심각·경고·주의)뿐이라 셀 전량보다 작고, 보고서 문서가 등급 배지를 그대로 쓴다</td></tr>
 * </table>
 */
@Schema(description = "생활권 시뮬레이션 보고서 (분석 응답 + 보고서 헤더)")
@Getter
@Setter
@ToString(callSuper = true)
@NoArgsConstructor
public class LifezoneReportVO extends LifezoneAnalysisResultVO {

    /** 보고서아이디 (rptp_id — TB_RPTP PK) */
    @Schema(description = "보고서아이디", example = "100")
    private Long rptpId;

    /** 실행아이디 (excn_id — TB_ANLS_EXCN PK. 결과 1행이 이 값으로 묶인다) */
    @Schema(description = "분석실행아이디", example = "100")
    private Long excnId;

    /**
     * 보고서명. {@code 생활권 {분석유형명} 보고서 {지역표시명} {격자}} (D14).
     * {@code LifezoneReportNaming} 이 이 문자열의 유일한 출처다.
     */
    @Schema(description = "보고서명", example = "생활권 민원건수 보고서 전체 지역 500m")
    private String rptpNm;

    /**
     * 지역명. 서버 3단 규칙 — {@code 전체 지역} / {@code 제주시 전체} / {@code 제주시 연동}.
     *
     * <p>DB 컬럼이 아니다 — POST 는 {@code LifezoneReportNaming} 이, GET 은
     * {@code ReportMapper.selectReportById} 의 CASE 가 만든다. <b>두 경로가 같은 문자열을 내야 한다.</b>
     */
    @Schema(description = "지역명 (전체 지역 | {시군구} 전체 | {시군구} {읍면동})", example = "전체 지역")
    private String regionNm;

    /** 분석기간설명 (anls_prd_expln — {@code yyyy.MM.dd ~ yyyy.MM.dd}, 시작=종료면 한 번만) */
    @Schema(description = "분석기간설명", example = "2026.07.04 ~ 2026.08.31")
    private String anlsPrdExpln;

    /** 파일형식 (file_fmt — PDF | DOCX | XLSX. 요청값을 그대로 저장·반환한다) */
    @Schema(description = "파일형식 (PDF | DOCX | XLSX)", example = "PDF")
    private String fileFmt;

    /** 생성일시 (crt_ymd — 보고서 생성 시각. 문서 부제의 생성일 출처) */
    @Schema(description = "생성일시", example = "2026-08-31T14:03:11")
    private LocalDateTime crtYmd;
}
