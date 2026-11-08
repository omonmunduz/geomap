import { useEffect, useState } from 'react'
import { api, ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'
import { Modal, Spinner } from './Modal'
import { toast } from '../lib/toast'

interface Account { subject: string; displayName: string; position: string; org?: string; provider: 'infocom' | 'tunduk'; active: boolean }

/** Окно выбора способа входа (БП-1): Infocom ID — сотрудники, Түндүк — граждане. В демо-режиме — выбор тестовой учётной записи. */
export function LoginModal({ onClose, onDenied }: { onClose: () => void; onDenied: (msg: string) => void }) {
  const { login } = useAuth()
  const [provider, setProvider] = useState<'infocom' | 'tunduk' | null>(null)
  const [accounts, setAccounts] = useState<Account[]>([])
  const [devLogin, setDevLogin] = useState(true)
  const [busy, setBusy] = useState(false)

  useEffect(() => { api('/auth/providers').then(r => { setAccounts(r.accounts); setDevLogin(r.devLogin) }) }, [])

  const choose = async (a: Account) => {
    setBusy(true)
    try {
      await login(a.subject)
      toast(`Вы вошли как ${a.displayName}`)
      onClose()
    } catch (e) {
      if (e instanceof ApiError && e.status === 403) { onClose(); onDenied(e.message) }
      else toast((e as Error).message, 'error')
    } finally { setBusy(false) }
  }

  return (
    <Modal title="Вход в Геопортал" onClose={onClose}>
      {busy ? <Spinner text="Выполняется вход..." /> : !provider ? (
        <div className="login-choices">
          <button className="login-btn" onClick={() => setProvider('infocom')}>
            <strong>Войти через Infocom ID</strong><span>для сотрудников государственных органов</span>
          </button>
          <button className="login-btn" onClick={() => setProvider('tunduk')}>
            <strong>Войти через Түндүк</strong><span>для граждан</span>
          </button>
          {!devLogin && <p className="muted">Внешние провайдеры входа не настроены.</p>}
        </div>
      ) : (
        <div>
          <div className="demo-note">
            Демонстрационный режим: внешний вход {provider === 'infocom' ? 'Infocom ID и запрос в Е-Кызмат' : 'Түндүк'} эмулируется.
            Выберите тестовую учётную запись.
          </div>
          <div className="account-list">
            {accounts.filter(a => a.provider === provider).map(a => (
              <button key={a.subject} className="account" onClick={() => choose(a)}>
                <span className="avatar">{initials(a.displayName)}</span>
                <span><strong>{a.displayName}</strong><small>{a.position}{a.org ? ` · ${a.org}` : ''}</small></span>
                {!a.active && <span className="tag tag-red">is_active=false</span>}
              </button>
            ))}
          </div>
          <button className="btn" onClick={() => setProvider(null)}>← Назад</button>
        </div>
      )}
    </Modal>
  )
}

export const initials = (n: string) => n.split(/\s+/).filter(Boolean).slice(0, 2).map(s => s[0]).join('').toUpperCase()
