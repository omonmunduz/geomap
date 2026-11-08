import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { Map as MlMap, GeoJSONSource } from 'maplibre-gl'
import { api, download } from '../lib/api'
import { toast } from '../lib/toast'
import type { LayerInfo } from '../lib/types'
import { addGeoLayer, fitBbox, newMap, setHighlight } from '../lib/mapStyle'
import { Spinner } from '../components/Modal'

const TYPE_COLORS: Record<string, string> = { USE_NOT_ALLOWED: '#e03131', NO_ZONE: '#f08c00', CROSSES_ZONES: '#7048e8' }
const fmt = (s?: string) => s ? new Date(s).toLocaleString('ru', { dateStyle: 'short', timeStyle: 'short' }) : '—'

/** Якорный сервис «Цифровое территориальное управление» — контроль зонирования (п. 3.7.1, БП-4). */
export function ZoningPage() {
  const el = useRef<HTMLDivElement>(null)
  const mapRef = useRef<MlMap | null>(null)
  const [ready, setReady] = useState(false)
  const [cfg, setCfg] = useState<any | null>(null)
  const [territories, setTerritories] = useState<any[]>([])
  const [territory, setTerritory] = useState<string>('')
  const [filters, setFilters] = useState({ type: '', status: '', present: 'true', from: '', to: '' })
  const [items, setItems] = useState<any[] | null>(null)
  const [selected, setSelected] = useState<any | null>(null)
  const [running, setRunning] = useState(false)
  const [lastRun, setLastRun] = useState<any | null>(null)

  useEffect(() => {
    Promise.all([api('/zoning/config'), api('/zoning/territories'), api('/zoning/checks')]).then(([c, t, ch]) => {
      setCfg(c); setTerritories(t)
      const bishkek = t.find((x: any) => /^Бишкек/.test(x.title ?? ''))
      setTerritory(bishkek?.id ?? '')
      if (ch[0]) setLastRun({ ...ch[0].stats, territoryName: ch[0].territoryName, runAt: ch[0].runAt, runBy: ch[0].runBy })
    }).catch(e => toast(e.message, 'error'))
  }, [])

  useEffect(() => {
    const map = newMap(el.current!)
    mapRef.current = map
    map.on('load', () => { setReady(true) })
    map.on('click', 'disc-fill', e => { const id = e.features?.[0]?.properties?.id; if (id) select(String(id)) })
    map.on('click', 'disc-line', e => { const id = e.features?.[0]?.properties?.id; if (id) select(String(id)) })
    map.on('mouseenter', 'disc-fill', () => { map.getCanvas().style.cursor = 'pointer' })
    map.on('mouseleave', 'disc-fill', () => { map.getCanvas().style.cursor = '' })
    return () => map.remove()
  }, [])

  // слои зонирования и текущего освоения + слой расхождений
  useEffect(() => {
    const map = mapRef.current
    if (!map || !ready || !cfg) return
    api<LayerInfo>(`/catalog/layers/${cfg.zoningLayer.id}`).then(z => addGeoLayer(map, z, { opacity: 0.3 }))
      .then(() => api<LayerInfo>(`/catalog/layers/${cfg.devLayer.id}`)).then(d => {
        addGeoLayer(map, d, { opacity: 0.75 })
        map.addSource('disc', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } })
        const color: any = ['match', ['get', 'type'], ...Object.entries(TYPE_COLORS).flat(), '#e03131']
        map.addLayer({ id: 'disc-fill', type: 'fill', source: 'disc', paint: { 'fill-color': color, 'fill-opacity': 0.55 } })
        map.addLayer({ id: 'disc-line', type: 'line', source: 'disc', paint: { 'line-color': color, 'line-width': ['interpolate', ['linear'], ['zoom'], 11, 2, 16, 4] } })
        map.addLayer({ id: 'disc-pt', type: 'circle', source: 'disc', maxzoom: 13, paint: { 'circle-color': color, 'circle-radius': 4, 'circle-stroke-color': '#fff', 'circle-stroke-width': 1 } })
        setHighlight(map, null)
        load()
      })
  }, [ready, cfg])

  const query = useCallback(() => {
    const p = new URLSearchParams()
    if (territory) p.set('territoryId', territory)
    Object.entries(filters).forEach(([k, v]) => v && p.set(k, v))
    return p
  }, [territory, filters])

  const load = useCallback(async () => {
    try {
      const r = await api<any[]>(`/zoning/discrepancies?${query()}`)
      setItems(r)
      const map = mapRef.current
      const src = map?.getSource('disc') as GeoJSONSource | undefined
      src?.setData({ type: 'FeatureCollection', features: r.map(d => ({ type: 'Feature', properties: { id: d.id, type: d.type }, geometry: d.geometry })) })
    } catch (e) { toast((e as Error).message, 'error') }
  }, [query])
  useEffect(() => { if (ready && mapRef.current?.getSource('disc')) load() }, [load, ready])

  useEffect(() => {
    const t = territories.find(x => x.id === territory)
    if (t && mapRef.current) fitBbox(mapRef.current, t.bbox, 13)
  }, [territory, territories])

  const run = async () => {
    setRunning(true)
    try {
      const r = await api('/zoning/checks', { body: { territoryId: territory || null } })
      setLastRun({ ...r, runAt: new Date().toISOString() })
      toast(`Проверка завершена: объектов ${r.objectsChecked}, расхождений ${r.discrepancies} (новых ${r.newDiscrepancies})`)
      await load()
    } catch (e) { toast((e as Error).message, 'error') } finally { setRunning(false) }
  }

  const select = async (id: string) => {
    try {
      const d = await api(`/zoning/discrepancies/${id}`)
      setSelected(d)
      const map = mapRef.current!
      setHighlight(map, { type: 'FeatureCollection', features: [
        { type: 'Feature', properties: {}, geometry: d.geometry },
        ...(d.zoneGeometry ? [{ type: 'Feature' as const, properties: {}, geometry: d.zoneGeometry }] : []),
      ] }, 'hl', '#0b5cad')
      map.flyTo({ center: [d.lon, d.lat], zoom: Math.max(map.getZoom(), 15) })
    } catch (e) { toast((e as Error).message, 'error') }
  }

  const report = (format: 'xlsx' | 'pdf') => {
    const p = query(); p.delete('present'); p.set('format', format)
    download(`/zoning/report?${p}`, `zoning-report.${format}`).then(() => toast('Отчёт сформирован')).catch(e => toast(e.message, 'error'))
  }

  const counts = useMemo(() => {
    const c: Record<string, number> = {}
    items?.forEach(i => { c[i.type] = (c[i.type] ?? 0) + 1 })
    return c
  }, [items])
  const L = (m: Record<string, string> | undefined, k?: string) => (k && m?.[k]) ?? k ?? '—'
  const zl = useMemo(() => Object.fromEntries((cfg?.zoneTypes ?? []).map((o: any) => [o.value, o.label])), [cfg])
  const ul = useMemo(() => Object.fromEntries((cfg?.useTypes ?? []).map((o: any) => [o.value, o.label])), [cfg])

  return (
    <div className="zoning-page">
      <aside className="side wide">
        <h3>Контроль зонирования</h3>
        <p className="muted small">Пересечение слоя «{cfg?.devLayer.title}» (v{cfg?.devLayer.version}) со слоем «{cfg?.zoningLayer.title}» (v{cfg?.zoningLayer.version}) и правилами разрешённого использования.</p>
        <label className="field">Территория
          <select value={territory} onChange={e => setTerritory(e.target.value)}>
            <option value="">Вся территория слоя зонирования</option>
            <optgroup label="Области и города респ. значения">{territories.filter(t => t.level === 'atd_oblasts').map(t => <option key={t.id} value={t.id}>{t.title}</option>)}</optgroup>
            <optgroup label="Районы и города">{territories.filter(t => t.level === 'atd_districts').map(t => <option key={t.id} value={t.id}>{t.title}</option>)}</optgroup>
          </select>
        </label>
        <button className="btn btn-primary block" disabled={running} onClick={run}>{running ? 'Выполняется проверка…' : 'Запустить проверку'}</button>
        {lastRun && (
          <div className="box small">
            <b>Последняя проверка:</b> {lastRun.territoryName}<br />
            {fmt(lastRun.runAt)}{lastRun.runBy ? ` · ${lastRun.runBy}` : ''}<br />
            Проверено объектов: {lastRun.objectsChecked}; расхождений: {lastRun.discrepancies}{lastRun.newDiscrepancies != null ? ` (новых: ${lastRun.newDiscrepancies})` : ''}
          </div>
        )}
        <h4>Фильтры реестра</h4>
        <div className="grid2">
          <label className="field">Тип
            <select value={filters.type} onChange={e => setFilters({ ...filters, type: e.target.value })}>
              <option value="">Все</option>{cfg && Object.entries(cfg.typeLabels).map(([k, v]) => <option key={k} value={k}>{v as string}</option>)}
            </select></label>
          <label className="field">Статус
            <select value={filters.status} onChange={e => setFilters({ ...filters, status: e.target.value })}>
              <option value="">Все</option>{cfg && Object.entries(cfg.statusLabels).map(([k, v]) => <option key={k} value={k}>{v as string}</option>)}
            </select></label>
          <label className="field">Выявлено с<input type="date" value={filters.from} onChange={e => setFilters({ ...filters, from: e.target.value })} /></label>
          <label className="field">по<input type="date" value={filters.to} onChange={e => setFilters({ ...filters, to: e.target.value })} /></label>
        </div>
        <label className="check small"><input type="checkbox" checked={filters.present === 'true'} onChange={e => setFilters({ ...filters, present: e.target.checked ? 'true' : '' })} /> Только обнаруженные при последней проверке</label>
        <div className="row gap">
          <button className="btn sm" onClick={() => report('xlsx')}>Отчёт XLSX</button>
          <button className="btn sm" onClick={() => report('pdf')}>Отчёт PDF</button>
        </div>
        <div className="legend-row">
          {cfg && Object.entries(cfg.typeLabels).map(([k, v]) => <span key={k}><i style={{ background: TYPE_COLORS[k] }} />{v as string}: <b>{counts[k] ?? 0}</b></span>)}
        </div>
        <h4>Реестр расхождений {items && <span className="muted">({items.length})</span>}</h4>
        {!items ? <Spinner /> : items.length === 0 ? <p className="muted">Расхождений не найдено. Запустите проверку.</p> : (
          <ul className="disc-list">
            {items.map(d => (
              <li key={d.id} className={selected?.id === d.id ? 'on' : ''} onClick={() => select(d.id)}>
                <i style={{ background: TYPE_COLORS[d.type] }} />
                <div><strong>{d.objectTitle}</strong> <span className={`st st-${d.status}`}>{d.statusLabel}</span>
                  <small>{d.typeLabel} · {L(ul, d.useType)} → {L(zl, d.zoneType)}</small></div>
              </li>
            ))}
          </ul>
        )}
      </aside>
      <section className="map-wrap"><div ref={el} className="map" /></section>
      {selected && <DiscrepancyCard d={selected} cfg={cfg} zl={zl} ul={ul} onClose={() => { setSelected(null); setHighlight(mapRef.current!, null) }}
        onChanged={async () => { await select(selected.id); await load() }} />}
    </div>
  )
}

