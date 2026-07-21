<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api.js'
import { useBatchStore } from '@/stores/batch.js'
import { useContextStore } from '@/stores/context.js'
import { useSettingsStore } from '@/stores/settings.js'
import { useGen } from '@/composables/useGen.js'

// P2-g:文件夹批量上新(平移 batch.js)。重路径(补生SKU图/定价)复用 useGen+contextStore。
const batch = useBatchStore()
const ctxStore = useContextStore()
const settings = useSettingsStore()
const { gen, runSkuImages, fillCostAndPrice, resolveTemplateId } = useGen()

let genBusy = false // 共用生图队列锁

function pickFolder() {
  const inp = document.createElement('input')
  inp.type = 'file'; inp.multiple = true; inp.webkitdirectory = true
  inp.onchange = () => batch.uploadTree([...inp.files]).then(afterPreflight).catch((e) => batch.setMsg('上传失败：' + e.message, 'err'))
  inp.click()
}

// 预检后自动链:预览首品 + 缺图商品自动补生(设置可关)
async function afterPreflight() {
  if (!batch.outcomes.length) return
  if (settings.settings.batchAutoPreview !== false) { try { await batch.previewOne(0) } catch (_) {} }
  if (settings.settings.batchAutoGen !== false) await autoGenAll()
}

// 确保某商品有 context(复用 gen-sku 导入链)
async function ensureContext(o) {
  if (o.contextId) return o.contextId
  const d = await api.post('/api/semi-auto/gen-sku', { folderPath: o.folderPath })
  if (d.error) throw new Error(d.error)
  if (!d.importId) throw new Error('未返回 importId')
  while (true) {
    await new Promise((r) => setTimeout(r, 1500))
    let t
    try { t = await api.get('/api/semi-auto/import-progress/' + d.importId) } catch (_) { continue }
    o.taskMsg = `建档 ${t.pct || 0}% · ${t.phase || '处理中…'}`
    if (t.done) {
      if (t.error) throw new Error(t.error)
      o.contextId = t.result?.contextId || ''
      o.warnings = t.result?.warnings || []
      if (!o.contextId) throw new Error('未建出 contextId')
      return o.contextId
    }
  }
}

// 单商品:建context→补生缺SKU图→定价→(按设置)上新。源 batchGenSku。
async function genSku(idx) {
  const o = batch.outcomes[idx]
  if (!o || !o.folderPath) { batch.setMsg('该商品无文件夹路径，无法生成', 'err'); return }
  if (genBusy) { batch.setMsg('已有一个AI生成在跑，请等它完成(共用生图队列)', 'err'); return }
  genBusy = true
  o.taskStatus = 'gen'; o.taskMsg = 'AI生成中…（建档→拉白底→出方案）'
  try {
    const contextId = await ensureContext(o)
    await ctxStore.load(contextId)
    gen.profitRate = batch.profitRate // 批量流利润率
    const plan0 = ctxStore.current?.structure?.plans?.[0] || null
    const missImg = plan0 ? (plan0.items || []).filter((it) => !it.imgDir).length : 0
    const hasWhite = (ctxStore.current?.visual?.whiteImages || []).length > 0
    if (missImg > 0 && hasWhite) {
      o.taskMsg = `补生 ${missImg} 张SKU图中…（进度见右侧）`
      await runSkuImages(settings.antipriceTemplates) // step2 补生
    } else if (missImg > 0 && !hasWhite) {
      throw new Error('缺SKU图但无白底图可参考(快麦也没拉到)，请补白底图后重试')
    }
    o.taskMsg = '自动定价中…'
    await fillCostAndPrice()
    const zero = (ctxStore.current?.structure?.plans?.[0]?.items || []).filter((it) => !(it.groupPrice > 0)).length
    if (zero > 0) { o.taskStatus = 'error'; o.taskMsg = `✗ 有 ${zero} 个SKU定不出价(快麦缺进价)，请补进价后重试`; return }
    o.missing = (o.missing || []).filter((m) => !m.includes('缺图'))
    o.title = ctxStore.current?.visual?.title || ''
    // 存本行方案(布局)供预览
    o._plans = (ctxStore.current?.structure?.plans || []).map((p) => ({
      planName: p.planName, description: p.description,
      items: (p.items || []).map((it) => ({ ...it, _img: it.imgDir ? '/api/gen/img?ref=' + encodeURIComponent(it.imgDir) : '' })),
    }))
    if (batch.preview && batch.preview.name === (o.mainItem || o.productName)) {
      batch.preview.title = o.title; batch.preview.contextId = contextId
      batch.preview.plans = o._plans; batch.preview.planIdx = 0
      batch.preview.sku = (o._plans[0]?.items || []).map((it) => it._img).filter(Boolean)
    }
    // 按设置决定是否自动上新
    if (!settings.settings.batchAutoList) {
      o.taskStatus = 'done'; o.status = 'ready'
      o.taskMsg = `✓ 已生图+定价，可点「开始批量上新」上到「${o.shopName}」`
      return
    }
    o.taskMsg = `从context上新到「${o.shopName}」…`
    const ld = await api.post('/api/listing/from-context', { contextId, planIndex: 0, dryRun: false, storeProfile: o.shopProfile || '' })
    if (ld.error || !ld.taskId) throw new Error(ld.error || '上新未返回taskId')
    o.taskId = ld.taskId
    batch.pollTask(idx)
    o.taskMsg = '✓ 已启动上新，进度见下…'
  } catch (e) {
    o.taskStatus = 'error'; o.taskMsg = '✗ 生成失败：' + e.message
  } finally {
    genBusy = false
  }
}

