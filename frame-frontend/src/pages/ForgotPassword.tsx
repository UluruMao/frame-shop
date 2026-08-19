import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { KeyRound } from 'lucide-react'
import { forgotPassword, sendCode } from '@/api/auth'
import CaptchaImage from '@/components/CaptchaImage'

/**
 * Forgot-password flow (UC015): phone → captcha → sendCode(FORGOT_PASSWORD) →
 * enter code + new password → forgotPassword → navigate to /login.
 * Mirrors the Register page's layout + 60s cooldown.
 */
export default function ForgotPassword() {
  const [phone, setPhone] = useState('')
  const [code, setCode] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [captchaId, setCaptchaId] = useState('')
  const [captchaAnswer, setCaptchaAnswer] = useState('')
  const [cooldown, setCooldown] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [done, setDone] = useState(false)
  const navigate = useNavigate()

  async function handleSendCode() {
    setError(null)
    if (!phone) { setError('enter phone first'); return }
    try {
      await sendCode({ phone, purpose: 'FORGOT_PASSWORD', captchaId, captchaAnswer })
      setCooldown(60)
      const t = setInterval(() => setCooldown((c) => { if (c <= 1) { clearInterval(t); return 0 } return c - 1 }), 1000)
    } catch (err) { setError(err instanceof Error ? err.message : 'send code failed') }
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setLoading(true)
    try {
      await forgotPassword(phone, code, newPassword)
      setDone(true)
      setTimeout(() => navigate('/login', { replace: true }), 1500)
    } catch (err) { setError(err instanceof Error ? err.message : 'reset failed') }
    finally { setLoading(false) }
  }

  return (
    <div className="min-h-screen flex items-center justify-center px-4">
      <form onSubmit={handleSubmit} className="w-full max-w-sm bg-white border border-slate-200 rounded-xl shadow-sm p-6 space-y-4">
        <div className="flex items-center gap-2">
          <KeyRound className="w-5 h-5 text-brand-600" />
          <h1 className="text-lg font-semibold text-slate-800">Reset password</h1>
        </div>
        <input className="w-full px-3 py-2 rounded-md border border-slate-300 text-sm focus:outline-none focus:ring-2 focus:ring-brand-500" placeholder="Phone (11 digits)" value={phone} onChange={(e) => setPhone(e.target.value)} required pattern="\d{11}" />
        <CaptchaImage onChange={(id, ans) => { setCaptchaId(id); setCaptchaAnswer(ans) }} />
        <div className="flex gap-2">
          <input className="flex-1 px-3 py-2 rounded-md border border-slate-300 text-sm focus:outline-none focus:ring-2 focus:ring-brand-500" placeholder="Verification code" value={code} onChange={(e) => setCode(e.target.value)} required />
          <button type="button" disabled={cooldown > 0} onClick={() => void handleSendCode()} className="px-3 py-2 rounded-md bg-brand-50 text-brand-700 text-sm font-medium hover:bg-brand-100 disabled:opacity-60">
            {cooldown > 0 ? `${cooldown}s` : 'Get code'}
          </button>
        </div>
        <input type="password" className="w-full px-3 py-2 rounded-md border border-slate-300 text-sm focus:outline-none focus:ring-2 focus:ring-brand-500" placeholder="New password (≥8)" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} required minLength={8} />
        {error && <p className="text-sm text-red-600">{error}</p>}
        {done && <p className="text-sm text-green-600">Password reset — redirecting to sign in…</p>}
        <button type="submit" disabled={loading} className="w-full py-2 rounded-md bg-brand-600 text-white text-sm font-medium hover:bg-brand-700 disabled:opacity-60">
          {loading ? 'Resetting…' : 'Reset password'}
        </button>
        <p className="text-xs text-slate-500 text-center">
          Remembered it? <Link to="/login" className="text-brand-600 hover:underline">Sign in</Link>
        </p>
      </form>
    </div>
  )
}
