import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useLocation } from 'react-router-dom'
import { LogIn } from 'lucide-react'
import { login } from '@/api/auth'
import { useAuthStore } from '@/store/useAuthStore'
import Button from '@/components/ui/Button'
import Field from '@/components/ui/Field'
import Input from '@/components/ui/Input'

export default function Login() {
  const [username, setUsername] = useState('demo1')
  const [password, setPassword] = useState('admin123')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const navigate = useNavigate()
  const location = useLocation()
  const setAuth = useAuthStore((s) => s.setAuth)

  // Redirect to the saved origin if present, else fall back to "/" — IndexRoute
  // then routes admins to /dashboard and non-admins to /products (so a USER who
  // logs in never lands on the admin-only Dashboard and triggers a 403).
  const from = (location.state as { from?: { pathname: string } } | null)?.from?.pathname ?? '/'

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setLoading(true)
    try {
      const res = await login({ username, password })
      setAuth(res.token, { userId: res.userId, username: res.username, role: res.role })
      navigate(from, { replace: true })
    } catch (err) {
      setError(err instanceof Error ? err.message : '登入失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center px-4">
      <form
        onSubmit={handleSubmit}
        className="w-full max-w-sm bg-white border border-slate-200 rounded-xl shadow-sm p-6 space-y-4"
      >
        <div className="flex items-center gap-2">
          <LogIn className="w-5 h-5 text-brand-600" />
          <h1 className="text-lg font-semibold text-slate-800">登入</h1>
        </div>

        <Field label="用户名" id="username">
          <Input
            id="username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
          />
        </Field>

        <Field label="密码" id="password">
          <Input
            id="password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
          />
        </Field>

        {error && (
          <div className="space-y-1">
            <p className="text-sm text-red-600">{error}</p>
            {/lock/i.test(error) && (
              <p className="text-xs text-slate-500">
                账户已锁定？{' '}
                <Link to="/forgot-password" className="text-brand-600 hover:underline">
                  重置密码
                </Link>
              </p>
            )}
          </div>
        )}

        <Button type="submit" loading={loading} className="w-full">
          {loading ? '登入中…' : '登入'}
        </Button>

        <p className="text-xs text-slate-400 text-center">
          演示账号：demo1 / admin123
        </p>
        <p className="text-xs text-slate-500 text-center">
          <Link to="/forgot-password" className="text-brand-600 hover:underline">
            忘记密码？
          </Link>
          {' · '}
          没有账号？{' '}
          <Link to="/register" className="text-brand-600 hover:underline">
            注册账号
          </Link>
          {' · '}
          <Link to="/admin/login" className="text-brand-600 hover:underline">
            管理员登入
          </Link>
        </p>
      </form>
    </div>
  )
}
