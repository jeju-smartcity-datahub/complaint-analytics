package jeju.datahub.report.service;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 보고서 목록 조회 전용 VO. (parking.TB_RPTP + TB_ANLS_EXCN + TB_ADMDST + TB_COM_CD — MyBatis 전용)
 *
 * <p>대시보드의 {@code jeju.datahub.dashboard.service.ReportVO} 와 이름이 겹치지 않게 {@code ReportListVO} 로 둔다.
 * 컬럼(스네이크) → 필드(카멜) 매핑은
 * {@code mybatis.configuration.map-underscore-to-camel-case=true} 로 자동 처리.
 *
 * <p><b>표시 값은 전부 '생성 시점 스냅샷' 이다.</b> 배포 주기·배포 부서는
 * {@code TB_RPTP} 가 직접 갖고, 지역은 {@code TB_ANLS_EXCN} 이 갖는다 — 자동 생성 설정
 * ({@code stng_id})을 JOIN 해 읽으면 <b>설정을 고치는 순간 과거 보고서의 표기까지 소급해 바뀐다.</b>
 *
 * <p><b>생성 구분에 따라 채워지는 칸이 다르다.</b>
 * <ul>
 *   <li>{@code AUTO} — 생성 시점 스냅샷으로 {@code cyclNm}·{@code deptNm} 이 채워진다.
 *       개정으로 {@code excn_id} 도 갖게 돼 {@code srcNm}·{@code regionNm} 이 함께 나온다
 *       (화면의 자동 생성 탭엔 출처 칸이 없어 표시는 하지 않는다).</li>
 *   <li>{@code MANUAL} — {@code excn_id} JOIN 으로 {@code srcNm}·{@code regionNm}·{@code anlsPrdExpln}
 *       이 채워지고, 설정으로 만든 보고서가 아니라 {@code cyclNm}·{@code deptNm} 은 null 이다.</li>
 * </ul>
 */
