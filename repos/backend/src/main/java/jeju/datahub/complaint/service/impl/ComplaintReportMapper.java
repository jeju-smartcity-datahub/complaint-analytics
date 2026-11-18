package jeju.datahub.complaint.service.impl;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import jeju.datahub.complaint.service.ComplaintAdmdstNmVO;
import jeju.datahub.complaint.service.ComplaintSnapshotVO;

/**
 * 민원현황 보고서 생성이 쓰는 조회 매퍼 — <b>지역 코드 → 지역명 변환</b> 하나뿐이다.
 *
 * <p>저장(3표 INSERT)은 JPA 담당이고, 여기 있는 것은
 * <b>저장 전에 채워야 하는 값을 읽는</b> 조회다. 화면은 코드({@code sggCd}·{@code emdCd})를 보내고
 * {@code TB_ANLS_EXCN} 은 이름({@code sgg_nm})을 저장하므로 변환은 <b>서버</b>가 한다 —
 * 프론트가 이름을 함께 보내면 코드와 이름이 어긋난 조합을 막을 방법이 없다.
 *
 * <p>{@code TB_ADMDST} 에는 JPA 엔티티가 없고(여러 도메인이 조인으로만 쓴다) 두 칸을 읽고 버리므로
 * 엔티티를 새로 만들지 않는다 — {@code HotspotReportMapper} 와 같은 판단이다.
 *
 * <p>집계 SQL 의 정본인 {@link ComplaintMapper} 에 얹지 않고 매퍼를 나눈 이유는 <b>수명이 다르기
 * 때문</b>이다 — 저쪽은 민원현황 조회 SQL 의 정본이고, 이쪽은 보고서 저장 경로에서만 쓰인다.
 */
@Mapper
public interface ComplaintReportMapper {

    /**
     * 시군구코드(5자리) → 시군구명.
     *
     * <p>🔴 {@code TB_ADMDST} 에 <b>{@code sgg_cd} 컬럼이 없다</b>(PK 는 {@code emd_cd}). 그래서
     * {@code LEFT(emd_cd, 5)} 로 대조한다 — {@code ComplaintMapper.xml} 의 집계 필터와 같은 방식이라
     * "조회는 걸리는데 보고서는 400" 같은 불일치가 생기지 않는다.
     *
     * @param sggCd 시군구코드 (5자리 — 50110=제주시, 50130=서귀포시)
     * @return 시군구명. 없는 코드면 {@code null} (호출부가 400 으로 끊는다)
     */
    String selectSggNmBySggCd(@Param("sggCd") String sggCd);

    /**
     * 읍면동코드 → 시군구명 + 읍면동명 (한 번의 조회로 <b>둘 다</b>).
     *
     * <p>🔴 두 칸을 함께 읽어야 보고서명 {@code 민원 현황 보고서 제주시 연동} 이 나온다 —
     * {@code ReportMapper.selectSggNmByEmdCd} 는 {@code sgg_nm} 하나뿐이라 쓸 수 없다.
     *
     * @param emdCd 읍면동코드 (8자리 — {@code TB_ADMDST} PK 라 최대 1행)
     * @return 시군구명 + 읍면동명. 없는 코드면 {@code null} (호출부가 400 으로 끊는다)
     */
    ComplaintAdmdstNmVO selectAdmdstNmByEmdCd(@Param("emdCd") String emdCd);

    // --- 민원현황 보고서 스냅샷 조회 --------------------------------------
    // 저장된 결과를 '생성 당시 그대로' 다시 읽는 경로다. 조회 전용 · 다중 JOIN 이라 MyBatis 다
    // 🔴 AnlsRsltRepository 에 조회 메서드를 늘리지 않는다 —
    // 그 인터페이스 주석이 "저장된 스냅샷 조회는 MyBatis 담당, 이 리포지터리는 save 만" 으로 못박혀 있다.
    // 🔴 report 도메인의 ReportMapper 가 아니라 여기에 둔다 — 민원 도메인의 statement 이고,
    //    같은 파일에 있으면 지역 코드 변환(위 두 개)과 수명이 같다.

    /**
     * 보고서에 묶인 민원현황 스냅샷 <b>한 행</b> — 실행 조건 + 재현 메타 + 결과 원문.
     *
     * <p>{@code rslt_json} 은 {@code {"kpi":{…},"emdCompare":[…],"negEmdCompare":[…]}} 이고
     * 키가 조회 응답 필드명 그대로라 <b>매핑 변환이 없다</b> — 역직렬화는 서비스가 Jackson 으로 한다.
     *
     * <p>🔴 두 JSON 칸 모두 {@code ::text} 캐스팅이 필수다 — {@code jsonb} 를 그대로 매핑하면
     * {@code PGobject} 가 와서 VO 의 {@code String} 필드에 붙지 않는다
     * ({@code mapper/report/ReportMapper.xml} 의 집중구역·생활권 스냅샷에 같은 경고가 있다).
     *
     * <p>헤더 5칸({@code rptpNm}·{@code regionNm}·{@code anlsPrdExpln}·{@code fileFmt}·
     * {@code crtYmd})은 주지 않는다 — {@code ReportMapper.selectReportById} 를 그대로 재사용한다
     * (지역 3단 CASE 를 여기 복제하면 같은 규칙이 두 곳에 살아 조용히 갈린다).
     *
     * @return 보고서 자체가 없으면 {@code null} (실행 미연결이면 행은 오고 {@code anlsTypeCd} 가 null,
     *         결과 미저장이면 {@code rsltJson} 이 null — 서비스가 셋을 각각 404 로 구분한다).
     *         🔴 배열이 빈 {@code []} 인 것은 정상이며 404 가 아니다(0건도 보고서다)
     */
    ComplaintSnapshotVO selectComplaintSnapshot(@Param("rptpId") Long rptpId);
}
