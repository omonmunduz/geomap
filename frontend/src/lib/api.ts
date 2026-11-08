// Обёртка над REST API Геопортала. Токен доступа (JWT) хранится в localStorage.
const KEY = 'gp_token'
let token: string | null = localStorage.getItem(KEY)
let onUnauthorized: (() => void) | null = null

export const getToken = () => token
export function setToken(t: string | null) {
  token = t
  if (t) localStorage.setItem(KEY, t)
  else localStorage.removeItem(KEY)
}
export function setUnauthorizedHandler(fn: () => void) { onUnauthorized = fn }

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message) }
}

type Opts = { method?: string; body?: unknown; form?: FormData; signal?: AbortSignal }

export async function api<T = any>(path: string, opts: Opts = {}): Promise<T> {
  const headers: Record<string, string> = {}
  if (token) headers.Authorization = `Bearer ${token}`
  let body: BodyInit | undefined
  if (opts.form) body = opts.form
  else if (opts.body !== undefined) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(opts.body) }
  const res = await fetch(`/api${path}`, { method: opts.method ?? (body ? 'POST' : 'GET'), headers, body, signal: opts.signal })
  if (res.status === 401 && token) { onUnauthorized?.(); throw new ApiError(401, 'Сессия истекла, требуется повторный вход') }
  const text = await res.text()
  const data = text ? JSON.parse(text) : null
  if (!res.ok) throw new ApiError(res.status, data?.error ?? `Ошибка ${res.status}`)
  return data as T
}

/** Скачивание файла с авторизацией (отчёты, CSV ошибок). */
export async function download(path: string, fallbackName: string) {
  const res = await fetch(`/api${path}`, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
  if (!res.ok) {
    const t = await res.text()
    throw new ApiError(res.status, (() => { try { return JSON.parse(t).error } catch { return `Ошибка ${res.status}` } })())
  }
  const cd = res.headers.get('Content-Disposition') ?? ''
  const m = /filename\*=UTF-8''([^;]+)/i.exec(cd) ?? /filename="?([^";]+)"?/i.exec(cd)
  const name = m ? decodeURIComponent(m[1]) : fallbackName
  const blob = await res.blob()
  saveBlob(blob, name)
}

export function saveBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = name
  document.body.appendChild(a); a.click(); a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 2000)
}

export const tileUrl = (layerId: string, draft = false, v = '') =>
  `${location.origin}/api/tiles/${layerId}/{z}/{x}/{y}.pbf?v=${encodeURIComponent(v)}${draft ? '&draft=true' : ''}`
