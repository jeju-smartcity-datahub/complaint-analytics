package jeju.datahub.lifezone.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 순위 1행의 세부 내역 1건 — 민원유형별 건수.
 *
 * <p>격자 안에서 <b>건수 상위 2종</b>까지만 실린다(동점은 코드 오름차순). 매퍼가
 * {@code dtl_json}(jsonb → text)으로 뽑은 것을 서비스가 Jackson 으로 역직렬화해 채운다.
 *
 * <p>🔴 <b>문자열 한 줄이 아니라 구조화 배열</b>인 이유 — {@code 인도·횡단보도 점유 152건 ·
 * 안전시설 인근 위반 98건} 같은 문구를 서버가 이어 붙이면 화면·PDF·DOCX 가 그 서식을 바꿀 수 없다.
 * 잇는 것({@code · })은 화면·문서의 몫이다.
 *
 * <p>유형명은 SQL·프론트에 하드코딩하지 않는다 — {@code TB_COM_CD}({@code CVLCPT_TYPE_CD})를
 * 조인해 읽으므로 코드가 늘거나 이름이 바뀌어도 따라간다.
 */
@Schema(description = "세부 내역 1건 (민원유형별 건수)")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class LifezoneDtlVO {

    /** 민원유형코드 ({@code CVLCPT_TYPE_CD}) */
    @Schema(description = "민원유형코드", example = "02")
    private String cvlcptTypeCd;

    /** 민원유형명. {@code TB_COM_CD.cd_nm} 값 그대로 */
    @Schema(description = "민원유형명", example = "인도·횡단보도 점유")
    private String cvlcptTypeNm;

    /** 이 유형의 건수. 합계는 {@code anlsVl} 이하다(상위 2종만 싣기 때문) */
    @Schema(description = "해당 유형 건수", example = "152")
    private int cnt;
}
