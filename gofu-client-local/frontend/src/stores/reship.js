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
    // 先登录 WPS 云文档(与补发分开,首次登录慢不会撞补发超时)。轮询到就绪。
    // 注意:这是【登录】动作,不是补发任务。完成后只报"登录态已录入",不能报"补发流程完成"、
    // 也不能置 done=true(否则会亮出下载结果表区、误导用户以为补发跑完了)。
    async wpsLogin(docUrl) {
      this.running = true; this.logs = []; this.done = false; this.mode = 'wps'
      this.setMsg('打开 WPS 云文档,请在弹出浏览器里登录…', '')
      try {
        const d = await api.post('/api/reship/wps-login', { docUrl })
        if (d.error) throw new Error(d.error)
        if (!d.taskId) throw new Error('未返回 taskId')
        await this.poll(d.taskId, 0, {
          isLogin: true,
          okMsg: '✓ WPS 登录态已录入,现在可点「开始补发」',
          failMsg: '✗ WPS 登录未完成,请重试(在弹出浏览器里登完 kdocs.cn)',
          runningMsg: '登录中… 请在弹出浏览器里登录 kdocs.cn',
        })
      } catch (e) {
        this.setMsg('WPS 登录失败：' + e.message, 'err'); this.running = false
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
    // opts:{isLogin,okMsg,failMsg,runningMsg} 供登录动作复用轮询但用登录文案、不亮补发完成/下载区。
    // 不传 opts = 补发任务(原行为):完成报"补发流程完成"、置 done、本地模式自动下载。
    async poll(taskId, tries = 0, opts = null) {
      if (tries > 1600) { this.setMsg(opts?.isLogin ? '登录轮询超时' : '补发轮询超时', 'err'); this.running = false; return }
      try {
        const t = await api.get('/api/task/' + taskId)
        const results = t.results || []
        // 登录动作不灌补发进度日志/标红(那是补发专用),避免"处理进度 N 条"误导成正在补发。
        if (!opts?.isLogin) {
          this.logs = results
          // 失败标红计数(code 含 NOT_FOUND / 或 type=error 且非中断;NEW_ADDRESS_MANUAL=新地址需人工也标红)
          this.redCount = results.filter((x) => x.type === 'error' || (x.code && /NOT_FOUND|EMPTY|FAILED|NEW_ADDRESS/i.test(x.code))).length
        }
        this.setMsg(opts?.runningMsg || `补发中… ${t.status} ${t.progress}/${t.total}`, '')
        if (t.status === 'running') { setTimeout(() => this.poll(taskId, tries + 1, opts), 1500); return }
        this.running = false
        const ok = t.status === 'done'
        // 登录动作:只报登录文案,不置 done(不亮"补发完成"下载区)、不自动下载。
        if (opts?.isLogin) {
          this.setMsg(ok ? opts.okMsg : opts.failMsg, ok ? 'ok' : 'err')
          return
        }
        this.done = true
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
