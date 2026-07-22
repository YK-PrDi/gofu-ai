// 快麦ERP订单补发 CLI 入口（GOFU 后端 ProcessBuilder 起 node 跑此脚本，读 stdout 进度）。
// 参照原 desktop/main/run-request.js 的组装 + automation-service.run。仅本地 XLSX。
// 用法: node reship-cli.js '<jsonConfig>'
//   config: { erpCompany, erpAccount, erpPassword, sourcePath, targetPath, userDataDir }
// 进度: 每行 stdout 输出一条 JSON: {"type":"progress"|"done"|"error", ...}。密码不写日志。
import fs from 'node:fs'
import { ErpBrowserRunner } from './erp-browser-runner.js'
import { AutomationService } from './automation-service.js'
import { WpsCloudGateway } from './wps-cloud-gateway.js'
import { readOrderRows } from './order-workbook.js'
import { readRemarkRecords, migrateRemarkArchive } from './migrate-image-note.js'

function emit(obj) {
  // 单行 JSON,后端按行读。绝不带 password。
  process.stdout.write(JSON.stringify(obj) + '\n')
}

async function main() {
  // --migrate-only:安全旁路,只跑备注迁移(不碰ERP浏览器/不改线上数据),供先验证迁移+文件读写。
  const migrateOnly = process.argv.includes('--migrate-only')

  // 配置来源:优先命令行 argv 里的 JSON(手动测试用);否则从 stdin 读(后端调用走这条,
  // 避免 Windows 下 ProcessBuilder 把含双引号的 JSON 参数转义搞坏,也不让密码出现在进程命令行)。
  let raw = process.argv.find((a, i) => i >= 2 && a.trim().startsWith('{'))
  if (!raw) {
    raw = await new Promise((resolve) => {
      let buf = ''
      process.stdin.setEncoding('utf8')
      process.stdin.on('data', (d) => (buf += d))
      process.stdin.on('end', () => resolve(buf.trim()))
      // 若无 stdin(直接跑无参),2秒后放弃
      setTimeout(() => resolve(buf.trim()), 2000)
    })
  }
  if (!raw) { emit({ type: 'error', message: '缺少配置(命令行JSON或stdin)' }); process.exitCode = 1; return }

  let cfg
  try { cfg = JSON.parse(raw) } catch (e) { emit({ type: 'error', message: '配置JSON解析失败: ' + e.message }); process.exitCode = 1; return }

  const company = String(cfg.erpCompany ?? '').trim()
  const account = String(cfg.erpAccount ?? '').trim()
  const password = String(cfg.erpPassword ?? '')
  const sourcePath = String(cfg.sourcePath ?? '').trim()
  const targetPath = String(cfg.targetPath ?? '').trim()
  const userDataDir = String(cfg.userDataDir ?? '').trim()

  if (!sourcePath) { emit({ type: 'error', message: '请提供补发表路径 sourcePath' }); process.exitCode = 1; return }

  // --wps-login:先登录WPS云文档(打开URL,用户在弹出Edge里登录,等文档就绪)。与补发分开,
  // 避免首次登录慢撞补发超时。sourcePath 当云文档URL用,wpsUserDataDir 存登录态。
  if (process.argv.includes('--wps-login')) {
    const wd = String(cfg.wpsUserDataDir ?? '').trim()
    if (!wd) { emit({ type: 'error', message: '缺少 wpsUserDataDir' }); process.exitCode = 1; return }
    const gw = new WpsCloudGateway({ userDataDir: wd, timeout: 180_000 }) // 登录给3分钟宽限
    try {
      emit({ type: 'progress', message: '正在打开 WPS 云文档,请在弹出的浏览器里登录…' })
      await gw.openLogin(sourcePath) // 打开文档URL(未登录会停在登录页)
      const r = await gw.inspectDocument(sourcePath) // 等文档就绪(登录+加载完)
      if (r.ok) emit({ type: 'done', ok: true, message: 'WPS 已登录,文档可编辑,可开始补发' })
      else emit({ type: 'done', ok: false, message: r.message || 'WPS 未就绪' })
      process.exitCode = r.ok ? 0 : 1
    } catch (e) {
      emit({ type: 'error', message: 'WPS 登录失败: ' + e.message }); process.exitCode = 1
    } finally {
      // 登录态已存 profile,关掉窗口(下次补发复用登录态)
      try { await gw.context?.close() } catch (_) {}
    }
    return
  }

  if (!targetPath) { emit({ type: 'error', message: '请提供 GOFU 补发表路径 targetPath' }); process.exitCode = 1; return }

  // --migrate-only:只读订单+跑迁移,输出到 targetPath 旁的 _迁移结果.xlsx,不碰 ERP、不改源表。
  if (migrateOnly) {
    try {
      const srcBytes = fs.readFileSync(sourcePath)
      const tgtBytes = fs.readFileSync(targetPath)
      const orders = readOrderRows(srcBytes)
      const remarks = readRemarkRecords(srcBytes)
      emit({ type: 'progress', message: `读到 ${orders.length} 条订单,${remarks.length} 条备注/待定行待迁移` })
      const out = migrateRemarkArchive(srcBytes, tgtBytes)
      const outPath = targetPath.replace(/\.xlsx$/i, '') + '_迁移结果.xlsx'
      fs.writeFileSync(outPath, out)
      emit({ type: 'done', ok: true, message: `迁移完成 → ${outPath}(源表/目标表未改)` })
      process.exitCode = 0
    } catch (e) {
      emit({ type: 'error', message: '迁移失败: ' + e.message }); process.exitCode = 1
    }
    return
  }

  // 完整模式需 ERP 凭据
  if (!company) { emit({ type: 'error', message: '请填写 ERP 公司' }); process.exitCode = 1; return }
  if (!account) { emit({ type: 'error', message: '请填写 ERP 账号' }); process.exitCode = 1; return }
  if (!password) { emit({ type: 'error', message: '请填写 ERP 密码' }); process.exitCode = 1; return }
  if (!userDataDir) { emit({ type: 'error', message: '请提供 ERP 浏览器 userDataDir' }); process.exitCode = 1; return }

  // 模式:local(sourcePath/targetPath是文件路径) 或 wps(是kdocs.cn云文档URL)。默认local。
  const sourceKind = String(cfg.sourceKind ?? 'local').trim() === 'wps' ? 'wps' : 'local'
  const targetKind = String(cfg.targetKind ?? 'local').trim() === 'wps' ? 'wps' : 'local'
  const wpsUserDataDir = String(cfg.wpsUserDataDir ?? '').trim()

  const runner = new ErpBrowserRunner({ userDataDir })
  // 任一端是 wps 才建网关(需 WPS 登录态目录)
  let wpsGateway = null
  if (sourceKind === 'wps' || targetKind === 'wps') {
    if (!wpsUserDataDir) { emit({ type: 'error', message: 'WPS 模式需提供 wpsUserDataDir' }); process.exitCode = 1; return }
    wpsGateway = new WpsCloudGateway({ userDataDir: wpsUserDataDir })
  }
  const service = new AutomationService({ runner, wpsGateway })

  // 请求形状同 desktop/main/run-request.js:erp 凭据 + source/target(local=路径,wps=URL)
  const request = {
    createdAt: new Date().toISOString(),
    erp: { company, account, password },
    source: { kind: sourceKind, value: sourcePath },
    target: { kind: targetKind, value: targetPath },
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
    // 关 persistent context(否则 Edge 上下文残留)。ERP + WPS 两个浏览器都关。
    try { await runner.context?.close() } catch (_) {}
    try { await wpsGateway?.context?.close() } catch (_) {}
  }
}

main()
