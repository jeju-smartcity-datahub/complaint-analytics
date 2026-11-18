package jeju.datahub.batch.pblpklt;

import java.util.List;

import jeju.datahub.batch.pblpklt.api.PblpkltOriginRow;

/**
 * ① 수집 Step 의 Processor 출력 — 원본 {@link PblpkltOriginRow} 와
 * {@link PblpkltAddressAnomalyClassifier} 가 판정한 anomaly 목록을 함께 들고 다닌다.
 *
 * <p>Spring Batch 의 Step 은 단일 Writer 만 받는다. 두 갈래(Origin / Anomaly) 로 갈라치기 위해
 * 같은 묶음을 emit 하고, Writer 측 {@link PblpkltItemWriter} 에서 둘 다 INSERT 한다.
 *
 * <h2>트랜잭션 보장</h2>
 * 둘 다 같은 청크 트랜잭션 안에서 쓰여진다 — 한쪽만 성공하고 한쪽이 실패하는 모순 상태가
 * 생기지 않는다. 가이드 3-1 의 "값 보존 + 곁가지 기록" 원칙과 정합.
 */
public record PblpkltFetchRow(
        PblpkltOriginRow origin,
        List<String> anomalyTypes) {

    /** anomaly 가 비어 있어도 (정상 행) origin Writer 가 처리할 수 있도록 빈 리스트 허용. */
    public PblpkltFetchRow {
        anomalyTypes = anomalyTypes == null ? List.of() : List.copyOf(anomalyTypes);
    }
}
