import request from './request'
import type { CoinBalance, CoinHistoryRow, PageResult } from './types'

/** GET /coins/balance — current balance + daily-claim status. */
export function getCoinBalance(): Promise<CoinBalance> {
  return request.get<CoinBalance>('/coins/balance')
}

/** GET /coins/history — paginated coin ledger. */
export function getCoinHistory(current = 1, size = 20): Promise<PageResult<CoinHistoryRow>> {
  return request.get<PageResult<CoinHistoryRow>>('/coins/history', { params: { current, size } })
}