function DiscrepancyCard({ d, cfg, zl, ul, onClose, onChanged }: { d: any; cfg: any; zl: Record<string, string>; ul: Record<string, string>; onClose: () => void; onChanged: () => void }) {
  const [status, setStatus] = useState(d.status)
  const [comment, setComment] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [busy, setBusy] = useState(false)
  useEffect(() => { setStatus(d.status); setComment(''); setFile(null) }, [d.id, d.status])

  const save = async () => {
    setBusy(true)
    try {
      const fd = new FormData()
      fd.append('status', status); if (comment) fd.append('comment', comment); if (file) fd.append('file', file)
      await api(`/zoning/discrepancies/${d.id}/status`, { form: fd })
      toast('Статус расхождения сохранён'); onChanged()
    } catch (e) { toast((e as Error).message, 'error') } finally { setBusy(false) }
  }
  const attrRows = (a: { label: string; value: any }[] | null) => a ? a.map(x => <tr key={x.label}><th>{x.label}</th><td>{String(x.value)}</td></tr>) : null

  return (
    <aside className="panel-right">
      <div className="card">
        <div className="panel-head"><div><h3>Расхождение: {d.objectTitle}</h3><small className="muted">{d.typeLabel}</small></div><button className="icon-btn" onClick={onClose}>✕</button></div>
        <div className={`alert ${d.type === 'USE_NOT_ALLOWED' ? 'alert-error' : 'alert-warn'}`}>{d.details}</div>
        {!d.stillPresent && <div className="alert alert-ok small">Не обнаружено при последней проверке — возможно, устранено.</div>}
        <dl className="kv">
          <dt>Статус</dt><dd><span className={`st st-${d.status}`}>{d.statusLabel}</span></dd>
          <dt>Территория</dt><dd>{d.territoryName ?? '—'}</dd>
          <dt>Ответственный орган</dt><dd>{d.responsibleOrg ?? '—'}</dd>
          <dt>Выявлено</dt><dd>{fmt(d.createdAt)}</dd>
          <dt>Координаты</dt><dd>{d.lat.toFixed(5)}, {d.lon.toFixed(5)}</dd>
        </dl>
        <h4>Объект текущего освоения</h4>
        <table className="attrs"><tbody>{attrRows(d.devAttrs)}</tbody></table>
        {d.zoneAttrs && <><h4>Утверждённая зона</h4><table className="attrs"><tbody>{attrRows(d.zoneAttrs)}</tbody></table></>}
        <h4>Рассмотрение</h4>
        <div className="box">
          <label className="field">Новый статус
            <select value={status} onChange={e => setStatus(e.target.value)}>{Object.entries(cfg.statusLabels).map(([k, v]) => <option key={k} value={k}>{v as string}</option>)}</select></label>
          <label className="field">Комментарий{['resolved', 'dismissed'].includes(status) && <b className="req">*</b>}
            <textarea rows={2} value={comment} onChange={e => setComment(e.target.value)} placeholder="Основание решения, реквизиты акта…" /></label>
          <label className="field">Акт / документ<input type="file" onChange={e => setFile(e.target.files?.[0] ?? null)} /></label>
          <button className="btn btn-primary" disabled={busy} onClick={save}>Сохранить</button>
        </div>
        <h4>История статусов</h4>
        <ul className="timeline">
          {d.history.map((h: any) => (
            <li key={h.id}>
              <b>{h.fromStatus ? `${cfg.statusLabels[h.fromStatus]} → ` : ''}{cfg.statusLabels[h.toStatus]}</b>
              <small>{fmt(h.at)} · {h.byName}</small>
              {h.comment && <div>{h.comment}</div>}
              {h.attachmentName && <button className="link" onClick={() => download(`/zoning/discrepancies/${d.id}/attachments/${h.id}`, h.attachmentName).catch(e => toast(e.message, 'error'))}>📎 {h.attachmentName}</button>}
            </li>
          ))}
        </ul>
      </div>
    </aside>
  )
}
