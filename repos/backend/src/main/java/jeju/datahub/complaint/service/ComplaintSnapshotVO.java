package jeju.datahub.complaint.service;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 보고서 1건의 <b>민원현황 스냅샷 원문</b> 조회 전용 VO.
 * ({@code ComplaintReportMapper.selectComplaintSnapshot} 결과)
 *
 * <p>스냅샷 조회(GET {@code /api/reports/{rptpId}/complaint})의 <b>404 판정 3종</b>
 * (보고서 없음 / 유형 불일치 / 결과 미저장)을 <b>한 쿼리로</b> 가르기 위한 한 행이다 —
 * {@code LifezoneSnapshotVO} 와 같은 모양이다(집중구역은 같은 자리를 statement 두 개로 나눴다).
 *
 * <p>⚠️ <b>응답({@link ComplaintReportVO})으로 나가는 것은 {@link #excnId} 하나</b>다.
 * {@link #anlsTypeCd}·{@link #rsltJson} 은 404 판정·본문 복원에 쓰고, 나머지 4칸
 * ({@link #anlsBgngYmd}·{@link #anlsEndYmd}·{@link #sggNm}·{@link #emdCd})과
 * {@link #anlsParamJson} 은 <b>읽어만 둔다</b> — 기간·지역은 헤더의 {@code anlsPrdExpln}·
 * {@code regionNm} 이 이미 문자열로 주므로 응답 VO 에 칸을 늘리지 않는다(민원현황 응답 계약은
 * 헤더 7칸 + kpi + 배열 2개가 전부다. 생활권은 조건 echo 계약이라 되비추지만 여기는 아니다).
 *
 * <p>🔴 <b>패키지가 {@code service} 바로 아래인 것은 의도다({@code service.impl} 이 아니다).</b>
 * {@code mybatis.type-aliases-package: jeju.datahub.**.service} 는 {@code .service} 로 끝나는
 * 패키지만 별칭을 만든다 — {@code service.impl} 에 두면 매퍼 XML 이 짧은 이름을 못 찾아
 * FQN 을 박아야 한다. {@code LifezoneSnapshotVO}·{@code ComplaintAdmdstNmVO} 가 같은 이유로 거기 있다.
 *
 * <p>🔴 <b>두 JSON 칸이 모두 {@link String} 이다.</b> 매퍼가 {@code ::text} 로 캐스팅해 읽기 때문이다 —
 * {@code jsonb} 를 그대로 매핑하면 {@code PGobject} 가 와서 이 필드에 붙지 않는다. 역직렬화는
 * 서비스가 Jackson 으로 한다.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ComplaintSnapshotVO {

    /** 분석실행아이디 (tb_rptp.excn_id). 실행이 연결되지 않은 보고서면 null */
    private Long excnId;

    /**
     * 분석유형코드 (tb_anls_excn.anls_type_cd — {@code ANLS_TYPE_CD} 공통코드).
     * {@code CVLCPT} 가 아니면 이 경로로 조회할 수 없다(404 — 실행 미연결로 null 인 경우 포함).
     */
    private String anlsTypeCd;

    /** 분석시작일자 (tb_anls_excn.anls_bgng_ymd, {@code YYYYMMDD}). 응답에는 나가지 않는다 */
    private String anlsBgngYmd;

    /** 분석종료일자 (tb_anls_excn.anls_end_ymd, {@code YYYYMMDD}). 응답에는 나가지 않는다 */
    private String anlsEndYmd;

    /** 시군구명 (tb_anls_excn.sgg_nm). 전 지역이면 null. 응답에는 나가지 않는다 */
    private String sggNm;

    /** 읍면동코드 (tb_anls_excn.emd_cd). 시군구 전체면 null. 응답에는 나가지 않는다 */
    private String emdCd;

    /**
     * 분석파라미터 JSON 원문 ({@code anls_param_json::text}) —
     * {@code {"sggCd":…,"emdCd":…,"cvlcptTypeCd":…}} (재현 메타).
     *
     * <p>집중구역·생활권과 달리 <b>복원에 쓰지 않는다</b> — 민원현황 응답에는 등급 경계·가중치처럼
     * 이 JSON 으로만 되살릴 수 있는 칸이 없다. "어떤 조건으로 만든 보고서인가" 를 묻는 근거로만 남는다.
     */
    private String anlsParamJson;

    /**
     * 분석결과 JSON 원문 ({@code rslt_json::text}) —
     * {@code {"kpi":{…},"emdCompare":[…],"negEmdCompare":[…]}}.
     *
     * <p>결과가 저장돼 있지 않으면 null 이다(404-③). 🔴 <b>배열이 비어 있는 것({@code []})은
     * 404 가 아니다</b> — 0건 보고서는 정상이고 200 이다.
     */
    private String rsltJson;
}
