import { useEffect, useState, type FormEvent, type ChangeEvent } from 'react'
import { User as UserIcon, Phone, KeyRound, Upload } from 'lucide-react'
import { fetchMe, changePassword } from '@/api/auth'
import { updateProfile, uploadAvatar, rebindInit, rebindVerifyOld, rebindConfirm } from '@/api/profile'
import { useAuthStore } from '@/store/useAuthStore'
import type { UserProfile } from '@/api/types'
import Button from '@/components/ui/Button'
import Input from '@/components/ui/Input'
import Card from '@/components/ui/Card'

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

  useEffect(() => {
    void (async () => {
      try {
        const p = await fetchMe()
        setProfile(p)
        setNickname(p.nickname ?? '')
        setSignature(p.signature ?? '')
        setAvatarUrl(p.avatarUrl)
      } catch (err) { setError(err instanceof Error ? err.message : 'failed to load profile') }
    })()
  }, [])

  async function handleSaveProfile(e: FormEvent) {
    e.preventDefault()
    setError(null); setMsg(null); setLoading(true)
    try {
      await updateProfile(nickname, signature)
      setMsg('Profile updated')
    } catch (err) { setError(err instanceof Error ? err.message : 'update failed') }
    finally { setLoading(false) }
  }

  async function handleAvatar(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (!file) return
    setError(null); setMsg(null)
    try {
      const url = await uploadAvatar(file)
      setAvatarUrl(url)
      setMsg('Avatar updated')
    } catch (err) { setError(err instanceof Error ? err.message : 'avatar upload failed') }
  }

  async function handleRebindInit() {
    setError(null); setMsg(null); setLoading(true)
    try {
      await rebindInit(true)
      setRebindStep('verify-old')
    } catch (err) { setError(err instanceof Error ? err.message : 'rebind init failed') }
    finally { setLoading(false) }
  }

  async function handleVerifyOld(e: FormEvent) {
    e.preventDefault()
    setError(null); setMsg(null); setLoading(true)
    try {
      await rebindVerifyOld(oldCode || null, oldPassword || null)
      setRebindStep('confirm')
    } catch (err) { setError(err instanceof Error ? err.message : 'verify-old failed') }
    finally { setLoading(false) }
  }

  async function handleConfirm(e: FormEvent) {
    e.preventDefault()
    setError(null); setMsg(null); setLoading(true)
    try {
      await rebindConfirm(newPhone, newCode)
      setMsg('Phone rebound')
      setRebindStep('idle')
      setOldCode(''); setOldPassword(''); setNewPhone(''); setNewCode('')
      // refresh the phone shown
      const p = await fetchMe()
      setProfile(p)
    } catch (err) { setError(err instanceof Error ? err.message : 'confirm failed') }
    finally { setLoading(false) }
  }

  async function handleChangePassword(e: FormEvent) {
    e.preventDefault()
    setError(null); setMsg(null); setLoading(true)
    try {
      await changePassword(oldPw, newPw)
      setMsg('Password changed')
      setOldPw(''); setNewPw('')
    } catch (err) { setError(err instanceof Error ? err.message : 'change password failed') }
    finally { setLoading(false) }
  }

  if (!profile) {
    return <div className="text-slate-500 text-sm">Loading…</div>
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-2">
        <UserIcon className="w-5 h-5 text-brand-600" />
        <h1 className="text-lg font-semibold text-slate-800">Profile</h1>
      </div>

      {error && <p className="text-sm text-red-600">{error}</p>}
      {msg && <p className="text-sm text-green-600">{msg}</p>}

      {/* Avatar + nickname/signature */}
      <form onSubmit={handleSaveProfile}>
        <Card className="p-5 space-y-4">
          <div className="flex items-center gap-4">
            <div className="w-16 h-16 rounded-full bg-slate-100 overflow-hidden flex items-center justify-center">
              {avatarUrl ? (
                <img src={avatarUrl} alt="avatar" className="w-full h-full object-cover" />
              ) : (
                <UserIcon className="w-8 h-8 text-slate-400" />
              )}
            </div>
            <label className="flex items-center gap-1.5 text-sm text-brand-600 hover:underline cursor-pointer">
              <Upload className="w-4 h-4" />
              Upload avatar
              <input type="file" accept="image/*" onChange={(e) => void handleAvatar(e)} className="hidden" />
            </label>
          </div>
          <div className="space-y-1.5">
            <label className="block text-sm text-slate-600">Nickname</label>
            <Input value={nickname} onChange={(e) => setNickname(e.target.value)} />
          </div>
          <div className="space-y-1.5">
            <label className="block text-sm text-slate-600">Signature</label>
            <Input value={signature} onChange={(e) => setSignature(e.target.value)} />
          </div>
          <div className="text-xs text-slate-500">Username: {profile.username} · Phone: {profile.phone ?? user?.phone ?? '—'} · Role: {profile.role}</div>
          <Button type="submit" loading={loading}>
            {loading ? 'Saving…' : 'Save profile'}
          </Button>
        </Card>
      </form>

      {/* Phone rebind (UC017) */}
      <Card className="p-5 space-y-4">
        <div className="flex items-center gap-2">
          <Phone className="w-4 h-4 text-brand-600" />
          <h2 className="text-sm font-semibold text-slate-700">Phone rebind</h2>
        </div>
        {rebindStep === 'idle' && (
          <Button type="button" variant="secondary" onClick={() => void handleRebindInit()} loading={loading}>
            Start rebind
          </Button>
        )}
        {rebindStep === 'verify-old' && (
          <form onSubmit={handleVerifyOld} className="space-y-3">
            <p className="text-xs text-slate-500">Verify old phone — enter the SMS code OR your password.</p>
            <Input placeholder="Old phone code (or leave blank)" value={oldCode} onChange={(e) => setOldCode(e.target.value)} />
            <Input type="password" placeholder="Password (or leave blank)" value={oldPassword} onChange={(e) => setOldPassword(e.target.value)} />
            <Button type="submit" loading={loading}>
              {loading ? 'Verifying…' : 'Verify old'}
            </Button>
          </form>
        )}
        {rebindStep === 'confirm' && (
          <form onSubmit={handleConfirm} className="space-y-3">
            <p className="text-xs text-slate-500">Enter new phone + the code sent to it.</p>
            <Input placeholder="New phone (11 digits)" value={newPhone} onChange={(e) => setNewPhone(e.target.value)} required pattern="\d{11}" />
            <Input placeholder="New phone code" value={newCode} onChange={(e) => setNewCode(e.target.value)} required />
            <Button type="submit" loading={loading}>
              {loading ? 'Confirming…' : 'Confirm rebind'}
            </Button>
          </form>
        )}
      </Card>

      {/* Change password */}
      <Card className="p-5 space-y-4">
        <div className="flex items-center gap-2">
          <KeyRound className="w-4 h-4 text-brand-600" />
          <h2 className="text-sm font-semibold text-slate-700">Change password</h2>
        </div>
        <form onSubmit={handleChangePassword} className="space-y-3">
          <Input type="password" placeholder="Old password" value={oldPw} onChange={(e) => setOldPw(e.target.value)} required />
          <Input type="password" placeholder="New password (≥8)" value={newPw} onChange={(e) => setNewPw(e.target.value)} required minLength={8} />
          <Button type="submit" loading={loading}>
            {loading ? 'Changing…' : 'Change password'}
          </Button>
        </form>
      </Card>
    </div>
  )
}
