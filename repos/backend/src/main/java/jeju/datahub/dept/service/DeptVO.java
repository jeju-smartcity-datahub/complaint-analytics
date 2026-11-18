package jeju.datahub.dept.service;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 부서 VO. {@code parking.TB_DEPT} 조회 결과.
 *
 * <p>🔴 <b>4필드를 모두 내보낸다</b> — 목록이 이미 {@code use_yn='Y'} 로 걸러졌는데도 {@code useYn} 을
 * 함께 주는 것은 {@code CommonCodeVO} 와 같은 관례이고, 프론트 {@code DeptItem} 이 그대로 대응한다.
 * 한 필드라도 빠지면 {@code tsc} 는 통과하고 <b>런타임에 {@code undefined}</b> 다.
 *
 * <p>🔴 {@code sortSeq} 는 <b>필드는 필수이지만 값은 nullable</b>({@code Integer}) 이다 —
 * 로그인이 자동 등재한 부서는 {@code sort_seq = NULL} 이고, 목록의
 * {@code ORDER BY sort_seq NULLS LAST} 자체가 그것을 전제한 설계다.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class DeptVO {

    /** 부서코드 (TB_DEPT.dept_cd — IdP 그룹 슬러그, 최대 30자) */
    private String deptCd;

    /** 부서명 (자동 등재분의 초기값은 슬러그와 같다 — 표시명은 사후 UPDATE 로 보정) */
    private String deptNm;

    /** 정렬순서. 🔴 자동 등재분은 {@code null} 이다 (목록이 NULLS LAST) */
    private Integer sortSeq;

    /** 사용여부 (Y/N). 목록은 {@code Y} 만 내린다 */
    private String useYn;
}
