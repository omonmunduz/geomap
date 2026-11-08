import { useState } from 'react'
import { useAuth, isStaff } from '../lib/auth'
import { initials } from './LoginModal'

export type Route = 'map' | 'my-layers' | 'zoning' | 'admin'

export function Header({ route, onLogin, lang, setLang }: { route: Route; onLogin: () => void; lang: 'ru' | 'ky'; setLang: (l: 'ru' | 'ky') => void }) {
  const { user, logout } = useAuth()
  const [menu, setMenu] = useState(false)
  const nav: { r: Route; label: string; show: boolean }[] = [
    { r: 'map', label: 'Карта', show: true },
    { r: 'my-layers', label: 'Мои слои', show: isStaff(user) },
    { r: 'zoning', label: 'Контроль зонирования', show: isStaff(user) },
    { r: 'admin', label: 'Администрирование', show: user?.role === 'admin' },
  ]
  return (
    <header className="topbar">
      <a className="brand" href="#/map">
        <svg width="26" height="26" viewBox="0 0 32 32" aria-hidden><circle cx="16" cy="16" r="14" fill="#0b5cad" /><path d="M7 18c4-6 7-6 9-3s5 3 9-3" stroke="#fff" strokeWidth="2.5" fill="none" /></svg>
        <span><strong>Геопортал НИПД КР</strong><small>Национальная инфраструктура пространственных данных</small></span>
      </a>
      <nav>
        {nav.filter(n => n.show).map(n => <a key={n.r} href={`#/${n.r}`} className={route === n.r ? 'active' : ''}>{n.label}</a>)}
      </nav>
      <div className="topbar-right">
        <div className="lang">
          <button className={lang === 'ru' ? 'on' : ''} onClick={() => setLang('ru')}>РУС</button>
          <button className={lang === 'ky' ? 'on' : ''} onClick={() => setLang('ky')}>КЫР</button>
        </div>
        {!user ? <button className="btn btn-primary" onClick={onLogin}>Войти</button> : (
          <div className="user-menu">
            <button className="avatar-btn" onClick={() => setMenu(m => !m)} title={user.name}>
              <span className="avatar">{initials(user.name)}</span>
              <span className="user-meta"><strong>{user.name}</strong><small>{roleLabel(user.role)}{user.orgName ? ` · ${user.orgName}` : ''}</small></span>
            </button>
            {menu && (
              <div className="dropdown" onMouseLeave={() => setMenu(false)}>
                {isStaff(user) && <a href="#/my-layers" onClick={() => setMenu(false)}>Мои слои</a>}
                {isStaff(user) && <a href="#/zoning" onClick={() => setMenu(false)}>Контроль зонирования</a>}
                {user.role === 'admin' && <a href="#/admin" onClick={() => setMenu(false)}>Администрирование</a>}
                <button onClick={() => { setMenu(false); logout() }}>Выйти</button>
              </div>
            )}
          </div>
        )}
      </div>
    </header>
  )
}

export const roleLabel = (r: string) => ({ authorized: 'Авторизованный пользователь', staff: 'Сотрудник ИС', admin: 'Администратор системы' } as Record<string, string>)[r] ?? r
