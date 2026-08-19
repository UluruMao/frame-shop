import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { UserPlus } from 'lucide-react'
import { register, sendCode } from '@/api/auth'
import CaptchaImage from '@/components/CaptchaImage'

export default function Register() {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [phone, setPhone] = useState('')
  const [code, setCode] = useState('')
  const [agreed, setAgreed] = useState(false)
  const [captchaId, setCaptchaId] = useState('')
  const [captchaAnswer, setCaptchaAnswer] = useState('')
  const [cooldown, setCooldown] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const navigate = useNavigate()

  async function handleSendCode() {
    setError(null)
    if (!phone) { setError('enter phone first'); return }
    try {
      await sendCode({ phone, purpose: 'REGISTER', captchaId, captchaAnswer })
      setCooldown(60)
      const t = setInterval(() => setCooldown((c) => { if (c <= 1) { clearInterval(t); return 0 } return c - 1 }), 1000)
    } catch (err) { setError(err instanceof Error ? err.message : 'send code failed') }
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    if (!agreed) { setError('please agree to the terms'); return }
    setLoading(true)
    try {
      await register({ username, password, phone, code, agreed })
      navigate('/login', { replace: true })
    } catch (err) { setError(err instanceof Error ? err.message : 'registration failed') }
    finally { setLoading(false) }
  }

  return (
    <div className="min-h-screen flex items-center justify-center px-4">
      <form onSubmit={handleSubmit} className="w-full max-w-sm bg-white border border-slate-200 rounded-xl shadow-sm p-6 space-y-4">
        <div className="flex items-center gap-2">
          <UserPlus className="w-5 h-5 text-brand-600" />
          <h1 className="text-lg font-semibold text-slate-800">Create account</h1>
        </div>
        <input className="w-full px-3 py-2 rounded-md border border-slate-300 text-sm focus:outline-none focus:ring-2 focus:ring-brand-500" placeholder="Username (4-25, alphanumeric)" value={username} onChange={(e) => setUsername(e.target.value)} required minLength={4} maxLength={25} />
        <input type="password" className="w-full px-3 py-2 rounded-md border border-slate-300 text-sm focus:outline-none focus:ring-2 focus:ring-brand-500" placeholder="Password (≥8, 2 of upper/lower/special)" value={password} onChange={(e) => setPassword(e.target.value)} required minLength={8} />
        <input className="w-full px-3 py-2 rounded-md border border-slate-300 text-sm focus:outline-none focus:ring-2 focus:ring-brand-500" placeholder="Phone (11 digits)" value={phone} onChange={(e) => setPhone(e.target.value)} required pattern="\d{11}" />
        <CaptchaImage onChange={(id, ans) => { setCaptchaId(id); setCaptchaAnswer(ans) }} />
        <div className="flex gap-2">
          <input className="flex-1 px-3 py-2 rounded-md border border-slate-300 text-sm focus:outline-none focus:ring-2 focus:ring-brand-500" placeholder="Verification code" value={code} onChange={(e) => setCode(e.target.value)} required />
          <button type="button" disabled={cooldown > 0} onClick={() => void handleSendCode()} className="px-3 py-2 rounded-md bg-brand-50 text-brand-700 text-sm font-medium hover:bg-brand-100 disabled:opacity-60">
            {cooldown > 0 ? `${cooldown}s` : 'Get code'}
          </button>
        </div>
        <label className="flex items-center gap-2 text-sm text-slate-600">
          <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} />
          I have read and agree to the Terms & Privacy Policy
        </label>
        {error && <p className="text-sm text-red-600">{error}</p>}
        <button type="submit" disabled={loading} className="w-full py-2 rounded-md bg-brand-600 text-white text-sm font-medium hover:bg-brand-700 disabled:opacity-60">
          {loading ? 'Creating…' : 'Register'}
        </button>
        <p className="text-xs text-slate-500 text-center">
          Already have an account? <Link to="/login" className="text-brand-600 hover:underline">Sign in</Link>
        </p>
      </form>
    </div>
  )
}
