package jeju.datahub.risknoti.service;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 알림 수신자 등록 요청 바디. (POST /api/noti-receivers)
 *
 * <p>🔁 <b>TSK-4250 — 계약이 바뀌었다(R6).</b> 수신자는 {@code TB_USER} 에서 고른다(R8) — 요청은
 * {@code userId} 만 보내고 서버가 이름·이메일·부서를 사용자 행에서 <b>복사</b>한다. 종전의
 * {@code deptCd}·{@code rcvrNm}·{@code eml}·{@code sggNm}·{@code emdCd} 필드는 폐지됐다.
 * 지역은 {@link #regions} 배열(수신자 × 지역 {@code TB_NOTI_RCVR_RGN})로 받는다.
 *
 * <p>여기 애노테이션은 <b>형식</b>만 본다. 사용자가 실재·활성인지({@code TB_USER}), 코드
 * ({@code RISK_LVL_CD})·지역({@code TB_ADMDST})이 실재하는지, 지역끼리 겹치는지(R11)는 DB 를 봐야 하므로
 * 서비스가 판단한다 — 어느 쪽이든 응답은 400 이다. 같은 사용자의 활성 수신자가 이미 있으면 409(R9).
 *
 * <p>컨트롤러에서 {@code @Valid} 를 빠뜨리면 이 검증이 통째로 발동하지 않는다.
 *
 * <p><b>{@code useYn}·{@code delYn}·{@code ntfctnChCd} 에 대응하는 필드는 일부러 두지 않았다.</b>
 * 등록은 항상 활성·미삭제이고(엔티티 초기값 — Step 6 규칙 4-1), 통보채널은 선택 UI 가 없어
 * 서버가 {@code 'EMAIL'} 로 고정한다(4-2). {@code rcvrId} 도 없다 — DB identity 가 매긴다(D11).
 *
 * <p>이 클래스는 등록 전용이다. 수정은 TSK-4250 Step 4 의 {@code PUT /api/noti-receivers/{rcvrId}}
 * (R10 — {@code regions}·{@code riskLvlCds} 만 받는 별도 DTO)가 맡는다 — 사람 필드는 수정 대상이 아니다.
 */
@Schema(description = "알림 수신자 등록 요청")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class NotiReceiverRequest {

    /** 수신자로 등록할 사용자 ({@code TB_USER.user_id}). 없거나 정지된 사용자면 400 */
    @NotBlank(message = "userId 는 필수입니다")
    @Size(max = 20, message = "userId 는 20자 이내여야 합니다")
    @Schema(description = "수신자로 등록할 사용자 ID (TB_USER.user_id)", example = "jisu.kim")
    private String userId;

    /**
     * 수신 지역 목록 (최소 1개 — R3).
     *
     * <p>🔴 <b>전체 지역은 빈 배열이 아니라 원소 {@code {}}</b> 다 — 빈 배열·누락은 400 이다
     * (지역 행 0개 = "아무 지역도 안 받음" 이라 fail-closed). 같은 지역 중복은 서버가 제거하고,
     * 겹침(전체 지역 + 다른 지역 · 시군구 전체 + 그 시군구 읍면동)은 400 이다(R11).
     */
    @NotEmpty(message = "regions 는 최소 1개 이상이어야 합니다 (전체 지역은 [{}])")
    @Valid
    @Schema(description = "수신 지역 목록 (최소 1개. 전체 지역은 [{}])",
            example = "[{\"sggNm\":\"제주시\",\"emdCd\":\"50110137\"},{\"sggNm\":\"서귀포시\"}]")
    private List<NotiReceiverRegionRequest> regions;

    /**
     * 수신 대상 위험단계 코드 목록 (RISK_LVL_CD, 다중 — 최소 1단계).
     *
     * <p>중복 원소는 서비스가 {@code LinkedHashSet} 으로 제거한다(복합 PK 위반 방지).
     * 빈 배열·누락은 400 이다.
     */
    @NotEmpty(message = "riskLvlCds 는 최소 1개 이상이어야 합니다")
    @Schema(description = "수신 대상 위험단계 코드 목록 (최소 1개)", example = "[\"CRIT\",\"WARN\"]")
    private List<String> riskLvlCds;
}
