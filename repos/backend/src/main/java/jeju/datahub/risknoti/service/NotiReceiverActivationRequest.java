package jeju.datahub.risknoti.service;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 알림 수신자 활성화 토글 요청 바디. (PATCH /api/noti-receivers/{rcvrId}/activation)
 *
 * <p>화면의 토글 스위치 하나만 바꾸는 요청이라 필드가 {@code useYn} 뿐이다 —
 * 등록 요청({@link NotiReceiverRequest})을 재사용하면 이름·이메일·지역이 필수가 돼 토글에
 * 불필요한 값을 요구하게 된다.
 *
 * <p>⚠️ 활성화({@code use_yn})와 삭제({@code del_yn})는 <b>뜻이 다르다</b> (D5·D14).
 * 비활성은 "메일을 보내지 않는다"이고 삭제는 "명단에서 숨긴다"이다.
 */
@Schema(description = "알림 수신자 활성화 토글 요청")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class NotiReceiverActivationRequest {

    /** 사용여부 — {@code Y} 또는 {@code N} 만 허용한다 */
    @NotBlank(message = "useYn 은 필수입니다")
    @Pattern(regexp = "[YN]", message = "useYn 은 Y 또는 N 이어야 합니다")
    @Schema(description = "사용여부 (Y | N)", example = "N")
    private String useYn;
}
