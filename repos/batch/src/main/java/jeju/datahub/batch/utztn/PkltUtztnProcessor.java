package jeju.datahub.batch.utztn;

import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import jeju.datahub.batch.utztn.PkltUtztnPkltIdResolver.Match;
import jeju.datahub.batch.utztn.api.PkltUtztnOriginRow;

/**
 * 원천 행 → 적재 행.
 *
 * <p>하는 일 셋 — ① 주차장 해석({@link PkltUtztnPkltIdResolver}) · ② 감사 기록(성공·실패 모두) ·
 * ③ {@code msmt_ym} → {@code tot_ym} 변환.
 *
 * <p>🔴 해석 실패는 <b>행 단위 확정 실패</b>다 — 감사 표에 사유를 남긴 뒤
 * {@link PkltUtztnSkippableException} 을 던져 그 행만 스킵한다. 기록이 청크 롤백에 휩쓸리지 않도록
 * Repository 가 {@code REQUIRES_NEW} 다.
 *
 * <p>⚠️ 실측으로는 미해석 0건이 예상되지만, 원천이 표기를 또 바꾸면(이미 한 번 바꿨다) 이 경로가 열린다.
 */
@Component
public class PkltUtztnProcessor implements ItemProcessor<PkltUtztnOriginRow, PkltUtztnRow> {

    private final PkltUtztnPkltIdResolver resolver;
    private final PkltUtztnMatchAuditRepository auditRepository;

    public PkltUtztnProcessor(PkltUtztnPkltIdResolver resolver,
                              PkltUtztnMatchAuditRepository auditRepository) {
        this.resolver = resolver;
        this.auditRepository = auditRepository;
    }

    @Override
    public PkltUtztnRow process(PkltUtztnOriginRow row) {
        Match match = resolver.resolve(row);
        auditRepository.record(row, match);

        if (!match.resolved()) {
            throw new PkltUtztnSkippableException(match.failRsn(),
                    "원천 '" + row.pkltNm() + "' / '" + row.pkltAddr() + "' (후보 " + match.candCnt() + "개)");
        }
        // 🔴 변환은 여기서 한다 — CHAR(6) 에 원문(7자)을 넣으면 잘리지 않고 22001 에러다.
        return new PkltUtztnRow(match.pkltId(), row.totYm(), row.useCnt(), row.totYm() == null ? null : row.useRate(), row.srcJson());
    }
}