@Schema(description = "보고서 목록 행 (자동 생성 / 직접 생성 공용)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ReportListVO {

    /** 보고서ID (rptp_id, BIGINT) */
    @Schema(description = "보고서ID", example = "1")
    private Long rptpId;

    /**
     * 분석실행ID (excn_id, BIGINT). <b>개정으로 AUTO·MANUAL 모두 값을 갖는다</b>
     * (자동 생성분도 생성 시점 지역을 담을 실행 행을 만든다).
     *
     * <p>출처({@code srcNm})·지역({@code regionNm})이 null 로 내려올 때 "실행 연결 누락"인지 확인하는 진단용이다.
     */
    @Schema(description = "분석실행ID", example = "3")
    private Long excnId;

    /** 생성구분 코드 (crt_type — AUTO | MANUAL | AI) */
    @Schema(description = "생성구분 코드", example = "AUTO")
    private String crtType;

    /** 생성구분명 (TB_COM_CD.cd_nm, group_cd=RPTP_CRT_TYPE_CD) */
    @Schema(description = "생성구분명", example = "자동")
    private String crtTypeNm;

    /** 보고서명 (rptp_nm) */
    @Schema(description = "보고서명", example = "2026년 6월 월간 민원 보고서")
    private String rptpNm;

    /** 배포주기 코드 (tb_rptp.crltn_cycl_cd — 생성 시점 스냅샷). 직접 생성이면 null */
    @Schema(description = "배포주기 코드 (자동 생성만)", example = "MONTH")
    private String cyclCd;

    /** 배포주기명 (TB_COM_CD.cd_nm, group_cd=CRLTN_CYCL_CD). 직접 생성이면 null */
    @Schema(description = "배포주기명 (자동 생성만)", example = "월간")
    private String cyclNm;

    /**
     * 출처명 — 분석 실행 유형의 코드명 <b>단독</b>.
     * 개정으로 자동 생성도 {@code excn_id} 를 가져 값이 채워지지만, 화면의 자동 생성 탭은 이 칸을 그리지 않는다.
     */
    @Schema(description = "출처명 (분석 실행 유형)", example = "민원현황")
    private String srcNm;

    /**
     * 분석유형코드 (tb_anls_excn.anls_type_cd — {@code ANLS_TYPE_CD} 공통코드).
     *
     * <p>{@link #srcNm} 은 같은 코드의 <b>표시명</b>이라 화면에 그리기는 좋아도 분기 조건으로는 쓸 수 없다
     * (코드명이 바뀌면 프론트 조건이 조용히 깨진다). 목록에서 "스냅샷을 열 수 있는 보고서"
     * ({@code HOTSPOT})만 미리보기·재다운로드를 띄우려면 <b>코드값</b>이 필요하다.
     *
     * <p>실행이 연결되지 않은 보고서면 null 이다.
     *
     * <p>🔴 {@code selectReportList} 와 {@code selectReportById} 가 <b>같은 VO 를 쓴다</b> —
     * 한쪽 SELECT 목록에만 넣으면 다른 쪽에서만 칸이 빈다.
     */
    @Schema(description = "분석유형코드 (스냅샷 조회 가능 여부 판별용)", example = "HOTSPOT")
    private String anlsTypeCd;

    /** 분석기간 설명 (anls_prd_expln). 직접 생성만 값을 갖는다 */
    @Schema(description = "분석기간 (직접 생성만)", example = "2026-05-01 ~ 2026-05-31")
    private String anlsPrdExpln;

    /** 생성자명 (creatr_nm — 사용자 마스터 도입 전 표시용 컬럼) */
    @Schema(description = "생성자명", example = "박서준")
    private String creatrNm;

    /** 배포부서 코드 (tb_rptp.crltn_dept_cd — 생성 시점 스냅샷). 직접 생성이면 null */
    @Schema(description = "배포부서 코드 (자동 생성만)", example = "CIVIL")
    private String deptCd;

    /** 배포부서명 (TB_DEPT.dept_nm). 직접 생성이면 null */
    @Schema(description = "배포부서명 (자동 생성만)", example = "종합민원실")
    private String deptNm;

    // ---------------------------------------------------------------- 생성 시점 지역
    // 실행(TB_ANLS_EXCN)이 갖는 값이다 — 설정(TB_RPTP_AUTO_CRT_STNG)을 읽지 않으므로
    // 설정을 수정·삭제해도 이미 만들어진 보고서의 지역 표기는 바뀌지 않는다.

    /** 시군구명 (tb_anls_excn.sgg_nm). NULL 이면 전체 지역 */
    @Schema(description = "시군구명 (생성 시점). null 이면 전체 지역", example = "제주시")
    private String sggNm;

    /** 읍면동코드 (tb_anls_excn.emd_cd). NULL 이면 시군구 전체 */
    @Schema(description = "읍면동코드 (생성 시점). null 이면 시군구 전체", example = "50110137")
    private String emdCd;

    /** 읍면동명 (TB_ADMDST.emd_nm — emd_cd JOIN) */
    @Schema(description = "읍면동명 (생성 시점)", example = "연동")
    private String emdNm;

    /**
     * 지역 표시명 — 서버가 3형태로 조립한다 (설정 목록의 {@code regionNm} 과 같은 규칙).
     * {@code {시군구} {읍면동}} / {@code {시군구} 전체} / {@code 전체 지역}.
     * 실행 연결이 없는 보고서는 null 이다.
     */
    @Schema(description = "지역 표시명 (생성 시점 — '제주시 연동' / '제주시 전체' / '전체 지역')", example = "제주시 연동")
    private String regionNm;

    /** 상태코드 (stts_cd) */
    @Schema(description = "상태코드", example = "DONE")
    private String sttsCd;

    /** 상태명 (TB_COM_CD.cd_nm, group_cd=RPTP_STTS_CD) */
    @Schema(description = "상태명", example = "완료")
    private String sttsNm;

    /** 파일형식 (file_fmt) */
    @Schema(description = "파일형식", example = "PDF")
    private String fileFmt;

    /** 생성일시 (crt_ymd, TIMESTAMP) */
    @Schema(description = "생성일시", example = "2026-07-01T09:00:00")
    private LocalDateTime crtYmd;
}
