import type { Estimate, Purchase } from '../types'

export function sumConfirmedEstimateAmount(estimates: Estimate[]): number {
  return estimates
    .filter((estimate) => estimate.status === 'CONFIRMED')
    .reduce((sum, estimate) => sum + estimate.totalAmount, 0)
}

export function calculateDocumentProfit(estimates: Estimate[], purchases: Purchase[]) {
  const totalEstimateAmount = sumConfirmedEstimateAmount(estimates)
  const totalPurchaseAmount = purchases.reduce((sum, purchase) => sum + purchase.totalAmount, 0)
  const margin = totalEstimateAmount - totalPurchaseAmount
  return {
    totalEstimateAmount,
    totalPurchaseAmount,
    margin,
    marginRate: totalEstimateAmount > 0 ? (margin / totalEstimateAmount) * 100 : 0,
  }
}
