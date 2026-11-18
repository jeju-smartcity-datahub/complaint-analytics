import { createContext, useContext, type ReactNode } from 'react'
import type { LoginUser } from '../api/auth'

/**
 * 로그인 사용자를 하위 트리에 전달한다.
 *
 * 🔴 이것뿐이다 — 인증 판정·조회는 `RequireAuth` 가 하고, 여기는 전달만 한다.
 *    `LoginUser` 타입은 `src/api/auth.ts` 것을 그대로 쓴다(재정의 금지).
 */
const AuthContext = createContext<LoginUser | null>(null)

/**
 * 🔴 시그니처가 `{ value, children }` 다 — `RequireAuth` 가 `<AuthProvider value={user}>` 로
 *    부르므로 `({ children })` 만 받게 만들면 컴파일되지 않는다.
 */
export function AuthProvider({ value, children }: { value: LoginUser; children: ReactNode }) {
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

/** 로그인 사용자. `RequireAuth` 안에서만 non-null 이다. */
export function useAuth(): LoginUser | null {
  return useContext(AuthContext)
}
