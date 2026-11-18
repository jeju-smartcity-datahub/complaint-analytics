package jeju.datahub.risknoti.service;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 알림 수신자의 수신 지역 원소 VO. {@code parking.TB_NOTI_RCVR_RGN} + {@code TB_ADMDST}
 * — MyBatis 조회 전용({@code RiskNotiMapper.selectReceiverRegions}).
 *
 * <p>원소는 3형태다: 읍면동 지정({@code sggNm}·{@code emdCd} 있음) · 시군구 전체({@code emdCd} NULL) ·
 * <b>전체 지역(둘 다 NULL — 명시 행)</b>. 🔴 NULL 을 "값 없음" 으로 읽고 걸러내지 않는다 — 걸러내면 그 사람이
 * 전체 지역 수신자인데 화면에 지역이 비어 보인다.
 */
@Schema(description = "수신 지역 원소 (전체 지역이면 sggNm·emdCd 가 둘 다 null)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class NotiReceiverRegionVO {

    /**
     * 소속 수신자ID — 서비스가 페이지 단위 한 번 조회한 결과를 수신자별로 나눌 때만 쓴다.
     * 응답 원소에는 내리지 않는다(부모 행에 이미 있다).
     */
    @JsonIgnore
    private Long rcvrId;

    /** 시군구명 (sgg_nm). NULL 이면 전체 지역 */
    @Schema(description = "시군구명 (NULL 이면 전체 지역)", example = "제주시")
    private String sggNm;

    /** 읍면동코드 (emd_cd). NULL 이면 시군구 전체 또는 전체 지역 */
    @Schema(description = "읍면동코드 (NULL 이면 시군구 전체·전체 지역)", example = "50110137")
    private String emdCd;

    /** 읍면동명 (TB_ADMDST.emd_nm) — {@code emd_cd} 가 nullable 이라 LEFT JOIN 이다 */
    @Schema(description = "읍면동명", example = "연동")
    private String emdNm;

    /**
     * 원소 표시명 — 3분기: {@code "{sgg_nm} {emd_nm}"} / {@code "{sgg_nm} 전체"} / {@code "전체 지역"}.
     * 조합은 매퍼 CASE 한 곳이 만든다 — 부모의 합친 {@code regionNm} 은 이 값을 {@code ", "} 로 잇는다.
     */
    @Schema(description = "지역 표시명 (3분기: '제주시 연동' | '제주시 전체' | '전체 지역')", example = "제주시 연동")
    private String regionNm;
}
