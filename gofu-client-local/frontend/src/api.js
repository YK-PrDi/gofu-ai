import { useSessionStore } from '@/stores/session.js'

// 统一 fetch 封装:收拢全部 /api 端点调用,自动挂 token(B1:云端签发),统一错误。
// 开发期 /api 由 vite proxy 转 5021;上线期同源。
async function request(path, { method = 'GET', body, headers = {}, raw = false } = {}) {
  const session = useSessionStore()
  const h = { ...headers }
  if (session.token) h['Authorization'] = `Bearer ${session.token}`
  let payload = body
  if (body && !(body instanceof FormData)) {
    h['Content-Type'] = 'application/json'
    payload = JSON.stringify(body)
  }
  const res = await fetch(path, { method, headers: h, body: payload })
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(`${res.status} ${res.statusText}${text ? ': ' + text : ''}`)
  }
  if (raw) return res
  const ct = res.headers.get('content-type') || ''
  return ct.includes('application/json') ? res.json() : res.text()
}

export const api = {
  get: (p, opts) => request(p, { ...opts, method: 'GET' }),
  post: (p, body, opts) => request(p, { ...opts, method: 'POST', body }),
  request,
}
