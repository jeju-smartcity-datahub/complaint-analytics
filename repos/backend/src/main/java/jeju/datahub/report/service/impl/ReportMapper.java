package jeju.datahub.report.service.impl;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import jeju.datahub.analysis.service.HotspotExcnMetaVO;
import jeju.datahub.lifezone.service.LifezoneSnapshotVO;
import jeju.datahub.report.service.ReportListFilter;
import jeju.datahub.report.service.ReportListVO;
import jeju.datahub.report.service.ReportSettingVO;

/**
 * 보고서 MyBatis 매퍼. SQL 은 resources/mapper/report/ReportMapper.xml.
 *
 * <p>다중 LEFT JOIN + 페이징 조회라 영속성 규칙상 MyBatis 담당이다.
 * {@code MyBatisConfig} 가 {@code @Mapper} 애노테이션이 붙은 인터페이스만 스캔하므로 애노테이션은 필수다.
 *
 * <p>⚠️ 여기 메서드명과 XML 의 {@code <select id="...">} 는 1:1 이어야 한다.
 * 어긋나도 컴파일은 통과하고 <b>호출 시점</b>에 {@code Invalid bound statement (not found)} 로 터진다.
 */
@Mapper
public interface ReportMapper {

    /**
     * 보고서 목록 (생성구분별, 최신순 페이징).
     *
     * <p>🔴 {@code ownerId} 는 <b>@Param 으로 반드시 선언한다</b> — XML 의
     * {@code ownerWhere} 조각이 OGNL 로 읽으므로, 빠지면 컴파일은 통과하고 <b>호출 시점</b>에
     * {@code no getter for 'ownerId'} 로 500 이 된다.
     *
     * @param crtType 생성구분 (AUTO | MANUAL | AI)
     * @param ownerId 요청자의 {@code TB_USER.user_id}. {@code null} 이면 MANUAL·AI 는 0건이다
     *                (미인증 전체 공개 방지 — 운영 체인은 401 이 먼저라 도달하지 않는 방어선)
     * @param filter  목록 필터. 🔴 {@code @Param("filter")} 필수 — XML {@code listFilterWhere} 가
     *                {@code filter.keyword} 등을 OGNL 로 읽어, 빠지면 호출 시점에 {@code no getter} 500 이다
     * @param size    LIMIT
     * @param offset  OFFSET (page * size — 오버플로를 피해 long 으로 받는다)
     */
    List<ReportListVO> selectReportList(@Param("crtType") String crtType,
                                        @Param("ownerId") String ownerId,
                                        @Param("filter") ReportListFilter filter,
                                        @Param("size") int size,
                                        @Param("offset") long offset);

    /**
     * 보고서 총건수 (생성구분별) — 페이징 메타용.
     *
     * <p>🔴 목록과 <b>같은 {@code ownerWhere}·{@code listFilterWhere} 조각</b>을 쓴다 — 조건이 갈리면
     * 페이지네이션이 어긋난다. {@code filter} 도 {@code @Param} 필수다.
     */
    int selectReportCount(@Param("crtType") String crtType, @Param("ownerId") String ownerId,
                          @Param("filter") ReportListFilter filter);

    /**
     * 자동 생성(AUTO) 보고서 1건이 요청자에게 보이는가 — 단건 가드 전용.
     *
     * <p>판정은 XML {@code autoVisible} 조각 <b>한 곳</b>이다(배포 부서 · {@code ALL} · 보고서 관리 메뉴 보유자).
     * 목록·총건수·대시보드와 같은 조각을 include 하므로 단건만 규칙이 갈리지 않는다.
     *
     * <p>🔴 {@code @Param} 두 개는 <b>반드시 선언한다</b> — 조각이 OGNL 로 {@code ownerId} 를 읽는다.
     *
     * @param rptpId  보고서 ID
     * @param ownerId 요청자의 {@code TB_USER.user_id}. {@code null} 이면 항상 {@code false}
     * @return 없는 보고서 · AUTO 가 아닌 보고서 · 열람 범위 밖이면 {@code false} (가드가 404 로 뭉갠다)
     */
    boolean existsVisibleAutoReport(@Param("rptpId") Long rptpId, @Param("ownerId") String ownerId);

    /** 보고서 단건 (미리보기 헤더용). 없으면 null */
    ReportListVO selectReportById(@Param("rptpId") Long rptpId);

    // --- 집중구역 보고서 스냅샷 조회 ------------------------------
    // 저장된 분석 결과를 '생성 당시 그대로' 다시 읽는 경로다. 조회 전용 · 다중 JOIN 이라 MyBatis 다
    // 결과 본문은 TB_ANLS_RSLT.rslt_json 한 칸이다. 두 statement 어느 쪽도 헤더(보고서명·지역명·기간·생성일시)를
    // 주지 않는다 — 헤더는 위 selectReportById 를 그대로 재사용한다(지역 3단 CASE 를 복제하지 않기 위해서다).

