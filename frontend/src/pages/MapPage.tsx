import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { Map as MlMap, MapMouseEvent } from 'maplibre-gl'
import MapboxDraw from '@mapbox/mapbox-gl-draw'
import { api, saveBlob } from '../lib/api'
import { useAuth } from '../lib/auth'
import { toast } from '../lib/toast'
import type { Attr, LayerInfo, SearchItem, Theme } from '../lib/types'
import { addGeoLayer, fitBbox, layerIds, newMap, removeGeoLayer, setBasemap, setHighlight, GEOM_LABEL, type Basemap } from '../lib/mapStyle'
import { AttrForm } from '../components/AttrForm'
import { Confirm, Spinner } from '../components/Modal'

// mapbox-gl-draw работает с MapLibre при замене CSS-классов
const dc = (MapboxDraw as any).constants.classes
dc.CANVAS = 'maplibregl-canvas'; dc.CONTROL_BASE = 'maplibregl-ctrl'; dc.CONTROL_PREFIX = 'maplibregl-ctrl-'
dc.CONTROL_GROUP = 'maplibregl-ctrl-group'; dc.ATTRIBUTION = 'maplibregl-ctrl-attrib'

/** Стили редактора без data-driven line-dasharray (не поддерживается MapLibre). */
const DRAW_STYLES: any[] = [
  { id: 'gl-draw-polygon-fill', type: 'fill', filter: ['all', ['==', '$type', 'Polygon']],
    paint: { 'fill-color': ['case', ['==', ['get', 'active'], 'true'], '#e8590c', '#0b5cad'], 'fill-opacity': 0.15 } },
  { id: 'gl-draw-lines', type: 'line', filter: ['any', ['==', '$type', 'LineString'], ['==', '$type', 'Polygon']],
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: { 'line-color': ['case', ['==', ['get', 'active'], 'true'], '#e8590c', '#0b5cad'], 'line-width': 2.5 } },
  { id: 'gl-draw-point-outer', type: 'circle', filter: ['all', ['==', '$type', 'Point'], ['==', 'meta', 'feature']],
    paint: { 'circle-radius': ['case', ['==', ['get', 'active'], 'true'], 8, 6], 'circle-color': '#fff' } },
  { id: 'gl-draw-point-inner', type: 'circle', filter: ['all', ['==', '$type', 'Point'], ['==', 'meta', 'feature']],
    paint: { 'circle-radius': ['case', ['==', ['get', 'active'], 'true'], 6, 4], 'circle-color': ['case', ['==', ['get', 'active'], 'true'], '#e8590c', '#0b5cad'] } },
  { id: 'gl-draw-vertex-outer', type: 'circle', filter: ['all', ['==', '$type', 'Point'], ['==', 'meta', 'vertex'], ['!=', 'mode', 'simple_select']],
    paint: { 'circle-radius': ['case', ['==', ['get', 'active'], 'true'], 7, 5], 'circle-color': '#fff' } },
  { id: 'gl-draw-vertex-inner', type: 'circle', filter: ['all', ['==', '$type', 'Point'], ['==', 'meta', 'vertex'], ['!=', 'mode', 'simple_select']],
    paint: { 'circle-radius': ['case', ['==', ['get', 'active'], 'true'], 5, 3], 'circle-color': '#e8590c' } },
  { id: 'gl-draw-midpoint', type: 'circle', filter: ['all', ['==', 'meta', 'midpoint']], paint: { 'circle-radius': 3, 'circle-color': '#e8590c' } },
]

const DEFAULT_VISIBLE = ['atd_oblasts', 'settlements', 'roads', 'hydrography']
const title = (l: { titleRu: string; titleKy?: string }, lang: string) => (lang === 'ky' && l.titleKy) ? l.titleKy : l.titleRu

