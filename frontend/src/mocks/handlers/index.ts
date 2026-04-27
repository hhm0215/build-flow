// 모든 핸들러를 하나로 합침 — browser.ts에서 이걸 import
import { authHandlers } from './auth.handlers'
import { sitesHandlers } from './sites.handlers'
import { estimatesHandlers } from './estimates.handlers'
import { purchasesHandlers } from './purchases.handlers'
import { taxesHandlers } from './taxes.handlers'
import { dashboardHandlers } from './dashboard.handlers'
import { notificationsHandlers } from './notifications.handlers'
import { warrantiesHandlers } from './warranties.handlers'

export const handlers = [
  ...authHandlers,
  ...sitesHandlers,
  ...estimatesHandlers,
  ...purchasesHandlers,
  ...taxesHandlers,
  ...dashboardHandlers,
  ...notificationsHandlers,
  ...warrantiesHandlers,
]