    /**
     * 보고서에 묶인 집중구역 분석 결과 JSON <b>원문 한 칸</b> ({@code TB_ANLS_RSLT.rslt_json}).
     *
     * <p>모양은 {@code {"zones":[{"rnk":…,"zoneNm":…,"cvlcptCnt":…,"crdnCnt":…,"snthsScr":…,
     * "mrkLat":…,"mrkLot":…}, …]}} 이고 배열은 저장 시점의 {@code rnk} 오름차순 그대로다.
     * 키가 {@code HotspotZoneVO} 필드명과 같아 <b>매핑 변환이 없다</b> — 역직렬화는 서비스가 Jackson 으로.
     *
     * <p>{@code ::text} 캐스팅으로 읽는다 (jsonb 를 그대로 매핑하면 {@code PGobject} 가 와서 붙지 않는다).
     *
     * <p>{@code emdNm}·{@code zoneNmSrc} 는 <b>담지 않는다</b> — 종전 결과 표에 컬럼이 없어 이관된
     * 기존 보고서에 그 두 칸이 없고, 새 보고서에만 값이 생기면 왕복 계약이 갈린다.
     * {@code grade}·{@code gradeNm} 도 저장하지 않으므로 서비스가 {@code HotspotGrade.of()} 로 채운다.
     *
     * @return 보고서가 없거나 결과가 저장돼 있지 않으면 {@code null}
     */
    String selectHotspotSnapshot(@Param("rptpId") Long rptpId);

    /**
     * 보고서 1건의 분석 실행 메타 — 유형 404 · 결과 없음 404 · 가중치 복원을 <b>한 쿼리로</b> 끝낸다.
     *
     * <p>{@code anls_param_json} 은 {@code ::text} 캐스팅으로 읽는다 (VO 필드가 {@code String} 이다.
     * jsonb 를 그대로 매핑하면 {@code PGobject} 가 와서 붙지 않는다).
     *
     * <p>{@code rsltCnt} 는 행수가 아니라 {@code jsonb_array_length(rslt_json->'zones')} 다.
     * 결과가 저장돼 있지 않으면 {@code COALESCE} 로 0 이 온다.
     *
     * @return 보고서 자체가 없으면 {@code null} (실행 미연결이면 행은 오고 {@code anlsTypeCd} 가 null)
     */
    HotspotExcnMetaVO selectHotspotExcnMeta(@Param("rptpId") Long rptpId);

    // --- 생활권 보고서 스냅샷 조회 -------------------------------
    // 집중구역과 같은 성격이지만 statement 는 하나다 — 생활권 응답이 기간·지역까지 되비추므로
    // (GET 과 같은 모양) 어차피 실행 행을 읽어야 하고, 유형 404 판정도 같은 행에서 나온다.

    /**
     * 보고서에 묶인 생활권 분석 스냅샷 <b>한 행</b> — 실행 조건 + 재현 메타 + 결과 원문.
     *
     * <p>{@code rslt_json} 은 {@code {"summary":{…},"ranking":[…],"cells":[…]}} 이고,
     * {@code anls_param_json} 은 격자·분석유형·분류방법·문구와 <b>등급 경계</b>
     * ({@code gradeScale}·{@code gradeCriteria})를 담는다 — 경계가 조회 결과에서 산출되므로
     * 이 값이 없으면 {@code cells} 등급을 재판정할 수 없다.
     *
     * <p>두 JSON 칸 모두 {@code ::text} 캐스팅으로 읽는다 (jsonb 를 그대로 매핑하면 {@code PGobject}
     * 가 와서 VO 의 {@code String} 필드에 붙지 않는다 — 바로 위 집중구역과 같은 함정).
     * 역직렬화는 서비스가 Jackson 으로 하며, 🔴 {@code LifezoneGradeScale} 은 record 라
     * <b>역직렬화하지 않고</b> {@code gradeCriteria[].minVl} 로 되살린다.
     *
     * <p>헤더(보고서명·지역명·기간표기·생성일시)는 주지 않는다 — 위 {@link #selectReportById} 를
     * 그대로 재사용한다(지역 3단 CASE 를 복제하지 않기 위해서다).
     *
     * @return 보고서 자체가 없으면 {@code null} (실행 미연결이면 행은 오고 {@code anlsTypeCd} 가 null,
     *         결과 미저장이면 {@code rsltJson} 이 null — 서비스가 셋을 각각 404 로 구분한다)
     */
    LifezoneSnapshotVO selectLifezoneSnapshot(@Param("rptpId") Long rptpId);

    /** 자동 생성 설정 목록 (살아 있는 것만, {@code crt_dt DESC}) */
    List<ReportSettingVO> selectSettingList();

    /**
     * 자동 생성 설정 단건. <b>{@code del_dt} 조건을 걸지 않는다</b> —
     * "없음"과 "이미 삭제됨"을 서비스가 구분해야 하기 때문이다.
     *
     * <p>등록·수정이 같은 트랜잭션에서 쓰기 전후로 두 번 부르므로
     * XML 에 {@code flushCache="true"} 가 걸려 있다 (MyBatis 1차 캐시 함정).
     *
     * @return 없으면 null
     */
    ReportSettingVO selectSettingById(@Param("stngId") Long stngId);

