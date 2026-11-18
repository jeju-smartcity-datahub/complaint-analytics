package jeju.datahub.report.service;

import jeju.datahub.report.service.impl.ReportMapper;
import jeju.datahub.report.service.impl.Rptp;
import jeju.datahub.report.service.impl.RptpRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * 보고서 소유 판정 한 곳 (TSK-4202 Step 2 — Step 4 의 단건·스냅샷 7종이 이 빈을 부른다).
 *
 * <p>판정은 두 갈래다 — {@code crt_type='AUTO'} 면 <b>배포 부서({@code crltn_dept_cd})가 요청자 부서와 같거나
 * {@code ALL} 이거나, 요청자가 보고서 관리 메뉴를 가졌을 때</b>만 통과하고(TSK-4257 D1·D3·D4),
 * 아니면({@code MANUAL}·{@code AI}) 요청자가 생성자일 때만 통과한다. 그 밖에는 <b>404</b> 다.
 *
 * <p>🔴 <b>{@code AUTO} 판정 규칙은 여기에 다시 쓰지 않는다</b>(TSK-4257 D8) — 목록·총건수·대시보드와 같은
 * SQL 조각({@code ReportMapper.xml} {@code autoVisible})을 {@link ReportMapper} 의 단건 EXISTS 조회로
 * 부른다. Java 로 따로 짜면 규칙이 두 벌이 되어 "목록엔 보이는데 열면 404" 가 생긴다.
 *
 * <p>🔴 <b>403 이 아니라 404 다.</b> 403 은 "그 보고서가 있다" 를 알려 주는 셈이라, 남의 보고서는
 * 처음부터 없는 것처럼 보이게 한다. 보고서가 아예 없을 때도 같은 404 다.
 *
 * <p>🔴 <b>{@code MANUAL}·{@code AI} 에는 관리자 예외를 두지 않는다</b>(TSK-4202 D3 · TSK-4257 D2) —
 * {@code ROLE_SYSTEM_ADMIN} 도 본인 것만 본다. {@code AUTO} 의 "관리자" 도 롤이 아니라 보고서 관리 메뉴
 * 보유로 판정한다(TSK-4257 D1). 롤 리터럴을 인가 코드에 두지 않는다는 TSK-4115 원칙과도 맞는다.
 *
 * <p>🔴 <b>입력은 JPA {@link RptpRepository#findById} 다</b> — {@code ReportMapper.selectReportById} 는
 * {@code creatr_nm} 만 SELECT 하고 {@code creatr_id} 가 없으며, 결과 타입 {@code ReportListVO} 에도
 * 그 필드가 없다. 🔴 그 VO 에 {@code creatrId} 를 더하지 않는다 — {@code selectReportList} 와 공용이라
 * {@code GET /api/reports} 응답 JSON 에 내부 {@code user_id} 가 새로 노출된다. PK 단건 조회라
 * {@code rule_persistence-jpa-mybatis.md} 의 "PK 단건 = JPA" 와도 맞는다.
 */
@Component
public class ReportOwnershipGuard {

    /** 자동 생성 보고서의 생성구분 (RPTP_CRT_TYPE) — 배포 부서·{@code ALL}·보고서 관리 메뉴 보유자에게만 보인다(TSK-4257 D1·D3·D4) */
    private static final String CRT_TYPE_AUTO = "AUTO";

    private final RptpRepository rptpRepository;
    private final ReportMapper reportMapper;

    public ReportOwnershipGuard(RptpRepository rptpRepository, ReportMapper reportMapper) {
        this.rptpRepository = rptpRepository;
        this.reportMapper = reportMapper;
    }

    /**
     * 요청자가 이 보고서를 볼 수 있는지 판정한다. 통과면 그냥 반환하고, 아니면 404 를 던진다.
     *
     * <p>🔴 <b>{@code ownerId} 를 왼쪽에 둔다</b> — {@code parking.tb_rptp.creatr_id} 는 nullable
     * ({@code varchar(20)})이라 {@code creatr_id.equals(...)} 는 NPE 다. {@code ownerId} 는 컨트롤러가
     * {@code CurrentUserResolver} 로 401 을 걸러 non-null 이 보장된다. {@code creatr_id} 가 NULL 인
     * 과거 행은 D4 대로 아무에게도 보이지 않는다(= 404).
     *
     * @param rptpId  보고서 ID
     * @param ownerId 요청자의 {@code TB_USER.user_id}
     * @throws ResponseStatusException 보고서가 없거나 요청자가 볼 수 없으면 404
     */
    @Transactional(readOnly = true)
    public void check(Long rptpId, String ownerId) {
        Optional<Rptp> found = rptpId == null ? Optional.empty() : rptpRepository.findById(rptpId);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Rptp rptp = found.orElseThrow();
        if (CRT_TYPE_AUTO.equals(rptp.getCrtType())) {
            // 🔴 TSK-4257 — 목록과 같은 조각(autoVisible)으로 판정한다. 남의 부서는 403 이 아니라 404(D7)
            if (ownerId == null || !reportMapper.existsVisibleAutoReport(rptpId, ownerId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            return;
        }
        if (ownerId == null || !ownerId.equals(rptp.getCreatrId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
