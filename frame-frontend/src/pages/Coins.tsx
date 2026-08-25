import { useEffect, useState } from 'react'
import { Coins as CoinsIcon } from 'lucide-react'
import { getCoinBalance, getCoinHistory } from '@/api/coin'
import type { CoinBalance, CoinHistoryRow, PageResult } from '@/api/types'
import Card from '@/components/ui/Card'
import PageHeader from '@/components/ui/PageHeader'

const SOURCE_LABEL: Record<string, string> = {
  DAILY_LOGIN: '每日登录',
  REVIEW_REWARD: '评价奖励',
  CHECKOUT_SPEND: '下单抵扣',
  REFUND_RETURN: '退款返还',
  EXPIRED: '过期失效',
}

export default function Coins() {
  const [balance, setBalance] = useState<CoinBalance | null>(null)
  const [history, setHistory] = useState<CoinHistoryRow[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    Promise.all([getCoinBalance(), getCoinHistory(1, 50)])
      .then(([b, h]: [CoinBalance, PageResult<CoinHistoryRow>]) => {
        setBalance(b)
        setHistory(h.records)
      })
      .finally(() => setLoading(false))
  }, [])

  if (loading) return <div className="text-sm text-slate-500">加载中…</div>

  return (
    <div className="space-y-4">
      <PageHeader icon={<CoinsIcon className="h-5 w-5 text-amber-500" />} title="我的金币" />

      {balance && (
        <Card className="p-4">
          <div className="flex items-baseline gap-2">
            <span className="text-3xl font-bold text-amber-600">{balance.balance}</span>
            <span className="text-sm text-slate-500">颗（100颗 = ¥1）</span>
          </div>
          <p className="mt-1 text-xs text-slate-500">
            {balance.claimedToday ? '今日已领取' : '今日可领取（登录后自动领取）'}
          </p>
        </Card>
      )}

      <Card className="p-4">
        <h2 className="mb-2 text-sm font-medium text-slate-700">金币明细</h2>
        {history.length === 0 ? (
          <p className="text-sm text-slate-500">暂无记录</p>
        ) : (
          <ul className="divide-y divide-slate-100">
            {history.map((r) => (
              <li key={r.id} className="flex justify-between py-2 text-sm">
                <div>
                  <div className="text-slate-700">{SOURCE_LABEL[r.source] ?? r.source}</div>
                  <div className="text-xs text-slate-400">{r.createTime}</div>
                </div>
                <span className={r.amount >= 0 ? 'text-green-600' : 'text-red-600'}>
                  {r.amount >= 0 ? `+${r.amount}` : r.amount}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  )
}
