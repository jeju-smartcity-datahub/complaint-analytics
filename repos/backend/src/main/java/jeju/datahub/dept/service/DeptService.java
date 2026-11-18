package jeju.datahub.dept.service;

import java.util.List;

/**
 * 부서 서비스 인터페이스.
 *
 * <p>부서 목록은 공통코드가 아니라 전용 표 {@code parking.TB_DEPT} 가 소유한다 —
 * 그 표는 마스터가 아니라 IdP {@code groups} claim 의 투영이다.
 */
public interface DeptService {

    /** 사용중({@code use_yn='Y'}) 부서 목록 ({@code sort_seq NULLS LAST, dept_cd} 순) */
    List<DeptVO> selectDepts();

    /**
     * 사용중 부서코드 목록.
     *
     * <p>🔴 <b>부서 코드 유효성 검증의 창구는 이 메서드 하나다</b> — 알림 수신자 등록
     * ({@code RiskNotiServiceImpl}) · 보고서 설정 CUD({@code ReportServiceImpl}) 가 함께 쓴다.
     * 같은 쿼리를 도메인 매퍼마다 복제하지 않는다.
     */
    List<String> activeDeptCds();
}
