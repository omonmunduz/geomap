import type { ReactNode } from 'react'

export function Modal({ title, onClose, children, footer, wide }: {
  title: ReactNode; onClose: () => void; children: ReactNode; footer?: ReactNode; wide?: boolean
}) {
  return (
    <div className="modal-backdrop" onMouseDown={e => { if (e.target === e.currentTarget) onClose() }}>
      <div className={`modal ${wide ? 'modal-wide' : ''}`} role="dialog" aria-modal="true">
        <div className="modal-head">
          <h2>{title}</h2>
          <button className="icon-btn" aria-label="Закрыть" onClick={onClose}>✕</button>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-foot">{footer}</div>}
      </div>
    </div>
  )
}

export function Spinner({ text }: { text?: string }) {
  return <div className="spinner-row"><span className="spinner" />{text && <span>{text}</span>}</div>
}

export function Confirm({ title, text, okLabel, danger, onOk, onCancel }: {
  title: string; text: ReactNode; okLabel: string; danger?: boolean; onOk: () => void; onCancel: () => void
}) {
  return (
    <Modal title={title} onClose={onCancel}
      footer={<><button className="btn" onClick={onCancel}>Отмена</button>
        <button className={`btn ${danger ? 'btn-danger' : 'btn-primary'}`} onClick={onOk}>{okLabel}</button></>}>
      <p>{text}</p>
    </Modal>
  )
}
