package jeju.datahub.risknoti.service;

/**
 * 같은 사용자의 활성 수신자({@code del_yn='N'})가 이미 있다 — <b>409 {@code {"code":"RCVR_DUPLICATED"}}</b>
 * (한 사용자 = 수신자 1행).
 *
 * <p>두 경로에서 던진다 — ① 서비스 선검사 ② 선검사와 INSERT 사이 경합으로 부분 유니크 인덱스
 * {@code uq_tb_noti_rcvr_user} 가 걸린 경우(그 제약명일 때만 번역 — 다른 무결성 위반은 500 으로 드러낸다).
 * 핸들러는 {@code NotiReceiverExceptionHandler} 다.
 */
public class NotiReceiverDuplicatedException extends RuntimeException {

    /** 응답 본문 {@code code} 값 */
    public static final String CODE = "RCVR_DUPLICATED";

    public NotiReceiverDuplicatedException(String userId) {
        super(CODE + " userId=" + userId);
    }

    public NotiReceiverDuplicatedException(String userId, Throwable cause) {
        super(CODE + " userId=" + userId, cause);
    }
}
