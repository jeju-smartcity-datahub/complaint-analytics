package jeju.datahub.batch.pblpklt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;

/**
 * 원천 식별자({@code pklt_id}) 존재 검증 — <b>비어 있는 행만</b> 거른다.
 *
 * <h2>🔴 2026-09-12 — {@code '-'} 기반 형식 검증을 폐기했다</h2>
 * 종전에는 {@code pklt_id} 에 {@code '-'} 가 없으면 {@code null} 을 반환해 그 행을 조용히 버렸다.
 * 옛 원천이 {@code "한림-0001"} 같은 prefix-순번 형태를 내려주던 시절의 전제다.
 * <b>새 원천의 {@code pklt_id} 는 {@code "12"} — {@code '-'} 가 없다.</b> 그대로 두면 전 행이
 * 지오코딩까지 가지도 못하고 여기서 필터돼 Job 이 {@code write_cnt=0} 으로 {@code COMPLETED}
 * 되는(= "성공했는데 아무것도 안 들어왔다") 상태가 된다.
 *
 * <p>🔁 2026-09-18 — 자체 배정 규칙({@code {읍면동}-{순번}}) 자체가 폐지됐다.
 * {@code pklt_id} 는 DB 가 IDENTITY 로 매기고, 우리가 만드는 형식은 더 이상 없다. 원천이 준
 * {@code pklt_id} 는 그대로 {@code origin_pk} 로 적재된다.
 *
 * <h2>그래도 이 Processor 를 체인에 남긴 이유</h2>
 * 원천 {@code pklt_id} 는 조회 커서와 스킵 기록에 필요하다. 저장 시의 주차장 매칭은 주소로 한다.
 * 원천 식별자 검증은 <b>지오코딩 쿼터를 쓰기 전에</b>
 * 걸러야 의미가 있어 체인 앞단(anomaly 분류 다음, 지오코딩 앞)에 그대로 둔다.
 *
 * <h2>입출력 타입</h2>
 * 입력은 {@link PblpkltFetchRow}(이전 단계 {@link PblpkltAddressAnomalyClassifier} 의 출력).
 * {@code null} 을 반환하면 그 행은 청크에서 스킵된다(스킵 테이블에는 남기지 않는다 — 원천
 * 식별자가 없어 키를 만들 수 없다).
 */
public class PblpkltIdConsistencyProcessor implements ItemProcessor<PblpkltFetchRow, PblpkltFetchRow> {

    private static final Logger log = LoggerFactory.getLogger(PblpkltIdConsistencyProcessor.class);

    @Override
    public PblpkltFetchRow process(PblpkltFetchRow item) {
        if (item == null || item.origin() == null) {
            return null;
        }
        String pkltId = item.origin().pkltId();
        if (pkltId == null || pkltId.isBlank()) {
            log.warn("pblpklt 행 스킵 — pklt_id 가 비어 있습니다(원본 식별자 부재)");
            return null;
        }
        return item;
    }
}
