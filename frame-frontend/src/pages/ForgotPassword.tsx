import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { KeyRound } from 'lucide-react'
import { forgotPassword, sendCode } from '@/api/auth'
import CaptchaImage from '@/components/CaptchaImage'
import Button from '@/components/ui/Button'
import Input from '@/components/ui/Input'

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
    if (!phone) { setError('请先输入手机号'); return }
    try {
      await sendCode({ phone, purpose: 'FORGOT_PASSWORD', captchaId, captchaAnswer })
      setCooldown(60)
      const t = setInterval(() => setCooldown((c) => { if (c <= 1) { clearInterval(t); return 0 } return c - 1 }), 1000)
    } catch (err) { setError(err instanceof Error ? err.message : '发送验证码失败') }
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setLoading(true)
    try {
      await forgotPassword(phone, code, newPassword)
      setDone(true)
      setTimeout(() => navigate('/login', { replace: true }), 1500)
    } catch (err) { setError(err instanceof Error ? err.message : '修改失败') }
    finally { setLoading(false) }
  }

  return (
    <div className="min-h-screen flex items-center justify-center px-4">
      <form onSubmit={handleSubmit} className="w-full max-w-sm bg-white border border-slate-200 rounded-xl shadow-sm p-6 space-y-4">
        <div className="flex items-center gap-2">
          <KeyRound className="w-5 h-5 text-brand-600" />
          <h1 className="text-lg font-semibold text-slate-800">重置密码</h1>
        </div>
        <Input placeholder="手机号（11 位数字）" value={phone} onChange={(e) => setPhone(e.target.value)} required pattern="\d{11}" />
        <CaptchaImage onChange={(id, ans) => { setCaptchaId(id); setCaptchaAnswer(ans) }} />
        <div className="flex gap-2">
          <Input className="flex-1" placeholder="验证码" value={code} onChange={(e) => setCode(e.target.value)} required />
          <Button type="button" variant="secondary" size="sm" disabled={cooldown > 0} onClick={() => void handleSendCode()}>
            {cooldown > 0 ? `${cooldown}s` : '获取验证码'}
          </Button>
        </div>
        <Input type="password" placeholder="新密码（至少 8 个字符）" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} required minLength={8} />
        {error && <p className="text-sm text-red-600">{error}</p>}
        {done && <p className="text-sm text-green-600">修改成功，正在跳转至登入…</p>}
        <Button type="submit" loading={loading} className="w-full">
          {loading ? '修改中…' : '确认修改'}
        </Button>
        <p className="text-xs text-slate-500 text-center">
          想起密码了？<Link to="/login" className="text-brand-600 hover:underline">登入</Link>
        </p>
      </form>
    </div>
  )
}
