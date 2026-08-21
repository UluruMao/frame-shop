import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { ShoppingCart, Loader2, CheckCircle2, XCircle, CreditCard, RotateCw } from 'lucide-react'
import { checkout, getOrder, payOrder } from '@/api/order'
import { getCoinBalance } from '@/api/coin'
import type { Order, OrderStatus, PaymentMethod } from '@/api/types'
import { useCart } from '@/store/CartContext'
import { formatPrice } from './Products'

/** Color for each lifecycle status badge (all 9 OrderStatus values). */
const STATUS_COLORS: Record<OrderStatus, string> = {
  PENDING: 'bg-amber-100 text-amber-700',
  PAID: 'bg-green-100 text-green-700',
  CANCELLED: 'bg-slate-100 text-slate-600',
  FAILED: 'bg-red-100 text-red-700',
  SHIPPED: 'bg-indigo-100 text-indigo-700',
  DELIVERED: 'bg-blue-100 text-blue-700',
  COMPLETED: 'bg-slate-100 text-slate-600',
  REFUND_PENDING: 'bg-orange-100 text-orange-700',
  REFUNDED: 'bg-red-100 text-red-700',
}

/** Payment-method radio options shown in the checkout form. */
const PAYMENT_OPTIONS: { value: PaymentMethod; label: string }[] = [
  { value: 'WECHAT', label: '微信' },
  { value: 'ALIPAY', label: '支付宝' },
  { value: 'BANK_CARD', label: '银行卡' },
]

/** The three checkout stages — drives the whole page's state machine. */
type Stage = 'form' | 'queued' | 'resolved' | 'paid'

const POLL_INTERVAL_MS = 1000
const POLL_TIMEOUT_MS = 30_000

/**
 * Three-stage flash-sale checkout.
 *
 * Stage 1 (form): collect name/email/address, POST /checkout with a
 * `crypto.randomUUID()` idempotencyKey → 202 {orderId, status:"queued"}.
 *
 * Stage 2 (queued): poll GET /orders/{orderId} every 1s. While the consumer
 * hasn't persisted, the backend returns a synthetic placeholder Order with
 * `totalInCents === 0` (no real row yet). The order is "resolved" when either
 * a real row appears (totalInCents > 0 → PENDING, show pay UI) or status is
 * FAILED (show retry). This discriminator is necessary because the synthetic
 * queued placeholder also returns status=PENDING.
 *
 * Stage 3 (paid): POST /orders/{orderId}/pay → confirmation; clear the cart.
 */
