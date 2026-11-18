package jeju.datahub.dept.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jeju.datahub.dept.service.DeptService;
import jeju.datahub.dept.service.DeptVO;
import lombok.RequiredArgsConstructor;

/**
 * 부서 REST 컨트롤러.
 *
 * <p>보고서 설정 폼(배포부서)과 알림 수신자 등록(소속부서)이 <b>같이 쓰는</b> 부서 select 소스다 —
 * {@code /api/regions}(지역 select) 와 같은 구조로 어느 화면 도메인에도 속하지 않아 독립 패키지에 둔다.
 *
 * <p>🔴 {@code TB_DEPT} 는 마스터가 아니라 IdP {@code groups} claim 의 투영이라 빈 표로
 * 시작한다 — <b>아직 아무도 로그인하지 않았다면 빈 배열이 정상</b>이다(버그가 아니다).
 */
@Tag(name = "부서", description = "부서(TB_DEPT) 조회 API")
@RestController
@RequestMapping("/api/depts")
@RequiredArgsConstructor
public class DeptController {

    private final DeptService deptService;

    @Operation(summary = "부서 목록",
            description = "부서 select 를 채우는 사용중(use_yn='Y') 부서 목록. 정렬은 sort_seq NULLS LAST, dept_cd.")
    @GetMapping
    public List<DeptVO> depts() {
        return deptService.selectDepts();
    }
}
