export type Role = 'authorized' | 'staff' | 'admin'
export interface User { id: string; name: string; role: Role; orgId?: string; orgCode?: string; orgName?: string }

export interface LayerInfo {
  id: string; code: string; appendixNo?: string; titleRu: string; titleKy?: string; description?: string
  kind: 'base' | 'thematic'; geomType: 'point' | 'line' | 'polygon' | 'mixed' | 'raster'
  accessLevel: 'open' | 'restricted'; isOperational: boolean; archived: boolean
  style: { color?: string; field?: string; palette?: Record<string, string>; labels?: Record<string, string> }
  draftVersion?: number; publishedVersion?: number; updatedAt: string
  owner: string; ownerCode: string; themeCode: string; themeTitle: string
  publishedCount: number; draftCount: number; canEdit: boolean; canView: boolean
  status: 'empty' | 'draft' | 'published' | 'published_with_draft' | 'archived'
  lastImport?: { id: string; status: string; progress: number; fileName: string; message?: string; createdAt: string } | null
}
export interface Theme { id: string; code: string; titleRu: string; titleKy?: string; layers: LayerInfo[] }

export interface Attr { name: string; labelRu: string; type: 'text' | 'number' | 'date' | 'enum'; required: boolean; optionsJson?: string }

export interface SearchItem { id: string; title: string; layerId: string; layerTitle: string; layerCode: string; lon: number; lat: number; bbox: number[] }
