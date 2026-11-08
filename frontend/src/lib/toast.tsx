import { useEffect, useState } from 'react'

type Kind = 'ok' | 'warn' | 'error'
interface T { id: number; text: string; kind: Kind; action?: { label: string; onClick: () => void } }
let push: ((t: T) => void) | null = null
let seq = 0

/** Toast-уведомление в правом нижнем углу (ЧТЗ п. 4.5, таблица 3). */
export function toast(text: string, kind: Kind = 'ok', action?: T['action']) {
  push?.({ id: ++seq, text, kind, action })
}

export function Toasts() {
  const [items, setItems] = useState<T[]>([])
  useEffect(() => {
    push = t => {
      setItems(x => [...x, t])
      setTimeout(() => setItems(x => x.filter(i => i.id !== t.id)), t.kind === 'error' ? 7000 : 4500)
    }
    return () => { push = null }
  }, [])
  return (
    <div className="toasts">
      {items.map(t => (
        <div key={t.id} className={`toast toast-${t.kind}`}>
          <span>{t.text}</span>
          {t.action && <button className="link" onClick={t.action.onClick}>{t.action.label}</button>}
        </div>
      ))}
    </div>
  )
}