export function MapPage({ lang }: { lang: 'ru' | 'ky' }) {
  const { user } = useAuth()
  const el = useRef<HTMLDivElement>(null)
  const mapRef = useRef<MlMap | null>(null)
  const drawRef = useRef<MapboxDraw | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [tree, setTree] = useState<Theme[]>([])
  const [open, setOpen] = useState<Record<string, boolean>>({ base: true })
  const [visible, setVisible] = useState<Set<string>>(new Set())
  const [basemap, setBm] = useState<Basemap>('osm')
  const [card, setCard] = useState<any | null>(null)
  const [results, setResults] = useState<{ title: string; items: SearchItem[]; total: number; page: number; fetchPage: (p: number) => Promise<void> } | null>(null)
  const [tool, setTool] = useState<'none' | 'area' | 'edit'>('none')
  const toolRef = useRef(tool); toolRef.current = tool

  const allLayers = useMemo(() => tree.flatMap(t => t.layers), [tree])
  const byCode = useMemo(() => Object.fromEntries(allLayers.map(l => [l.code, l])), [allLayers])

  const loadTree = useCallback(async () => {
    const t = await api<Theme[]>('/catalog/tree')
    setTree(t)
    setVisible(v => {
      if (v.size) return new Set([...v].filter(id => t.some(th => th.layers.some(l => l.id === id))))
      const init = t.flatMap(th => th.layers).filter(l => DEFAULT_VISIBLE.includes(l.code)).map(l => l.id)
      return new Set(init)
    })
  }, [])

  // карта
  useEffect(() => {
    const map = newMap(el.current!)
    mapRef.current = map
    map.on('load', () => { setHighlight(map, null); setLoaded(true) })
    map.on('click', (e: MapMouseEvent) => {
      if (toolRef.current !== 'none') return
      const ids = map.getStyle().layers.map(l => l.id).filter(id => id.startsWith('L-'))
      const f = map.queryRenderedFeatures(e.point, { layers: ids })[0]
      if (f?.properties?.id) openFeature(String(f.properties.id))
    })
    map.on('mousemove', e => {
      if (toolRef.current !== 'none') return
      const ids = map.getStyle().layers.map(l => l.id).filter(id => id.startsWith('L-'))
      map.getCanvas().style.cursor = map.queryRenderedFeatures(e.point, { layers: ids }).length ? 'pointer' : ''
    })
    return () => { map.remove(); mapRef.current = null }
  }, [])

  useEffect(() => { loadTree().catch(e => toast(e.message, 'error')) }, [user, loadTree])

  // синхронизация видимых слоёв с картой (порядок — как в дереве: базовые снизу)
  useEffect(() => {
    const map = mapRef.current
    if (!map || !loaded) return
    allLayers.forEach(l => { removeGeoLayer(map, l.id, false); removeGeoLayer(map, l.id, true) })
    allLayers.filter(l => visible.has(l.id)).forEach(l => {
      const draft = l.canEdit && (l.status === 'draft' || l.status === 'published_with_draft')
      addGeoLayer(map, l, { draft, before: 'hl-fill' })
    })
  }, [allLayers, visible, loaded])

  useEffect(() => { if (mapRef.current && loaded) setBasemap(mapRef.current, basemap) }, [basemap, loaded])

  const openFeature = async (id: string, zoom = false) => {
    try {
      const f = await api(`/features/${id}`)
      setCard(f)
      if (mapRef.current) {
        setHighlight(mapRef.current, { type: 'Feature', geometry: f.geometry, properties: {} })
        if (zoom) fitBbox(mapRef.current, f.bbox, 15)
      }
    } catch (e) { toast((e as Error).message, 'error') }
  }

  const toggle = (l: LayerInfo) => setVisible(v => { const n = new Set(v); n.has(l.id) ? n.delete(l.id) : n.add(l.id); return n })

  const zoomLayer = async (l: LayerInfo) => {
    const d = await api(`/catalog/layers/${l.id}`)
    if (d.bbox) fitBbox(mapRef.current!, d.bbox, 13)
    if (!visible.has(l.id)) toggle(l)
  }

  // ---------- поиск в области (прямоугольник)
  useEffect(() => {
    const map = mapRef.current
    if (!map || !loaded) return
    if (tool !== 'area') { map.dragPan.enable(); return }
    map.dragPan.disable()
    map.getCanvas().style.cursor = 'crosshair'
    let start: [number, number] | null = null
    const box = (a: [number, number], b: [number, number]): GeoJSON.Polygon => ({ type: 'Polygon', coordinates: [[a, [b[0], a[1]], b, [a[0], b[1]], a]] })
    const down = (e: MapMouseEvent) => { start = [e.lngLat.lng, e.lngLat.lat] }
    const move = (e: MapMouseEvent) => { if (start) setHighlight(map, { type: 'Feature', geometry: box(start, [e.lngLat.lng, e.lngLat.lat]), properties: {} }, 'area', '#0b5cad') }
    const up = async (e: MapMouseEvent) => {
      if (!start) return
      const g = box(start, [e.lngLat.lng, e.lngLat.lat]); start = null
      setTool('none'); map.getCanvas().style.cursor = ''
      await runAreaSearch(g)
    }
    map.on('mousedown', down); map.on('mousemove', move); map.on('mouseup', up)
    return () => { map.off('mousedown', down); map.off('mousemove', move); map.off('mouseup', up); map.dragPan.enable() }
  }, [tool, loaded])

  const runAreaSearch = async (g: GeoJSON.Polygon) => {
    const layers = allLayers.filter(l => visible.has(l.id) && l.canView).map(l => l.id)
    if (!layers.length) { toast('Включите хотя бы один слой для поиска в области', 'warn'); return }
    const fetchPage = async (p: number) => {
      const r = await api<{ total: number; items: SearchItem[] }>('/search/area', { body: { geometry: g, layers, limit: 20, offset: p * 20 } })
      setResults({ title: 'Объекты в выбранной области', items: r.items, total: r.total, page: p, fetchPage })
    }
    await fetchPage(0)
    ;(async () => {
      const all = await api<{ items: SearchItem[] }>('/search/area', { body: { geometry: g, layers, limit: 500 } })
      setHighlight(mapRef.current!, { type: 'FeatureCollection', features: all.items.map(i => ({ type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [i.lon, i.lat] } })) })
    })()
  }

  const runTextSearch = async (q: string) => {
    const fetchPage = async (p: number) => {
      const r = await api<{ total: number; items: SearchItem[] }>(`/search?q=${encodeURIComponent(q)}&limit=20&offset=${p * 20}`)
      setResults({ title: `Результаты поиска «${q}»`, items: r.items, total: r.total, page: p, fetchPage })
    }
    await fetchPage(0)
  }

  const exportResults = async (fmt: 'csv' | 'geojson') => {
    if (!results) return
    const items = results.items
    if (fmt === 'csv') {
      const rows = [['Наименование', 'Слой', 'Долгота', 'Широта'], ...items.map(i => [i.title ?? '', i.layerTitle, i.lon.toFixed(6), i.lat.toFixed(6)])]
      saveBlob(new Blob(['﻿' + rows.map(r => r.map(c => `"${String(c).replace(/"/g, '""')}"`).join(';')).join('\n')], { type: 'text/csv' }), 'search-results.csv')
    } else {
      const fc = { type: 'FeatureCollection', features: items.map(i => ({ type: 'Feature', properties: { id: i.id, title: i.title, layer: i.layerTitle }, geometry: { type: 'Point', coordinates: [i.lon, i.lat] } })) }
      saveBlob(new Blob([JSON.stringify(fc)], { type: 'application/geo+json' }), 'search-results.geojson')
    }
  }

  const editable = allLayers.filter(l => l.canEdit && l.geomType !== 'raster')

  return (
    <div className="map-page">
      <aside className="side">
        <div className="side-head">
          <h3>Слои</h3>
          <label className="basemap">Подложка:&nbsp;
            <select value={basemap} onChange={e => setBm(e.target.value as Basemap)}>
              <option value="osm">OpenStreetMap</option>
              <option value="none">Без подложки</option>
            </select>
          </label>
        </div>
        <div className="tree">
          {tree.map(t => (
            <div key={t.id} className="theme">
              <button className="theme-head" onClick={() => setOpen(o => ({ ...o, [t.code]: !o[t.code] }))}>
                <span className={`tri ${open[t.code] ? 'open' : ''}`}>▶</span>
                <span>{title(t, lang)}</span>
                <span className="count">{t.layers.filter(l => visible.has(l.id)).length || ''}</span>
              </button>
              {open[t.code] && t.layers.map(l => {
                const hasData = l.publishedCount > 0 || (l.canEdit && l.draftCount > 0)
                return (
                  <div key={l.id} className={`layer-row ${hasData ? '' : 'disabled'}`} title={`${l.description ?? ''}\nВладелец: ${l.owner}${l.appendixNo ? `\nПриложение 1, № ${l.appendixNo}` : ''}`}>
                    <label>
                      <input type="checkbox" disabled={!hasData} checked={visible.has(l.id)} onChange={() => toggle(l)} />
                      <span className="swatch" style={{ background: l.style?.color }} />
                      <span className="ltitle">{title(l, lang)}</span>
                    </label>
                    <span className="badges">
                      {l.accessLevel === 'restricted' && <span className="tag tag-amber" title="Для служебного пользования">ДСП</span>}
                      {l.isOperational && <span className="tag tag-red" title="Оперативный слой">опер.</span>}
                      {!hasData && <span className="nodata">нет данных</span>}
                      {hasData && <button className="icon-btn sm" title="Приблизить к слою" onClick={() => zoomLayer(l)}>⤢</button>}
                    </span>
                    {visible.has(l.id) && l.style?.palette && (
                      <div className="legend">{Object.entries(l.style.palette).map(([k, c]) => <span key={k}><i style={{ background: c }} />{l.style.labels?.[k] ?? k}</span>)}</div>
                    )}
                  </div>
                )
              })}
            </div>
          ))}
        </div>
      </aside>

      <section className="map-wrap">
        <div ref={el} className="map" />
        <SearchBox onPick={i => { fitBbox(mapRef.current!, i.bbox, 14); openFeature(i.id) }} onSubmit={runTextSearch} />
        <div className="map-tools">
          <button className={`tool ${tool === 'area' ? 'on' : ''}`} title="Поиск в области: нарисуйте прямоугольник" onClick={() => setTool(t => t === 'area' ? 'none' : 'area')}>▭ Поиск в области</button>
          {editable.length > 0 && (
            <button className={`tool ${tool === 'edit' ? 'on' : ''}`} title="Редактор геометрии" onClick={() => setTool(t => t === 'edit' ? 'none' : 'edit')}>✎ Редактор</button>
          )}
          <button className="tool" title="Печать карты" onClick={() => window.print()}>⎙ Печать</button>
        </div>
        {tool === 'area' && <div className="hint">Зажмите левую кнопку мыши и выделите прямоугольную область</div>}
        {tool === 'edit' && mapRef.current && (
          <Editor map={mapRef.current} drawRef={drawRef} layers={editable} lang={lang} onClose={() => setTool('none')}
            onSaved={async (id, lid) => { await loadTree(); setVisible(v => new Set(v).add(lid)); openFeature(id) }} />
        )}
        {!loaded && <div className="map-loading"><Spinner text="Загрузка карты..." /></div>}
      </section>

      {(card || results) && (
        <aside className="panel-right">
          {card ? (
            <FeatureCard f={card} onClose={() => { setCard(null); setHighlight(mapRef.current!, null) }}
              onZoom={() => fitBbox(mapRef.current!, card.bbox, 16)}
              onChanged={async (id) => { await loadTree(); if (id) openFeature(id); else { setCard(null); setHighlight(mapRef.current!, null) } }}
              onEditGeometry={() => { setTool('edit'); setTimeout(() => window.dispatchEvent(new CustomEvent('gp-edit-geom', { detail: card })), 50) }} />
          ) : results && (
            <div className="results">
              <div className="panel-head"><h3>{results.title}</h3><button className="icon-btn" onClick={() => { setResults(null); setHighlight(mapRef.current!, null); setHighlight(mapRef.current!, null, 'area') }}>✕</button></div>
              <div className="muted">Найдено: {results.total}</div>
              <div className="row gap">
                <button className="btn sm" onClick={() => exportResults('csv')}>Экспорт CSV</button>
                <button className="btn sm" onClick={() => exportResults('geojson')}>Экспорт GeoJSON</button>
              </div>
              <ul className="result-list">
                {results.items.map(i => (
                  <li key={i.id} onClick={() => { fitBbox(mapRef.current!, i.bbox, 15); openFeature(i.id) }}>
                    <strong>{i.title || '(без наименования)'}</strong><small>{i.layerTitle}</small>
                  </li>
                ))}
              </ul>
              {results.total > 20 && (
                <div className="pager">
                  <button className="btn sm" disabled={results.page === 0} onClick={() => results.fetchPage(results.page - 1)}>Назад</button>
                  <span>{results.page + 1} / {Math.ceil(results.total / 20)}</span>
                  <button className="btn sm" disabled={(results.page + 1) * 20 >= results.total} onClick={() => results.fetchPage(results.page + 1)}>Вперёд</button>
                </div>
              )}
            </div>
          )}
        </aside>
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------- строка поиска

function SearchBox({ onPick, onSubmit }: { onPick: (i: SearchItem) => void; onSubmit: (q: string) => void }) {
  const [q, setQ] = useState('')
  const [items, setItems] = useState<SearchItem[]>([])
  const [open, setOpen] = useState(false)
  useEffect(() => {
    if (q.trim().length < 3) { setItems([]); return }
    const ctl = new AbortController()
    const t = setTimeout(() => api<{ items: SearchItem[] }>(`/search?q=${encodeURIComponent(q)}&limit=7`, { signal: ctl.signal })
      .then(r => { setItems(r.items); setOpen(true) }).catch(() => {}), 250)
    return () => { clearTimeout(t); ctl.abort() }
  }, [q])
  return (
    <div className="searchbox">
      <span className="lens">⌕</span>
      <input placeholder="Поиск: населённый пункт, район, объект…" value={q} onChange={e => setQ(e.target.value)} onFocus={() => setOpen(true)}
        onKeyDown={e => { if (e.key === 'Enter' && q.trim().length >= 2) { setOpen(false); onSubmit(q.trim()) } }} />
      {open && items.length > 0 && (
        <ul className="suggest" onMouseLeave={() => setOpen(false)}>
          {items.map(i => <li key={i.id} onClick={() => { setOpen(false); onPick(i) }}><strong>{i.title || '(без наименования)'}</strong><small>{i.layerTitle}</small></li>)}
        </ul>
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------- карточка объекта

function FeatureCard({ f, onClose, onZoom, onChanged, onEditGeometry }: {
  f: any; onClose: () => void; onZoom: () => void; onChanged: (id: string | null) => void; onEditGeometry: () => void
}) {
  const { user } = useAuth()
  const [editAttrs, setEditAttrs] = useState(false)
  const [attrs, setAttrs] = useState<Attr[] | null>(null)
  const [confirmDel, setConfirmDel] = useState(false)
  const [history, setHistory] = useState<any[] | null>(null)
  const favKey = 'gp_fav'
  const favs: string[] = JSON.parse(localStorage.getItem(favKey) ?? '[]')
  const [fav, setFav] = useState(favs.includes(f.id))
  useEffect(() => { setEditAttrs(false); setHistory(null); setFav(JSON.parse(localStorage.getItem(favKey) ?? '[]').includes(f.id)) }, [f.id])

  const toggleFav = () => {
    if (!user) { toast('Войдите, чтобы сохранить в избранное', 'warn'); return }
    const cur: string[] = JSON.parse(localStorage.getItem(favKey) ?? '[]')
    const next = cur.includes(f.id) ? cur.filter(x => x !== f.id) : [...cur, f.id]
    localStorage.setItem(favKey, JSON.stringify(next)); setFav(next.includes(f.id))
  }
  const exportFeature = (fmt: 'geojson' | 'csv') => {
    if (fmt === 'geojson') saveBlob(new Blob([JSON.stringify({ type: 'Feature', id: f.id, properties: f.rawAttrs, geometry: f.geometry })], { type: 'application/geo+json' }), `${f.title ?? 'object'}.geojson`)
    else {
      const rows = [['Атрибут', 'Значение'], ['Наименование', f.title ?? ''], ['Слой', f.layerTitle], ...f.attributes.map((a: any) => [a.label, a.value ?? '']), ['Долгота', f.lon], ['Широта', f.lat]]
      saveBlob(new Blob(['﻿' + rows.map(r => r.map((c: any) => `"${String(c).replace(/"/g, '""')}"`).join(';')).join('\n')], { type: 'text/csv' }), `${f.title ?? 'object'}.csv`)
    }
  }
  const startEditAttrs = async () => { const d = await api(`/catalog/layers/${f.layerId}`); setAttrs(d.attributes); setEditAttrs(true) }
  const saveAttrs = async (values: Record<string, any>) => {
    try { await api(`/features/${f.id}`, { method: 'PUT', body: { attrs: values } }); toast('Объект сохранён'); setEditAttrs(false); onChanged(f.id) }
    catch (e) { toast((e as Error).message, 'error') }
  }
  const del = async () => {
    try { await api(`/features/${f.id}`, { method: 'DELETE' }); toast('Объект удалён'); setConfirmDel(false); onChanged(null) }
    catch (e) { toast((e as Error).message, 'error') }
  }

  return (
    <div className="card printable">
      <div className="panel-head">
        <div><h3>{f.title || '(без наименования)'}</h3><small className="muted">{f.layerTitle}</small></div>
        <button className="icon-btn" onClick={onClose}>✕</button>
      </div>
      <div className="card-actions">
        <button className={`icon-btn ${fav ? 'fav' : ''}`} title="В избранное" onClick={toggleFav}>{fav ? '★' : '☆'}</button>
        <button className="icon-btn" title="Приблизить" onClick={onZoom}>⤢</button>
        <button className="icon-btn" title="Печать" onClick={() => window.print()}>⎙</button>
        <button className="icon-btn" title="Экспорт GeoJSON" onClick={() => exportFeature('geojson')}>⇩</button>
        <button className="btn sm" onClick={() => exportFeature('csv')}>CSV</button>
      </div>
      <dl className="kv">
        <dt>Страна</dt><dd>Кыргызская Республика</dd>
        {f.region && <><dt>Область</dt><dd>{f.region}</dd></>}
        {f.district && <><dt>Район</dt><dd>{f.district}</dd></>}
        {f.areaM2 != null && <><dt>Площадь</dt><dd>{f.areaM2 > 1e6 ? `${(f.areaM2 / 1e6).toFixed(2)} км²` : `${Math.round(f.areaM2).toLocaleString('ru')} м²`}</dd></>}
        {f.lengthM != null && <><dt>Протяжённость</dt><dd>{(f.lengthM / 1000).toFixed(2)} км</dd></>}
        <dt>Координаты</dt><dd>{f.lat.toFixed(5)}, {f.lon.toFixed(5)}</dd>
      </dl>
      {editAttrs && attrs ? (
        <AttrForm attrs={attrs} initial={f.rawAttrs} onCancel={() => setEditAttrs(false)} onSave={saveAttrs} />
      ) : (
        <table className="attrs">
          <tbody>{f.attributes.filter((a: any) => a.value != null && a.value !== '').map((a: any) => <tr key={a.name}><th>{a.label}</th><td>{String(a.value)}</td></tr>)}</tbody>
        </table>
      )}
      {f.canEdit && !editAttrs && (
        <div className="edit-actions">
          <button className="btn sm" onClick={startEditAttrs}>Изменить атрибуты</button>
          <button className="btn sm" onClick={onEditGeometry}>Изменить геометрию</button>
          <button className="btn sm" onClick={async () => setHistory(await api(`/features/${f.id}/history`))}>История</button>
          <button className="btn sm btn-danger-outline" onClick={() => setConfirmDel(true)}>Удалить</button>
        </div>
      )}
      {history && (
        <div className="history">
          <h4>История изменений</h4>
          {history.length === 0 ? <p className="muted">Объект загружен импортом, правок не было.</p> :
            <ul>{history.map(h => <li key={h.id}><b>{({ create: 'Создан', update: 'Изменён', delete: 'Удалён' } as any)[h.op]}</b> · {new Date(h.at).toLocaleString('ru')} · {h.author}</li>)}</ul>}
        </div>
      )}
      {confirmDel && <Confirm title="Удаление объекта" text="Удалить объект без возможности восстановления через интерфейс?" okLabel="Удалить" danger onOk={del} onCancel={() => setConfirmDel(false)} />}
    </div>
  )
}

// ---------------------------------------------------------------------------- редактор геометрии (п. 4.4.6)

function Editor({ map, drawRef, layers, lang, onClose, onSaved }: {
  map: MlMap; drawRef: React.MutableRefObject<MapboxDraw | null>; layers: LayerInfo[]; lang: string; onClose: () => void; onSaved: (id: string, layerId: string) => void
}) {
  const [layerId, setLayerId] = useState(layers[0]?.id ?? '')
  const [attrs, setAttrs] = useState<Attr[]>([])
  const [pending, setPending] = useState<GeoJSON.Geometry | null>(null)
  const [editing, setEditing] = useState<any | null>(null)
  const [coords, setCoords] = useState({ lon: '', lat: '' })
  const layer = layers.find(l => l.id === layerId)

  useEffect(() => {
    const draw = new MapboxDraw({ displayControlsDefault: false, userProperties: true, styles: DRAW_STYLES })
    drawRef.current = draw
    map.addControl(draw as any)
    const onCreate = (e: any) => setPending(e.features[0].geometry)
    map.on('draw.create', onCreate)
    const onEditGeom = (e: Event) => {
      const f = (e as CustomEvent).detail
      draw.deleteAll()
      const [id] = draw.add({ type: 'Feature', properties: {}, geometry: f.geometry })
      ;(draw as any).changeMode(f.geometry.type.includes('Point') ? 'simple_select' : 'direct_select', f.geometry.type.includes('Point') ? { featureIds: [id] } : { featureId: id } as any)
      setEditing(f)
    }
    window.addEventListener('gp-edit-geom', onEditGeom)
    return () => {
      window.removeEventListener('gp-edit-geom', onEditGeom)
      map.off('draw.create', onCreate)
      try { map.removeControl(draw as any) } catch { /* карта уже удалена */ }
      drawRef.current = null
    }
  }, [map])

  useEffect(() => { if (layerId) api(`/catalog/layers/${layerId}`).then(d => setAttrs(d.attributes)) }, [layerId])

  const start = (mode: 'draw_point' | 'draw_line_string' | 'draw_polygon') => {
    setPending(null); setEditing(null)
    drawRef.current?.deleteAll(); ;(drawRef.current as any)?.changeMode(mode)
  }
  const fromCoords = () => {
    const lon = parseFloat(coords.lon.replace(',', '.')), lat = parseFloat(coords.lat.replace(',', '.'))
    if (isNaN(lon) || isNaN(lat)) { toast('Введите долготу и широту (WGS-84, градусы)', 'warn'); return }
    const g: GeoJSON.Point = { type: 'Point', coordinates: [lon, lat] }
    drawRef.current?.deleteAll(); drawRef.current?.add({ type: 'Feature', properties: {}, geometry: g })
    map.flyTo({ center: [lon, lat], zoom: Math.max(map.getZoom(), 14) })
    setPending(g)
  }
  const save = async (values: Record<string, any>) => {
    if (!pending || !layer) return
    try {
      const f = await api(`/layers/${layer.id}/features`, { body: { geometry: pending, attrs: values } })
      toast('Объект сохранён'); drawRef.current?.deleteAll(); setPending(null); onSaved(f.id, layer.id)
    } catch (e) { toast((e as Error).message, 'error') }
  }
  const saveGeometry = async () => {
    const g = drawRef.current?.getAll().features[0]?.geometry
    if (!g || !editing) return
    try { await api(`/features/${editing.id}`, { method: 'PUT', body: { geometry: g } }); toast('Объект сохранён'); drawRef.current?.deleteAll(); setEditing(null); onSaved(editing.id, editing.layerId) }
    catch (e) { toast((e as Error).message, 'error') }
  }
  const gt = layer?.geomType

  return (
    <div className="editor">
      <div className="panel-head"><h3>Редактор геометрии</h3><button className="icon-btn" onClick={onClose}>✕</button></div>
      {editing ? (
        <>
          <p>Редактирование «{editing.title}». Перетаскивайте узлы; Delete — удалить выбранный узел.</p>
          <div className="row gap"><button className="btn btn-primary" onClick={saveGeometry}>Сохранить геометрию</button>
            <button className="btn" onClick={() => { drawRef.current?.deleteAll(); setEditing(null) }}>Отмена</button></div>
        </>
      ) : pending ? (
        <>
          <p className="muted">Заполните атрибуты объекта слоя «{layer && title(layer, lang)}»</p>
          <AttrForm attrs={attrs} initial={{}} onCancel={() => { drawRef.current?.deleteAll(); setPending(null) }} onSave={save} />
        </>
      ) : (
        <>
          <label className="field">Слой
            <select value={layerId} onChange={e => setLayerId(e.target.value)}>
              {layers.map(l => <option key={l.id} value={l.id}>{title(l, lang)} ({GEOM_LABEL[l.geomType]})</option>)}
            </select>
          </label>
          <div className="row gap">
            <button className="btn" disabled={!(gt === 'point' || gt === 'mixed')} onClick={() => start('draw_point')}>• Точка</button>
            <button className="btn" disabled={!(gt === 'line' || gt === 'mixed')} onClick={() => start('draw_line_string')}>╱ Линия</button>
            <button className="btn" disabled={!(gt === 'polygon' || gt === 'mixed')} onClick={() => start('draw_polygon')}>▱ Полигон</button>
          </div>
          <p className="muted small">Кликайте по карте, чтобы добавить точки контура. Двойной клик — завершить.</p>
          {(gt === 'point' || gt === 'mixed') && (
            <div className="coords">
              <span className="small">Ввести координаты (WGS-84):</span>
              <input placeholder="Долгота" value={coords.lon} onChange={e => setCoords(c => ({ ...c, lon: e.target.value }))} />
              <input placeholder="Широта" value={coords.lat} onChange={e => setCoords(c => ({ ...c, lat: e.target.value }))} />
              <button className="btn sm" onClick={fromCoords}>Добавить точку</button>
            </div>
          )}
        </>
      )}
    </div>
  )
}
