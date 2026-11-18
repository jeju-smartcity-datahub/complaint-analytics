package jeju.datahub.auth.web;

import jakarta.validation.constraints.NotBlank;

/**
 * 이메일/비밀번호 로그인 요청 본문.
 *
 * <h2>🔴 자격증명은 본문으로만 받는다</h2>
 * 쿼리스트링으로 받으면 Tomcat 액세스 로그({@code logs/access.<날짜>.log})에 URL 이 통째로 남아
 * <b>비밀번호가 평문으로 파일에 적힌다</b>. 그래서 이 타입은 {@code @RequestBody} 전용이며
 * {@code @ModelAttribute}/쿼리 파라미터로 바인딩하지 않는다.
 *
 * <h2>🔴 {@code toString()} 을 재정의하는 이유</h2>
 * 레코드 기본 {@code toString()} 은 모든 필드를 그대로 찍는다. 디버거·예외 메시지·
 * {@code MethodArgumentNotValidException} 같은 프레임워크 경로가 DTO 를 문자열화하는 순간
 * 비밀번호가 로그로 새므로 <b>마스킹을 타입에 붙여</b> 호출부의 주의에 기대지 않는다
 * (선례: {@code DhubPasswordAuthClient.PasswordLoginResult}).
 */
public record LoginRequest(@NotBlank String email, @NotBlank String password) {

    /** 🔴 password 를 절대 찍지 않는다. */
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=****]";
    }
}
