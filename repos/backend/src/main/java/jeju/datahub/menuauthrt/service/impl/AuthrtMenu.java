package jeju.datahub.menuauthrt.service.impl;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 권한×메뉴 접근 매핑 엔티티. (parking.TB_AUTHRT_MENU — 쓰기 전용)
 *
 * <p>행이 존재하면 해당 권한이 그 메뉴에 접근 허용이라는 의미이며, 별도 상태 컬럼이 없다.
 * 저장(전체 교체)은 영속성 규칙상 JPA 담당이라 이 엔티티를 쓴다
 * (쓰기=JPA).
 *
 * <p><b>조회는 이 엔티티로 하지 않는다.</b> 화면 목록·단건 판정은 다중 LEFT JOIN + 컬렉션 집계라
 * MyBatis({@link MenuAuthrtMapper})가 담당하고 결과는 조회 전용 VO 로 받는다 —
 * 같은 테이블을 엔티티와 VO 로 겸용하지 않는다는 규칙에 따른다.
 *
 * <p>컬럼명은 DDL {@code database/migrations/sql/29-TB_AUTHRT_MENU.sql} 기준으로 명시한다.
 * JPA 는 MyBatis 의 {@code map-underscore-to-camel-case} 설정과 무관하므로
 * {@code @Column(name=...)} 을 생략하면 안 된다.
 */
@Entity
@Table(name = "tb_authrt_menu", schema = "parking")
@IdClass(AuthrtMenuId.class)
@Getter
@Setter
@ToString
@NoArgsConstructor
public class AuthrtMenu {

    /** 권한코드 (authrt_cd, 복합 PK #1 — TB_AUTHRT 참조) */
    @Id
    @Column(name = "authrt_cd", nullable = false, length = 30)
    private String authrtCd;

    /** 메뉴아이디 (menu_id, 복합 PK #2 — TB_MENU 참조) */
    @Id
    @Column(name = "menu_id", nullable = false, length = 20)
    private String menuId;

    /** 등록자ID (rgtr_id, NOT NULL) */
    @Column(name = "rgtr_id", nullable = false, length = 20)
    private String rgtrId;

    /** 등록일시 (reg_dt, NOT NULL) */
    @Column(name = "reg_dt", nullable = false)
    private LocalDateTime regDt;

    /** 수정자ID (mdfr_id, nullable) */
    @Column(name = "mdfr_id", length = 20)
    private String mdfrId;

    /** 수정일시 (mdfcn_dt, nullable) */
    @Column(name = "mdfcn_dt")
    private LocalDateTime mdfcnDt;

    /**
     * 신규 매핑 행 생성용 생성자.
     *
     * <p>저장은 항상 <b>전체 교체</b>(해당 메뉴의 매핑을 지우고 새로 넣는다)라
     * 이 엔티티로 만들어지는 행은 예외 없이 신규 INSERT 다. 따라서 수정 이력 컬럼
     * ({@code mdfr_id}/{@code mdfcn_dt})은 <b>채우지 않고 NULL 로 둔다</b> —
     * 시드({@code database/migrations/sql/seeding/02-TB_MENU_AUTHRT.sql})의 매핑 INSERT 도
     * {@code (authrt_cd, menu_id, rgtr_id, reg_dt)} 4개만 넣어 같은 규약을 쓴다.
     * UPDATE 경로가 생기면 그때 두 컬럼을 채운다.
     *
     * @param authrtCd 권한코드
     * @param menuId   메뉴아이디
     * @param userId   등록자 ID (인증 도입 전에는 상수 'system')
     * @param now      등록 일시
     */
    public AuthrtMenu(String authrtCd, String menuId, String userId, LocalDateTime now) {
        this.authrtCd = authrtCd;
        this.menuId = menuId;
        this.rgtrId = userId;
        this.regDt = now;
    }
}
