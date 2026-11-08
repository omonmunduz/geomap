import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { api, getToken, setToken, setUnauthorizedHandler } from './api'
import type { User } from './types'
import { toast } from './toast'

interface AuthCtx { user: User | null; login: (subject: string) => Promise<void>; logout: () => void; ready: boolean }
const Ctx = createContext<AuthCtx>({ user: null, login: async () => {}, logout: () => {}, ready: false })

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [ready, setReady] = useState(false)

  useEffect(() => {
    setUnauthorizedHandler(() => {
      setToken(null); setUser(null)
      toast('Сессия истекла, требуется повторный вход', 'warn')
    })
    if (!getToken()) { setReady(true); return }
    api('/auth/me').then(u => setUser(u.anonymous ? null : u)).catch(() => setToken(null)).finally(() => setReady(true))
  }, [])

  const login = async (subject: string) => {
    const r = await api<{ token: string; user: User }>('/auth/dev-login', { body: { subject } })
    setToken(r.token)
    setUser(r.user)
  }
  const logout = () => { setToken(null); setUser(null); location.hash = '#/map' }
  return <Ctx.Provider value={{ user, login, logout, ready }}>{children}</Ctx.Provider>
}

export const useAuth = () => useContext(Ctx)
export const isStaff = (u: User | null) => u?.role === 'staff' || u?.role === 'admin'
