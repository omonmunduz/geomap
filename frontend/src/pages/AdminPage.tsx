import { useEffect, useState } from 'react'
import { api } from '../lib/api'
import { useAuth } from '../lib/auth'
import { toast } from '../lib/toast'
import { roleLabel } from '../components/Header'
import { Spinner } from '../components/Modal'

const ACTIONS: Record<string, string> = {
  login: 'Вход', login_denied: 'Отказ во входе', layer_create: 'Создание слоя', layer_publish: 'Публикация слоя', layer_unpublish: 'Снятие с публикации',
  layer_rollback: 'Откат версии', layer_update: 'Изменение слоя', import_commit: 'Импорт данных', feature_create: 'Создание объекта',
  feature_update: 'Изменение объекта', feature_delete: 'Удаление объекта', zoning_check: 'Проверка зонирования',
  discrepancy_status: 'Статус расхождения', report_export: 'Выгрузка отчёта', user_update: 'Изменение пользователя', zoning_rules_update: 'Правила зонирования',
}

/** UC-07: пользователи и роли, журнал событий и ИБ, правила зонирования. */
export function AdminPage() {
  const [tab, setTab] = useState<'users' | 'audit' | 'rules'>('users')
  const [stats, setStats] = useState<any | null>(null)
  useEffect(() => { api('/admin/stats').then(setStats).catch(e => toast(e.message, 'error')) }, [tab])
  return (
    <div className="page">
      <div className="page-head"><h1>Администрирование</h1></div>
      {stats && (
        <div className="stats">
          <div><b>{stats.users}</b><span>пользователей</span></div>
          <div><b>{stats.publishedLayers} / {stats.layers}</b><span>слоёв опубликовано</span></div>
          <div><b>{Number(stats.publishedFeatures).toLocaleString('ru')}</b><span>опубликованных объектов</span></div>
          <div><b>{stats.imports}</b><span>заданий импорта</span></div>
          <div><b>{stats.discrepancies}</b><span>расхождений в реестре</span></div>
          <div><b>{stats.events24h}</b><span>событий за 24 ч</span></div>
          <div className={stats.denied24h > 0 ? 'warn' : ''}><b>{stats.denied24h}</b><span>отказов за 24 ч</span></div>
        </div>
      )}
      <div className="tabs">
        <button className={tab === 'users' ? 'on' : ''} onClick={() => setTab('users')}>Пользователи и роли</button>
        <button className={tab === 'audit' ? 'on' : ''} onClick={() => setTab('audit')}>Журнал событий</button>
        <button className={tab === 'rules' ? 'on' : ''} onClick={() => setTab('rules')}>Правила зонирования</button>
      </div>
      {tab === 'users' ? <Users /> : tab === 'audit' ? <Audit /> : <Rules />}
    </div>
  )
}

function Users() {
  const { user: me } = useAuth()
  const [rows, setRows] = useState<any[] | null>(null)
  const load = () => api('/admin/users').then(setRows)
  useEffect(() => { load() }, [])
  const patch = async (id: string, body: any) => {
    try { await api(`/admin/users/${id}`, { method: 'PATCH', body }); toast('Изменения сохранены'); load() } catch (e) { toast((e as Error).message, 'error') }
  }
  if (!rows) return <Spinner />
  return (
    <table className="table">
      <thead><tr><th>Пользователь</th><th>Организация</th><th>Должность</th><th>Роль</th><th>Статус</th><th>Последний вход</th></tr></thead>
      <tbody>{rows.map(u => (
        <tr key={u.id}>
          <td><b>{u.displayName}</b><br /><small className="muted">{u.extSubject}</small></td>
          <td>{u.org ?? '—'}</td><td>{u.position ?? '—'}</td>
          <td>{u.id === me?.id || u.extSubject.startsWith('system:') ? roleLabel(u.role) : (
            <select value={u.role} onChange={e => patch(u.id, { role: e.target.value })}>
              <option value="authorized">{roleLabel('authorized')}</option>
              <option value="staff" disabled={!u.org}>{roleLabel('staff')}</option>
              <option value="admin" disabled={!u.org}>{roleLabel('admin')}</option>
            </select>)}</td>
          <td>{u.id === me?.id || u.extSubject.startsWith('system:') ? (u.status === 'active' ? 'Активен' : 'Заблокирован') : (
            <button className={`btn sm ${u.status === 'blocked' ? 'btn-danger-outline' : ''}`} onClick={() => patch(u.id, { status: u.status === 'active' ? 'blocked' : 'active' })}>
              {u.status === 'active' ? 'Активен — заблокировать' : 'Заблокирован — разблокировать'}</button>)}</td>
          <td>{u.lastLoginAt ? new Date(u.lastLoginAt).toLocaleString('ru') : '—'}</td>
        </tr>))}</tbody>
    </table>
  )
}

