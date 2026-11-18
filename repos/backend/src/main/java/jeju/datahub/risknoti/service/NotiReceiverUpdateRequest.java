package jeju.datahub.risknoti.service;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 알림 수신자 수정 요청 바디. (PUT /api/noti-receivers/{rcvrId})
 *
 * <p>수신 지역({@code TB_NOTI_RCVR_RGN})과 수신 단계({@code TB_NOTI_RCVR_LVL})를 <b>통째로 교체</b>한다.
 *
 * <p>🔴 <b>사람 필드(이름·이메일·부서·{@code userId})가 없다 — 무시하는 것이 아니라 받지 않는다.</b>
 * 사람 정보는 {@code TB_USER} 가 정본이라 화면에서 고치지 않는다. 다른 사람으로 바꾸려면 삭제 후 등록한다.
 * 활성화({@code use_yn})도 여기 없다 — 기존 {@code PATCH /{rcvrId}/activation} 이 맡는다.
 *
 * <p>검증 규칙은 등록 요청 {@link NotiReceiverRequest} 와 같다 — 여기 애노테이션은 형식만 보고, 코드·지역 실재와
 * 지역 겹침은 서비스가 등록과 <b>같은 메서드</b>로 판단한다(어느 쪽이든 400).
 *
 * <p>컨트롤러에서 {@code @Valid} 를 빠뜨리면 이 검증이 통째로 발동하지 않는다.
 */
@Schema(description = "알림 수신자 수정 요청 (지역·단계 통째 교체)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class NotiReceiverUpdateRequest {

    /**
     * 수신 지역 목록 (최소 1개).
     *
     * <p>🔴 <b>전체 지역은 빈 배열이 아니라 원소 {@code {}}</b> 다 — 빈 배열·누락은 400 이다
     * (지역 행 0개 = "아무 지역도 안 받음" 이라 fail-closed). 같은 지역 중복은 서버가 제거하고,
     * 겹침(전체 지역 + 다른 지역 · 시군구 전체 + 그 시군구 읍면동)은 400 이다.
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
