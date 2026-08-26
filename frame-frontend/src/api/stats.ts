import request from './request'

export interface MonthRevenue {
  month: string
  revenueCents: number
}

export interface DashboardStats {
  totalRevenueCents: number
  totalOrders: number
  totalProducts: number
  refundCount: number
  avgRating: number
  monthlyRevenue: MonthRevenue[]
}

/** GET /admin/stats — admin dashboard aggregate metrics. */
export function getAdminStats(): Promise<DashboardStats> {
  return request.get<DashboardStats>('/admin/stats')
}
