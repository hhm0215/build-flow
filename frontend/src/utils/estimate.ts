import type { Estimate } from '../types'

export function sumConfirmedEstimateAmount(estimates: Estimate[]): number {
  return estimates
    .filter((estimate) => estimate.status === 'CONFIRMED')
    .reduce((sum, estimate) => sum + estimate.totalAmount, 0)
}