export default function Checkout() {
  const navigate = useNavigate()
  const { items, totalCents, clear } = useCart()

  const [stage, setStage] = useState<Stage>('form')
  const [customerName, setCustomerName] = useState('')
  const [customerEmail, setCustomerEmail] = useState('')
  const [shippingAddress, setShippingAddress] = useState('')
  const [customerPhone, setCustomerPhone] = useState('')
  const [paymentMethod, setPaymentMethod] = useState<PaymentMethod>('WECHAT')
  const [orderId, setOrderId] = useState<number | null>(null)
  const [order, setOrder] = useState<Order | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [paying, setPaying] = useState(false)

  // UC012 coin-spend state (checkout form only). Disabled for flash-sale carts.
  const [coinBalance, setCoinBalance] = useState(0)
  const [useCoins, setUseCoins] = useState(false)
  const [coinAmount, setCoinAmount] = useState(0)
  const hasFlash = items.some((i) => i.isFlashSale)
  const subtotalCents = totalCents
  const maxCoins = Math.floor((subtotalCents / 2) / 100) * 100 // 50% cap, 100-coin granularity
  const usableCoins = Math.min(maxCoins, Math.floor(coinBalance / 100) * 100)
  // CORRECTED: coins map 1:1 to cents (100 coins = ¥1 = 100 cents), so
  // coinAmount coins = coinAmount cents of discount — NOT Math.floor(coinAmount / 100).
  const coinDiscountCents = useCoins ? coinAmount : 0
  const paidCents = subtotalCents - coinDiscountCents
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null)
  // Guards the poll callback against firing after the stage has transitioned
  // away from 'queued' (e.g. a queued interval tick that lands after the
  // immediate poll already resolved). Set false before transitioning; checked
  // at the top of poll() so a stale tick early-returns instead of overwriting
  // order state.
  const pollingRef = useRef(true)

  // Clean up the poller on unmount.
  useEffect(() => {
    return () => {
      if (timerRef.current) clearInterval(timerRef.current)
    }
  }, [])

  // UC012: fetch the user's coin balance once the checkout form mounts, so the
  // coins UI can show the balance + cap the spendable amount. Failures (e.g. not
  // logged in) silently leave the balance at 0 → checkbox stays disabled.
  useEffect(() => {
    if (stage === 'form') {
      getCoinBalance().then((b) => setCoinBalance(b.balance)).catch(() => {})
    }
  }, [stage])

  // Stage 2: poll getOrder until the consumer resolves the order.
  useEffect(() => {
    if (stage !== 'queued' || orderId === null) return
    const startedAt = Date.now()
    pollingRef.current = true

    const poll = async () => {
      // A prior tick (or the immediate poll) may have already transitioned
      // the stage; bail before issuing another getOrder.
      if (!pollingRef.current) return
      try {
        const o = await getOrder(orderId)
        // Resolved: real row (totalInCents>0) OR terminal FAILED. The synthetic
        // queued placeholder has totalInCents===0 and status=PENDING.
        const resolved = o.totalInCents > 0 || o.status === 'FAILED'
        if (resolved) {
          // Stop further polls before transitioning so a queued interval tick
          // can't fire a stale getOrder on top of the new state.
          pollingRef.current = false
          if (timerRef.current) clearInterval(timerRef.current)
          setOrder(o)
          setStage('resolved')
        }
      } catch (err) {
        if (!pollingRef.current) return
        // A 404 here likely means the correlation id hasn't been mapped yet —
        // keep polling until timeout. Other errors surface and stop the poll.
        if (Date.now() - startedAt > POLL_TIMEOUT_MS) {
          pollingRef.current = false
          if (timerRef.current) clearInterval(timerRef.current)
          setError(err instanceof Error ? err.message : 'order resolution timed out')
          setStage('resolved')
        }
      }
    }

    // Poll immediately, then every POLL_INTERVAL_MS.
    void poll()
    timerRef.current = setInterval(poll, POLL_INTERVAL_MS)
    return () => {
      pollingRef.current = false
      if (timerRef.current) clearInterval(timerRef.current)
    }
  }, [stage, orderId])

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (items.length === 0) {
      setError('Your cart is empty')
      return
    }
    setError(null)
    try {
      const res = await checkout({
        items: items.map((i) => ({ productId: i.productId, quantity: i.quantity })),
        customerName,
        customerEmail,
        shippingAddress,
        customerPhone,
        paymentMethod,
        idempotencyKey: crypto.randomUUID(),
        useCoins: useCoins && coinAmount > 0,
        coinAmount: useCoins ? coinAmount : 0,
      })
      setOrderId(res.orderId)
      setStage('queued')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'checkout failed')
    }
  }

  async function handlePay() {
    if (orderId === null) return
    setPaying(true)
    setError(null)
    try {
      const paid = await payOrder(orderId)
      setOrder(paid)
      setStage('paid')
      clear()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'payment failed')
    } finally {
      setPaying(false)
    }
  }

  function handleRetry() {
    // Reset to form so the user can re-submit (a new idempotencyKey is generated).
    setStage('form')
    setOrderId(null)
    setOrder(null)
    setError(null)
  }

  // --- Empty-cart guard ---
  if (items.length === 0 && stage === 'form') {
    return (
      <div className="space-y-4">
        <PageHeader />
        <div className="bg-white border border-slate-200 rounded-lg p-6 text-center text-slate-500 text-sm">
          Your cart is empty.{' '}
          <button onClick={() => navigate('/products')} className="text-brand-600 hover:underline">
            Browse products
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <PageHeader />

      {/* Stage 1: form */}
      {stage === 'form' && (
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="bg-white border border-slate-200 rounded-lg p-4">
            <h2 className="text-sm font-medium text-slate-700 mb-3">Order summary</h2>
            <div className="space-y-1">
              {items.map((i) => (
                <div key={i.productId} className="flex justify-between text-sm">
                  <span className="text-slate-700">
                    {i.name} × {i.quantity}
                  </span>
                  <span className="text-slate-600">{formatPrice(i.priceInCents * i.quantity)}</span>
                </div>
              ))}
            </div>
            <div className="flex justify-between border-t border-slate-100 mt-2 pt-2 text-sm font-medium">
              <span className="text-slate-800">Total</span>
              <span className="text-slate-900">{formatPrice(totalCents)}</span>
            </div>
          </div>

          <div className="bg-white border border-slate-200 rounded-lg p-4 space-y-3">
            <h2 className="text-sm font-medium text-slate-700">Shipping details</h2>
            <Field label="Name" id="customerName" value={customerName} onChange={setCustomerName} required />
            <Field
              label="Email"
              id="customerEmail"
              type="email"
              value={customerEmail}
              onChange={setCustomerEmail}
              required
            />
            <Field
              label="Shipping address"
              id="shippingAddress"
              value={shippingAddress}
              onChange={setShippingAddress}
              required
            />
            <Field
              label="Phone"
              id="customerPhone"
              type="tel"
              value={customerPhone}
              onChange={setCustomerPhone}
              required
            />
          </div>

          <div className="bg-white border border-slate-200 rounded-lg p-4 space-y-3">
            <h2 className="text-sm font-medium text-slate-700">Payment method</h2>
            <div role="radiogroup" className="flex gap-4">
              {PAYMENT_OPTIONS.map((opt) => (
                <label
                  key={opt.value}
                  className="flex items-center gap-1.5 text-sm text-slate-700 cursor-pointer"
                >
                  <input
                    type="radio"
                    name="paymentMethod"
                    value={opt.value}
                    checked={paymentMethod === opt.value}
                    onChange={() => setPaymentMethod(opt.value)}
                    className="focus:ring-brand-500 text-brand-600"
                  />
                  {opt.label}
                </label>
              ))}
            </div>
          </div>

          <div className="bg-white border border-slate-200 rounded-lg p-4 space-y-3">
            <h2 className="text-sm font-medium text-slate-700">金币 (UC012)</h2>
            {hasFlash ? (
              <p className="text-xs text-slate-500">折扣商品无法使用金币</p>
            ) : (
              <>
                <label className="flex items-center gap-2 text-sm text-slate-700">
                  <input
                    type="checkbox"
                    aria-label="使用金币"
                    checked={useCoins}
                    onChange={(e) => { setUseCoins(e.target.checked); if (!e.target.checked) setCoinAmount(0) }}
                    disabled={coinBalance < 100}
                  />
                  使用金币 (余额 {coinBalance}, 最多抵 {usableCoins} 颗 = ¥{(usableCoins / 100).toFixed(2)})
                </label>
                {useCoins && (
                  <div className="flex items-center gap-2 text-sm">
                    <span className="text-slate-600">抵扣金币数</span>
                    <input
                      type="number"
                      min={0}
                      max={usableCoins}
                      step={100}
                      aria-label="抵扣金币数"
                      className="w-24 px-2 py-1 rounded border border-slate-300"
                      value={coinAmount}
                      onChange={(e) => setCoinAmount(Math.min(Number(e.target.value), usableCoins))}
                    />
                    <span className="text-slate-500">优惠 ¥{(coinDiscountCents / 100).toFixed(2)}，实付 ¥{(paidCents / 100).toFixed(2)}</span>
                  </div>
                )}
              </>
            )}
          </div>

          {error && <p className="text-sm text-red-600">{error}</p>}

          <button
            type="submit"
            className="w-full py-2.5 rounded-md bg-brand-600 text-white text-sm font-medium hover:bg-brand-700 flex items-center justify-center gap-2"
          >
            <ShoppingCart className="w-4 h-4" />
            Place order
          </button>
        </form>
      )}

      {/* Stage 2: queued — polling the consumer */}
      {stage === 'queued' && (
        <div className="bg-white border border-slate-200 rounded-lg p-8 flex flex-col items-center gap-3 text-center">
          <Loader2 className="w-8 h-8 text-brand-600 animate-spin" />
          <h2 className="text-sm font-medium text-slate-800">Reserving stock…</h2>
          <p className="text-xs text-slate-500">
            Order #{orderId} queued. Waiting for confirmation…
          </p>
        </div>
      )}

      {/* Stage 3a: resolved — PENDING (pay) or FAILED (retry) */}
      {stage === 'resolved' && (
        <div className="space-y-4">
          {order && order.status === 'FAILED' ? (
            <div className="bg-white border border-slate-200 rounded-lg p-8 flex flex-col items-center gap-3 text-center">
              <XCircle className="w-8 h-8 text-red-600" />
              <h2 className="text-sm font-medium text-slate-800">Order failed</h2>
              <p className="text-xs text-slate-500">
                We couldn't reserve the stock for order #{orderId}.
              </p>
              {error && <p className="text-xs text-red-600">{error}</p>}
              <button
                onClick={handleRetry}
                className="mt-2 flex items-center gap-1.5 px-4 py-2 rounded-md bg-slate-100 text-slate-700 text-sm font-medium hover:bg-slate-200"
              >
                <RotateCw className="w-4 h-4" />
                Try again
              </button>
            </div>
          ) : order ? (
            <div className="space-y-4">
              <div className="bg-white border border-slate-200 rounded-lg p-4">
                <div className="flex items-center gap-2 mb-3">
                  <CheckCircle2 className="w-5 h-5 text-green-600" />
                  <h2 className="text-sm font-medium text-slate-800">Stock reserved</h2>
                </div>
                <div className="space-y-1 text-sm">
                  <div className="flex justify-between">
                    <span className="text-slate-500">Order</span>
                    <span className="text-slate-700">#{orderId}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-slate-500">Status</span>
                    <StatusBadge status={order.status} />
                  </div>
                  <div className="flex justify-between border-t border-slate-100 mt-2 pt-2 font-medium">
                    <span className="text-slate-800">实付</span>
                    <span className="text-slate-900">{formatPrice(order.paidInCents || order.totalInCents)}</span>
                  </div>
                </div>
              </div>

              {error && <p className="text-sm text-red-600">{error}</p>}

              <button
                onClick={handlePay}
                disabled={paying}
                className="w-full py-2.5 rounded-md bg-green-600 text-white text-sm font-medium hover:bg-green-700 disabled:opacity-60 flex items-center justify-center gap-2"
              >
                <CreditCard className="w-4 h-4" />
                {paying ? 'Processing…' : `Pay ${formatPrice(order.paidInCents || order.totalInCents)}`}
              </button>
            </div>
          ) : null}
        </div>
      )}

      {/* Stage 3b: paid — confirmation */}
      {stage === 'paid' && order && (
        <div className="bg-white border border-slate-200 rounded-lg p-8 flex flex-col items-center gap-3 text-center">
          <CheckCircle2 className="w-10 h-10 text-green-600" />
          <h2 className="text-base font-semibold text-slate-800">Payment successful</h2>
          <p className="text-sm text-slate-500">
            Order #{orderId} is confirmed and paid.
          </p>
          <div className="flex gap-2 mt-2">
            <button
              onClick={() => navigate('/orders')}
              className="px-4 py-2 rounded-md bg-brand-600 text-white text-sm font-medium hover:bg-brand-700"
            >
              View orders
            </button>
            <button
              onClick={() => navigate('/products')}
              className="px-4 py-2 rounded-md bg-slate-100 text-slate-700 text-sm font-medium hover:bg-slate-200"
            >
              Continue shopping
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

function PageHeader() {
  return (
    <div className="flex items-center gap-2">
      <ShoppingCart className="w-5 h-5 text-brand-600" />
      <h1 className="text-xl font-semibold text-slate-800">Checkout</h1>
    </div>
  )
}

function Field({
  label,
  id,
  value,
  onChange,
  type = 'text',
  required = false,
}: {
  label: string
  id: string
  value: string
  onChange: (v: string) => void
  type?: string
  required?: boolean
}) {
  return (
    <div className="space-y-1">
      <label className="block text-sm text-slate-600" htmlFor={id}>
        {label}
      </label>
      <input
        id={id}
        type={type}
        required={required}
        className="w-full px-3 py-2 rounded-md border border-slate-300 text-sm focus:outline-none focus:ring-2 focus:ring-brand-500"
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
    </div>
  )
}

function StatusBadge({ status }: { status: OrderStatus }) {
  return (
    <span className={`px-2 py-0.5 rounded text-xs font-medium ${STATUS_COLORS[status]}`}>{status}</span>
  )
}
