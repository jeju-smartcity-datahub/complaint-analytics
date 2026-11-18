package jeju.datahub.analysis.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 구역추천(요금제 적용) 분석 서비스 인터페이스.
 *
 * <p>이 화면이 답하는 질문은 <b>"이 읍면동에서 유료화한다면 어느 주차장부터 해야 하나"</b> 다
 * <b>읍면동을 추천하는 화면이 아니다.</b> 사용자가 읍면동을 고르면 그 안의 무료 주차장
 * 중 어디부터 유료화할지 순위를 낸다.
 *
 * <p>창구가 둘인 이유 — {@link #selectZones} 는 <b>읍면동을 고르기 위한</b> 참고 목록이고
 * ({@code TB_ADMDST} 전량 · 주차장 조회를 하지 않는다), {@link #selectZoneRcmdtn} 는 고른 뒤의
 * <b>주력</b>이다(그 동 안의 주차장 순위 + 지도 핀).
 *
 * <p>🔴 <b>기간 파라미터가 민원·단속 둘로 갈린다.</b> 단속 보유 구간이 민원과 달라
 * ({@code tb_crdn_dsctn} 은 2025-02~2026-10, 이도이동은 2025-09~2026-07 뿐) 민원 창을 그대로
 * 쓰면 배정 단속이 0건이 된다(종전엔 E0 판정이 이 창을 썼다). 단속 창은 생략 가능하고 기본값은
 * 분석 정본과 같은 20개월({@code 20250101}~{@code 20260831})이다.
 *
 * <p>🔴 {@code minCpcty}·{@code altmntRng} 는 <b>요청 파라미터가 아니라 설정</b>이다
 * ({@code RECOMMEND} 분석변수). 적용값은 응답 {@code crtrVls} 에 굳힌다 — 특히 배정 반경은
 * 값을 바꾸면 순위가 뒤집히므로 어느 기준으로 낸 목록인지 남아야 재현된다.
 *
 * <p>⚠️ 구현({@code ZoneRcmdtnServiceImpl})은 {@code YYYYMMDD} 문자열을 파싱해 매퍼가 요구하는
 * 타입({@code CHAR(10)} 대시 문자열 · {@code timestamp} 반열림)으로 바꾼다. 형식 오류·화이트리스트
 * 밖의 {@code trgtTypeSe} 는 SQL 로 흘려보내지 않고 <b>400</b> 으로 끊는다.
 */
public interface ZoneRcmdtnService {

    /**
     * 단속 창 기본 시작일 — 분석 정본과 같은 <b>20개월</b>({@code 20250101}~{@code 20260831}).
     *
     * <p>🔴 <b>민원 창을 돌려쓰지 않는다</b>(클래스 주석). 🔴 컨트롤러도 이 값으로 기본값을
     * 맞춰 기간 상한을 판정하므로 <b>인터페이스에 둔다</b> — 구현에만 두면 컨트롤러가 생략값의
     * 실제 창을 모른 채 상한을 검사해(한쪽만 준 요청에서) 10년 창이 통과한다.
     */
    String DEFAULT_CRDN_BGNG_YMD = "20250101";

    /** 단속 창 기본 종료일(<b>포함</b>). 반열림 상한은 여기 {@code +1일} 이다 */
    String DEFAULT_CRDN_END_YMD = "20260831";

    /**
     * 읍면동 선택 목록({@code emdCd}·{@code emdNm}·{@code sggNm}). 🔴 {@code TB_ADMDST} 기준이라 원천이 0건인 동도
     * 빠지지 않는다. 읍면동 요금제 대상 판정은 제거됐다.
     *
     * <p>주차장 조회를 하지 않으므로 순위·배정률은 없다.
     *
     * @param bgngYmd      민원 조회 시작일 {@code YYYYMMDD} (필수)
     * @param endYmd       민원 조회 종료일 {@code YYYYMMDD} (필수 · 포함)
     * @param crdnBgngYmd  단속 창 시작일 {@code YYYYMMDD}. {@code null} 이면 기본값
     * @param crdnEndYmd   단속 창 종료일 {@code YYYYMMDD}(포함). {@code null} 이면 기본값
     * @param trgtTypeSe   후보 주차장 유형 범위 {@code NARROW}(기본) | {@code WIDE}
     * @return 시군구명 → 읍면동명 순 목록
     */
    List<ZoneRcmdtnResultVO.Zone> selectZones(String bgngYmd, String endYmd,
                                              String crdnBgngYmd, String crdnEndYmd,
                                              String trgtTypeSe);

    /**
     * 위와 같고 분석 계수 일시 입력을 받는다. 검증(목록 밖 400)만 하고 결과에는 영향이 없다.
     *
     * @param vrblVls 일시 입력({@code RECOMMEND_FEE} 스코프). {@code null}·빈 Map 이면 설정값이다. 규칙 위반은 400
     */
    List<ZoneRcmdtnResultVO.Zone> selectZones(String bgngYmd, String endYmd,
                                              String crdnBgngYmd, String crdnEndYmd,
                                              String trgtTypeSe, Map<String, BigDecimal> vrblVls);

    /**
     * 선택한 읍면동 + 후보 주차장 순위 + 배정 커버리지(읍면동 판정 제거).
     * 시군구 모드면 그 시군구 안 전 읍면동의 후보 주차장 순위(한 목록)만 낸다
     * ({@code zone}·{@code asgn} 은 {@code null} · {@code cumCpcty} 는 {@code null}).
     *
     * <p>🔴 {@code emdCd}·{@code sggNm} 은 <b>정확히 하나</b>다 — 둘 다 없음·둘 다 있음·{@code sggNm} 40자
     * 초과·없는 {@code sggNm} 은 400, 없는 {@code emdCd} 는 404. 공백은 "없음" 으로 정규화한다.
     *
     * @param emdCd 읍면동 코드(8자리) — 읍면동 모드
     * @param sggNm 시군구명 — 시군구 모드. 🔴 {@code emdCd} 바로 뒤다(전부 {@code String} 이라 순서 실수가
     *              컴파일로 잡히지 않는다)
     * @return 결과 1건. {@code excnId} 는 <b>채우지 않는다</b> — 실행이력 적재는 책임이다
     */
    ZoneRcmdtnResultVO selectZoneRcmdtn(String emdCd, String sggNm, String bgngYmd, String endYmd,
                                        String crdnBgngYmd, String crdnEndYmd,
                                        String trgtTypeSe);

    /**
     * 위와 같고 분석 계수 일시 입력을 받는다. 적용 내역은 응답 {@code vrblAjmt} 다.
     *
     * @param vrblVls 일시 입력({@code RECOMMEND_FEE} 스코프). {@code null}·빈 Map 이면 설정값이다. 규칙 위반은 400
     */
    ZoneRcmdtnResultVO selectZoneRcmdtn(String emdCd, String sggNm, String bgngYmd, String endYmd,
                                        String crdnBgngYmd, String crdnEndYmd,
                                        String trgtTypeSe, Map<String, BigDecimal> vrblVls);

    /**
     * 위 8인자와 같은 결과에 요금제 엑셀 재계산 데이터 {@code wbk}(개월수 · 고정 상수 · 계수 10행)를 붙인다.
     *
     * <p>🔴 <b>보고서 경로 전용</b>이다 — 조회 GET 응답에는 {@code wbk} 를 싣지 않는다(화면 페이로드 불변).
     * 🔁 2026-11-11 — 읍면동 판정 원값(종전 {@code zoneBase})은 싣지 않는다(엑셀에 판정을 두지 않는다).
     */
    ZoneRcmdtnResultVO selectZoneRcmdtnWithWorkbook(String emdCd, String sggNm, String bgngYmd, String endYmd,
                                                    String crdnBgngYmd, String crdnEndYmd,
                                                    String trgtTypeSe, Map<String, BigDecimal> vrblVls);

    /**
     * 종전 6인자 창구(읍면동 모드 전용). {@code sggNm = null} 로 7인자에 위임한다.
     *
     * <p>기존 테스트 호출부를 그대로 두기 위해 남긴다. {@code @Transactional} 이 없는 순수 조회라
     * {@code default} 자기호출에 트랜잭션 문제가 없다. 🔴 시군구를 받아야 하는 main 경로(보고서 생성)는
     * 이 메서드에 맡기지 않는다 — {@code sggNm} 이 버려져 "둘 다 없음" 400 이 된다.
     */
    default ZoneRcmdtnResultVO selectZoneRcmdtn(String emdCd, String bgngYmd, String endYmd,
                                                String crdnBgngYmd, String crdnEndYmd,
                                                String trgtTypeSe) {
        return selectZoneRcmdtn(emdCd, null, bgngYmd, endYmd, crdnBgngYmd, crdnEndYmd, trgtTypeSe);
    }

    /**
     * {@link #selectZoneRcmdtn} + <b>실행이력·결과 적재</b>.
     *
     * <p>실행 1회 = {@code parking.TB_ANLS_EXCN} <b>한 행</b>({@code anls_type_cd='RECOMMEND'} ·
     * {@code emd_cd} 채움) + {@code parking.TB_ANLS_RSLT} <b>한 행</b>({@code rslt_json})이다.
     * 적재는 영속성 규칙상 <b>JPA</b> 담당이다(쓰기=JPA).
     *
     * <p>🔴 {@link #selectZoneRcmdtn} 은 <b>순수 조회로 남긴다</b> — 적재를 그 안에 넣으면
     * 단위검증(판정·순위)이 돌 때마다 분석실행 이력이 늘어난다. 화면이 부르는 창구는 이 메서드다.
     *
     * <p>🔴 {@code anls_param_json} 에는 조회 조건(기간·<b>단속 창</b>·{@code trgtTypeSe}·
     * {@code emdCd})과 <b>적용 계수 전체</b>를 담는다. 특히 {@code pcshrCrtrVl}·{@code paidDmndRt} 는
     * {@link ZoneRcmdtnCrtrVls} 에서 {@code @JsonIgnore} 라 <b>VO 를 그대로 직렬화하면 빠진다</b> —
     * 필요 면수 산식의 분모이므로 빠지면 같은 조건으로 재현할 수 없다.
     *
     * <p>시군구 모드면 {@code sgg_nm = sggNm} · {@code emd_cd = NULL} 이고
     * {@code anls_param_json} 에 {@code scope}·{@code sggNm} 이 실린다(읍면동 모드도 {@code scope:"EMD"}).
     * 🔴 {@code default} 위임을 두지 않는다 — {@code @Transactional} 이 구현 메서드에만 있어 자기호출로는
     * 트랜잭션이 걸리지 않는다.
     *
     * @param sggNm    시군구명(시군구 모드) — {@code emdCd} 와 배타(정확히 하나)
     * @param excutrId 실행자 ID — 로그인 사용자 {@code user_id}. 해석·401 은 컨트롤러 책임이다
     * @return {@link ZoneRcmdtnResultVO#getExcnId() excnId} 가 채워진 결과 1건
     */
    ZoneRcmdtnResultVO analyzeAndRecord(String emdCd, String sggNm, String bgngYmd, String endYmd,
                                        String crdnBgngYmd, String crdnEndYmd,
                                        String trgtTypeSe, String excutrId);

    /**
     * 위와 같고 분석 계수 일시 입력을 받는다. 적용 내역은 {@code anls_param_json.vrblAjmt} 에 남는다.
     *
     * @param vrblVls 일시 입력({@code RECOMMEND_FEE} 스코프). {@code null}·빈 Map 이면 설정값이다. 규칙 위반은 400
     */
    ZoneRcmdtnResultVO analyzeAndRecord(String emdCd, String sggNm, String bgngYmd, String endYmd,
                                        String crdnBgngYmd, String crdnEndYmd,
                                        String trgtTypeSe, String excutrId, Map<String, BigDecimal> vrblVls);
}
