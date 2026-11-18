package jeju.datahub.batch.utztn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.SkipListener;
import org.springframework.stereotype.Component;

import jeju.datahub.batch.utztn.api.PkltUtztnOriginRow;

/**
 * 스킵된 행을 로그로 드러낸다 (TSK-4189 Step 7).
 *
 * <p>🔴 <b>기록 자체는 Processor 가 이미 감사 표에 남겼다</b>({@code REQUIRES_NEW} 라 롤백에도 남는다) —
 * 여기서 DB 를 다시 쓰지 않는다. 이 리스너는 "스킵이 있었다" 를 운영자가 로그에서 바로 보게 하는 역할이다.
 *
 * <p>⚠️ {@code SkipListener} 오버로드 함정 — {@code listener(Object)} 로 잡히면 콜백이 0회다
 * ({@code PblpkltSkipListener} 에 같은 기록이 있다). Step 조립에서 타입이 {@code SkipListener} 로
 * 들어가는지 확인한다.
 */
@Component
public class PkltUtztnSkipListener implements SkipListener<PkltUtztnOriginRow, PkltUtztnRow> {

    private static final Logger log = LoggerFactory.getLogger(PkltUtztnSkipListener.class);

    @Override
    public void onSkipInProcess(PkltUtztnOriginRow item, Throwable t) {
        String rsn = (t instanceof PkltUtztnSkippableException e) ? e.failRsn() : "UNKNOWN";
        log.warn("이용패턴 행 스킵 [{}] — '{}' / '{}' (감사 표 batch.tb_pklt_utztn_mtch 에 사유가 남았다)",
                rsn, item.pkltNm(), item.pkltAddr());
    }
}
