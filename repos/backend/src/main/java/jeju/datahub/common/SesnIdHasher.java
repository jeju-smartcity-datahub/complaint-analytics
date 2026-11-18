package jeju.datahub.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 세션 ID 해시 — D5. 표현형식을 여기 한 곳에서만 정한다(hex 소문자 16자).
 *
 * <p>세션 해시는 MDC(접근 로그)·로그인 이력 기록·세션 종료 보정 세 곳에서 쓰인다. 각자 구현하면
 * 한쪽이 hex, 다른 쪽이 base64url 이 되어 {@code closeSession} 이 영원히 매칭되지 않고
 * {@code cntn_end_dt} 가 항상 NULL 이 된다 — "NULL 은 정상"(D6)이라 <b>검증에서도 안 걸린다.</b>
 *
 * <p>🔴 <b>base64url 로 바꾸지 않는다.</b> 앞 16자가 hex 면 64비트, base64url 이면 96비트라
 * 길이 의미가 다르고 이미 적재된 행과 대조가 깨진다. {@code parking.TB_LGN_HSTRY.sesn_id} 는
 * {@code CHECK (length = 16)} 으로 이 계약을 DB 에서도 강제한다.
 */
public final class SesnIdHasher {
    private SesnIdHasher() {}

    /**
     * SHA-256 → hex 소문자 → 앞 16자. DB {@code sesn_id} · MDC {@code %X{sesnId}} 가 같은 값을 쓴다.
     *
     * <p>🔴 입력이 null/공백이면 <b>null 을 반환한다</b> — {@code "-"} 같은 센티널을 돌려주면
     * Step 4 의 {@code sesn_id} 계약("SHA-256 앞 16자")을 깨고, 여러 행이 같은 값으로 뭉쳐
     * {@code closeSession} 의 조회 키가 오염된다. MDC 표시용 기본값({@code -})은 <b>호출부(필터)가</b> 준다.
     */
    public static String shortHash(String sesnId) {
        if (sesnId == null || sesnId.isBlank()) return null;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(sesnId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);   // JDK 필수 알고리즘이라 실제로는 안 난다
        }
    }
}
