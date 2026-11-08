import { useCallback, useEffect, useRef, useState } from 'react'
import type { Map as MlMap } from 'maplibre-gl'
import { api, download } from '../lib/api'
import { useAuth } from '../lib/auth'
import { toast } from '../lib/toast'
import type { Attr, LayerInfo } from '../lib/types'
import { addGeoLayer, fitBbox, newMap, setHighlight, GEOM_LABEL, IMPORT_STATUS, STATUS_LABEL } from '../lib/mapStyle'
import { Confirm, Modal, Spinner } from '../components/Modal'

const fmtDate = (s?: string) => s ? new Date(s).toLocaleString('ru', { dateStyle: 'short', timeStyle: 'short' }) : '—'
const title = (l: { titleRu: string; titleKy?: string }, lang: string) => (lang === 'ky' && l.titleKy) ? l.titleKy : l.titleRu

export function MyLayersPage({ lang }: { lang: 'ru' | 'ky' }) {
  const { user } = useAuth()
  const [layers, setLayers] = useState<LayerInfo[] | null>(null)
  const [wizard, setWizard] = useState<{ layerId?: string } | null>(null)
  const [openId, setOpenId] = useState<string | null>(null)
  const [filter, setFilter] = useState('')

  const load = useCallback(() => api<LayerInfo[]>('/my/layers').then(setLayers).catch(e => toast(e.message, 'error')), [])
  useEffect(() => { load() }, [load])
  // опрос статуса фоновых импортов
  useEffect(() => {
    if (!layers?.some(l => l.lastImport && ['queued', 'running'].includes(l.lastImport.status))) return
    const t = setInterval(load, 1500)
    return () => clearInterval(t)
  }, [layers, load])

  const shown = (layers ?? []).filter(l => !filter || title(l, lang).toLowerCase().includes(filter.toLowerCase()))

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Мои слои</h1>
          <p className="muted">{user?.role === 'admin' ? 'Все слои Геопортала (администратор)' : `Слои, закреплённые за организацией: ${user?.orgName}`}</p>
        </div>
        <div className="row gap">
          <input className="search-inline" placeholder="Фильтр по наименованию" value={filter} onChange={e => setFilter(e.target.value)} />
          <button className="btn btn-primary" onClick={() => setWizard({})}>+ Импорт данных</button>
        </div>
      </div>
      {!layers ? <Spinner text="Загрузка..." /> : (
        <table className="table">
          <thead><tr><th>Наименование слоя</th><th>Тип геометрии</th><th>Доступ</th><th>Статус</th><th>Объектов</th><th>Последнее изменение</th><th>Статус импорта</th></tr></thead>
          <tbody>
            {shown.map(l => (
              <tr key={l.id} onClick={() => setOpenId(l.id)} className="clickable">
                <td><span className="swatch" style={{ background: l.style?.color }} /> {title(l, lang)}
                  {l.appendixNo && l.appendixNo !== '—' && <small className="muted"> · №{l.appendixNo}</small>}
                  {user?.role === 'admin' && <small className="muted"> · {l.owner}</small>}</td>
                <td>{GEOM_LABEL[l.geomType]}</td>
                <td>{l.accessLevel === 'open' ? 'Открытый' : <span className="tag tag-amber">ДСП</span>}</td>
                <td><span className={`status status-${l.status}`}>{STATUS_LABEL[l.status]}</span></td>
                <td>{l.status === 'published_with_draft' ? `${l.publishedCount} → ${l.draftCount}` : (l.publishedVersion ? l.publishedCount : l.draftCount) || '—'}</td>
                <td>{fmtDate(l.updatedAt)}</td>
                <td onClick={e => e.stopPropagation()}>
                  {l.lastImport ? <ImportStatus j={l.lastImport} /> : <span className="muted">—</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {wizard && layers && <ImportWizard layers={layers.filter(l => l.canEdit && l.geomType !== 'raster')} initialLayerId={wizard.layerId} lang={lang}
        onClose={() => setWizard(null)} onStarted={() => { setWizard(null); load() }} />}
      {openId && <LayerDrawer id={openId} lang={lang} onClose={() => setOpenId(null)} onChanged={load}
        onImport={() => { setWizard({ layerId: openId }); setOpenId(null) }} />}
    </div>
  )
}

function ImportStatus({ j }: { j: NonNullable<LayerInfo['lastImport']> }) {
  const [more, setMore] = useState(false)
  return (
    <span className={`imp imp-${j.status}`}>
      {IMPORT_STATUS[j.status] ?? j.status}
      {j.status === 'running' && <span className="progress"><i style={{ width: `${j.progress}%` }} /></span>}
      {j.status === 'failed' && <button className="link" onClick={() => setMore(m => !m)}>Подробнее</button>}
      {more && <small className="err block">{j.message}</small>}
    </span>
  )
}

// ---------------------------------------------------------------------------- карточка слоя

function LayerDrawer({ id, lang, onClose, onChanged, onImport }: { id: string; lang: string; onClose: () => void; onChanged: () => void; onImport: () => void }) {
  const [d, setD] = useState<any | null>(null)
  const [confirm, setConfirm] = useState<null | { kind: 'publish' | 'unpublish' | 'rollback'; version?: number }>(null)
  const [edit, setEdit] = useState<{ titleRu: string; titleKy: string; accessLevel: string; color: string } | null>(null)
  const el = useRef<HTMLDivElement>(null)
  const mapRef = useRef<MlMap | null>(null)

  const load = useCallback(() => api(`/catalog/layers/${id}`).then(setD), [id])
  useEffect(() => { load() }, [load])

  useEffect(() => {
    if (!d || !el.current) return
    mapRef.current?.remove()
    const map = newMap(el.current)
    mapRef.current = map
    map.on('load', () => {
      if (d.draftVersion || d.publishedVersion) addGeoLayer(map, d, { draft: !!d.draftVersion, opacity: 0.45 })
      if (d.bbox) fitBbox(map, d.bbox, 14)
    })
    return () => { map.remove(); mapRef.current = null }
  }, [d?.id, d?.updatedAt])

  const act = async () => {
    if (!confirm) return
    try {
      const path = confirm.kind === 'rollback' ? `/layers/${id}/rollback/${confirm.version}` : `/layers/${id}/${confirm.kind}`
      const r = await api(path, { method: 'POST' })
      setD(r); onChanged()
      toast(confirm.kind === 'publish' ? 'Слой опубликован' : confirm.kind === 'unpublish' ? 'Публикация снята' : `Слой откатан к версии ${confirm.version}`)
    } catch (e) { toast((e as Error).message, 'error') }
    setConfirm(null)
  }
  const saveEdit = async () => {
    try { setD(await api(`/layers/${id}`, { method: 'PATCH', body: edit })); setEdit(null); onChanged(); toast('Изменения сохранены') }
    catch (e) { toast((e as Error).message, 'error') }
  }

  if (!d) return <div className="drawer"><Spinner /></div>
  const access = d.accessLevel === 'open' ? 'открытый' : 'для служебного пользования'
  return (
    <div className="drawer-backdrop" onMouseDown={e => e.target === e.currentTarget && onClose()}>
      <div className="drawer">
        <div className="panel-head">
          <div><h2>{title(d, lang)}</h2><small className="muted">{d.themeTitle} · {d.owner}{d.appendixNo && d.appendixNo !== '—' ? ` · Приложение 1, № ${d.appendixNo}` : ''}</small></div>
          <button className="icon-btn" onClick={onClose}>✕</button>
        </div>
        <div className="row gap wrap">
          <span className={`status status-${d.status}`}>{STATUS_LABEL[d.status]}</span>
          <span className="tag">{GEOM_LABEL[d.geomType]}</span>
          <span className="tag">{d.accessLevel === 'open' ? 'Открытый' : 'ДСП'}</span>
          {d.isOperational && <span className="tag tag-red">оперативный</span>}
        </div>
        <div ref={el} className="mini-map" />
        {d.draftVersion && <p className="muted small">Предпросмотр: версия {d.draftVersion} ({d.draftCount} объектов){d.publishedVersion ? `; опубликована версия ${d.publishedVersion} (${d.publishedCount})` : ''}</p>}
        <div className="row gap wrap">
          {d.canEdit && <button className="btn" onClick={onImport}>+ Импорт данных</button>}
          {d.canEdit && d.draftVersion && d.draftVersion !== d.publishedVersion && <button className="btn btn-primary" onClick={() => setConfirm({ kind: 'publish' })}>Опубликовать</button>}
          {d.canEdit && d.publishedVersion && <button className="btn" onClick={() => setConfirm({ kind: 'unpublish' })}>Снять с публикации</button>}
          {d.canEdit && <button className="btn" onClick={() => setEdit({ titleRu: d.titleRu, titleKy: d.titleKy ?? '', accessLevel: d.accessLevel, color: d.style?.color ?? '#1971c2' })}>Свойства</button>}
        </div>
        {edit && (
          <div className="box">
            <label className="field">Наименование (рус.)<input value={edit.titleRu} onChange={e => setEdit({ ...edit, titleRu: e.target.value })} /></label>
            <label className="field">Наименование (кыр.)<input value={edit.titleKy} onChange={e => setEdit({ ...edit, titleKy: e.target.value })} /></label>
            <div className="row gap">
              <label className="field">Уровень доступа
                <select value={edit.accessLevel} onChange={e => setEdit({ ...edit, accessLevel: e.target.value })}>
                  <option value="open">Открытый</option><option value="restricted">Для служебного пользования</option>
                </select></label>
              <label className="field">Цвет<input type="color" value={edit.color} onChange={e => setEdit({ ...edit, color: e.target.value })} /></label>
            </div>
            <div className="row gap"><button className="btn" onClick={() => setEdit(null)}>Отмена</button><button className="btn btn-primary" onClick={saveEdit}>Сохранить</button></div>
          </div>
        )}
        <h4>Описание и метаданные (ISO 19115)</h4>
        <dl className="kv">
          <dt>Аннотация</dt><dd>{d.metadata?.abstract ?? d.description ?? '—'}</dd>
          <dt>Периодичность</dt><dd>{d.metadata?.updateFreq ?? '—'}</dd>
          <dt>Ответственный</dt><dd>{d.metadata?.contact ?? d.owner}</dd>
          <dt>Система координат</dt><dd>WGS-84 (EPSG:4326), отдача — EPSG:3857</dd>
        </dl>
        {d.attributes?.length > 0 && <>
          <h4>Атрибутная схема</h4>
          <table className="table compact"><thead><tr><th>Атрибут</th><th>Код</th><th>Тип</th><th>Обяз.</th></tr></thead>
            <tbody>{d.attributes.map((a: Attr) => <tr key={a.name}><td>{a.labelRu}</td><td><code>{a.name}</code></td><td>{a.type}</td><td>{a.required ? '✓' : ''}</td></tr>)}</tbody></table>
        </>}
        {d.versions && <>
          <h4>История версий</h4>
          <table className="table compact">
            <thead><tr><th>Версия</th><th>Дата</th><th>Автор</th><th>Источник</th><th>Объектов</th><th></th></tr></thead>
            <tbody>{d.versions.map((v: any) => (
              <tr key={v.version}>
                <td>v{v.version}{v.version === d.publishedVersion && <span className="tag tag-green">опубл.</span>}{v.version === d.draftVersion && v.version !== d.publishedVersion && <span className="tag">черновик</span>}</td>
                <td>{fmtDate(v.createdAt)}</td><td>{v.author ?? '—'}</td><td>{v.source}{v.sourceSrid ? ` (EPSG:${v.sourceSrid})` : ''}</td><td>{v.featureCount}</td>
                <td>{d.canEdit && v.version !== d.draftVersion && <button className="link" onClick={() => setConfirm({ kind: 'rollback', version: v.version })}>Откатить к этой версии</button>}</td>
              </tr>))}</tbody>
          </table>
        </>}
      </div>
      {confirm && <Confirm
        title={confirm.kind === 'publish' ? 'Публикация слоя' : confirm.kind === 'unpublish' ? 'Снятие с публикации' : 'Откат версии'}
        text={confirm.kind === 'publish' ? `Опубликовать слой «${d.titleRu}»? Слой станет доступен пользователям с уровнем доступа: ${access}.`
          : confirm.kind === 'unpublish' ? `Снять слой «${d.titleRu}» с публикации? Он исчезнет с публичной карты.`
          : `Сделать версию ${confirm.version} текущей${d.publishedVersion ? ' и опубликованной' : ''}?`}
        okLabel={confirm.kind === 'publish' ? 'Опубликовать' : confirm.kind === 'unpublish' ? 'Снять' : 'Откатить'}
        onOk={act} onCancel={() => setConfirm(null)} />}
    </div>
  )
}

// ---------------------------------------------------------------------------- мастер импорта (БП-2, 6 шагов)

const SRS = [
  { srid: 4326, label: 'WGS-84 (EPSG:4326)' },
  { srid: 7692, label: 'Kyrg-06 / зона 1 (EPSG:7692)' },
  { srid: 7693, label: 'Kyrg-06 / зона 2 (EPSG:7693)' },
  { srid: 7694, label: 'Kyrg-06 / зона 3 (EPSG:7694)' },
  { srid: 7695, label: 'Kyrg-06 / зона 4 (EPSG:7695)' },
  { srid: 7696, label: 'Kyrg-06 / зона 5 (EPSG:7696)' },
  { srid: 32642, label: 'WGS-84 / ITRF-2005 UTM 42N (EPSG:32642)' },
  { srid: 32643, label: 'WGS-84 / ITRF-2005 UTM 43N (EPSG:32643)' },
  { srid: 32644, label: 'WGS-84 / ITRF-2005 UTM 44N (EPSG:32644)' },
]
const STEPS = ['Выбор слоя', 'Загрузка файла', 'Система координат', 'Сопоставление полей', 'Результаты проверки', 'Подтверждение']
const KEEP = '__keep__'

function ImportWizard({ layers, initialLayerId, lang, onClose, onStarted }: {
  layers: LayerInfo[]; initialLayerId?: string; lang: string; onClose: () => void; onStarted: () => void
}) {
  const [step, setStep] = useState(0)
  const [busy, setBusy] = useState<string | null>(null)
  const [layerId, setLayerId] = useState(initialLayerId ?? '')
  const [creating, setCreating] = useState(false)
  const [nl, setNl] = useState({ titleRu: '', titleKy: '', geomType: 'polygon', themeCode: 'urban', accessLevel: 'open' })
  const [themes, setThemes] = useState<{ code: string; titleRu: string }[]>([])
  const [file, setFile] = useState<File | null>(null)
  const [analysis, setAnalysis] = useState<any | null>(null)
  const [srid, setSrid] = useState<string>('')
  const [customSrid, setCustomSrid] = useState('')
  const [mapping, setMapping] = useState<Record<string, string>>({})
  const [titleField, setTitleField] = useState('')
  const [makeValid, setMakeValid] = useState(false)
  const [result, setResult] = useState<any | null>(null)
  const [drag, setDrag] = useState(false)

  useEffect(() => { api('/catalog/themes').then(setThemes) }, [])
  const layer = layers.find(l => l.id === layerId)

  const next = async () => {
    try {
      if (step === 0) {
        if (creating) {
          if (!nl.titleRu.trim()) { toast('Укажите наименование слоя', 'warn'); return }
          setBusy('Создание слоя...')
          const l = await api('/layers', { body: nl })
          layers.push(l); setLayerId(l.id); setCreating(false)
        } else if (!layerId) { toast('Выберите целевой слой', 'warn'); return }
        setStep(1)
      } else if (step === 1) {
        if (!file) { toast('Выберите файл', 'warn'); return }
        setBusy('Анализ файла...')
        const fd = new FormData(); fd.append('file', file); fd.append('layerId', layerId)
        const a = await api('/imports', { form: fd })
        setAnalysis(a)
        setSrid(a.detectedSrid ? String(a.detectedSrid) : '')
        const m: Record<string, string> = {}
        Object.entries(a.suggestedMapping as Record<string, string | null>).forEach(([k, v]) => { m[k] = v ?? '' })
        if (!a.targetAttributes.length) Object.keys(m).forEach(k => { m[k] = KEEP })
        setMapping(m); setTitleField(a.suggestedTitleField ?? '')
        setStep(2)
      } else if (step === 2) {
        const s = srid === 'other' ? customSrid : srid
        if (!s || isNaN(Number(s))) { toast('Укажите исходную систему координат', 'warn'); return }
        setStep(3)
      } else if (step === 3) {
        setBusy('Выполняется проверка данных...')
        const s = Number(srid === 'other' ? customSrid : srid)
        const mp: Record<string, string | null> = {}
        Object.entries(mapping).forEach(([k, v]) => { mp[k] = v === KEEP ? k : (v || null) })
        const r = await api(`/imports/${analysis.jobId}/validate`, { body: { sourceSrid: s, mapping: mp, titleField: titleField || null, makeValid } })
        setResult(r); setStep(4)
      } else if (step === 4) {
        if (result.blocking) return
        setStep(5)
      } else if (step === 5) {
        setBusy('Запуск импорта...')
        await api(`/imports/${analysis.jobId}/commit`, { method: 'POST' })
        toast('Импорт запущен', 'ok')
        onStarted()
      }
    } catch (e) { toast((e as Error).message, 'error') } finally { setBusy(null) }
  }

  const back = () => setStep(s => step === 4 ? 1 : Math.max(0, s - 1))
  const srsLabel = (s: number) => SRS.find(x => x.srid === s)?.label ?? `EPSG:${s}`

  return (
    <Modal wide title="Импорт данных" onClose={onClose}
      footer={<>
        {step > 0 && <button className="btn" disabled={!!busy} onClick={back}>{step === 4 ? 'Назад к загрузке файла' : 'Назад'}</button>}
        <span className="grow" />
        <button className="btn" onClick={onClose}>Отмена</button>
        <button className="btn btn-primary" disabled={!!busy || (step === 4 && result?.blocking)} onClick={next}>{step === 5 ? 'Импортировать' : 'Далее'}</button>
      </>}>
      <ol className="steps">{STEPS.map((s, i) => <li key={s} className={i === step ? 'on' : i < step ? 'done' : ''}><span>{i + 1}</span>{s}</li>)}</ol>
      {busy ? <Spinner text={busy} /> : (
        <div className="step-body">
          {step === 0 && (!creating ? (
            <>
              <label className="field">Целевой слой
                <select value={layerId} onChange={e => setLayerId(e.target.value)}>
                  <option value="">— выберите слой организации —</option>
                  {layers.map(l => <option key={l.id} value={l.id}>{title(l, lang)} · {GEOM_LABEL[l.geomType]}</option>)}
                </select>
              </label>
              <button className="link" onClick={() => setCreating(true)}>Создать новый слой</button>
            </>
          ) : (
            <div className="grid2">
              <label className="field">Наименование (рус.)<b className="req">*</b><input value={nl.titleRu} onChange={e => setNl({ ...nl, titleRu: e.target.value })} /></label>
              <label className="field">Наименование (кыр.)<input value={nl.titleKy} onChange={e => setNl({ ...nl, titleKy: e.target.value })} /></label>
              <label className="field">Тип геометрии
                <select value={nl.geomType} onChange={e => setNl({ ...nl, geomType: e.target.value })}>
                  <option value="point">Точка</option><option value="line">Линия</option><option value="polygon">Полигон</option><option value="mixed">Смешанный</option>
                </select></label>
              <label className="field">Тема
                <select value={nl.themeCode} onChange={e => setNl({ ...nl, themeCode: e.target.value })}>{themes.map(t => <option key={t.code} value={t.code}>{t.titleRu}</option>)}</select></label>
              <div className="field">Уровень доступа
                <div className="seg">
                  <button className={nl.accessLevel === 'open' ? 'on' : ''} onClick={() => setNl({ ...nl, accessLevel: 'open' })}>Открытый</button>
                  <button className={nl.accessLevel === 'restricted' ? 'on' : ''} onClick={() => setNl({ ...nl, accessLevel: 'restricted' })}>Для служебного пользования</button>
                </div></div>
              <div><button className="link" onClick={() => setCreating(false)}>← Выбрать существующий слой</button></div>
            </div>
          ))}

          {step === 1 && (
            <div className={`dropzone ${drag ? 'drag' : ''}`} onDragOver={e => { e.preventDefault(); setDrag(true) }} onDragLeave={() => setDrag(false)}
              onDrop={e => { e.preventDefault(); setDrag(false); if (e.dataTransfer.files[0]) setFile(e.dataTransfer.files[0]) }}>
              {file ? <div className="file-chip"><span className="ext">{file.name.split('.').pop()?.toUpperCase()}</span><span>{file.name}</span><small>{(file.size / 1024).toFixed(1)} КБ</small></div>
                : <p>Перетащите файл сюда</p>}
              <label className="btn">Выбрать файл<input type="file" hidden accept=".zip,.geojson,.json,.gpkg,.kml,.kmz,.csv,.tab,.mif,.gml"
                onChange={e => e.target.files?.[0] && setFile(e.target.files[0])} /></label>
              <p className="muted small">Shapefile (ZIP), GeoPackage, GeoJSON, KML/KMZ, MapInfo (TAB/MIF в ZIP), File Geodatabase (ZIP), CSV с координатами</p>
            </div>
          )}

          {step === 2 && analysis && (
            <>
              <p>Файл <b>{analysis.fileName}</b>: слой «{analysis.ogrLayer}», объектов: <b>{analysis.featureCount}</b>, геометрия: {analysis.geometryType ?? '—'}</p>
              <label className="field">Исходная система координат
                <select className={!srid ? 'required-empty' : ''} value={srid} onChange={e => setSrid(e.target.value)}>
                  <option value="">— не определена —</option>
                  {analysis.detectedSrid && !SRS.some(s => s.srid === analysis.detectedSrid) && <option value={analysis.detectedSrid}>{analysis.detectedSrsName} (EPSG:{analysis.detectedSrid})</option>}
                  {SRS.map(s => <option key={s.srid} value={s.srid}>{s.label}</option>)}
                  <option value="other">Иная (код EPSG)…</option>
                </select>
              </label>
              {analysis.detectedSrid && String(analysis.detectedSrid) === srid && <p className="ok-text">✓ определено автоматически: {analysis.detectedSrsName ?? srsLabel(analysis.detectedSrid)}</p>}
              {!analysis.detectedSrid && <p className="warn-text">Система координат не определена по файлу — укажите её вручную.</p>}
              {srid === 'other' && <label className="field">Код EPSG<input value={customSrid} onChange={e => setCustomSrid(e.target.value)} placeholder="например, 7694" /></label>}
              <p className="muted small">Данные будут трансформированы в WGS-84 (EPSG:4326) для хранения.</p>
            </>
          )}

          {step === 3 && analysis && (
            <>
              <table className="table compact">
                <thead><tr><th>Поле источника</th><th>Тип</th><th>Атрибут слоя</th></tr></thead>
                <tbody>{analysis.fields.map((f: any) => (
                  <tr key={f.name}><td><code>{f.name}</code></td><td>{f.type}</td><td>
                    <select value={mapping[f.name] ?? ''} onChange={e => setMapping({ ...mapping, [f.name]: e.target.value })}>
                      <option value="">Не переносить</option>
                      <option value={KEEP}>Сохранить как «{f.name}»</option>
                      {analysis.targetAttributes.map((a: Attr) => <option key={a.name} value={a.name}>{a.labelRu}{a.required ? ' *' : ''}</option>)}
                    </select></td></tr>))}</tbody>
              </table>
              {analysis.targetAttributes.some((a: Attr) => a.required) && <p className="muted small">* — обязательные атрибуты слоя: {analysis.targetAttributes.filter((a: Attr) => a.required).map((a: Attr) => a.labelRu).join(', ')}</p>}
              <div className="row gap wrap">
                <label className="field">Поле наименования объекта (для поиска и карточки)
                  <select value={titleField} onChange={e => setTitleField(e.target.value)}>
                    <option value="">— по атрибутной схеме слоя —</option>
                    {analysis.fields.map((f: any) => <option key={f.name} value={f.name}>{f.name}</option>)}
                  </select></label>
                <label className="check"><input type="checkbox" checked={makeValid} onChange={e => setMakeValid(e.target.checked)} /> Исправлять невалидную геометрию автоматически (ST_MakeValid)</label>
              </div>
            </>
          )}

          {step === 4 && result && (
            <>
              {result.blocking ? (
                <div className="alert alert-error">
                  <b>Проверка не пройдена.</b> Объектов: {result.total}, без ошибок: {result.valid}. Исправьте источник и загрузите файл повторно.
                  {result.globalErrors.map((g: string) => <div key={g}>• {g}</div>)}
                </div>
              ) : <div className="alert alert-ok">Проверка пройдена успешно: <b>{result.total}</b> объектов готовы к импорту.</div>}
              {(result.errors.length > 0 || result.warnings.length > 0) && (
                <div className="err-list">
                  {result.errors.slice(0, 200).map((e: any, i: number) => <div key={'e' + i} className="err">Объект №{e.fid}: {e.message}</div>)}
                  {result.warnings.slice(0, 100).map((e: any, i: number) => <div key={'w' + i} className="warn-text">Объект №{e.fid}: {e.message}</div>)}
                </div>
              )}
              <div className="row gap">
                <button className="btn sm" onClick={() => download(`/imports/${analysis.jobId}/errors.csv`, 'import-errors.csv').catch(e => toast(e.message, 'error'))}>Скачать отчёт об ошибках (CSV)</button>
              </div>
              <PreviewMap preview={result.preview} errorFids={result.errorFids} bbox={result.bbox} others={layers.filter(l => l.id !== layerId && (l.publishedVersion || l.draftVersion))} />
            </>
          )}

          {step === 5 && result && layer && (
            <dl className="kv">
              <dt>Слой</dt><dd>{title(layer, lang)}</dd>
              <dt>Файл</dt><dd>{analysis.fileName}</dd>
              <dt>Число объектов</dt><dd>{result.total}</dd>
              <dt>Исходная СК</dt><dd>{srsLabel(Number(srid === 'other' ? customSrid : srid))}</dd>
              <dt>Дата</dt><dd>{new Date().toLocaleString('ru')}</dd>
              <dt>Результат</dt><dd>Новая версия слоя (черновик). Публикация — отдельным действием «Опубликовать»{layer.isOperational ? '; слой оперативный — будет опубликован сразу' : ''}.</dd>
            </dl>
          )}
        </div>
      )}
    </Modal>
  )
}

/** Карта предпросмотра загружаемых объектов поверх существующих слоёв организации (шаг 5). */
function PreviewMap({ preview, errorFids, bbox, others }: { preview: any; errorFids: number[]; bbox: number[] | null; others: LayerInfo[] }) {
  const el = useRef<HTMLDivElement>(null)
  const mapRef = useRef<MlMap | null>(null)
  const [on, setOn] = useState<Record<string, boolean>>({})
  const [ready, setReady] = useState(false)
  useEffect(() => {
    const map = newMap(el.current!)
    mapRef.current = map
    map.on('load', () => {
      const bad = new Set(errorFids)
      const fc = { ...preview, features: preview.features.map((f: any) => ({ ...f, properties: { ...f.properties, bad: bad.has(f.properties.fid) } })) }
      map.addSource('pv', { type: 'geojson', data: fc })
      const c: any = ['case', ['get', 'bad'], '#e03131', '#0b5cad']
      map.addLayer({ id: 'pv-f', type: 'fill', source: 'pv', filter: ['==', ['geometry-type'], 'Polygon'], paint: { 'fill-color': c, 'fill-opacity': 0.35 } })
      map.addLayer({ id: 'pv-l', type: 'line', source: 'pv', filter: ['!=', ['geometry-type'], 'Point'], paint: { 'line-color': c, 'line-width': 2 } })
      map.addLayer({ id: 'pv-p', type: 'circle', source: 'pv', filter: ['==', ['geometry-type'], 'Point'], paint: { 'circle-color': c, 'circle-radius': 6, 'circle-stroke-color': '#fff', 'circle-stroke-width': 1.5 } })
      if (bbox) fitBbox(map, bbox, 15)
      setHighlight(map, null)
      setReady(true)
    })
    return () => map.remove()
  }, [])
  useEffect(() => {
    const map = mapRef.current
    if (!map || !ready) return
    others.forEach(l => {
      const draft = !!l.draftVersion && l.draftVersion !== l.publishedVersion
      if (on[l.id]) addGeoLayer(map, l, { draft, before: 'pv-f', opacity: 0.2 })
      else { ['fill', 'line', 'circle'].forEach(s => map.getLayer(`L-${l.id}-${s}`) && map.removeLayer(`L-${l.id}-${s}`)); const src = `S-${l.id}${draft ? '-d' : ''}`; map.getSource(src) && map.removeSource(src) }
    })
  }, [on, ready])
  return (
    <div className="preview">
      <div ref={el} className="preview-map" />
      <div className="preview-side">
        <b className="small">Существующие слои</b>
        {others.length === 0 && <p className="muted small">Нет других слоёв организации</p>}
        {others.map(l => <label key={l.id} className="check small"><input type="checkbox" checked={!!on[l.id]} onChange={e => setOn({ ...on, [l.id]: e.target.checked })} />{l.titleRu}</label>)}
        <p className="small"><span className="dot blue" /> загружаемые объекты<br /><span className="dot red" /> объекты с ошибками</p>
      </div>
    </div>
  )
}
