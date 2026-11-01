/*
 * BuildFlow 견적 가져오기 계획(estimates.py --emit-plan) 적용기.
 *
 * 쓰는 곳: 로그인된 로컬 BuildFlow 페이지(localhost/127.0.0.1/::1)의 개발자 도구 콘솔.
 *   1) 이 파일 내용을 콘솔에 붙여 넣는다 → window.buildflowApplyPlan 정의
 *   2) const result = await buildflowApplyPlan(<계획 JSON 붙여 넣기>)
 *   3) copy(JSON.stringify(result, null, 2))  → 저장소 밖 파일로 저장
 *   4) python -I -X utf8 scripts/import/estimates.py --record-result <그 파일>
 *
 * 규칙은 estimates.py --apply와 같다: 이름이 정확히 같은 기존 거래처·현장 재사용(둘 이상·보관 항목이면 멈춤),
 * 띄어쓰기·기호만 다른 기존 현장이 있으면 멈춤, 견적 메모의 sha256 표식으로 중복 생성 방지,
 * 확정 대상만 DRAFT일 때 확정, 선택 시 현장 상태를 COMPLETED로 변경(수주 전·보관 현장은 건너뜀).
 *
 * 매출 세금계산서 계획(taxes.py --emit-plan)은 같은 방법으로 붙여 넣은 뒤
 *   const result = await buildflowApplyTaxPlan(<계획 JSON>) → 결과 JSON을 taxes.py --record-result로 기록.
 * 현장 연결 규칙(보수적): 상호를 정규화(주식회사·(주)·㈜·공백·기호 제거)해 활성 거래처와 정확히 1곳 일치하고,
 * 그 거래처의 활성 현장 중 현장명 정규화 키가 품목명 정규화 키에 들어 있는 현장이 정확히 1개일 때만 연결한다.
 * 0개·여러 개면 만들지 않고 skipped에 사유(거래처 없음/현장 없음/후보 여럿)를 남긴다. 거래처·현장은 만들지 않는다.
 * 메모의 출처 표식이 이미 있는 세금계산서는 다시 만들지 않고, 다른 파일·수동 입력으로 같은 내용(작성일자·상호·
 * 공급가액·세액)이 이미 있으면 건너뛴다. 입금 확인은 하지 않는다.
 *
 * 토큰은 페이지 localStorage('buildflow-auth')에서 요청마다 읽어 Authorization 헤더에만 쓴다.
 * 반환값·오류 메시지·콘솔 출력에는 토큰을 넣지 않는다. 다른 출처(origin)로는 요청하지 않는다.
 */
;(function (root, factory) {
  const api = factory(root)
  if (typeof module === 'object' && module && module.exports) {
    module.exports = api
  } else {
    root.buildflowApplyPlan = api.applyPlan
    root.buildflowApplyTaxPlan = api.applyTaxPlan
  }
})(typeof globalThis !== 'undefined' ? globalThis : this, function (root) {
  'use strict'

  const AUTH_STORAGE_KEY = 'buildflow-auth'
  const PLAN_FORMAT = 'buildflow-import-plan'
  const RESULT_FORMAT = 'buildflow-import-result'
  const FORMAT_VERSION = 1
  const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]', '::1'])
  const COMPLETED = 'COMPLETED'
  const LEDGER_FIELDS = ['entry', 'sha256', 'sheet', 'rows', 'itemCount', 'total', 'fileName',
    'estimateDate', 'dateSource', 'sheetYear']

  /** estimates.py site_key와 같은 규칙: 전각→반각, 공백·괄호·기호 제거, 소문자. */
  function siteKey(name) {
    let out = ''
    for (const raw of String(name == null ? '' : name)) {
      let ch = raw
      const code = ch.codePointAt(0)
      if (code >= 0xff01 && code <= 0xff5e) ch = String.fromCodePoint(code - 0xfee0)
      else if (code === 0x3000) ch = ' '
      if (/[\s\p{P}\p{S}]/u.test(ch)) continue
      out += ch.toLowerCase()
    }
    return out
  }

  class StopError extends Error {}

  function readToken(storage) {
    let state = null
    try {
      state = JSON.parse(storage.getItem(AUTH_STORAGE_KEY) || 'null')?.state
    } catch {
      state = null
    }
    const token = state && typeof state.accessToken === 'string' ? state.accessToken : ''
    if (!token) throw new StopError('로그인 상태가 아닙니다 — BuildFlow에 로그인한 탭의 콘솔에서 실행하세요')
    return token
  }

  function checkOrigin(origin) {
    let url
    try {
      url = new URL(origin)
    } catch {
      throw new StopError('페이지 주소를 확인할 수 없습니다')
    }
    if (!['http:', 'https:'].includes(url.protocol) || !LOCAL_HOSTS.has(url.hostname)) {
      throw new StopError('로컬 개발 호스트(localhost/127.0.0.1/::1)에서만 실행합니다')
    }
    return url.origin
  }

  function makeApi(env) {
    return async function request(method, path, body) {
      const headers = { Accept: 'application/json', Authorization: 'Bearer ' + readToken(env.storage) }
      if (body !== undefined) headers['Content-Type'] = 'application/json'
      let response
      try {
        response = await env.fetch(env.origin + path, {
          method,
          headers,
          body: body === undefined ? undefined : JSON.stringify(body),
          redirect: 'error',
          credentials: 'omit',
          cache: 'no-store',
        })
      } catch (error) {
        throw new StopError(`연결 실패(${method} ${path.split('?')[0]})`)
      }
      let payload = null
      try {
        const text = await response.text()
        payload = text ? JSON.parse(text) : null
      } catch {
        payload = null
      }
      if (response.status === 401) {
        throw new StopError('인증 만료 — 페이지를 새로 고쳐 다시 로그인한 뒤 같은 계획으로 다시 실행하세요(이미 넣은 견적은 표식으로 건너뜀)')
      }
      if (response.ok && (!payload || typeof payload !== 'object' || payload.success !== false)) {
        return payload && typeof payload === 'object' && 'data' in payload ? payload.data : payload
      }
      const message = payload && typeof payload === 'object' ? String(payload.error?.message || payload.error || payload.message || '') : ''
      throw new StopError(`HTTP ${response.status}: ${(message || '응답 오류').slice(0, 300)}`)
    }
  }

  function matchByName(rows, field, name, label) {
    const matches = rows.filter((row) => String(row?.[field] ?? '').trim() === name)
    const active = matches.filter((row) => !row.archivedAt)
    if (active.length > 1) throw new StopError(`같은 이름의 ${label}이(가) ${active.length}개라 자동으로 고를 수 없음 — 이름을 정리 후 다시 실행`)
    if (active.length) return active[0]
    if (matches.length) throw new StopError(`보관된 ${label}과 이름이 같음 — 복원하거나 이름을 바꾼 뒤 다시 실행`)
    return null
  }

  function validatePlan(plan, origin) {
    if (!plan || typeof plan !== 'object' || plan.format !== PLAN_FORMAT || plan.version !== FORMAT_VERSION) {
      throw new StopError('estimates.py --emit-plan 계획 형식이 아닙니다(format/version)')
    }
    if (plan.baseUrl !== origin) {
      throw new StopError(`계획 대상(${plan.baseUrl})과 이 페이지 주소(${origin})가 다릅니다 — --base-url을 맞춰 다시 내보내세요`)
    }
    if (!Array.isArray(plan.entries) || !Array.isArray(plan.known || [])) {
      throw new StopError('계획에 entries 목록이 없습니다')
    }
  }

  async function applyPlan(plan, options) {
    const opts = options || {}
    const env = {
      fetch: opts.fetch || (root.fetch && root.fetch.bind(root)),
      storage: opts.storage || root.localStorage,
      origin: null,
    }
    const result = {
      format: RESULT_FORMAT,
      version: FORMAT_VERSION,
      baseUrl: null,
      planGeneratedAt: plan && plan.generatedAt ? String(plan.generatedAt) : null,
      startedAt: new Date().toISOString(),
      finishedAt: null,
      ok: false,
      error: null,
      entries: [],
      siteStatus: [],
      skipped: [],
    }
    try {
      env.origin = checkOrigin(opts.origin || (root.location && root.location.origin))
      result.baseUrl = env.origin
      validatePlan(plan, env.origin)
      readToken(env.storage) // 시작 전 로그인 확인
      const request = makeApi(env)
      const clients = (await request('GET', '/api/v1/clients')) || []
      const sites = (await request('GET', '/api/v1/sites')) || []

      const clientIdOf = async (name) => {
        if (!name) return [null, false]
        const found = matchByName(clients, 'companyName', name, '거래처')
        if (found) return [Number(found.id), false]
        const created = await request('POST', '/api/v1/clients', { companyName: name.slice(0, 200) })
        clients.push(created)
        return [Number(created.id), true]
      }
      const siteIdOf = async (entry, clientId) => {
        const name = entry.siteName
        const found = matchByName(sites, 'siteName', name, '현장')
        if (found) {
          const linked = found.client && found.client.id != null ? Number(found.client.id) : null
          if (clientId != null && linked != null && linked !== clientId) {
            throw new StopError(`기존 현장 '${name}'의 거래처가 견적 수신처와 다름 — --site-alias로 이름을 나눠 다시 내보내세요`)
          }
          return [Number(found.id), false]
        }
        const key = siteKey(name)
        const near = sites.find((row) => siteKey(row?.siteName) === key)
        if (near) throw new StopError(`기존 현장 '${near.siteName}'과 띄어쓰기·기호만 다름 — --site-alias로 적재 이름을 확정한 뒤 다시 내보내세요`)
        const body = { siteName: name.slice(0, 200) }
        if (entry.newSiteMemo) body.memo = entry.newSiteMemo
        if (clientId != null) body.clientId = clientId
        const created = await request('POST', '/api/v1/sites', body)
        sites.push(created)
        return [Number(created.id), true]
      }

      const confirmIds = []
      for (const entry of plan.entries) {
        if (!entry || !entry.marker || !entry.siteName || !entry.estimate) throw new StopError('계획 항목 형식이 올바르지 않습니다')
        const [clientId, createdClient] = await clientIdOf(entry.clientName)
        const [siteId, createdSite] = await siteIdOf(entry, clientId)
        const rows = (await request('GET', `/api/v1/estimates?siteId=${encodeURIComponent(siteId)}`)) || []
        const existing = rows.find((row) => String(row?.memo ?? '').includes(entry.marker))
        let estimate
        let action
        if (existing) {
          estimate = existing
          action = 'linked'
        } else {
          estimate = await request('POST', '/api/v1/estimates', { siteId, ...entry.estimate })
          action = 'created'
        }
        const record = {}
        for (const field of LEDGER_FIELDS) record[field] = entry[field] ?? null
        Object.assign(record, {
          estimateId: Number(estimate.id), siteId, clientId, createdSite, createdClient,
          status: estimate.status ?? null, action,
        })
        result.entries.push(record)
        if (entry.confirm) confirmIds.push(record.estimateId)
      }
      for (const known of plan.known || []) {
        if (known && known.confirm && known.estimateId != null) confirmIds.push(Number(known.estimateId))
      }

      for (const estimateId of [...new Set(confirmIds)]) {
        const current = (await request('GET', `/api/v1/estimates/${estimateId}`)) || {}
        if (current.status !== 'DRAFT') {
          result.skipped.push({ estimateId, reason: `이미 ${current.status}` })
          continue
        }
        const confirmed = (await request('PATCH', `/api/v1/estimates/${estimateId}/confirm`)) || {}
        for (const record of result.entries) {
          if (record.estimateId === estimateId) record.status = confirmed.status ?? 'CONFIRMED'
        }
      }

      if (plan.options && plan.options.completeSites) {
        const siteIds = [...result.entries.map((r) => r.siteId), ...(plan.known || []).map((k) => k && k.siteId)]
          .filter((id) => id != null)
          .map(Number)
        for (const siteId of [...new Set(siteIds)]) {
          const current = (await request('GET', `/api/v1/sites/${siteId}`)) || {}
          if (current.archivedAt) {
            result.skipped.push({ siteId, reason: '보관된 현장' })
            continue
          }
          if (current.status === COMPLETED) continue
          if (current.status === 'BIDDING') {
            result.skipped.push({ siteId, reason: '수주 전(BIDDING) — 화면에서 수주 처리 후 변경' })
            continue
          }
          const changed = (await request('PATCH', `/api/v1/sites/${siteId}/status`, { status: COMPLETED })) || {}
          result.siteStatus.push({ siteId, from: current.status ?? null, status: changed.status ?? COMPLETED })
        }
      }
      result.ok = true
    } catch (error) {
      result.ok = false
      result.error = error instanceof StopError ? error.message : `예상하지 못한 오류(${error && error.name ? error.name : 'Error'})`
    }
    result.finishedAt = new Date().toISOString()
    return result
  }

  // ─────────────────────────────────────────
  // 매출 세금계산서 계획(taxes.py --emit-plan)
  // ─────────────────────────────────────────

  const TAX_PLAN_FORMAT = 'buildflow-tax-import-plan'
  const TAX_RESULT_FORMAT = 'buildflow-tax-import-result'
  const TAX_LEDGER_FIELDS = ['entry', 'sha256', 'sheet', 'row', 'fileName', 'issueDate', 'supplyAmount', 'taxAmount']
  const REASON_NO_CLIENT = '거래처 없음'
  const REASON_NO_SITE = '현장 없음'
  const REASON_MANY = '후보 여럿'
  const REASON_SAME_CONTENT = '같은 내용 이미 있음'
  /** taxes.py marker_of와 같은 형식: [가져오기 매출 sha256:<16자> 행:<n>] */
  const TAX_MARKER = /\[가져오기 매출 sha256:([0-9a-f]{16}) 행:\d+\]/

  /** 상호 비교 키: 주식회사·(주)·㈜ 표기를 없앤 뒤 siteKey(공백·기호 제거, 전각→반각, 소문자). */
  function companyKey(name) {
    const stripped = String(name == null ? '' : name)
      .replace(/주식회사/g, '')
      .replace(/[(（]\s*주\s*[)）]/g, '')
      .replace(/㈜/g, '')
    return siteKey(stripped)
  }

  /** 금액 문자열 비교용: '1000', '1000.0', 1000 → '1000'. */
  function amountKey(value) {
    const n = Number(value)
    return Number.isFinite(n) ? n.toFixed(2) : String(value)
  }

  function contentKey(issueDate, counterparty, supplyAmount, taxAmount) {
    return [String(issueDate || ''), companyKey(counterparty), amountKey(supplyAmount), amountKey(taxAmount)].join('|')
  }

  function validateTaxPlan(plan, origin) {
    if (!plan || typeof plan !== 'object' || plan.format !== TAX_PLAN_FORMAT || plan.version !== FORMAT_VERSION) {
      throw new StopError('taxes.py --emit-plan 계획 형식이 아닙니다(format/version)')
    }
    if (plan.baseUrl !== origin) {
      throw new StopError(`계획 대상(${plan.baseUrl})과 이 페이지 주소(${origin})가 다릅니다 — --base-url을 맞춰 다시 내보내세요`)
    }
    if (!Array.isArray(plan.entries)) throw new StopError('계획에 entries 목록이 없습니다')
    for (const entry of plan.entries) {
      const invoice = entry && entry.invoice
      if (!entry || !entry.marker || !entry.counterparty || !invoice || invoice.type !== 'SALES' ||
          !String(invoice.memo || '').includes(entry.marker)) {
        throw new StopError('계획 항목 형식이 올바르지 않습니다')
      }
    }
  }

  /** 거래처·현장 후보 결정. 반환: { site, client } 또는 { reason, candidates }. */
  function matchSite(entry, clients, sites) {
    const key = companyKey(entry.counterparty)
    const matchedClients = key ? clients.filter((c) => !c?.archivedAt && companyKey(c?.companyName) === key) : []
    if (!matchedClients.length) return { reason: REASON_NO_CLIENT, candidates: [] }
    if (matchedClients.length > 1) return { reason: REASON_MANY, candidates: matchedClients.map((c) => Number(c.id)), of: 'client' }
    const client = matchedClients[0]
    const itemKey = siteKey(entry.itemName)
    const matchedSites = itemKey
      ? sites.filter((s) => {
        if (s?.archivedAt || !s?.client || Number(s.client.id) !== Number(client.id)) return false
        const k = siteKey(s.siteName)
        return k && itemKey.includes(k)
      })
      : []
    if (!matchedSites.length) return { reason: REASON_NO_SITE, candidates: [], clientId: Number(client.id) }
    if (matchedSites.length > 1) {
      return { reason: REASON_MANY, candidates: matchedSites.map((s) => Number(s.id)), of: 'site', clientId: Number(client.id) }
    }
    return { site: matchedSites[0], client }
  }

  async function applyTaxPlan(plan, options) {
    const opts = options || {}
    const env = {
      fetch: opts.fetch || (root.fetch && root.fetch.bind(root)),
      storage: opts.storage || root.localStorage,
      origin: null,
    }
    const result = {
      format: TAX_RESULT_FORMAT,
      version: FORMAT_VERSION,
      kind: 'tax',
      baseUrl: null,
      planGeneratedAt: plan && plan.generatedAt ? String(plan.generatedAt) : null,
      startedAt: new Date().toISOString(),
      finishedAt: null,
      ok: false,
      error: null,
      entries: [],
      skipped: [],
    }
    try {
      env.origin = checkOrigin(opts.origin || (root.location && root.location.origin))
      result.baseUrl = env.origin
      validateTaxPlan(plan, env.origin)
      readToken(env.storage)
      const request = makeApi(env)
      const clients = (await request('GET', '/api/v1/clients')) || []
      const sites = (await request('GET', '/api/v1/sites')) || []
      const invoices = (await request('GET', '/api/v1/taxes?type=SALES')) || []

      // 이 계획의 표식이 없는 기존 매출 세금계산서(다른 파일·수동 입력). 같은 파일에서 앞서 넣은 행은
      // 같은 내용이어도 서로 다른 세금계산서일 수 있으므로 비교 대상에서 뺀다(행 표식으로만 판단).
      const planMarkers = new Set(plan.entries.map((e) => e.marker))
      const others = []
      for (const row of invoices) {
        const memo = String(row?.memo ?? '')
        const tag = TAX_MARKER.exec(memo)
        if (tag && planMarkers.has(tag[0])) continue
        others.push({
          key: contentKey(row?.issueDate, row?.counterparty, row?.supplyAmount, row?.taxAmount),
          file: tag ? tag[1] : null,
          used: false,
        })
      }

      for (const entry of plan.entries) {
        const base = {}
        for (const field of TAX_LEDGER_FIELDS) base[field] = entry[field] ?? null
        const existing = invoices.find((row) => String(row?.memo ?? '').includes(entry.marker))
        if (existing) {
          result.entries.push({ ...base, taxInvoiceId: Number(existing.id), siteId: Number(existing.siteId), clientId: null, action: 'linked' })
          continue
        }
        const k = contentKey(entry.invoice.issueDate, entry.invoice.counterparty, entry.invoice.supplyAmount, entry.invoice.taxAmount)
        const fileTag = String(entry.sha256 || '').slice(0, 16)
        const same = others.find((o) => !o.used && o.key === k && o.file !== fileTag)
        if (same) {
          same.used = true
          result.skipped.push({ ...base, reason: REASON_SAME_CONTENT, detail: '다른 파일·수동 입력으로 같은 작성일자·상호·금액이 이미 있음' })
          continue
        }
        const match = matchSite(entry, clients, sites)
        if (!match.site) {
          const skip = { ...base, reason: match.reason, candidates: match.candidates }
          if (match.of) skip.of = match.of === 'client' ? '거래처' : '현장'
          if (match.clientId != null) skip.clientId = match.clientId
          result.skipped.push(skip)
          continue
        }
        const siteId = Number(match.site.id)
        const created = await request('POST', '/api/v1/taxes', { siteId, ...entry.invoice })
        invoices.push(created)
        result.entries.push({ ...base, taxInvoiceId: Number(created.id), siteId, clientId: Number(match.client.id), action: 'created' })
      }
      result.ok = true
    } catch (error) {
      result.ok = false
      result.error = error instanceof StopError ? error.message : `예상하지 못한 오류(${error && error.name ? error.name : 'Error'})`
    }
    result.finishedAt = new Date().toISOString()
    return result
  }

  return { applyPlan, applyTaxPlan, siteKey, companyKey }
})
