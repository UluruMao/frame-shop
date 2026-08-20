interface StarRatingProps {
  value: number
  onChange: (v: number) => void
  label: string
}

/** A 1-5 star input row (商品质量 / 物流速度 / 客服态度). */
export default function StarRating({ value, onChange, label }: StarRatingProps) {
  return (
    <div className="flex items-center gap-2">
      <span className="w-20 text-sm text-slate-600">{label}</span>
      <div className="flex gap-1">
        {[1, 2, 3, 4, 5].map((n) => (
          <button
            key={n}
            type="button"
            aria-label={`star ${n}`}
            onClick={() => onChange(n)}
            className={`text-2xl ${n <= value ? 'text-amber-500' : 'text-slate-300'}`}
          >
            ★
          </button>
        ))}
      </div>
    </div>
  )
}
