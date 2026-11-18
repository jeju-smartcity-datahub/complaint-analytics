package jeju.datahub.batch.collect;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 실행 내 재시도 중복만 막는다. 서로 다른 실행은 같은 자료여도 별도 관측으로 쌓인다. */
public final class ParkingObservationHash {
    private ParkingObservationHash() { }
    public static String of(long parkingId, String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((parkingId + "\n" + payload).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
