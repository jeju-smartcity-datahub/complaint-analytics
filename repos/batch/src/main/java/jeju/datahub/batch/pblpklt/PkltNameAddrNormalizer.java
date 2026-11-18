package jeju.datahub.batch.pblpklt;

/**
 * 주차장 이름·주소 정규화 — 원천 문자열을 우리 {@code parking.tb_pblpklt} 와 대조할 때 쓴다 (TSK-4254 Step 4).
 *
 * <p>본문은 이용패턴 {@code PkltUtztnPkltIdResolver.normAddr/normName}(TSK-4189)에서 <b>그대로</b> 옮겼다.
 * 이용패턴·안전점검 두 잡이 같은 규칙을 쓰도록 공용으로 뺀 것이다 — 한쪽만 고치면 같은 주차장이 잡마다
 * 다르게 해석된다. 이용패턴 리졸버의 두 {@code static} 메서드는 시그니처를 유지한 채 여기로 위임만 한다.
 *
 * <p>🔴 둘 다 원천과 우리 표 <b>양쪽</b>에 같이 적용한다.
 */
public final class PkltNameAddrNormalizer {

    private PkltNameAddrNormalizer() {
    }

    /**
     * 읍면 접두만 제거한다. 🔴 <b>다필지 주소를 첫 지번으로 자르지 않는다</b> —
     * 그 정규화는 해석을 367까지만 올리면서 우리 표 안 1:N 을 29 → 43 으로 늘린다
     * (2단 이름 폴백이 같은 5건을 1:N 위험 없이 잡으므로 불필요하다).
     */
    public static String normAddr(String addr) {
        return addr == null ? null : addr.replaceFirst("^[가-힣]+(읍|면)\\s+", "").trim();
    }

    /**
     * 괄호 내용 제거 + 공백 전부 제거.
     * 🔴 {@code \(.*\)} 는 greedy 라 {@code 'A(가)B(나)' → 'A'} 가 된다(가운데 본문까지 삭제) —
     * {@code [^)]*} 로 좁힌다.
     */
    public static String normName(String nm) {
        return nm == null ? null : nm.replaceAll("\\([^)]*\\)", "").replace(" ", "");
    }
}
