<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useReshipStore } from '@/stores/reship.js'

// 售后·订单补发(方案2文件选择器):选补发表+GOFU补发表→上传→快麦ERP自动补发→失败标红→下载结果。
// 备注/待定行迁移到GOFU补发表。ERP账号在【设置·订单补发】配。第一期仅本地XLSX。
const reship = useReshipStore()

const mode = ref('local') // local本地文件 / wps云表
const sourceFile = ref(null)
const targetFile = ref(null)
// WPS 云文档URL:两个链接常固定复用,持久化到 localStorage 免得每次重填。
// 机器相关(不上云),localStorage 正合适。初始值从上次输入恢复。
const sourceUrl = ref(localStorage.getItem('reship.wps.sourceUrl') || '') // WPS 补发表云文档URL
const targetUrl = ref(localStorage.getItem('reship.wps.targetUrl') || '') // WPS GOFU补发表云文档URL
watch(sourceUrl, (v) => localStorage.setItem('reship.wps.sourceUrl', v || ''))
watch(targetUrl, (v) => localStorage.setItem('reship.wps.targetUrl', v || ''))

function pickFile(role) {
  const inp = document.createElement('input')
  inp.type = 'file'; inp.accept = '.xlsx'
  inp.onchange = () => {
    const f = inp.files?.[0]
    if (!f) return
    if (role === 'source') sourceFile.value = f
    else targetFile.value = f
  }
  inp.click()
}

async function start() {
  if (mode.value === 'local') {
    if (!sourceFile.value || !targetFile.value) { ElMessage.error('请先选择 补发表 和 GOFU补发表 两个文件'); return }
    if (!confirm('将逐单在快麦 ERP 真实创建补发单(改线上业务数据)。确认开始?')) return
    await reship.runWithFiles(sourceFile.value, targetFile.value)
  } else {
    if (!sourceUrl.value.trim() || !targetUrl.value.trim()) { ElMessage.error('请填 补发表 和 GOFU补发表 两个 WPS 云文档链接'); return }
    if (!confirm('将读 WPS 云表逐单在快麦 ERP 创建补发单,结果就地写回云表(改线上业务数据)。确认开始?')) return
    await reship.runWithWps(sourceUrl.value.trim(), targetUrl.value.trim())
  }
}

// 先登录 WPS(用补发表URL登一次,登录态按域名共享)。首次登录慢先走这个,再点开始补发。
async function loginWps() {
  if (!sourceUrl.value.trim()) { ElMessage.error('请先填补发表 WPS 云文档链接'); return }
  await reship.wpsLogin(sourceUrl.value.trim())
}

// 日志行样式:成功绿/失败红/跳过灰
function rowType(r) {
  if (r.type === 'error' || (r.code && /NOT_FOUND|EMPTY|FAILED/i.test(r.code))) return 'err'
  if (r.code && /SKIPPED|ROUTED|ALREADY/i.test(r.code)) return 'skip'
  if (r.type === 'done' || r.stage === 'success') return 'ok'
  return ''
}
</script>

