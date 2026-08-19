// Minimal assert — throws on false. The runner catches the throw → records [FAIL].
export function assert(condition, message) {
  if (!condition) {
    const err = new Error(message || 'assertion failed')
    err.name = 'AssertionError'
    throw err
  }
}

// Assert two values are equal (deep-ish for primitives/objects via JSON).
export function assertEqual(actual, expected, label) {
  const a = JSON.stringify(actual)
  const e = JSON.stringify(expected)
  if (a !== e) {
    throw new Error(`${label || 'assertEqual'} failed: expected ${e}, got ${a}`)
  }
}
