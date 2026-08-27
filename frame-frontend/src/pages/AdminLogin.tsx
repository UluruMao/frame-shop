import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { LogIn } from 'lucide-react'
import { login } from '@/api/auth'
import { useAuthStore } from '@/store/useAuthStore'
import Button from '@/components/ui/Button'
import Field from '@/components/ui/Field'
import Input from '@/components/ui/Input'

/**
 * Admin login page (standalone route /admin/login). Mirrors the guest Login
 * form, but on success: if the account is not an ADMIN, show "该账号无管理员权限"
 * and clear the stored token; if it is an admin, go to /admin/dashboard.
 */
export default function AdminLogin() {
  const [username, setUsername] = useState('admin')
  const [password, setPassword] = useState('admin123')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const navigate = useNavigate()
  const setAuth = useAuthStore((s) => s.setAuth)
  const clear = useAuthStore((s) => s.clear)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setLoading(true)
    try {
      const res = await login({ username, password })
      if (res.role !== 'ADMIN') {
        clear()
        setError('该账号无管理员权限')
        return
      }
      setAuth(res.token, { userId: res.userId, username: res.username, role: res.role })
      navigate('/admin/dashboard', { replace: true })
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
          <h1 className="text-lg font-semibold text-slate-800">管理后台登入</h1>
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
          <p className="text-sm text-red-600">{error}</p>
        )}

        <Button type="submit" loading={loading} className="w-full">
          {loading ? '登入中…' : '登入'}
        </Button>

        <p className="text-xs text-slate-400 text-center">
          演示账号：admin / admin123
        </p>
        <p className="text-xs text-slate-500 text-center">
          <Link to="/login" className="text-brand-600 hover:underline">
            用户登入
          </Link>
        </p>
      </form>
    </div>
  )
}
