import request from './request'

export interface MonthRevenue {
  month: string
  revenueCents: number
}

export interface ProductUnits {
  productId: number
  name: string
  units: number
}

export interface TrendPoint {
  bucket: string
  cents: number
}

export interface DashboardStats {
  totalRevenueCents: number
  totalOrders: number
  totalProducts: number
  refundCount: number
  avgRating: number
  monthlyRevenue: MonthRevenue[]
  unitsByProduct: ProductUnits[]
  revenueTrend: TrendPoint[]
  salesShareByProduct: ProductUnits[]
}

/** GET /admin/stats — admin dashboard aggregate metrics (time-windowed). */
export function getAdminStats(range = '30d', from?: string, to?: string): Promise<DashboardStats> {
  return request.get<DashboardStats>('/admin/stats', { params: { range, from, to } })
}
