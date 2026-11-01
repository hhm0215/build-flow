import type { SiteDisplay } from '../utils/siteName'

/** 목록 표의 '현장' 열. 보관된 현장은 흐리게 표시하고 '보관' 표식을 붙인다. */
export default function SiteNameCell({ site }: { site: SiteDisplay }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, color: site.archived ? 'var(--text-muted)' : 'var(--text-secondary)' }}>
      {site.label}
      {site.archived && (
        <span style={{
          fontSize: 10, fontWeight: 600, padding: '1px 6px', borderRadius: 10,
          border: '1px solid var(--border-strong)', color: 'var(--text-muted)',
        }}>
          보관
        </span>
      )}
    </span>
  )
}
