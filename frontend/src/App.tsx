import { useEffect, useState } from 'react'
import { Header, type Route } from './components/Header'
import { LoginModal } from './components/LoginModal'
import { Toasts } from './lib/toast'
import { useAuth, isStaff } from './lib/auth'
import { MapPage } from './pages/MapPage'
import { MyLayersPage } from './pages/MyLayersPage'
import { ZoningPage } from './pages/ZoningPage'
import { AdminPage } from './pages/AdminPage'

const parse = (): Route => {
  const r = location.hash.replace(/^#\//, '').split('?')[0]
  return (['map', 'my-layers', 'zoning', 'admin'] as Route[]).includes(r as Route) ? (r as Route) : 'map'
}

export function App() {
  const { user, ready } = useAuth()
  const [route, setRoute] = useState<Route>(parse())
  const [login, setLogin] = useState(false)
  const [denied, setDenied] = useState<string | null>(null)
  const [lang, setLang] = useState<'ru' | 'ky'>((localStorage.getItem('gp_lang') as 'ru' | 'ky') ?? 'ru')

  useEffect(() => { const f = () => setRoute(parse()); window.addEventListener('hashchange', f); return () => window.removeEventListener('hashchange', f) }, [])
  useEffect(() => { localStorage.setItem('gp_lang', lang) }, [lang])

  if (denied) return (
    <div className="denied">
      <div>
        <h1>Доступ запрещён</h1>
        <p>{denied}</p>
        <button className="btn btn-primary" onClick={() => { setDenied(null); location.hash = '#/map' }}>Вернуться на главную</button>
      </div>
    </div>
  )

  const needStaff = route !== 'map' && ready && !isStaff(user)
  const needAdmin = route === 'admin' && ready && user?.role !== 'admin'
  return (
    <div className="app">
      <Header route={route} onLogin={() => setLogin(true)} lang={lang} setLang={setLang} />
      <main className="content">
        {(needStaff || needAdmin) ? (
          <div className="empty-page">
            <h2>Раздел служебного контура</h2>
            <p>{user ? 'У вашей учётной записи нет доступа к этому разделу.' : 'Войдите через Infocom ID, чтобы открыть этот раздел.'}</p>
            {!user && <button className="btn btn-primary" onClick={() => setLogin(true)}>Войти</button>}
          </div>
        ) : route === 'map' ? <MapPage lang={lang} />
          : route === 'my-layers' ? <MyLayersPage lang={lang} />
          : route === 'zoning' ? <ZoningPage />
          : <AdminPage />}
      </main>
      {login && <LoginModal onClose={() => setLogin(false)} onDenied={setDenied} />}
      <Toasts />
    </div>
  )
}
