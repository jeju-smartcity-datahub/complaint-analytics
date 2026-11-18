package jeju.datahub.batch.acdnt;

import jeju.datahub.batch.acdnt.api.PkltAcdntOriginRow;

public record PkltAcdntRow(long pkltId, PkltAcdntOriginRow source, String srcHash) { }
