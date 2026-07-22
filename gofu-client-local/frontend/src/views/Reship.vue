<script setup>
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { useReshipStore } from '@/stores/reship.js'
import { useSettingsStore } from '@/stores/settings.js'

// 售后·订单补发:读补发表→快麦ERP自动补发→失败标红→提示导出。备注/待定行迁移到GOFU补发表。
// ERP账号/补发表路径在【设置·订单补发】里配。第一期仅本地XLSX,不做拼多多补发/WPS。
const reship = useReshipStore()
const settings = useSettingsStore()

const sourcePath = computed(() => settings.settings.reshipSourcePath?.trim())
const targetPath = computed(() => settings.settings.reshipTargetPath?.trim())
const ready = computed(() => sourcePath.value && targetPath.value)

async function start() {
  if (!ready.value) { ElMessage.error('请先在「设置」里填补发表路径 + GOFU补发表路径 + ERP账号'); return }
  if (!confirm('将读取补发表,逐单在快麦 ERP 真实创建补发单(改线上业务数据)。确认开始?')) return
  await reship.run(sourcePath.value, targetPath.value)
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
      <template #header>快麦 ERP 订单补发（读补发表 → 逐单补发 → 失败标红）</template>
      <p class="desc">
        补发表 / GOFU补发表路径 与 ERP 账号在 <b>设置 · 订单补发</b> 配置。
        流程：已补发跳过 → 备注/待定行迁移到 GOFU 补发表 → 其余在快麦 ERP 创建补发单；无法补发的订单会在补发表中<b style="color:#f56c6c">整行标红</b>。
        <br />第一期仅本地 XLSX，不做拼多多补发 / WPS 云表。
      </p>
      <div class="paths">
        <div>补发表：<b>{{ sourcePath || '（未配置）' }}</b></div>
        <div>GOFU补发表：<b>{{ targetPath || '（未配置）' }}</b></div>
      </div>
      <el-button type="primary" :disabled="!ready || reship.running" :loading="reship.running" @click="start">
        开始补发
      </el-button>
      <el-alert v-if="reship.msg" :title="reship.msg" :closable="false" style="margin-top:12px"
        :type="reship.msgType === 'ok' ? 'success' : reship.msgType === 'err' ? 'error' : 'info'" />

      <!-- 失败标红提示(第一期只提示,导出后置) -->
      <el-alert v-if="reship.done && reship.redCount > 0" type="warning" :closable="false" style="margin-top:10px"
        :title="`有 ${reship.redCount} 单无法在快麦补发，已在补发表中标红。是否需要导出标红订单表？（导出功能第二期提供，当前可直接打开补发表查看标红行）`" />
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
.paths { font-size: 13px; margin: 10px 0; display: flex; flex-direction: column; gap: 4px; }
.sec { margin-top: 16px; }
.log-list { max-height: 420px; overflow-y: auto; }
.log-row { padding: 6px 10px; border-bottom: 1px solid #f0f2f5; font-size: 13px; }
.log-row.ok { color: #67c23a; }
.log-row.err { color: #f56c6c; background: #fef0f0; }
.log-row.skip { color: #909399; }
</style>
