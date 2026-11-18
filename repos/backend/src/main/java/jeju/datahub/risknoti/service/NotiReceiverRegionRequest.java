package jeju.datahub.risknoti.service;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 알림 수신자 지역 원소 — {@link NotiReceiverRequest#getRegions()} 의 한 칸 (TSK-4250 R6).
 *
 * <p>3형태다 (D13 단건 규칙을 원소마다 적용):
 * <ul>
 *   <li>{@code emdCd} 있음 → 읍면동. {@code sggNm} 은 <b>무시되고</b> 서버가 {@code TB_ADMDST} 에서 다시 채운다
 *       ('제주시 + 성산읍' 같은 불일치 조합 차단). 없는 {@code emdCd} 는 400.</li>
 *   <li>{@code sggNm} 만 → 시군구 전체. 실재하는 시군구명인지 대조한다(오타 저장 방지 — 없으면 400).</li>
 *   <li>둘 다 비어 있음({@code {}}) → <b>전체 지역</b>. {@code sgg_nm}·{@code emd_cd} 둘 다 NULL 인 행으로 저장된다(R3).</li>
 * </ul>
 * 빈 문자열·공백은 "지정 안 함" 으로 본다.
 */
@Schema(description = "알림 수신 지역 (둘 다 비우면 전체 지역)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class NotiReceiverRegionRequest {

    /** 시군구명 (선택). {@code emdCd} 를 함께 보내면 무시된다 */
    @Size(max = 40, message = "sggNm 은 40자 이내여야 합니다")
    @Schema(description = "시군구명 (선택. emdCd 를 함께 보내면 무시된다)", example = "제주시")
    private String sggNm;

    /** 읍면동코드 (선택). 비우면 시군구 전체, {@code sggNm} 도 비우면 전체 지역 */
    @Size(max = 8, message = "emdCd 는 8자 이내여야 합니다")
    @Schema(description = "읍면동코드 (선택. 비우면 시군구 전체)", example = "50110137")
    private String emdCd;
}