    // --- 설정 CRUD 입력 검증용 조회 -----------------------------------------------
    // 쓰기는 JPA 지만 "값이 실제로 존재하는 코드·지역인가" 는 공통코드/행정구역 테이블을 읽어야 안다.
    // 단순 조회라 엔티티를 새로 만들 이유가 없어(대조만 하고 버린다) 스칼라로 받는다.

    /**
     * 보고서 종류로 쓸 수 있는 코드값 목록.
     * {@code ANLS_TYPE_CD} 중 {@code addl_atrb1 IN ('RPTP','BOTH')} 인 것만.
     *
     * <p>분석 화면 전용 코드({@code addl_atrb1='ANLS'}, 예: {@code POLICY_SIM})를 걸러내는 서버측 방어다 —
     * 프론트 드롭다운이 이미 필터하지만 요청은 프론트를 거치지 않고도 들어온다.
     */
    List<String> selectReportTypeCds();

    /**
     * 특정 코드 그룹의 사용중({@code use_yn='Y'}) 코드값 목록.
     * 설정 CRUD 는 {@code CRLTN_CYCL_CD} 에 쓴다.
     * (배포부서는 공통코드가 아니라 {@code TB_DEPT} 라 {@code DeptService.activeDeptCds()} 가 본다)
     */
    List<String> selectCodeValues(@Param("groupCd") String groupCd);

    /**
     * 읍면동코드로 시군구명을 찾는다 (지역 정규화).
     *
     * <p>클라이언트가 보낸 {@code sggNm} 을 믿지 않고 이 값으로 덮어쓴다.
     *
     * @return 없는 {@code emdCd} 면 null (→ 서비스가 400. FK 위반을 500 으로 흘리지 않는다)
     */
    String selectSggNmByEmdCd(@Param("emdCd") String emdCd);

    /** 시군구명 존재 확인 (읍면동 없이 시군구 전체를 지정한 경우). 없으면 0 */
    int countSggNm(@Param("sggNm") String sggNm);

    // --- 자동 생성 -----------------------------------------------------
    // 조회 3개다. 설정 1행은 위 selectSettingById 를 그대로 재사용한다 — 그 statement 가 이미
    // del_dt 까지 주므로("없음" 과 "삭제됨" 을 서비스가 가른다) 자동 생성용을 따로 만들 이유가 없다.

    /**
     * 같은 설정·같은 대상기간으로 이미 적재된 보고서의 ID (멱등 확인).
     *
     * <p>{@code uq_rptp_stng_prd(stng_id, trgt_prd_cd)} 가 걸린 두 칸이라 최대 1행이다.
     * 🔴 이 선조회는 <b>빠른 경로일 뿐</b>이다 — 동시 실행 경합은 여기를 빠져나가므로 최종 보증은
     * UNIQUE 제약이 진다({@code ReportAutoCrtServiceImpl} 이 제약 위반을 200 으로 접는다).
     *
     * <p>{@code stng_id} 가 NULL 인 직접 생성분은 PostgreSQL 의 NULL 비교 규칙상 자동으로 빠진다.
     *
     * @return 없으면 null
     */
    Long selectAutoCrtRptpId(@Param("stngId") Long stngId, @Param("trgtPrdCd") String trgtPrdCd);

    /**
     * 시군구<b>명</b> → 시군구<b>코드</b> (지역 변환).
     *
     * <p>민원현황 생성 요청은 코드({@code sggCd})를 받는데 자동 생성 설정은 이름({@code sgg_nm})만
     * 들고 있어 이 변환이 필요하다. 🔴 {@code TB_ADMDST} 에는 {@code sgg_cd} 컬럼이 <b>없다</b> —
     * 읍면동코드 앞 5자리가 곧 시군구코드다({@code 50110}=제주시 · {@code 50130}=서귀포시).
     *
     * <p>실재하는 것은 반대 방향({@code ComplaintReportMapper.selectSggNmBySggCd})뿐이라 새로 둔다.
     * 변환을 백엔드에 두는 이유는 {@code LEFT(emd_cd,5)} 규칙이 이미 백엔드 매퍼 여러 곳에 퍼져
     * 있어서다 — 배치가 변환하면 그 규칙이 레포 두 곳으로 갈린다.
     *
     * @return 없는 시군구명이면 null (→ 서비스가 400)
     */
    String selectSggCdBySggNm(@Param("sggNm") String sggNm);

    /**
     * 읍면동코드로 읍면동명을 찾는다 — 자동 생성 보고서명의 지역 접미용.
     *
     * <p>{@code ReportAutoCrtNaming.regionNm} 은 코드가 아니라 <b>이름</b>을 받는다. 시군구명은
     * 바로 위 {@link #selectSggNmByEmdCd} 가 주므로 여기서는 한 칸만 더 읽는다.
     *
     * @return 없는 {@code emdCd} 면 null (→ 서비스가 400. FK 위반을 500 으로 흘리지 않는다)
     */
    String selectEmdNmByEmdCd(@Param("emdCd") String emdCd);
}
