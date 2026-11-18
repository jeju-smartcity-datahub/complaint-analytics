package jeju.datahub.risknoti.service;

import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 알림 수신자 행 VO. (parking.TB_NOTI_RCVR + TB_USER + TB_DEPT + TB_NOTI_RCVR_LVL + TB_COM_CD — MyBatis 조회 전용)
 *
 * <p><b>1행 = 수신자 1명</b>이다 (TSK-4250 R9 — 한 사용자 = 활성 수신자 1행). 지역은 다중이라
 * {@link #regions} 배열로 내린다(R6).
 *
 * <p>목록({@code GET}의 {@code content[]}) · 등록({@code POST}) · 수정({@code PUT}) · 활성화({@code PATCH}) 응답이
 * <b>모두 이 형태</b>다 — 프론트가 응답을 같은 렌더러로 처리한다. 단건 3경로는 서비스의 같은 헬퍼가 지역을 붙인다.
 *
 * <p>이름·이메일·부서는 {@code TB_USER} <b>최신값</b>이다(Q4) — 사용자 행이 없을 때만 수신자 행의 등록 시점
 * 복사본으로 <b>행 단위</b> 폴백한다.
 *
 * <p><b>단계 라벨({@code riskLvlNm})은 내리지 않는다</b> — 알림 이력 응답과 같은 원칙으로
 * 배지 라벨(심각/경고/주의)은 프론트가 코드값에서 만든다.
 *
 * <p>컬럼(스네이크) → 필드(카멜) 매핑은 {@code map-underscore-to-camel-case} 로 자동 처리되지만,
 * {@code receiverMap} 은 <b>중첩 {@code <collection>} 을 쓰는 resultMap</b> 이라
 * 자동 매핑이 꺼진다(autoMappingBehavior=PARTIAL) — XML 이 전 필드를 명시 매핑한다.
 * {@link #regions}·{@link #regionNm} 은 resultMap 에 없다 — 서비스가 두 번째 쿼리로 채운다.
 */
@Schema(description = "알림 수신자 행 (수신자 1명)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class NotiReceiverVO {

    /** 수신자ID (rcvr_id, BIGINT identity — TSK-4028 D11) */
    @Schema(description = "수신자ID", example = "100")
    private Long rcvrId;

    /** 소속부서 코드 — {@code TB_USER.dept_cd} 최신값(Q4 · 사용자 행이 없을 때만 수신자 행 복사본) */
    @Schema(description = "소속부서 코드", example = "PARK")
    private String deptCd;

    /** 소속부서명 ({@code TB_DEPT.dept_nm}) — 위에서 고른 {@code dept_cd} 로 조인. nullable 이라 LEFT JOIN 이다 */
    @Schema(description = "소속부서명", example = "주차관리")
    private String deptNm;

    /** 수신자명 — {@code TB_USER.user_nm} 최신값(Q4 · 행 단위 폴백은 {@code rcvr_nm}) */
    @Schema(description = "수신자명", example = "김지수")
    private String rcvrNm;

    /** 이메일 — {@code TB_USER.user_eml_addr} 최신값(Q4 · 행 단위 폴백은 {@code eml}) */
    @Schema(description = "이메일", example = "jisu.kim@jeju.go.kr")
    private String eml;

    /**
     * 수신 지역 목록 (TB_NOTI_RCVR_RGN — 다중, TSK-4250 R6). 정렬: 시군구 → 읍면동명(시군구 전체가 그 읍면동들 앞).
     *
     * <p>전체 지역은 원소 1개({@code sggNm}·{@code emdCd} 둘 다 null)다(R3). 🔴 receiverMap 의 {@code <collection>} 으로
     * 붙이지 않는다 — 단계 × 지역으로 행이 곱해진다. 서비스가 {@code selectReceiverRegions} 로 붙인다.
     * 0행이면 빈 배열이다(정상 경로로는 생기지 않는다 — fail-closed 상태).
     */
    @Schema(description = "수신 지역 목록 (전체 지역은 sggNm·emdCd 가 둘 다 null 인 원소 1개)")
    private List<NotiReceiverRegionVO> regions = new ArrayList<>();

    /**
     * 합친 지역 표시명 — 각 원소의 {@code regionNm}({@code 제주시 연동} / {@code 제주시 전체} / {@code 전체 지역})을
     * {@code ", "} 로 이은 값. 화면 표의 지역 칸이 그대로 찍는다(R6 — 프론트에서 다시 조립하지 않는다).
     *
     * <p>⚠️ 지역 0행은 {@code "지역 없음"} 이다 — {@code "전체 지역"} 으로 보이면 fail-closed 상태(아무 지역도
     * 받지 않음)를 가린다. 서비스가 채운다(매퍼가 아니다).
     *
     * <p>⚠️ 알림 이력의 {@code regionNm}(= 읍면동명 고정)과 규칙이 다르다 — 이력은 판정이 언제나
     * 읍면동 단위라 분기가 없고(D12), 수신자는 수신 범위다.
     */
    @Schema(description = "합친 지역 표시명 (원소 regionNm 을 ', ' 로 연결 · 0행이면 '지역 없음')",
            example = "제주시 연동, 제주시 노형동")
    private String regionNm;

    /**
     * 수신 대상 위험단계 코드 목록 (TB_NOTI_RCVR_LVL — 다중).
     *
     * <p>MyBatis {@code <collection ... notNullColumn="risk_lvl_cd">} 이 접어 넣는다.
     * 매핑이 0건이면 <b>빈 배열</b>이다 ({@code [null]} 이 아니다 — {@code notNullColumn} 이 막는다).
     * 초기값을 빈 리스트로 둬 매핑 0건일 때도 {@code null} 이 내려가지 않게 한다.
     */
    @Schema(description = "수신 대상 위험단계 코드 목록", example = "[\"CRIT\",\"WARN\"]")
    private List<String> riskLvlCds = new ArrayList<>();

    /** 사용여부 (use_yn) — 화면의 토글 스위치 (Y/N) */
    @Schema(description = "사용여부 (Y/N)", example = "Y")
    private String useYn;
}
