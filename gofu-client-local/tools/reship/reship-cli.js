// 快麦ERP订单补发 CLI 入口（GOFU 后端 ProcessBuilder 起 node 跑此脚本，读 stdout 进度）。
// 参照原 desktop/main/run-request.js 的组装 + automation-service.run。仅本地 XLSX。
// 用法: node reship-cli.js '<jsonConfig>'
//   config: { erpCompany, erpAccount, erpPassword, sourcePath, targetPath, userDataDir }
// 进度: 每行 stdout 输出一条 JSON: {"type":"progress"|"done"|"error", ...}。密码不写日志。
import { ErpBrowserRunner } from './erp-browser-runner.js'
import { AutomationService } from './automation-service.js'

function emit(obj) {
  // 单行 JSON,后端按行读。绝不带 password。
  process.stdout.write(JSON.stringify(obj) + '\n')
}

async function main() {
  const raw = process.argv[2]
  if (!raw) { emit({ type: 'error', message: '缺少配置参数(JSON)' }); process.exitCode = 1; return }

  let cfg
  try { cfg = JSON.parse(raw) } catch (e) { emit({ type: 'error', message: '配置JSON解析失败: ' + e.message }); process.exitCode = 1; return }

  const company = String(cfg.erpCompany ?? '').trim()
  const account = String(cfg.erpAccount ?? '').trim()
  const password = String(cfg.erpPassword ?? '')
  const sourcePath = String(cfg.sourcePath ?? '').trim()
  const targetPath = String(cfg.targetPath ?? '').trim()
  const userDataDir = String(cfg.userDataDir ?? '').trim()

  if (!company) { emit({ type: 'error', message: '请填写 ERP 公司' }); process.exitCode = 1; return }
  if (!account) { emit({ type: 'error', message: '请填写 ERP 账号' }); process.exitCode = 1; return }
  if (!password) { emit({ type: 'error', message: '请填写 ERP 密码' }); process.exitCode = 1; return }
  if (!sourcePath) { emit({ type: 'error', message: '请提供补发表路径 sourcePath' }); process.exitCode = 1; return }
  if (!targetPath) { emit({ type: 'error', message: '请提供 GOFU 补发表路径 targetPath' }); process.exitCode = 1; return }
  if (!userDataDir) { emit({ type: 'error', message: '请提供 ERP 浏览器 userDataDir' }); process.exitCode = 1; return }

  const runner = new ErpBrowserRunner({ userDataDir })
  const service = new AutomationService({ runner })

  // 请求形状同 desktop/main/run-request.js:erp 凭据 + 本地 source/target
  const request = {
    createdAt: new Date().toISOString(),
    erp: { company, account, password },
    source: { kind: 'local', value: sourcePath },
    target: { kind: 'local', value: targetPath },
  }

  const onProgress = (event) => {
    // event 里可能含 message/stage/row/orderNo/code/summary,均不含密码。原样透传给后端。
    emit({ type: 'progress', ...event })
  }

  try {
    const result = await service.run(request, onProgress)
    emit({ type: 'done', ...result })
    process.exitCode = result.ok ? 0 : 1
  } catch (e) {
    emit({ type: 'error', message: e.message || String(e) })
    process.exitCode = 1
  } finally {
    // runner 无 close 方法,直接关它的 persistent context(否则 Edge 上下文残留)
    try { await runner.context?.close() } catch (_) {}
  }
}

main()
