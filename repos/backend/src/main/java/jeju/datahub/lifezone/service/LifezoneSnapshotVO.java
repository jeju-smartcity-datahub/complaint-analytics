package jeju.datahub.lifezone.service;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 보고서 1건의 <b>생활권 스냅샷 원문</b> 조회 전용 VO.
 * ({@code ReportMapper.selectLifezoneSnapshot} 결과 — TSK-4088 Step 11)
 *
 * <p>스냅샷 조회(GET {@code /api/reports/{rptpId}/lifezone})가 필요로 하는 것을 <b>한 쿼리로</b>
 * 모은다 — 유형 판정(404) · 결과 원문 · 재현 메타 · 실행 조건(기간·지역). 집중구역은 이 자리를
 * 두 statement({@code selectHotspotExcnMeta} + {@code selectHotspotSnapshot})로 나눴지만,
 * 생활권은 <b>기간·지역까지 응답 본문에 되비춰야</b> 해서(Step 4 GET 과 같은 모양) 어차피 실행 행을
 * 읽어야 하므로 한 번에 읽는다. 헤더 4칸(보고서명·지역명·기간표기·생성일시)은 여기서 주지 않고
 * {@code selectReportById} 를 재사용한다 — 지역 3단 CASE 를 두 곳에 복제하지 않기 위해서다.
 *
 * <p>🔴 <b>패키지가 {@code service} 바로 아래인 것은 의도다({@code service.impl} 이 아니다).</b>
 * {@code mybatis.type-aliases-package: jeju.datahub.**.service} 는 {@code .service} 로 끝나는
 * 패키지만 별칭을 만든다 — {@code service.impl} 에 두면 매퍼 XML 이 짧은 이름을 못 찾는다.
 *
 * <p>🔴 <b>두 JSON 칸이 모두 {@link String} 이다.</b> 매퍼가 {@code ::text} 로 캐스팅해 읽기 때문이다 —
 * {@code jsonb} 를 그대로 매핑하면 {@code PGobject} 가 와서 이 필드에 붙지 않는다. 역직렬화는
 * 서비스가 Jackson 으로 한다.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class LifezoneSnapshotVO {

    /** 분석실행아이디 (tb_rptp.excn_id). 실행이 연결되지 않은 보고서면 null */
    private Long excnId;

    /**
     * 분석유형코드 (tb_anls_excn.anls_type_cd — {@code ANLS_TYPE_CD} 공통코드).
     * {@code LIFEZONE} 이 아니면 이 경로로 조회할 수 없다(404).
     */
    private String anlsTypeCd;

    /** 분석시작일자 (tb_anls_excn.anls_bgng_ymd, {@code YYYYMMDD}) — 응답 {@code bgngYmd} */
    private String anlsBgngYmd;

    /** 분석종료일자 (tb_anls_excn.anls_end_ymd, {@code YYYYMMDD}) — 응답 {@code endYmd} */
    private String anlsEndYmd;

    /** 시군구명 (tb_anls_excn.sgg_nm). 전 지역이면 null */
    private String sggNm;

    /** 읍면동코드 (tb_anls_excn.emd_cd). 시군구 전체면 null */
    private String emdCd;

    /**
     * 분석파라미터 JSON 원문 ({@code anls_param_json::text}).
     * {@code {"gridSz":…,"anlsTrgtCd":…,"clsfMthd":…,"gradeScale":{…},"gradeCriteria":[…]}}.
     * 🔴 등급 경계가 여기에만 있다 — 없으면 {@code cells} 등급을 재판정할 수 없다(D17).
     */
    private String anlsParamJson;

    /**
     * 분석결과 JSON 원문 ({@code rslt_json::text}).
     * {@code {"summary":{…},"ranking":[…],"cells":[…]}} — 결과만 담는다(D13).
     * 결과가 저장돼 있지 않으면 null 이다(404).
     */
    private String rsltJson;
}
