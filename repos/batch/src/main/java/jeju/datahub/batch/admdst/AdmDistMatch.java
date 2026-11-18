package jeju.datahub.batch.admdst;

import java.util.Locale;

/**
 * 좌표 → 읍면동 매칭 결과 (계획서 Step 7-4).
 *
 * <p><b>실패했을 때 "얼마나 아깝게 실패했는지"를 함께 돌려주려고</b> 존재한다. 예전에는
 * {@code Optional<String>} 이라 스킵 사유가 {@code EMD_UNRESOLVED} 라는 것만 알 수 있었는데,
 * 그것만으로는 <b>버퍼를 넓히면 살아나는 좌표</b>(경계에서 60m)와 <b>좌표 자체가 잘못된 건</b>
 * (경계에서 5km)을 구분할 수 없다. 둘은 대응이 정반대다 — 앞은 버퍼 조정, 뒤는 지오코딩 재검토.
 *
 * <p>최근접 후보는 2차 매칭 쿼리가 이미 {@code <-> LIMIT 5} 로 뽑는 값이라 <b>추가 조회 비용이
 * 없다</b>.
 *
 * @param emdCd                매칭된 읍면동 코드. 실패면 {@code null}
 * @param nearestEmdCd         가장 가까운 읍면동 코드(실패 진단용). 후보를 못 구했으면 {@code null}
 * @param nearestEmdNm         가장 가까운 읍면동 이름 — 코드만 있으면 사람이 못 읽는다
 * @param nearestDistanceMeters 그 경계까지의 실제 거리(m, geography). 후보가 없으면 {@code null}
 */
public record AdmDistMatch(
        String emdCd,
        String nearestEmdCd,
        String nearestEmdNm,
        Double nearestDistanceMeters) {

    /** 1차(포함) 또는 2차(버퍼)로 매칭됨. */
    public static AdmDistMatch matched(String emdCd) {
        return new AdmDistMatch(emdCd, null, null, null);
    }

    /** 1·2차 모두 실패 — 최근접 후보를 진단 정보로 함께 싣는다. */
    public static AdmDistMatch unresolved(
            String nearestEmdCd, String nearestEmdNm, Double nearestDistanceMeters) {
        return new AdmDistMatch(null, nearestEmdCd, nearestEmdNm, nearestDistanceMeters);
    }

    /** 좌표가 없어 조회 자체를 하지 않음(미분류 행 방어). */
    public static AdmDistMatch notAttempted() {
        return new AdmDistMatch(null, null, null, null);
    }

    /** 매칭 성공 여부. {@code false} 면 호출자가 {@code EMD_UNRESOLVED} 로 스킵한다. */
    public boolean matched() {
        return emdCd != null;
    }

    /**
     * 스킵 {@code note} 에 실을 실패 설명.
     *
     * <p>예: {@code 최근접 50110107(건입동) 60.25m > 버퍼 50m} — 이 한 줄로 "버퍼만 넓히면 살아나는
     * 건인지"를 바로 판단할 수 있다(Step 7-4 표).
     */
    public String describeNearest() {
        if (nearestEmdCd == null || nearestDistanceMeters == null) {
            // 후보 자체가 없다 = tb_admdst 가 비었거나 좌표가 지구 반대편이다. 둘 다 경계 데이터 점검 대상.
            return "최근접 후보 없음(행정동 경계 데이터 확인 필요)";
        }
        // 소수점은 로케일 무관하게 '.' 이어야 한다 — 로그·note 를 기계로도 파싱할 수 있게.
        return String.format(Locale.ROOT, "최근접 %s(%s) %.2fm > 버퍼 %dm",
                nearestEmdCd, nearestEmdNm, nearestDistanceMeters, AdmDistResolver.BUFFER_METERS);
    }
}
