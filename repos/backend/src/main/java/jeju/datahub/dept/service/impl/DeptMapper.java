package jeju.datahub.dept.service.impl;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;

import jeju.datahub.dept.service.DeptVO;

/**
 * 부서 MyBatis 매퍼. SQL 은 {@code resources/mapper/dept/DeptMapper.xml}.
 *
 * <p>조회라 MyBatis 다 — 쓰기(자동 등재)는
 * JPA {@link DeptRepository} 가 맡는다. 한 클래스를 엔티티이자 매퍼 결과 타입으로 겸용하지 않는다.
 *
 * <p>🔴 {@code @Mapper} 는 <b>필수</b>다 — {@code MyBatisConfig} 가 이 애노테이션이 붙은
 * 인터페이스만 매퍼 빈으로 스캔한다.
 */
@Mapper
public interface DeptMapper {

    /** 사용중 부서 목록 (화면 select 소스) */
    List<DeptVO> selectDepts();

    /** 사용중 부서코드만 (코드 유효성 대조용 — 스칼라라 VO 를 만들지 않는다) */
    List<String> selectDeptCds();
}
