package jeju.datahub.report.service;

/**
 * 생성자명({@code creatr_nm}) 폭 맞추기 — 공용 유틸.
 *
 * <p>🔴 {@code parking.tb_rptp.creatr_nm} 은 {@code VARCHAR(50)} 인데 {@code parking.tb_user.user_nm} 은
 * {@code VARCHAR(100)} 이다. 자르지 않고 저장하면 긴 이름에서 PostgreSQL 이 에러를 내 보고서 생성이
 * 통째로 <b>500</b> 이 된다.
 *
 * <p>🔴 <b>둘 자리를 여기로 못박았다.</b> 원래 {@code ZoneRcmdtnReportServiceImpl} 의 private static
 * {@code cutCreatrNm} 이었으나, 같은 규칙이 필요한 보고서 생성 경로가 {@code analysis}·
 * {@code complaint}·{@code lifezone} 3개 도메인에 흩어져 있어 어느 도메인 패키지에 두어도 역참조가
 * 생긴다. {@code report} 가 {@code tb_rptp.creatr_nm} 의 주인이라 그쪽이 맞다.
 *
 * <p>⚠️ {@code tb_rptp_auto_crt_stng.creatr_nm} 도 {@code VARCHAR(50)} 이라 자동 생성 <b>설정</b>
 * 저장 경로도 같은 함수를 쓴다.
 */
public final class ReportCreatorName {

    /** {@code creatr_nm} 폭. 🔴 상수만 떼어 두면 서로게이트 처리가 이 숫자를 참조하지 못한다 — 함께 둔다 */
    public static final int CREATR_NM_MAX_LEN = 50;

    private ReportCreatorName() {
    }

    /**
     * 생성자명을 {@code creatr_nm} 폭(50자)으로 자른다. 안 자르면 긴 이름에서 생성이 500 이다.
     *
     * <p>⚠️ {@code String.length()} 기준(UTF-16 단위)이다 — PostgreSQL {@code VARCHAR(n)} 은 문자 수라, 보충
     * 문자가 섞이면 조금 더 짧게 잘릴 뿐 넘치지는 않는다. 자른 자리가 서로게이트 쌍의 가운데면 한 칸 더
     * 물러난다(반쪽 문자를 저장하지 않는다).
     *
     * <p>⚠️ {@code null} 은 그대로 {@code null} 이다 — 비어 있다는 사실을 임의 값으로 덮지 않는다.
     */
    public static String cut(String userNm) {
        if (userNm == null || userNm.length() <= CREATR_NM_MAX_LEN) {
            return userNm;
        }
        int cut = Character.isHighSurrogate(userNm.charAt(CREATR_NM_MAX_LEN - 1))
                ? CREATR_NM_MAX_LEN - 1 : CREATR_NM_MAX_LEN;
        return userNm.substring(0, cut);
    }
}
