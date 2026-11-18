package jeju.datahub.analysis.service;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 보고서 1건의 <b>분석 실행 메타</b> 조회 전용 VO.
 * ({@code ReportMapper.selectHotspotExcnMeta} 결과)
 *
 * <p>스냅샷 조회(GET {@code /api/reports/{rptpId}/hotspot})가 <b>본문을 읽기 전에</b> 판정해야 하는
 * 것들을 한 쿼리로 모은다 — 실행 유형(유형 404) · 스냅샷 행수(0행 404) · 재현 파라미터(가중치).
 * 구역 목록 쿼리({@code selectHotspotSnapshot})는 {@code rslt_json} 한 칸만 뽑아 이 셋을 알지 못한다.
 *
 * <p>🔴 <b>패키지가 {@code service} 바로 아래인 것은 의도다({@code service.impl} 이 아니다).</b>
 * {@code mybatis.type-aliases-package: jeju.datahub.**.service} 는 <b>{@code .service} 로 끝나는
 * 패키지만</b> 별칭을 만든다 — {@code service.impl} 에 두면 매퍼 XML 이 짧은 이름을 못 찾아 FQCN 을
 * 적어야 한다({@code ZoneNameCacheVO} 가 그런 경우다). {@link HotspotZoneVO}·{@link HotspotReportVO}
 * 와 같은 자리에 둔다.
 *
 * <p>🔴 <b>{@link #anlsParamJson} 은 {@code String} 이다.</b> 매퍼가 {@code anls_param_json::text} 로
 * 캐스팅해 읽기 때문이다 — {@code jsonb} 를 그대로 매핑하면 {@code PGobject} 가 와서 이 필드에 붙지
 * 않는다. 역직렬화(가중치 목록 복원)는 서비스가 Jackson 으로 한다.
 *
 * <p>{@code ReportListVO} 에 이 칸들을 더하지 않는 이유: 그 VO 는 목록·단건 응답 계약이라
 * 실행 파라미터 원문이 목록 응답에까지 딸려 나간다. 이 VO 는 응답으로 나가지 않는 내부 조회용이다.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class HotspotExcnMetaVO {

    /** 분석실행아이디 (tb_rptp.excn_id). 실행이 연결되지 않은 보고서면 null */
    private Long excnId;

    /**
     * 분석유형코드 (tb_anls_excn.anls_type_cd — {@code ANLS_TYPE_CD} 공통코드).
     * {@code HOTSPOT} 이 아니면 이 경로로 조회할 수 없다(404).
     */
    private String anlsTypeCd;

    /**
     * 분석파라미터 JSON 원문 (tb_anls_excn.anls_param_json 을 {@code ::text} 로 캐스팅한 값).
     * {@code {"eps":…,"minPoints":…,"limit":…,"weights":[…],"gradeCriteria":{…}}}.
     * 시드로 만들어진 실행 행은 NULL 일 수 있다.
     */
    private String anlsParamJson;

    /**
     * 스냅샷 구역 수 ({@code TB_ANLS_RSLT.rslt_json} 의 {@code jsonb_array_length(->'zones')}).
     * 결과 행 자체가 없으면 {@code COALESCE} 로 0 이 온다. 0 이면 열 수 없는 보고서라 404 로 끊는다 — 빈 구역 목록과 NULL 가중치가 화면으로 새는 것을 막는다.
     */
    private int rsltCnt;
}
