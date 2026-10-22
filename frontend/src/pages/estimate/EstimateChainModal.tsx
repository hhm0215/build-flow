import { Modal } from 'antd'
import type { Estimate } from '../../types'
import { additionsOfChain, estimateChain } from '../../utils/estimate'
import { ESTIMATE_KIND_LABEL, ESTIMATE_STATUS_COLOR, ESTIMATE_STATUS_LABEL } from './estimateLabels'

interface Props {
  estimate: Estimate
  estimates: Estimate[]
  onClose: () => void
}

function Row({ estimate, current }: { estimate: Estimate; current: boolean }) {
  const color = ESTIMATE_STATUS_COLOR[estimate.status]
  return (
    <li
      data-testid={`chain-${estimate.id}`}
      style={{
        display: 'grid', gridTemplateColumns: '1fr auto', gap: 8, padding: '10px 0',
        borderBottom: '1px solid var(--border)', fontWeight: current ? 700 : 400,
      }}
    >
      <div>
        <div style={{ fontSize: 13, color: 'var(--text-primary)' }}>
          #{estimate.id} {estimate.title}
        </div>
        <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>
          {ESTIMATE_KIND_LABEL[estimate.kind]}
          {estimate.revisionOfId != null && ` · 원 견적 #${estimate.revisionOfId}`}
          {estimate.supersededById != null && ` · #${estimate.supersededById}로 대체`}
          {` · ${estimate.estimateDate}`}
        </div>
      </div>
      <div style={{ textAlign: 'right' }}>
        <span style={{ fontSize: 11, fontWeight: 600, color }}>{ESTIMATE_STATUS_LABEL[estimate.status]}</span>
        <div style={{
          fontSize: 13,
          color: 'var(--text-secondary)',
          textDecoration: estimate.status === 'SUPERSEDED' ? 'line-through' : 'none',
        }}>
          ₩{estimate.totalAmount.toLocaleString('ko-KR')}
        </div>
      </div>
    </li>
  )
}

/** 같은 수정 체인의 버전 이력과 그 체인에 붙은 추가공사. 손익에는 체인마다 확정본 1개만 반영된다. */
export default function EstimateChainModal({ estimate, estimates, onClose }: Props) {
  const chain = estimateChain(estimates, estimate)
  const additions = additionsOfChain(estimates, chain)

  return (
    <Modal title="견적 변경 이력" open onCancel={onClose} footer={null} width={560}>
      <p style={{ fontSize: 12, color: 'var(--text-muted)' }}>
        수정본을 확정하면 이전 확정본은 대체됨으로 남고 손익 합계에서 빠집니다. 추가공사는 별도로 합산됩니다.
      </p>
      <ul aria-label="수정 체인" style={{ listStyle: 'none', padding: 0, margin: 0 }}>
        {chain.map((item) => <Row key={item.id} estimate={item} current={item.id === estimate.id} />)}
      </ul>
      {additions.length > 0 && (
        <>
          <div style={{ fontSize: 13, fontWeight: 600, marginTop: 16 }}>추가공사</div>
          <ul aria-label="추가공사" style={{ listStyle: 'none', padding: 0, margin: 0 }}>
            {additions.map((item) => <Row key={item.id} estimate={item} current={item.id === estimate.id} />)}
          </ul>
        </>
      )}
    </Modal>
  )
}
