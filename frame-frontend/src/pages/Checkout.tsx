import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { ShoppingCart, Loader2, CheckCircle2, XCircle, CreditCard, RotateCw } from 'lucide-react'
import { checkout, getOrder, payOrder } from '@/api/order'
import type { Order, OrderStatus } from '@/api/types'
import { useCart } from '@/store/CartContext'
import { formatPrice } from './Products'

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
  const [orderId, setOrderId] = useState<number | null>(null)
  const [order, setOrder] = useState<Order | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [paying, setPaying] = useState(false)
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null)

  // Clean up the poller on unmount.
  useEffect(() => {
    return () => {
      if (timerRef.current) clearInterval(timerRef.current)
    }
  }, [])

  // Stage 2: poll getOrder until the consumer resolves the order.
  useEffect(() => {
    if (stage !== 'queued' || orderId === null) return
    const startedAt = Date.now()

    const poll = async () => {
      try {
        const o = await getOrder(orderId)
        // Resolved: real row (totalInCents>0) OR terminal FAILED. The synthetic
        // queued placeholder has totalInCents===0 and status=PENDING.
        const resolved = o.totalInCents > 0 || o.status === 'FAILED'
        if (resolved) {
          setOrder(o)
          setStage('resolved')
        }
      } catch (err) {
        // A 404 here likely means the correlation id hasn't been mapped yet —
        // keep polling until timeout. Other errors surface and stop the poll.
        if (Date.now() - startedAt > POLL_TIMEOUT_MS) {
          setError(err instanceof Error ? err.message : 'order resolution timed out')
          setStage('resolved')
        }
      }
    }

    // Poll immediately, then every POLL_INTERVAL_MS.
    void poll()
    timerRef.current = setInterval(poll, POLL_INTERVAL_MS)
    return () => {
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
        idempotencyKey: crypto.randomUUID(),
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
                    <span className="text-slate-800">Total</span>
                    <span className="text-slate-900">{formatPrice(order.totalInCents)}</span>
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
                {paying ? 'Processing…' : `Pay ${formatPrice(order.totalInCents)}`}
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
  const colors: Record<OrderStatus, string> = {
    PENDING: 'bg-amber-100 text-amber-700',
    PAID: 'bg-green-100 text-green-700',
    CANCELLED: 'bg-slate-100 text-slate-600',
    FAILED: 'bg-red-100 text-red-700',
  }
  return (
    <span className={`px-2 py-0.5 rounded text-xs font-medium ${colors[status]}`}>{status}</span>
  )
}
