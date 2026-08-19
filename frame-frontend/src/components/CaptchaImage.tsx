import { useEffect, useState } from 'react'
import { RefreshCw } from 'lucide-react'
import { getCaptcha } from '@/api/auth'
import type { CaptchaResponse } from '@/api/types'

/** Renders the in-house SVG captcha + a refresh button. Lifts captchaId/answer up via onChange. */
export default function CaptchaImage({ onChange }: {
  onChange: (captchaId: string, answer: string) => void
}) {
  const [cap, setCap] = useState<CaptchaResponse | null>(null)
  const [answer, setAnswer] = useState('')

  async function load() {
    const c = await getCaptcha()
    setCap(c)
    setAnswer('')
    onChange(c.captchaId, '')
  }
  useEffect(() => { void load() }, [])

  return (
    <div className="flex items-center gap-2">
      {cap && <span dangerouslySetInnerHTML={{ __html: cap.svgImage }} />}
      <button type="button" onClick={() => void load()} className="p-1 text-slate-500 hover:text-slate-800">
        <RefreshCw className="w-4 h-4" />
      </button>
      <input
        placeholder="captcha"
        className="w-24 px-2 py-1 rounded-md border border-slate-300 text-sm focus:outline-none focus:ring-2 focus:ring-brand-500"
        value={answer}
        onChange={(e) => { setAnswer(e.target.value); onChange(cap?.captchaId ?? '', e.target.value) }}
      />
    </div>
  )
}
