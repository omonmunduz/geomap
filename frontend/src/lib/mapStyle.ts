import maplibregl, { type StyleSpecification, type Map as MlMap, type ExpressionSpecification } from 'maplibre-gl'
import { getToken, tileUrl } from './api'
import type { LayerInfo } from './types'

export type Basemap = 'osm' | 'none'

export const KG_BOUNDS: [number, number, number, number] = [69.2, 39.1, 80.3, 43.3]

export function baseStyle(b: Basemap): StyleSpecification {
  return {
    version: 8,
    sources: {
      osm: {
        type: 'raster', tileSize: 256, maxzoom: 19,
        tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
        attribution: '© участники OpenStreetMap',
      },
    },
    layers: [
      { id: 'bg', type: 'background', paint: { 'background-color': '#eef1f4' } },
      { id: 'osm', type: 'raster', source: 'osm', layout: { visibility: b === 'osm' ? 'visible' : 'none' }, paint: { 'raster-saturation': -0.25 } },
    ],
  }
}

/** Добавляет Authorization к запросам тайлов API (служебные и черновые слои). */
export function transformRequest(url: string) {
  const t = getToken()
  if (t && url.startsWith(location.origin + '/api/')) return { url, headers: { Authorization: `Bearer ${t}` } }
  return { url }
}

export function newMap(container: HTMLElement, basemap: Basemap = 'osm') {
  const map = new maplibregl.Map({
    container, style: baseStyle(basemap), bounds: KG_BOUNDS, fitBoundsOptions: { padding: 20 },
    transformRequest, attributionControl: { compact: true }, maxZoom: 20,
  })
  map.addControl(new maplibregl.NavigationControl({ visualizePitch: false }), 'bottom-right')
  map.addControl(new maplibregl.ScaleControl({ unit: 'metric' }), 'bottom-left')
  return map
}

export function setBasemap(map: MlMap, b: Basemap) {
  if (map.getLayer('osm')) map.setLayoutProperty('osm', 'visibility', b === 'osm' ? 'visible' : 'none')
}

function colorExpr(l: LayerInfo): string | ExpressionSpecification {
  const c = l.style?.color ?? '#1971c2'
  const pal = l.style?.palette
  if (!pal || !l.style?.field) return c
  const m: any[] = ['match', ['get', 'cls']]
  Object.entries(pal).forEach(([k, v]) => m.push(k, v))
  m.push(c)
  return m as ExpressionSpecification
}

export const layerIds = (id: string) => [`L-${id}-fill`, `L-${id}-line`, `L-${id}-circle`]

/** Добавляет слой Геопортала (векторные тайлы MVT) на карту с оформлением по типу геометрии. */
export function addGeoLayer(map: MlMap, l: LayerInfo, opts: { draft?: boolean; before?: string; opacity?: number } = {}) {
  const src = `S-${l.id}${opts.draft ? '-d' : ''}`
  if (map.getSource(src)) return
  map.addSource(src, { type: 'vector', tiles: [tileUrl(l.id, opts.draft, l.updatedAt)], maxzoom: 16 })
  const color = colorExpr(l)
  const [fill, line, circle] = layerIds(l.id)
  const g = l.geomType
  const before = opts.before && map.getLayer(opts.before) ? opts.before : undefined
  if (g === 'polygon' || g === 'mixed') {
    map.addLayer({ id: fill, type: 'fill', source: src, 'source-layer': 'features', filter: ['==', ['geometry-type'], 'Polygon'],
      paint: { 'fill-color': color, 'fill-opacity': opts.opacity ?? (l.kind === 'base' ? 0.08 : 0.45) } }, before)
  }
  map.addLayer({ id: line, type: 'line', source: src, 'source-layer': 'features',
    filter: g === 'line' ? ['==', ['geometry-type'], 'LineString'] : ['in', ['geometry-type'], ['literal', ['LineString', 'Polygon']]],
    paint: { 'line-color': color, 'line-width': g === 'line' || g === 'mixed' ? 2 : 1.2, 'line-opacity': 0.9 } }, before)
  if (g === 'point' || g === 'mixed') {
    map.addLayer({ id: circle, type: 'circle', source: src, 'source-layer': 'features', filter: ['==', ['geometry-type'], 'Point'],
      paint: { 'circle-color': color, 'circle-radius': ['interpolate', ['linear'], ['zoom'], 5, 3.5, 12, 6], 'circle-stroke-color': '#fff', 'circle-stroke-width': 1.5 } }, before)
  }
}

export function removeGeoLayer(map: MlMap, id: string, draft = false) {
  layerIds(id).forEach(x => map.getLayer(x) && map.removeLayer(x))
  const src = `S-${id}${draft ? '-d' : ''}`
  if (map.getSource(src)) map.removeSource(src)
}

/** Подсветка объектов (поиск, карточка, расхождения). */
export function setHighlight(map: MlMap, data: GeoJSON.FeatureCollection | GeoJSON.Feature | null, id = 'hl', color = '#e8590c') {
  const fc: GeoJSON.FeatureCollection = !data ? { type: 'FeatureCollection', features: [] }
    : data.type === 'FeatureCollection' ? data : { type: 'FeatureCollection', features: [data] }
  const src = map.getSource(id) as maplibregl.GeoJSONSource | undefined
  if (src) { src.setData(fc); return }
  map.addSource(id, { type: 'geojson', data: fc })
  map.addLayer({ id: `${id}-fill`, type: 'fill', source: id, filter: ['==', ['geometry-type'], 'Polygon'], paint: { 'fill-color': color, 'fill-opacity': 0.25 } })
  map.addLayer({ id: `${id}-line`, type: 'line', source: id, paint: { 'line-color': color, 'line-width': 3 } })
  map.addLayer({ id: `${id}-pt`, type: 'circle', source: id, filter: ['==', ['geometry-type'], 'Point'],
    paint: { 'circle-radius': 9, 'circle-color': 'rgba(0,0,0,0)', 'circle-stroke-color': color, 'circle-stroke-width': 3 } })
}

export function fitBbox(map: MlMap, b?: number[] | null, maxZoom = 15) {
  if (!b || b.length < 4) return
  map.fitBounds([[b[0], b[1]], [b[2], b[3]]], { padding: 60, maxZoom, duration: 700 })
}

export const GEOM_LABEL: Record<string, string> = { point: 'Точка', line: 'Линия', polygon: 'Полигон', mixed: 'Смешанный', raster: 'Растр' }
export const STATUS_LABEL: Record<string, string> = {
  empty: 'Нет данных', draft: 'Черновик', published: 'Опубликован', published_with_draft: 'Опубликован + черновик', archived: 'Архивный',
}
export const IMPORT_STATUS: Record<string, string> = {
  uploaded: 'Файл загружен', validated: 'Проверен', invalid: 'Ошибки проверки', queued: 'В очереди', running: 'Обрабатывается',
  done: 'Готово', failed: 'Ошибка', cancelled: 'Отменён',
}