// 全自动补生:对所有缺图·可AI生成的商品串行跑
async function autoGenAll() {
  const targets = batch.outcomes.map((o, i) => ({ o, i })).filter((x) => x.o.status === 'sku_gen_available' && !x.o.taskStatus)
  if (!targets.length) return
  batch.setMsg(`自动为 ${targets.length} 个缺图商品生成SKU图…（串行）`, '')
  targets.forEach(({ o }, k) => { if (k > 0) { o.taskStatus = 'queued'; o.taskMsg = '排队等待自动补生…' } })
  for (const { i, o } of targets) { o.taskStatus = ''; try { await genSku(i) } catch (_) {} }
  batch.setMsg('批量自动流程完成。' + (settings.settings.batchAutoList ? '已按设置自动上新。' : '未开自动上新，请核对后上。'), 'ok')
}

async function preview(idx) { try { await batch.previewOne(idx) } catch (e) { batch.setMsg('预览失败：' + e.message, 'err') } }
async function run() { try { await batch.run() } catch (e) { batch.setMsg('批量上新失败：' + e.message, 'err') } }

const previewPlanIdx = ref(0)
</script>

<template>
  <div class="batch">
    <h2>批量上新</h2>
    <div class="cols">
      <div class="left">
        <el-card>
          <template #header>文件夹批量上新（多套图 → 多店，串行连续）</template>
          <p class="desc">选大文件夹（结构：店铺/商品/角色子目录）。上传后自动预检、预览首品、缺图商品自动补生（设置可关）。</p>
          <div class="actions">
            <el-button :disabled="batch.busy" @click="pickFolder">📁 选择大文件夹</el-button>
            <span v-if="batch.folderName" class="picked">已选：<b>{{ batch.folderName }}</b>（{{ batch.fileCount }} 张图）</span>
          </div>
          <div class="profit">
            <span>本批利润率 {{ Math.round(batch.profitRate * 100) }}%</span>
            <el-slider v-model="batch.profitRate" :min="0.2" :max="0.8" :step="0.01" style="width:180px" />
          </div>
          <el-button type="primary" :disabled="!batch.canRun || batch.busy" @click="run">开始批量上新（{{ batch.readyCount }} 个齐全）</el-button>
          <el-progress v-if="gen.running" :percentage="gen.progress" style="margin-top:10px" />
          <el-alert v-if="batch.msg" :title="batch.msg" :closable="false" style="margin-top:12px"
            :type="batch.msgType === 'ok' ? 'success' : batch.msgType === 'err' ? 'error' : 'info'" />
        </el-card>

        <!-- SKU 方案布局(带定价):放左侧,与右侧图预览均衡 -->
        <el-card v-if="batch.preview && batch.preview.plans && batch.preview.plans.length" class="sec">
          <template #header>SKU 方案 / 定价 · {{ batch.preview.name }}</template>
          <el-tabs v-model="previewPlanIdx">
            <el-tab-pane v-for="(pl, i) in batch.preview.plans" :key="i" :label="`方案${i + 1}`" :name="i" />
          </el-tabs>
          <el-table :data="batch.preview.plans[previewPlanIdx]?.items || []" size="small" max-height="320">
            <el-table-column label="图" width="56">
              <template #default="{ row }"><img v-if="row._img" :src="row._img" class="sku-thumb" /><span v-else class="miss">缺</span></template>
            </el-table-column>
            <el-table-column label="SKU" prop="skuDisplayName" show-overflow-tooltip />
            <el-table-column label="成本" width="70"><template #default="{ row }">{{ (row.cost || 0).toFixed(2) }}</template></el-table-column>
            <el-table-column label="拼单价" width="80"><template #default="{ row }">{{ (row.groupPrice || 0).toFixed(2) }}</template></el-table-column>
          </el-table>
        </el-card>

        <!-- 按店铺分组的商品列表 -->
        <el-card v-for="g in batch.byShop" :key="g.shop" class="sec">
          <template #header>🏬 {{ g.shop }}（{{ g.rows.length }} 个商品）</template>
          <div v-for="row in g.rows" :key="row._i" class="prod-row">
            <div class="prod-main">
              <el-tag :type="batch.statusClass(row.status) === 'ok' ? 'success' : batch.statusClass(row.status) === 'err' ? 'danger' : 'warning'" size="small">
                {{ batch.statusText(row.status) }}
              </el-tag>
              <span class="prod-name">{{ row.mainItem || row.productName }}</span>
              <el-button size="small" text @click="preview(row._i)">👁 预览</el-button>
              <el-button v-if="row.status === 'sku_gen_available'" size="small" type="primary" :disabled="row.taskStatus === 'gen'" @click="genSku(row._i)">AI生成</el-button>
            </div>
            <div v-if="row.taskMsg" class="prod-msg" :class="row.taskStatus">{{ row.taskMsg }}</div>
            <div v-if="(row.missing || []).length" class="prod-miss">
              <div v-for="(m, k) in row.missing" :key="k">· {{ m }}</div>
            </div>
          </div>
        </el-card>
      </div>

      <!-- 右栏预览 -->
      <div class="right">
        <el-card class="preview">
          <template #header>预览{{ batch.preview ? '：' + batch.preview.name : '' }}</template>
          <el-empty v-if="!batch.preview" description="上传文件夹后，点商品👁预览" :image-size="60" />
          <template v-else>
            <div class="pv-meta">
              <el-tag size="small">{{ batch.preview.shop }}</el-tag>
              <el-tag size="small" type="info">{{ batch.preview.category }}</el-tag>
            </div>
            <el-input v-if="batch.preview.contextId" v-model="batch.preview.title" type="textarea" :rows="2"
              placeholder="标题" @blur="batch.saveTitle()" style="margin:10px 0" />
            <div v-if="batch.preview.main.length" class="psec">
              <div class="psec-t">主图（{{ batch.preview.main.length }}）</div>
              <div class="pgrid"><img v-for="(m, i) in batch.preview.main" :key="i" :src="m" /></div>
            </div>
            <div v-if="batch.preview.detail.length" class="psec">
              <div class="psec-t">详情图（{{ batch.preview.detail.length }}）</div>
              <div class="pgrid"><img v-for="(d, i) in batch.preview.detail" :key="i" :src="d" /></div>
            </div>
            <div v-if="batch.preview.sku.length" class="psec">
              <div class="psec-t">SKU图（{{ batch.preview.sku.length }}）</div>
              <div class="pgrid"><img v-for="(s, i) in batch.preview.sku" :key="i" :src="s" /></div>
            </div>
            <div v-if="batch.preview.white.length" class="psec">
              <div class="psec-t">白底图（{{ batch.preview.white.length }}）</div>
              <div class="pgrid pgrid-sm"><img v-for="(w, i) in batch.preview.white" :key="i" :src="w" /></div>
            </div>
          </template>
        </el-card>
      </div>
    </div>
  </div>
