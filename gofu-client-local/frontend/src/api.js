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
  let res
  try {
    res = await fetch(path, { method, headers: h, body: payload })
  } catch (e) {
    // #3(07.28) 修:浏览器 fetch 抛 TypeError"Failed to fetch"=没收到任何 HTTP 响应,
    // 即本地 5021 后端此刻不可达(未启动/重启中/刚崩)——不是接口逻辑错(那会是带 body 的 500/502)。
    // 把这条含糊报错转成可操作提示,避免用户对着"Failed to fetch"无从下手。
    throw new Error(`本地服务未响应（${method} ${path}）：后端(5021)可能正在启动或已退出，请稍候重试；若持续失败请重启应用。`)
  }
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
