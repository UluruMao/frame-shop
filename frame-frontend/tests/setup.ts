import '@testing-library/jest-dom/vitest'

// jsdom lacks ResizeObserver, which recharts' ResponsiveContainer depends on.
// Provide a minimal stub so chart components render in tests.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

globalThis.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver
