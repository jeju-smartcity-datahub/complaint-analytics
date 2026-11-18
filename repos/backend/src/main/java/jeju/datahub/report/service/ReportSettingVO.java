package jeju.datahub.report.service;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 보고서 자동 생성 설정 조회 전용 VO.
 * (parking.TB_RPTP_AUTO_CRT_STNG + TB_ADMDST + TB_COM_CD — MyBatis 전용)
 *
 * <p>컬럼(스네이크) → 필드(카멜) 매핑은
 * {@code mybatis.configuration.map-underscore-to-camel-case=true} 로 자동 처리.
 *
 * <p><b>지역 표시명({@code regionNm})은 서버가 만들어 내린다</b>.
 * 프론트가 {@code sggNm}/{@code emdNm} 을 조합하지 않는다 — 조합 규칙이 화면마다 갈리는 것을 막는다.
 * 규칙은 알림 수신자와 동일한 3단이다:
 * 둘 다 NULL = {@code 전체 지역} / {@code sggNm} 만 = {@code {시군구} 전체} /
 * {@code emdCd} 있으면 = {@code {시군구} {읍면동}}.
 */
@Schema(description = "보고서 자동 생성 설정")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ReportSettingVO {

    /** 설정아이디 (stng_id, BIGINT) */
    @Schema(description = "설정아이디", example = "1")
    private Long stngId;

    /** 보고서종류 코드 (rptp_type_cd — ANLS_TYPE_CD 중 addl_atrb1 IN ('RPTP','BOTH')) */
    @Schema(description = "보고서종류 코드", example = "CVLCPT")
    private String rptpTypeCd;

    /** 보고서종류명 (TB_COM_CD.cd_nm, group_cd=ANLS_TYPE_CD) */
    @Schema(description = "보고서종류명", example = "민원현황")
    private String rptpTypeNm;

    /** 배포부서 코드 (crltn_dept_cd) */
    @Schema(description = "배포부서 코드", example = "CIVIL")
    private String crltnDeptCd;

    /** 배포부서명 (TB_DEPT.dept_nm) */
    @Schema(description = "배포부서명", example = "종합민원실")
    private String crltnDeptNm;

    /** 배포주기 코드 (crltn_cycl_cd) */
    @Schema(description = "배포주기 코드", example = "MONTH")
    private String crltnCyclCd;

    /** 배포주기명 (TB_COM_CD.cd_nm, group_cd=CRLTN_CYCL_CD) */
    @Schema(description = "배포주기명", example = "월간")
    private String crltnCyclNm;

    /** 배포기준월 (crltn_mm, 1~12). 주기가 월간이면 null */
    @Schema(description = "배포기준월 (1~12, 월간이면 null)", example = "1")
    private Integer crltnMm;

    /** 배포기준일 (crltn_dd, 1~28 — 말일 편차를 피해 28 로 제한) */
    @Schema(description = "배포기준일 (1~28)", example = "5")
    private Integer crltnDd;

    /**
     * 다음 배포 예정일.
     *
     * <p>🔁 2026-08-24 — <b>DB 컬럼이 아니다.</b> crltn_ymd 컬럼이 삭제됐고
     * ({@code 45-DROP-RPTP-STNG-COLUMNS.sql}), 이 값은 조회할 때마다 서비스가
     * 주기·기준월·기준일로 계산해 채운다({@code ReportServiceImpl.fillNextCrltnYmd}).
     * 그래서 언제 읽어도 오늘 이후의 가장 이른 배포일이다 — 저장하던 시절처럼 낡지 않는다.
     * API 계약은 종전과 같다.
     */
    @Schema(description = "다음 배포 예정일", example = "2026-09-05")
    private LocalDate crltnYmd;

    /** 활성여부 (actvtn_yn) — 배치가 이 설정을 돌릴지. 삭제 여부와는 다른 뜻이다 */
    @Schema(description = "활성여부", example = "true")
    private Boolean actvtnYn;

    /** 생성자ID (creatr_id — 등록한 로그인 사용자의 user_id. 자동 생성 보고서의 생성자로도 쓰인다) */
    @Schema(description = "생성자ID", example = "system")
    private String creatrId;

    /** 생성자명 (creatr_nm — 화면 표시용) */
    @Schema(description = "생성자명", example = "박서준")
    private String creatrNm;

    /** 생성일시 (crt_dt) */
    @Schema(description = "생성일시", example = "2026-07-01T09:00:00")
    private LocalDateTime crtDt;

    /** 최근생성일시 (last_crt_dt). null 이면 화면에 `미생성` 으로 표시한다 */
    @Schema(description = "최근생성일시 (null = 미생성)", example = "2026-08-05T02:00:00")
    private LocalDateTime lastCrtDt;

    /** 시군구명 (sgg_nm). null 이면 전체 지역 */
    @Schema(description = "시군구명 (null = 전체 지역)", example = "제주시")
    private String sggNm;

    /** 읍면동코드 (emd_cd). null 이면 시군구 전체 */
    @Schema(description = "읍면동코드 (null = 시군구 전체)", example = "50110560")
    private String emdCd;

    /** 읍면동명 (TB_ADMDST.emd_nm) */
    @Schema(description = "읍면동명", example = "연동")
    private String emdNm;

    /** 지역 표시명 — 서버가 3단 규칙으로 조립한다 */
    @Schema(description = "지역 표시명 (전체 지역 | {시군구} 전체 | {시군구} {읍면동})", example = "제주시 연동")
    private String regionNm;

    /**
     * 삭제 시각 (del_dt). NULL=존재, 값 있음=삭제됨 (소프트 삭제).
     *
     * <p><b>응답에는 내리지 않는다</b>({@code @JsonIgnore}) — 화면에 복원 UI 가 없어 계약에 노출할 이유가 없다.
     * 목록({@code selectSettingList})은 {@code del_dt IS NULL} 만 읽으므로 항상 null 이고,
     * 단건({@code selectSettingById})만 값을 채운다 — 수정·삭제가
     * "없음(404)"과 "이미 삭제됨(404)"을 구분하는 데 쓴다.
     */
    @JsonIgnore
    @Schema(hidden = true)
    private LocalDateTime delDt;
}
