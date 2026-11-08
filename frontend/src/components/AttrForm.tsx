import { useState } from 'react'
import type { Attr } from '../lib/types'

/** Форма атрибутов по атрибутной схеме слоя; обязательные поля отмечены звёздочкой (п. 4.4.6). */
export function AttrForm({ attrs, initial, onSave, onCancel }: {
  attrs: Attr[]; initial: Record<string, any>; onSave: (v: Record<string, any>) => void; onCancel: () => void
}) {
  const [v, setV] = useState<Record<string, any>>({ ...initial })
  const [errors, setErrors] = useState<Record<string, string>>({})
  const extra = Object.keys(initial ?? {}).filter(k => !attrs.some(a => a.name === k))
  const fields: Attr[] = attrs.length ? [...attrs, ...extra.map(k => ({ name: k, labelRu: k, type: 'text', required: false } as Attr))]
    : [{ name: 'name', labelRu: 'Наименование', type: 'text', required: false } as Attr, ...extra.filter(k => k !== 'name').map(k => ({ name: k, labelRu: k, type: 'text', required: false } as Attr))]

  const submit = () => {
    const e: Record<string, string> = {}
    fields.forEach(a => {
      const val = v[a.name]
      if (a.required && (val == null || String(val).trim() === '')) e[a.name] = 'Обязательное поле'
      if (a.type === 'number' && val != null && String(val).trim() !== '' && isNaN(Number(String(val).replace(',', '.')))) e[a.name] = 'Введите число'
    })
    setErrors(e)
    if (!Object.keys(e).length) onSave(v)
  }

  return (
    <div className="attr-form">
      {fields.map(a => {
        const opts: { value: string; label: string }[] = a.type === 'enum' && a.optionsJson ? JSON.parse(a.optionsJson) : []
        return (
          <label key={a.name} className="field">
            <span>{a.labelRu}{a.required && <b className="req">*</b>}</span>
            {a.type === 'enum' ? (
              <select value={v[a.name] ?? ''} onChange={e => setV({ ...v, [a.name]: e.target.value })}>
                <option value="">—</option>
                {opts.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
            ) : (
              <input type={a.type === 'date' ? 'date' : 'text'} inputMode={a.type === 'number' ? 'decimal' : undefined}
                value={v[a.name] ?? ''} onChange={e => setV({ ...v, [a.name]: e.target.value })} />
            )}
            {errors[a.name] && <small className="err">{errors[a.name]}</small>}
          </label>
        )
      })}
      <div className="row gap">
        <button className="btn" onClick={onCancel}>Отмена</button>
        <button className="btn btn-primary" onClick={submit}>Сохранить</button>
      </div>
    </div>
  )
}
