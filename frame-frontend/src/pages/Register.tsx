import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { UserPlus } from 'lucide-react'
import { register, sendCode } from '@/api/auth'
import CaptchaImage from '@/components/CaptchaImage'
import Button from '@/components/ui/Button'
import Input from '@/components/ui/Input'

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
    if (!phone) { setError('请先输入手机号'); return }
    try {
      await sendCode({ phone, purpose: 'REGISTER', captchaId, captchaAnswer })
      setCooldown(60)
      const t = setInterval(() => setCooldown((c) => { if (c <= 1) { clearInterval(t); return 0 } return c - 1 }), 1000)
    } catch (err) { setError(err instanceof Error ? err.message : '发送验证码失败') }
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    if (!agreed) { setError('请先同意条款'); return }
    setLoading(true)
    try {
      await register({ username, password, phone, code, agreed })
      navigate('/login', { replace: true })
    } catch (err) { setError(err instanceof Error ? err.message : '注册失败') }
    finally { setLoading(false) }
  }

  return (
    <div className="min-h-screen flex items-center justify-center px-4">
      <form onSubmit={handleSubmit} className="w-full max-w-sm bg-white border border-slate-200 rounded-xl shadow-sm p-6 space-y-4">
        <div className="flex items-center gap-2">
          <UserPlus className="w-5 h-5 text-brand-600" />
          <h1 className="text-lg font-semibold text-slate-800">注册</h1>
        </div>
        <Input placeholder="用户名（4-25 个字符，不含特殊字符）" value={username} onChange={(e) => setUsername(e.target.value)} required minLength={4} maxLength={25} />
        <Input type="password" placeholder="密码（至少 8 个字符，需含大写字母、小写字母、特殊字符中的任意两项）" value={password} onChange={(e) => setPassword(e.target.value)} required minLength={8} />
        <Input placeholder="手机号（11 位数字）" value={phone} onChange={(e) => setPhone(e.target.value)} required pattern="\d{11}" />
        <CaptchaImage onChange={(id, ans) => { setCaptchaId(id); setCaptchaAnswer(ans) }} />
        <div className="flex gap-2">
          <Input className="flex-1" placeholder="验证码" value={code} onChange={(e) => setCode(e.target.value)} required />
          <Button type="button" variant="secondary" size="sm" disabled={cooldown > 0} onClick={() => void handleSendCode()}>
            {cooldown > 0 ? `${cooldown}s` : '获取验证码'}
          </Button>
        </div>
        <label className="flex items-center gap-2 text-sm text-slate-600">
          <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} />
          已阅读并同意服务条例与隐私条例
        </label>
        {error && <p className="text-sm text-red-600">{error}</p>}
        <Button type="submit" loading={loading} className="w-full">
          {loading ? '注册中…' : '注册'}
        </Button>
        <p className="text-xs text-slate-500 text-center">
          已有账号？<Link to="/login" className="text-brand-600 hover:underline">登入</Link>
        </p>
      </form>
    </div>
  )
}
