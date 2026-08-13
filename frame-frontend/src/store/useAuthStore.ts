import { create } from 'zustand'
import { persist } from 'zustand/middleware'

interface AuthUser {
  userId: number
  username: string
}

interface AuthState {
  token: string | null
  user: AuthUser | null
  setAuth: (token: string, user: AuthUser) => void
  clear: () => void
  isAuthenticated: () => boolean
}

/**
 * Global auth store (Zustand + persist to localStorage). The Axios request
 * interceptor reads `token` from here to attach the Bearer header; the response
 * interceptor calls `clear()` on 401.
 */
export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      token: null,
      user: null,
      setAuth: (token, user) => set({ token, user }),
      clear: () => set({ token: null, user: null }),
      isAuthenticated: () => !!get().token,
    }),
    {
      name: 'frame-auth', // localStorage key
      // Only persist the token + user, not the methods.
      partialize: (state) => ({ token: state.token, user: state.user }),
    },
  ),
)
