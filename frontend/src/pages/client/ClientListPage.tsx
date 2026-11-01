import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { motion } from 'motion/react'
import { Archive, ArchiveRestore, Building2, Pencil, Plus } from 'lucide-react'
import { Button, Popconfirm, message } from 'antd'
import PageHeader from '../../components/PageHeader'
import ErrorState from '../../components/ErrorState'
import FilterBar from '../../components/filters/FilterBar'
import FilterSearch from '../../components/filters/FilterSearch'
import FilterSelect from '../../components/filters/FilterSelect'
import { FilterSchema } from '../../hooks/useFilterParams'
import { useListFilters } from '../../hooks/useListFilters'
import { useViewport } from '../../hooks/useViewport'
import { useArchiveClient, useClients, useRestoreClient } from '../../api/clients.api'
import { useSites } from '../../api/sites.api'
import ClientCreateModal from '../site/ClientCreateModal'
import ClientEditModal from '../site/ClientEditModal'
import type { Client } from '../../types'
import { sitesOfClientPath } from '../../utils/siteClientFilter'

// 현장 목록과 같은 규칙: 미지정이면 보관되지 않은 거래처만 보여준다
type ArchiveView = 'ARCHIVED' | 'ALL'

const ARCHIVE_OPTIONS: { value: ArchiveView; label: string }[] = [
  { value: 'ARCHIVED', label: '보관된 거래처만' },
  { value: 'ALL', label: '보관 포함 전체' },
]

interface ClientFilters {
  q: string
  archive: ArchiveView
}

const FILTER_SCHEMA: FilterSchema = {
  q: { type: 'string' },
  archive: { type: 'enum', values: ['ARCHIVED', 'ALL'] },
}

interface LinkedSiteCount {
  total: number
  archived: number
}

function errorMessageOf(cause: unknown, fallback: string) {
  return (cause as { response?: { data?: { error?: string } } })?.response?.data?.error ?? fallback
}

const cellStyle = { padding: '14px 20px', fontSize: 13, color: 'var(--text-secondary)' } as const
const siteLinkStyle = { color: 'inherit', textDecoration: 'underline', textUnderlineOffset: 3 } as const

export default function ClientListPage() {
  const { isMobile } = useViewport()
  const { data, isLoading, isError, refetch } = useClients()
  const { data: sitesData, isError: sitesError } = useSites()
  const archiveClient = useArchiveClient()
  const restoreClient = useRestoreClient()
  const clients = useMemo(() => data ?? [], [data])

  // 현장 목록 응답은 보관 현장까지 모두 포함하므로 거래처별 연결 현장 수를 여기서 센다
  const linkedSites = useMemo(() => {
    const counts = new Map<number, LinkedSiteCount>()
    for (const site of sitesData ?? []) {
      const clientId = site.client?.id
      if (clientId == null) continue
      const current = counts.get(clientId) ?? { total: 0, archived: 0 }
      counts.set(clientId, {
        total: current.total + 1,
        archived: current.archived + (site.archivedAt ? 1 : 0),
      })
    }
    return counts
  }, [sitesData])
  const sitesKnown = !!sitesData && !sitesError

  const { filters, setFilters, filtered, activeCount, resetFilters } = useListFilters({
    schema: FILTER_SCHEMA,
    items: clients,
    filterFn: (client: Client, f: Partial<ClientFilters>, rawQ) => {
      const q = rawQ.trim().toLowerCase()
      if (q) {
        const haystack = [client.companyName, client.representative, client.businessNo, client.phone, client.email]
          .filter(Boolean)
          .join(' ')
          .toLowerCase()
        if (!haystack.includes(q)) return false
      }
      if (f.archive === 'ARCHIVED' && !client.archivedAt) return false
      if (!f.archive && client.archivedAt) return false
      return true
    },
  })

  const [createOpen, setCreateOpen] = useState(false)
  const [editTarget, setEditTarget] = useState<Client | null>(null)
  const [pendingId, setPendingId] = useState<number | null>(null)

  const toggleArchive = (client: Client) => {
    const archived = !!client.archivedAt
    const mutation = archived ? restoreClient : archiveClient
    setPendingId(client.id)
    mutation.mutate(client.id, {
      onSuccess: () => {
        message.success(archived ? '거래처를 복원했습니다.' : '거래처를 보관했습니다. 연결된 현장과 자료는 그대로 유지됩니다.')
      },
      onError: (cause: unknown) => {
        message.error(errorMessageOf(cause, archived ? '복원하지 못했습니다.' : '보관하지 못했습니다.'))
      },
      onSettled: () => setPendingId(null),
    })
  }

  const headers = isMobile
    ? ['업체명', '연락처', '연결 현장', '처리']
    : ['업체명', '대표자', '사업자등록번호', '연락처', '연결 현장', '처리']

  return (
    <div>
      <PageHeader
        icon={Building2}
        title="거래처"
        description="발주처를 등록하고 수정·보관합니다. 현장에 연결되지 않은 거래처도 여기서 관리합니다."
        action={
          <motion.button
            whileHover={{ scale: 1.02 }}
            whileTap={{ scale: 0.98 }}
            onClick={() => setCreateOpen(true)}
            style={{
              display: 'flex', alignItems: 'center', gap: 6,
              padding: '8px 16px',
              background: 'var(--accent-gradient)',
              border: 'none', borderRadius: 'var(--radius-sm)',
              color: 'white', fontSize: 13, fontWeight: 600, cursor: 'pointer',
              boxShadow: '0 0 16px rgba(59,130,246,0.2)',
            }}
          >
            <Plus size={14} strokeWidth={2.5} />
            거래처 등록
          </motion.button>
        }
      />

      <ClientCreateModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={() => {
          setCreateOpen(false)
          message.success('거래처를 등록했습니다.')
        }}
      />
      {editTarget && <ClientEditModal client={editTarget} onClose={() => setEditTarget(null)} />}

      <FilterBar activeCount={activeCount} onReset={resetFilters}>
        <FilterSearch
          value={filters.q ?? ''}
          onChange={(v) => setFilters({ q: v })}
          placeholder="업체명, 대표자, 사업자번호, 연락처 검색"
          width={300}
        />
        <FilterSelect<ArchiveView>
          placeholder="보관 제외 (기본)"
          value={filters.archive}
          options={ARCHIVE_OPTIONS}
          onChange={(v) => setFilters({ archive: v })}
          width={180}
        />
      </FilterBar>

      {isError ? (
        <ErrorState onRetry={refetch} />
      ) : isLoading ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {[1, 2, 3].map((i) => (
            <div key={i} className="shimmer" style={{ height: 56, borderRadius: 10 }} />
          ))}
        </div>
      ) : filtered.length === 0 ? (
        <div style={{ textAlign: 'center', padding: '48px 0', color: 'var(--text-muted)', fontSize: 14 }}>
          {activeCount > 0 ? '필터 조건에 맞는 거래처가 없습니다.' : '등록된 거래처가 없습니다.'}
        </div>
      ) : (
        <div
          className="bf-table-wrap"
          style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, overflowX: 'auto', overflowY: 'hidden' }}
        >
          <table className="bf-list-table bf-list-table--sticky" style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--border)' }}>
                {headers.map((h) => (
                  <th key={h} style={{
                    padding: '11px 20px', textAlign: 'left',
                    fontSize: 11, fontWeight: 600, color: 'var(--text-muted)',
                    letterSpacing: '0.05em', textTransform: 'uppercase',
                  }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {filtered.map((client, i) => {
                const archived = !!client.archivedAt
                const linked = linkedSites.get(client.id) ?? { total: 0, archived: 0 }
                const contact = [client.phone, client.email].filter(Boolean).join(' · ') || '-'
                const busy = pendingId === client.id
                const sitesPath = sitesOfClientPath(client.id, linked.archived)
                // 보관 거래처는 내용만 흐리게 — 처리 버튼(복원)은 또렷하게 둔다
                const cell = archived ? { ...cellStyle, opacity: 0.5 } : cellStyle
                return (
                  <tr
                    key={client.id}
                    data-archived={archived || undefined}
                    style={{ borderBottom: i < filtered.length - 1 ? '1px solid var(--border)' : 'none' }}
                  >
                    <td style={{ ...cell, color: 'var(--text-primary)', fontWeight: 600 }}>
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
                        <Link
                          to={sitesPath}
                          title="이 거래처의 현장 보기"
                          style={{ color: 'inherit', textDecoration: 'none' }}
                        >
                          {client.companyName}
                        </Link>
                        {archived && (
                          <span
                            style={{
                              fontSize: 11, fontWeight: 600, padding: '2px 8px', borderRadius: 20,
                              background: 'rgba(161,161,170,0.12)', color: '#a1a1aa',
                              border: '1px solid rgba(161,161,170,0.3)',
                            }}
                          >
                            보관
                          </span>
                        )}
                      </span>
                      {isMobile && client.representative && (
                        <div style={{ fontSize: 12, fontWeight: 400, color: 'var(--text-muted)', marginTop: 2 }}>
                          {client.representative}
                        </div>
                      )}
                    </td>
                    {!isMobile && <td style={cell}>{client.representative || '-'}</td>}
                    {!isMobile && <td style={cell}>{client.businessNo || '-'}</td>}
                    <td style={cell}>{contact}</td>
                    <td style={cell} data-testid={`linked-sites-${client.id}`}>
                      {sitesKnown ? (
                        <Link to={sitesPath} title="이 거래처의 현장 보기" style={siteLinkStyle}>
                          {`${linked.total}곳${linked.archived > 0 ? ` (보관 ${linked.archived})` : ''}`}
                        </Link>
                      ) : (
                        '-'
                      )}
                    </td>
                    <td style={{ padding: '14px 20px' }}>
                      <div style={{ display: 'flex', gap: 6 }}>
                        <Button size="small" icon={<Pencil size={12} />} onClick={() => setEditTarget(client)}>
                          수정
                        </Button>
                        <Popconfirm
                          title={archived ? '거래처를 복원할까요?' : '거래처를 보관할까요?'}
                          description={archived
                            ? '복원하면 다시 수정하고 새 현장에 연결할 수 있습니다.'
                            : '보관하면 수정과 새 현장 연결이 막힙니다. 연결된 현장·자료는 유지되고 언제든 복원할 수 있습니다.'}
                          okText={archived ? '복원' : '보관'}
                          cancelText="취소"
                          onConfirm={() => toggleArchive(client)}
                        >
                          <Button
                            size="small"
                            loading={busy}
                            icon={archived ? <ArchiveRestore size={12} /> : <Archive size={12} />}
                          >
                            {archived ? '복원' : '보관'}
                          </Button>
                        </Popconfirm>
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
