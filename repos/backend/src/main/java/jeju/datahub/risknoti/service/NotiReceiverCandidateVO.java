package jeju.datahub.risknoti.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 알림 수신자 <b>후보</b> 행 VO. (parking.TB_USER + TB_DEPT + TB_NOTI_RCVR — MyBatis 조회 전용)
 *
 * <p>등록 모달의 "소속 → 사용자" select 를 채운다. 수신자는 {@code TB_USER} 에서 고른다.
 *
 * <p>🔴 {@code registered=true} 면 이미 활성 수신자({@code del_yn='N'})가 있는 사람이다 —
 * 한 사용자 = 수신자 1행이라 화면이 select 에서 비활성화한다(재등록은 409).
 * 삭제({@code del_yn='Y'})한 사람은 {@code false} 로 돌아와 다시 고를 수 있다.
 *
 * <p>{@code deptCd}·{@code deptNm} 이 null 이면 무소속이다(목록 맨 뒤에 온다).
 */
@Schema(description = "알림 수신자 후보 (활성 사용자 1명)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class NotiReceiverCandidateVO {

    /** 사용자아이디 (TB_USER.user_id) — 등록 요청이 이 값을 보낸다 */
    @Schema(description = "사용자아이디", example = "hong@jeju.go.kr")
    private String userId;

    /** 사용자명 (TB_USER.user_nm) */
    @Schema(description = "사용자명", example = "홍길동")
    private String userNm;

    /** 소속부서 코드 (TB_USER.dept_cd). 무소속이면 null */
    @Schema(description = "소속부서 코드 (무소속이면 null)", example = "parking", nullable = true)
    private String deptCd;

    /** 소속부서명 (TB_DEPT.dept_nm). 무소속이면 null */
    @Schema(description = "소속부서명 (무소속이면 null)", example = "주차정책과", nullable = true)
    private String deptNm;

    /** 이메일 (TB_USER.user_eml_addr) */
    @Schema(description = "이메일", example = "hong@jeju.go.kr")
    private String eml;

    /** 활성 수신자 등록 여부 — true 면 화면이 선택을 막는다 */
    @Schema(description = "이미 활성 수신자로 등록돼 있는지 (true 면 선택 불가 — R9)", example = "false")
    private boolean registered;
}
