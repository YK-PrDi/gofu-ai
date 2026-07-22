import { defineStore } from 'pinia'
import { api } from '@/api.js'
import { useSettingsStore } from '@/stores/settings.js'

// 订单补发(售后区):起后端补发任务 + 轮询进度。后端 /api/reship/run 返 taskId,复用 /api/task/{id}。
export const useReshipStore = defineStore('reship', {
  state: () => ({
    running: false,
    msg: '',
    msgType: '',
    logs: [], // 逐行进度 {stage,message,code,row}
    redCount: 0, // 补发失败标红行数
    done: false,
    mode: 'local', // 'local'本地文件 / 'wps'云表:决定完成后是否自动下载(wps就地写回云表无本地文件)
  }),
  actions: {
    setMsg(m, t) { this.msg = m; this.msgType = t || '' },
    // 文件转base64(去data:前缀)
    fileToB64(file) {
      return new Promise((resolve, reject) => {
        const rd = new FileReader()
        rd.onload = () => resolve(String(rd.result).split(',')[1] || '')
        rd.onerror = reject
        rd.readAsDataURL(file)
      })
    },
    // 本地模式:上传两个表→拿临时路径→run→轮询。sourceFile/targetFile 是 File 对象。
    async runWithFiles(sourceFile, targetFile) {
      this.running = true; this.logs = []; this.redCount = 0; this.done = false; this.mode = 'local'
      this.setMsg('上传补发表…', '')
      try {
        const [sb, tb] = await Promise.all([this.fileToB64(sourceFile), this.fileToB64(targetFile)])
        const su = await api.post('/api/reship/upload', { role: 'source', name: sourceFile.name, b64: sb })
        if (su.error) throw new Error(su.error)
        const tu = await api.post('/api/reship/upload', { role: 'target', name: targetFile.name, b64: tb })
        if (tu.error) throw new Error(tu.error)
        this.setMsg('启动补发…', '')
        const d = await api.post('/api/reship/run', {
          sourcePath: su.path, targetPath: tu.path, sourceKind: 'local', targetKind: 'local',
        })
        if (d.error) throw new Error(d.error)
        if (!d.taskId) throw new Error('未返回 taskId')
        await this.poll(d.taskId)
      } catch (e) {
        this.setMsg('失败：' + e.message, 'err'); this.running = false
      }
    },
    // WPS 模式:两个 kdocs.cn 云文档 URL 直接跑(无需上传)。结果就地写回云表,不走本地下载。
    async runWithWps(sourceUrl, targetUrl) {
      this.running = true; this.logs = []; this.redCount = 0; this.done = false; this.mode = 'wps'
      this.setMsg('启动补发(WPS 云表)…', '')
      try {
        const d = await api.post('/api/reship/run', {
          sourcePath: sourceUrl, targetPath: targetUrl, sourceKind: 'wps', targetKind: 'wps',
        })
        if (d.error) throw new Error(d.error)
        if (!d.taskId) throw new Error('未返回 taskId')
        await this.poll(d.taskId)
      } catch (e) {
        this.setMsg('失败：' + e.message, 'err'); this.running = false
      }
    },
    // 下载结果表(role=source标红版/target迁移版)
    downloadResult(role) {
      const a = document.createElement('a')
      a.href = '/api/reship/download?role=' + role
      a.download = ''
      a.click()
    },
    async poll(taskId, tries = 0) {
      if (tries > 1600) { this.setMsg('补发轮询超时', 'err'); this.running = false; return }
      try {
        const t = await api.get('/api/task/' + taskId)
        const results = t.results || []
        this.logs = results
        // 失败标红计数(code 含 NOT_FOUND / 或 type=error 且非中断)
        this.redCount = results.filter((x) => x.type === 'error' || (x.code && /NOT_FOUND|EMPTY|FAILED/i.test(x.code))).length
        this.setMsg(`补发中… ${t.status} ${t.progress}/${t.total}`, '')
        if (t.status === 'running') { setTimeout(() => this.poll(taskId, tries + 1), 1500); return }
        this.running = false; this.done = true
        const ok = t.status === 'done'
        this.setMsg(ok ? '✓ 补发流程完成' : '✗ 补发未完全成功,见下方日志', ok ? 'ok' : 'err')
        // 本地模式且设置开(默认)才自动下载结果表;WPS模式结果就地写回云表,无本地文件可下。
        if (this.mode === 'local' && ok && useSettingsStore().settings.reshipAutoDownload) {
          this.downloadResult('source')
          setTimeout(() => this.downloadResult('target'), 500)
        }
      } catch (e) { this.setMsg('轮询失败：' + e.message, 'err'); this.running = false }
    },
  },
})
