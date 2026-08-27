import { useEffect, useState, type FormEvent, type ChangeEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { User as UserIcon, Phone, KeyRound, Upload, Trash2 } from 'lucide-react'
import { fetchMe, changePassword, deleteAccount } from '@/api/auth'
import { updateProfile, uploadAvatar, rebindInit, rebindVerifyOld, rebindConfirm } from '@/api/profile'
import { useAuthStore } from '@/store/useAuthStore'
import type { UserProfile } from '@/api/types'
import Button from '@/components/ui/Button'
import Input from '@/components/ui/Input'
import Card from '@/components/ui/Card'
import Modal from '@/components/ui/Modal'
import PageHeader from '@/components/ui/PageHeader'

/**
 * Profile page (UC016 + UC017 + password change).
 * - Fetches fetchMe() on mount; nickname/signature/avatar editable.
 * - Phone rebind: init → verify-old (code or password) → confirm (new phone + code).
 * - Change password: old + new → changePassword.
 */
export default function Profile() {
  const [profile, setProfile] = useState<UserProfile | null>(null)
  const [nickname, setNickname] = useState('')
  const [signature, setSignature] = useState('')
  const [avatarUrl, setAvatarUrl] = useState<string | null>(null)

  // Phone rebind state
  const [rebindStep, setRebindStep] = useState<'idle' | 'verify-old' | 'confirm'>('idle')
  const [oldCode, setOldCode] = useState('')
  const [oldPassword, setOldPassword] = useState('')
  const [newPhone, setNewPhone] = useState('')
  const [newCode, setNewCode] = useState('')

  // Change password state
  const [oldPw, setOldPw] = useState('')
  const [newPw, setNewPw] = useState('')

  const [error, setError] = useState<string | null>(null)
  const [msg, setMsg] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const user = useAuthStore((s) => s.user)

  // Account self-deletion (danger zone) state
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [deleteStep, setDeleteStep] = useState<'confirm' | 'password'>('confirm')
  const [delPw, setDelPw] = useState('')
  const navigate = useNavigate()
  const clear = useAuthStore((s) => s.clear)

  useEffect(() => {
    void (async () => {
      try {
        const p = await fetchMe()
        setProfile(p)
        setNickname(p.nickname ?? '')
        setSignature(p.signature ?? '')
        setAvatarUrl(p.avatarUrl)
      } catch (err) { setError(err instanceof Error ? err.message : '加载资料失败') }
    })()
  }, [])

  async function handleSaveProfile(e: FormEvent) {
    e.preventDefault()
    setError(null); setMsg(null); setLoading(true)
    try {
      await updateProfile(nickname, signature)
      setMsg('资料已更新')
    } catch (err) { setError(err instanceof Error ? err.message : '更新失败') }
    finally { setLoading(false) }
  }

  async function handleAvatar(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (!file) return
    setError(null); setMsg(null)
    try {
      const url = await uploadAvatar(file)
      setAvatarUrl(url)
      setMsg('头像已更新')
    } catch (err) { setError(err instanceof Error ? err.message : '头像上传失败') }
  }

  async function handleRebindInit() {
    setError(null); setMsg(null); setLoading(true)
    try {
      await rebindInit(true)
      setRebindStep('verify-old')
    } catch (err) { setError(err instanceof Error ? err.message : '换绑初始化失败') }
    finally { setLoading(false) }
  }

  async function handleVerifyOld(e: FormEvent) {
    e.preventDefault()
    setError(null); setMsg(null); setLoading(true)
    try {
      await rebindVerifyOld(oldCode || null, oldPassword || null)
      setRebindStep('confirm')
    } catch (err) { setError(err instanceof Error ? err.message : '验证原手机号失败') }
    finally { setLoading(false) }
  }

  async function handleConfirm(e: FormEvent) {
    e.preventDefault()
    setError(null); setMsg(null); setLoading(true)
    try {
      await rebindConfirm(newPhone, newCode)
      setMsg('手机号已换绑')
      setRebindStep('idle')
      setOldCode(''); setOldPassword(''); setNewPhone(''); setNewCode('')
      // refresh the phone shown
      const p = await fetchMe()
      setProfile(p)
    } catch (err) { setError(err instanceof Error ? err.message : '确认换绑失败') }
    finally { setLoading(false) }
  }

  async function handleChangePassword(e: FormEvent) {
    e.preventDefault()
    setError(null); setMsg(null); setLoading(true)
    try {
      await changePassword(oldPw, newPw)
      setMsg('密码已修改')
      setOldPw(''); setNewPw('')
    } catch (err) { setError(err instanceof Error ? err.message : '修改密码失败') }
    finally { setLoading(false) }
  }

  if (!profile) {
    return <div className="text-slate-500 text-sm">加载中…</div>
  }

  return (
    <div className="space-y-6">
      <PageHeader icon={<UserIcon className="w-5 h-5 text-brand-600" />} title="个人资料" />

      {error && <p className="text-sm text-red-600">{error}</p>}
      {msg && <p className="text-sm text-green-600">{msg}</p>}

      {/* Avatar + nickname/signature */}
      <form onSubmit={handleSaveProfile}>
        <Card className="p-5 space-y-4">
          <div className="flex items-center gap-4">
            <div className="w-16 h-16 rounded-full bg-slate-100 overflow-hidden flex items-center justify-center">
              {avatarUrl ? (
                <img src={avatarUrl} alt="头像" className="w-full h-full object-cover" />
              ) : (
                <UserIcon className="w-8 h-8 text-slate-400" />
              )}
            </div>
            <label className="flex items-center gap-1.5 text-sm text-brand-600 hover:underline cursor-pointer">
              <Upload className="w-4 h-4" />
              上传头像
              <input type="file" accept="image/*" onChange={(e) => void handleAvatar(e)} className="hidden" />
            </label>
          </div>
          <div className="space-y-1.5">
            <label className="block text-sm text-slate-600">昵称</label>
            <Input value={nickname} onChange={(e) => setNickname(e.target.value)} />
          </div>
          <div className="space-y-1.5">
            <label className="block text-sm text-slate-600">个性签名</label>
            <Input value={signature} onChange={(e) => setSignature(e.target.value)} />
          </div>
          <div className="text-xs text-slate-500">用户名：{profile.username} · 手机号：{profile.phone ?? user?.phone ?? '—'} · 角色：{profile.role}</div>
          <Button type="submit" loading={loading}>
            {loading ? '保存中…' : '保存资料'}
          </Button>
        </Card>
      </form>

      {/* Phone rebind (UC017) */}
      <Card className="p-5 space-y-4">
        <div className="flex items-center gap-2">
          <Phone className="w-4 h-4 text-brand-600" />
          <h2 className="text-sm font-semibold text-slate-700">换绑手机号</h2>
        </div>
        {rebindStep === 'idle' && (
          <Button type="button" variant="secondary" onClick={() => void handleRebindInit()} loading={loading}>
            开始换绑
          </Button>
        )}
        {rebindStep === 'verify-old' && (
          <form onSubmit={handleVerifyOld} className="space-y-3">
            <p className="text-xs text-slate-500">验证原手机号 — 输入短信验证码或登录密码。</p>
            <Input placeholder="原手机验证码（可不填）" value={oldCode} onChange={(e) => setOldCode(e.target.value)} />
            <Input type="password" placeholder="密码（可不填）" value={oldPassword} onChange={(e) => setOldPassword(e.target.value)} />
            <Button type="submit" loading={loading}>
              {loading ? '验证中…' : '验证原手机号'}
            </Button>
          </form>
        )}
        {rebindStep === 'confirm' && (
          <form onSubmit={handleConfirm} className="space-y-3">
            <p className="text-xs text-slate-500">输入新手机号及收到的验证码。</p>
            <Input placeholder="新手机号（11位）" value={newPhone} onChange={(e) => setNewPhone(e.target.value)} required pattern="\d{11}" />
            <Input placeholder="新手机验证码" value={newCode} onChange={(e) => setNewCode(e.target.value)} required />
            <Button type="submit" loading={loading}>
              {loading ? '确认中…' : '确认换绑'}
            </Button>
          </form>
        )}
      </Card>

      {/* Change password */}
      <Card className="p-5 space-y-4">
        <div className="flex items-center gap-2">
          <KeyRound className="w-4 h-4 text-brand-600" />
          <h2 className="text-sm font-semibold text-slate-700">修改密码</h2>
        </div>
        <form onSubmit={handleChangePassword} className="space-y-3">
          <Input type="password" placeholder="原密码" value={oldPw} onChange={(e) => setOldPw(e.target.value)} required />
          <Input type="password" placeholder="新密码（≥8位）" value={newPw} onChange={(e) => setNewPw(e.target.value)} required minLength={8} />
          <Button type="submit" loading={loading}>
            {loading ? '修改中…' : '修改密码'}
          </Button>
        </form>
      </Card>

      {/* Danger zone — account self-deletion (soft delete) */}
      <Card className="p-5 space-y-4 border-red-200">
        <div className="flex items-center gap-2">
          <Trash2 className="w-4 h-4 text-red-600" />
          <h2 className="text-sm font-semibold text-slate-700">注销账号</h2>
        </div>
        <p className="text-xs text-slate-500">注销后将无法登录。您的订单和消费记录将保留用于统计。</p>
        <Button variant="secondary" className="text-red-600" onClick={() => { setDeleteOpen(true); setDeleteStep('confirm'); setDelPw('') }}>
          注销账号
        </Button>
      </Card>

      <Modal open={deleteOpen} onClose={() => setDeleteOpen(false)} title="确认注销">
        {deleteStep === 'confirm' ? (
          <>
            <p className="text-sm text-slate-600">此操作不可逆。您的订单和消费记录将保留，但您将无法再登录此账号。</p>
            <div className="mt-4 flex justify-end gap-2">
              <Button variant="secondary" onClick={() => setDeleteOpen(false)}>取消</Button>
              <Button variant="primary" className="bg-red-600" onClick={() => setDeleteStep('password')}>继续</Button>
            </div>
          </>
        ) : (
          <form onSubmit={async (e) => {
            e.preventDefault()
            try { await deleteAccount(delPw) } catch (err) { setError(err instanceof Error ? err.message : '注销失败'); return }
            clear(); setDeleteOpen(false); navigate('/login', { replace: true })
          }} className="space-y-3">
            <Input type="password" placeholder="登录密码" value={delPw} onChange={(e) => setDelPw(e.target.value)} required />
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => setDeleteOpen(false)}>取消</Button>
              <Button type="submit" loading={loading} className="bg-red-600">确认注销</Button>
            </div>
          </form>
        )}
      </Modal>
    </div>
  )
}