function Audit() {
  const [rows, setRows] = useState<any[] | null>(null)
  const [action, setAction] = useState('')
  const [outcome, setOutcome] = useState('')
  useEffect(() => { setRows(null); api(`/admin/audit?limit=300&action=${action}&outcome=${outcome}`).then(setRows) }, [action, outcome])
  return (
    <>
      <div className="row gap">
        <select value={action} onChange={e => setAction(e.target.value)}><option value="">Все события</option>{Object.entries(ACTIONS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select>
        <select value={outcome} onChange={e => setOutcome(e.target.value)}><option value="">Любой результат</option><option value="success">Успешно</option><option value="denied">Отказ</option></select>
      </div>
      {!rows ? <Spinner /> : (
        <table className="table compact">
          <thead><tr><th>Время</th><th>Пользователь</th><th>Орг.</th><th>Событие</th><th>Объект</th><th>Результат</th><th>Детали</th><th>IP</th></tr></thead>
          <tbody>{rows.map(r => (
            <tr key={r.id} className={r.outcome !== 'success' ? 'row-warn' : ''}>
              <td>{new Date(r.at).toLocaleString('ru')}</td><td>{r.userName ?? '—'}</td><td>{r.orgCode ?? '—'}</td>
              <td>{ACTIONS[r.action] ?? r.action}</td><td><small>{r.objectType} {r.objectId?.slice(0, 8)}</small></td>
              <td>{r.outcome === 'success' ? 'успешно' : <span className="tag tag-red">{r.outcome}</span>}</td>
              <td><small>{r.details ? Object.entries(r.details).map(([k, v]) => `${k}: ${typeof v === 'object' ? JSON.stringify(v) : v}`).join('; ') : ''}</small></td>
              <td><small>{r.ip}</small></td>
            </tr>))}</tbody>
        </table>
      )}
    </>
  )
}

function Rules() {
  const [cfg, setCfg] = useState<any | null>(null)
  const [rules, setRules] = useState<Record<string, boolean>>({})
  useEffect(() => { api('/zoning/config').then(c => { setCfg(c); setRules(Object.fromEntries(c.rules.map((r: any) => [`${r.zoneType}|${r.useType}`, r.allowed]))) }) }, [])
  const save = async () => {
    try {
      await api('/admin/zoning-rules', { method: 'PUT', body: Object.entries(rules).map(([k, v]) => { const [zoneType, useType] = k.split('|'); return { zoneType, useType, allowed: v } }) })
      toast('Правила сохранены. Изменения учитываются при следующей проверке.')
    } catch (e) { toast((e as Error).message, 'error') }
  }
  if (!cfg) return <Spinner />
  return (
    <>
      <p className="muted">Матрица разрешённого использования: вид зоны (строки) × фактическое использование объекта (столбцы). Справочник конфигурируется администратором (FR-CFG-01).</p>
      <table className="table matrix">
        <thead><tr><th>Зона \ Использование</th>{cfg.useTypes.map((u: any) => <th key={u.value}>{u.label}</th>)}</tr></thead>
        <tbody>{cfg.zoneTypes.map((z: any) => (
          <tr key={z.value}><th>{z.label}</th>{cfg.useTypes.map((u: any) => {
            const k = `${z.value}|${u.value}`
            return <td key={k}><input type="checkbox" checked={!!rules[k]} onChange={e => setRules({ ...rules, [k]: e.target.checked })} /></td>
          })}</tr>))}</tbody>
      </table>
      <button className="btn btn-primary" onClick={save}>Сохранить правила</button>
    </>
  )
}