<template>
  <div class="reship">
    <h2>订单补发</h2>
    <el-card>
      <template #header>快麦 ERP 订单补发（选表 → 逐单补发 → 失败标红 → 下载结果）</template>
      <p class="desc">
        选<b>补发表</b>(源)和 <b>GOFU补发表</b>(备注/待定行迁移写入的目标模板)两个 .xlsx 文件。
        流程：已补发跳过 → 备注/待定行迁移到 GOFU 补发表 → 其余在快麦 ERP 创建补发单；无法补发的订单在补发表中<b style="color:#f56c6c">整行标红</b>。
        处理完可下载两个结果表。ERP 账号在 <b>设置 · 订单补发</b> 配。第一期仅本地 XLSX。
      </p>
      <el-radio-group v-model="mode" style="margin-bottom:12px">
        <el-radio-button label="local">本地 XLSX 文件</el-radio-button>
        <el-radio-button label="wps">WPS 云表</el-radio-button>
      </el-radio-group>

      <!-- 本地模式:选文件 -->
      <div v-if="mode === 'local'" class="files">
        <div class="file-row">
          <el-button @click="pickFile('source')">选择补发表</el-button>
          <span :class="{ picked: sourceFile }">{{ sourceFile ? sourceFile.name : '未选择' }}</span>
        </div>
        <div class="file-row">
          <el-button @click="pickFile('target')">选择 GOFU补发表</el-button>
          <span :class="{ picked: targetFile }">{{ targetFile ? targetFile.name : '未选择' }}</span>
        </div>
      </div>
      <!-- WPS模式:填云文档URL -->
      <div v-else class="files">
        <el-input v-model="sourceUrl" placeholder="补发表 WPS 云文档链接(kdocs.cn/...)" style="margin-bottom:8px" />
        <el-input v-model="targetUrl" placeholder="GOFU补发表 WPS 云文档链接(kdocs.cn/...)" />
        <div style="margin-top:8px">
          <el-button :disabled="reship.running" :loading="reship.running" @click="loginWps">1. 登录 WPS(首次先点)</el-button>
        </div>
        <p class="wps-hint">首次用先点「登录 WPS」,在弹出浏览器里登录 kdocs.cn(慢慢等登完),再点下方「开始补发」——分开避免首次登录慢导致补发超时。结果就地写回云表,不下载本地文件。</p>
      </div>

      <el-button type="primary" :disabled="reship.running" :loading="reship.running" @click="start">
        开始补发
      </el-button>
      <el-alert v-if="reship.msg" :title="reship.msg" :closable="false" style="margin-top:12px"
        :type="reship.msgType === 'ok' ? 'success' : reship.msgType === 'err' ? 'error' : 'info'" />

      <!-- 完成后:本地模式下载结果表(WPS就地写回云表无本地文件) -->
      <div v-if="reship.done" class="downloads">
        <el-alert v-if="reship.redCount > 0" type="warning" :closable="false" style="margin-bottom:10px"
          :title="`有 ${reship.redCount} 单无法在快麦补发,已标红——需人工去拼多多补发。`" />
        <template v-if="reship.mode === 'local'">
          <p v-if="reship.redCount >= 0" class="dl-hint">结果表已按设置自动下载到浏览器默认目录;也可手动下载：</p>
          <el-button type="success" @click="reship.downloadResult('source')">下载补发表(标红版,含无法补发行)</el-button>
          <el-button @click="reship.downloadResult('target')">下载 GOFU补发表(迁移后)</el-button>
        </template>
        <p v-else class="dl-hint">WPS 云表模式:结果已就地写回云表(标红/迁移),打开云文档即可查看。</p>
      </div>
    </el-card>

    <!-- 逐行进度日志 -->
    <el-card v-if="reship.logs.length" class="sec">
      <template #header>处理进度（{{ reship.logs.length }} 条）</template>
      <div class="log-list">
        <div v-for="(r, i) in reship.logs" :key="i" class="log-row" :class="rowType(r)">
          <span class="log-msg">{{ r.message || r.code || JSON.stringify(r) }}</span>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.reship { max-width: 900px; }
.desc { color: #606266; font-size: 13px; line-height: 1.7; }
.files { margin: 12px 0; display: flex; flex-direction: column; gap: 8px; }
.file-row { display: flex; align-items: center; gap: 10px; font-size: 13px; }
.file-row span { color: #c0c4cc; }
.file-row span.picked { color: #303133; }
.downloads { margin-top: 12px; }
.wps-hint { font-size: 12px; color: #909399; margin: 8px 0 0; }
.dl-hint { font-size: 13px; color: #606266; margin: 0 0 8px; }
.sec { margin-top: 16px; }
.log-list { max-height: 420px; overflow-y: auto; }
.log-row { padding: 6px 10px; border-bottom: 1px solid #f0f2f5; font-size: 13px; }
.log-row.ok { color: #67c23a; }
.log-row.err { color: #f56c6c; background: #fef0f0; }
.log-row.skip { color: #909399; }
</style>
