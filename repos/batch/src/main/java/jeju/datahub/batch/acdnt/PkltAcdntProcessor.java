package jeju.datahub.batch.acdnt;

import jeju.datahub.batch.acdnt.api.PkltAcdntOriginRow;
import jeju.datahub.batch.collect.SourceFingerprint;
import jeju.datahub.batch.collect.SourceTimestamp;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

@Component
public class PkltAcdntProcessor implements ItemProcessor<PkltAcdntOriginRow, PkltAcdntRow> {
    private final PkltAcdntPkltIdResolver resolver;
    public PkltAcdntProcessor(PkltAcdntPkltIdResolver resolver) { this.resolver = resolver; }
    @Override public PkltAcdntRow process(PkltAcdntOriginRow row) {
        long id = resolver.resolve(row);
        return new PkltAcdntRow(id, row, SourceFingerprint.hash(Long.toString(id),
                SourceTimestamp.format(row.acdntDt()), row.acdntType()));
    }
}