</template>

<style scoped>
.batch { max-width: 1400px; }
.cols { display: flex; gap: 16px; align-items: flex-start; }
.left { flex: 1 1 0; display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.right { flex: 1 1 0; position: sticky; top: 16px; min-width: 0; }
.desc { color: #606266; font-size: 13px; line-height: 1.6; }
.actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.picked { font-size: 13px; }
.profit { display: flex; align-items: center; gap: 12px; margin: 10px 0; font-size: 13px; }
.prod-row { padding: 8px 0; border-bottom: 1px solid #f0f2f5; }
.prod-main { display: flex; align-items: center; gap: 8px; }
.prod-name { flex: 1; font-size: 13px; }
.prod-msg { font-size: 12px; color: #606266; margin-top: 4px; }
.prod-msg.error { color: #f56c6c; }
.prod-msg.done { color: #67c23a; }
.prod-miss { font-size: 12px; color: #e6a23c; margin-top: 2px; }
.pv-meta { display: flex; gap: 8px; }
.psec { margin-top: 14px; }
.psec-t { font-size: 13px; color: #909399; margin-bottom: 8px; }
.pgrid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.pgrid img { width: 100%; border: 1px solid #ebeef5; border-radius: 4px; }
.pgrid-sm { grid-template-columns: repeat(6, 1fr); }
.sku-thumb { width: 40px; height: 40px; object-fit: contain; border: 1px solid #ebeef5; border-radius: 4px; }
.miss { color: #c0c4cc; font-size: 11px; }
</style>
