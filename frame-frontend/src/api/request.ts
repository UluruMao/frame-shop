import axios, { type AxiosError, type AxiosInstance, type InternalAxiosRequestConfig } from 'axios'
import { useAuthStore } from '@/store/useAuthStore'

/**
 * Our Axios instance returns the *unwrapped* payload (T), not an AxiosResponse<T>,
 * because the response interceptor returns `body.data` from the backend's `Result`
 * envelope. This interface mirrors that so callers get `Promise<T>`.
 */
export interface ApiClient {
  get<T>(url: string, config?: object): Promise<T>
  post<T>(url: string, data?: unknown, config?: object): Promise<T>
  put<T>(url: string, data?: unknown, config?: object): Promise<T>
  delete<T>(url: string, config?: object): Promise<T>
}

/**
 * Pre-configured Axios instance.
 *
 * - base URL `/api` is proxied to the backend (:8081) by Vite in dev — see vite.config.ts.
 * - Request interceptor attaches `Authorization: Bearer <token>` from the auth store.
 * - Response interceptor unwraps the `Result.data` envelope and surfaces `message` on
 *   failure; on 401 it clears the token and redirects to /login.
 */
const instance: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
})

// ---- request: attach token ----
instance.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = useAuthStore.getState().token
    if (token) {
      config.headers.set('Authorization', `Bearer ${token}`)
    }
    return config
  },
  (error) => Promise.reject(error),
)

// ---- response: unwrap Result, handle 401 ----
instance.interceptors.response.use(
  (response) => {
    const body = response.data
    // If the payload looks like a Result envelope, unwrap .data; else pass through.
    if (body && typeof body === 'object' && 'code' in body && 'message' in body) {
      if (body.code === 200) {
        return body.data
      }
      // Business error: reject with the server's message so callers' catch() see it.
      return Promise.reject(new Error(body.message || 'request failed'))
    }
    return body
  },
  (error: AxiosError<{ message?: string; code?: number }>) => {
    const status = error.response?.status
    const message =
      error.response?.data?.message || error.message || 'network error'

    if (status === 401) {
      // Token invalid/expired: clear and bounce to login.
      useAuthStore.getState().clear()
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
    }
    return Promise.reject(new Error(message))
  },
)

// Cast to ApiClient so the unwrapped return type flows to callers.
const request = instance as unknown as ApiClient

export default request
