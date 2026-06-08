import { useCallback, useEffect, useMemo } from 'react'
import { useSearchParams } from 'react-router-dom'

export type FilterFieldSchema =
  | { type: 'string' }
  | { type: 'number' }
  | { type: 'date' }
  | { type: 'enum'; values: readonly string[] }

export type FilterSchema = Record<string, FilterFieldSchema>

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

function parseField(raw: string, schema: FilterFieldSchema): unknown | undefined {
  if (raw === '') return undefined
  switch (schema.type) {
    case 'string':
      return raw
    case 'number': {
      const n = Number(raw)
      return Number.isFinite(n) ? n : undefined
    }
    case 'date':
      return ISO_DATE.test(raw) ? raw : undefined
    case 'enum':
      return (schema.values as readonly string[]).includes(raw) ? raw : undefined
  }
}

function serializeField(value: unknown): string | undefined {
  if (value === undefined || value === null || value === '') return undefined
  return String(value)
}

export function useFilterParams<T extends object>(
  schema: FilterSchema,
): [Partial<T>, (next: Partial<T>) => void] {
  const [searchParams, setSearchParams] = useSearchParams()

  const { parsed, hasInvalid } = useMemo(() => {
    const result: Record<string, unknown> = {}
    let invalid = false
    for (const key of Object.keys(schema)) {
      const raw = searchParams.get(key)
      if (raw === null) continue
      const value = parseField(raw, schema[key])
      if (value === undefined) {
        invalid = true
      } else {
        result[key] = value
      }
    }
    return { parsed: result as Partial<T>, hasInvalid: invalid }
  }, [schema, searchParams])

  useEffect(() => {
    if (!hasInvalid) return
    const next = new URLSearchParams(searchParams)
    for (const key of Object.keys(schema)) {
      const raw = next.get(key)
      if (raw !== null && parseField(raw, schema[key]) === undefined) {
        next.delete(key)
      }
    }
    if (next.toString() === searchParams.toString()) return
    setSearchParams(next, { replace: true })
  }, [hasInvalid, schema, searchParams, setSearchParams])

  const setFilters = useCallback(
    (nextValues: Partial<T>) => {
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          for (const key of Object.keys(nextValues)) {
            const raw = serializeField((nextValues as Record<string, unknown>)[key])
            if (raw === undefined) {
              next.delete(key)
            } else {
              next.set(key, raw)
            }
          }
          return next
        },
        { replace: true },
      )
    },
    [setSearchParams],
  )

  return [parsed, setFilters]
}
