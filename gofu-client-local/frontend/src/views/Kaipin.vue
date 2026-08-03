<script setup>
// 【开品模式】迪士尼素材库融合生图。物理隔离独立页面，删除时一把摘干净。
// 流程:上传产品图+填写信息 → 外观分析(kaipin_analyze) → 确认/编辑分析卡片
//      → 选迪士尼素材标签+生图(kaipin-generate) → 筛图（仅出图，不上新）
import { reactive, computed, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api.js'
import { useContextStore } from '@/stores/context.js'
import { useKaipinStore } from '@/stores/kaipin.js'

const ctxStore = useContextStore()
const kpStore = useKaipinStore()

const imgUrl = (r) => '/api/gen/img?ref=' + encodeURIComponent(r)

// ─── 分析输入（不存 store，切页后图片需重传，文字字段切页会丢失 —— 可接受）───
const input = reactive({
  imageA: null, imageAPreview: '',
  imageB: null, imageBPreview: '',
  productA: '', productB: '',
  selling: '',
  focus: 'cost', focusText: '',
  style: '', styleText: '',
})
const focusOptions = [
  { value: 'cost', label: '成本量产' }, { value: 'premium', label: '颜值溢价' },
  { value: 'disruptive', label: '颠覆创新' }, { value: 'custom', label: '自定义' },
]
const styleOptions = [
  { value: '', label: '不指定' }, { value: 'dopamine', label: '多巴胺' },
  { value: 'wood', label: '木元素' }, { value: 'cartoon', label: '卡通' },
  { value: 'ins', label: 'ins 风' }, { value: 'minimal', label: '极简' },
  { value: 'cyberpunk', label: '赛博朋克' }, { value: 'custom', label: '自定义' },
]

// ─── 局部进行中状态（不放 store，刷新/切页后这些进行中标志自然归零） ───
const analyzing = ref(false)
const generating = ref(false)

// ─── 标签列表（局部，onMounted 加载一次） ───
const tags = ref([])
const tagsErr = ref('')   // 非空=拉取标签失败(与"真的没素材"区分开)

// ─── 切页保留的状态统一读 kpStore ───
const mainImages = computed(() => (ctxStore.currentFor('kaipin')?.visual?.mainImages || []).filter(Boolean))
const maxPick = computed(() => Math.min(6, mainImages.value.length || 6))
const busy = computed(() => analyzing.value || generating.value)

// ─── 加载标签列表 ───
onMounted(async () => {
  // 恢复 context（切到别的页面再回来，current 被别页顶掉了，mainImages 会变空）
  const own = ctxStore.ownedId('kaipin') || kpStore.contextId
  if (own) {
    try { await ctxStore.adopt('kaipin', own) } catch (_) {}
  }
  await loadTags()
})

// 标签加载:失败与"真的没素材"必须分开报。原来 catch 只 console.warn,页面一律显示
// 「未找到标签，请先导入素材」——但请求失败(云端没起/连错库/网络断)时素材其实在，
// 这条提示会把人引到"再导一遍素材"的错方向(08.02 用户实际踩到)。
async function loadTags() {
  tagsErr.value = ''
  try {
    const d = await api.get('/api/disney/tags')
    tags.value = (d.tags || []).map(t => ({ value: t.tag, label: t.tag + '（' + t.count + '张）' }))
    if (tags.value.length && !kpStore.selectedTag) kpStore.selectedTag = tags.value[0].value
  } catch (e) {
    tagsErr.value = e.message || String(e)
    console.warn('[开品] 加载标签失败:', tagsErr.value)
  }
}

// ─── 图片选取 ───
function fileToB64(f) {
  return new Promise((resolve) => {
    const rd = new FileReader()
    rd.onload = () => resolve(rd.result)
    rd.readAsDataURL(f)
  })
}
async function pickImageA(e) {
  const f = e.target.files[0]; if (!f) return
  input.imageAPreview = await fileToB64(f); input.imageA = f
}
async function pickImageB(e) {
  const f = e.target.files[0]; if (!f) return
  input.imageBPreview = await fileToB64(f); input.imageB = f
}

// ─── 外观分析 ───
async function analyze() {
  if (!input.productA && !input.imageA) { ElMessage.warning('请至少填写产品A描述或上传图片'); return }
  analyzing.value = true; kpStore.analyzed = false; kpStore.analyzeMsg = '分析中（调用 Gemini，约 15-30 秒）…'; kpStore.analyzeMsgType = ''
  try {
    const form = new FormData()
    if (input.imageA) form.append('imageA', input.imageA)
    if (input.imageB) form.append('imageB', input.imageB)
    form.append('productA', input.productA || '')
    form.append('productB', input.productB || '')
    form.append('selling', input.selling || '')
    form.append('focus', input.focus || 'cost')
    if (input.focus === 'custom') form.append('focusText', input.focusText || '')
    form.append('style', input.style || '')
    if (input.style === 'custom') form.append('styleText', input.styleText || '')
    const d = await fetch('/api/kaipin_analyze', { method: 'POST', body: form }).then(r => r.json())
    if (d.error) throw new Error(d.error)
    kpStore.fields = (d.fields || []).map(f => ({ ...f }))  // 可编辑副本
    kpStore.analyzed = true
    kpStore.analyzeMsg = '✓ 分析完成，可编辑下方卡片后选标签生图。'; kpStore.analyzeMsgType = 'ok'
  } catch (e) {
    kpStore.analyzeMsg = '分析失败：' + e.message; kpStore.analyzeMsgType = 'err'
  } finally { analyzing.value = false }
}

// ─── 上传产品图到云端，获取 imageRef ───
async function uploadUserImage() {
  if (!input.imageA) throw new Error('请先上传产品图A')
  const ext = input.imageA.name.toLowerCase().endsWith('.png') ? 'png' : 'jpg'
  const b64 = (input.imageAPreview || '').replace(/^data:[^;]+;base64,/, '')
  const d = await api.post('/api/gen/upload-image', { base64: b64, ext })
  if (!d.imageRef) throw new Error('图片上传失败')
  return d.imageRef
}

// ─── 先建 context 再生图 ───
async function startGenerate() {
  if (!kpStore.selectedTag) { ElMessage.warning('请选择迪士尼标签'); return }
  if (!input.imageA && !input.productA) { ElMessage.warning('请上传产品图A或填写产品描述'); return }
  generating.value = true; kpStore.genDone = false; kpStore.picked = []; kpStore.cloudTaskId = ''
  kpStore.genPct = 0; kpStore.genPhase = '准备中…'; kpStore.genMsg = ''; kpStore.genMsgType = ''
  try {
    // 1) 上传用户图
    kpStore.genPhase = '上传产品图…'; kpStore.genPct = 5
    const userImageRef = await uploadUserImage()

    // 2) 建 context（用卖点/品类信息）
    kpStore.genPhase = '建立商品档案…'; kpStore.genPct = 15
    const ctx = await api.post('/api/context', {
      mainItem: input.productA || '开品商品',
      visual: { mainImages: [], whiteImages: [userImageRef], title: input.productA || '开品商品', sellingPoints: [] }
    })
    const contextId = ctx.id
    kpStore.contextId = contextId
    await ctxStore.adopt('kaipin', contextId)

    // 3) 调云端生图
    kpStore.genPhase = '生成中…'; kpStore.genPct = 20
    const genPromptFull = buildGenPrompt()
    const d = await api.post('/api/flow/kaipin-generate', {
      contextId, userImageRef, tag: kpStore.selectedTag, n: kpStore.n,
      count: kpStore.genCount, prompt: genPromptFull,
    })
    if (d.error) throw new Error(d.error)
    kpStore.cloudTaskId = d.taskId

    // 4) 轮询
    await pollGenTask(d.taskId, d.total || kpStore.genCount)
  } catch (e) {
    kpStore.genMsg = '生图失败：' + e.message; kpStore.genMsgType = 'err'
  } finally { generating.value = false }
}

function buildGenPrompt() {
  if (kpStore.genPrompt.trim()) return kpStore.genPrompt.trim()
  const card = kpStore.fields.map(f => f.key + ': ' + f.value).join('；')
  return `以第一张图片为产品主体，将迪士尼参考图案的造型语言融入产品外观或背景场景中，保持产品功能结构清晰，整体活泼可爱，适合电商主图展示。${card ? '外观设计参考：' + card.substring(0, 200) : ''}`
}

async function pollGenTask(taskId, total) {
  for (let tries = 0; tries < 1200; tries++) {
    await new Promise((r) => setTimeout(r, 2500))
    let t
    try { t = await api.get('/api/flow/task/' + taskId) } catch (_) { continue }
    const done = t.progress || 0
    kpStore.genPct = 20 + Math.round(78 * done / Math.max(1, total))
    const succ = t.successCount ?? 0
    kpStore.genPhase = `生图 已尝试${done}/${total}(成功${succ})…` + (done > 0 && succ === 0 ? ' ⚠ 全部失败,请检查生图服务/账户余额' : '')
    // 本页还持有 current 才刷(用户切走了就别把别页顶掉)
    if (kpStore.contextId && ctxStore.origin === 'kaipin') { try { await ctxStore.load(kpStore.contextId) } catch (_) {} }
    if (t.status === 'done' || t.status === 'error') {
      if (t.status === 'error') throw new Error(t.error || `云端生图失败（已尝试${done}张，成功${t.successCount??0}张）——若账户欠费请充值 api.linapi.net`)
      kpStore.genPct = 100; kpStore.genDone = true
      kpStore.genMsg = `✓ 生图完成，共 ${mainImages.value.length} 张。请在下方筛选图片。`
      kpStore.genMsgType = 'ok'; return
    }
  }
  throw new Error('生图轮询超时')
}

async function cancelGen() {
  if (!kpStore.cloudTaskId) return
  try { await api.post('/api/flow/cancel/' + kpStore.cloudTaskId, {}) } catch (_) {}
  kpStore.genMsg = '已请求停止生图。'; kpStore.genMsgType = ''
}

// ─── 筛图 ───
function toggle(key) {
  const i = kpStore.picked.indexOf(key)
  if (i >= 0) kpStore.picked.splice(i, 1)
  else {
    if (kpStore.picked.length >= maxPick.value) { ElMessage.warning(`最多选 ${maxPick.value} 张`); return }
    kpStore.picked.push(key)
  }
}
const isPicked = (key) => kpStore.picked.includes(key)

</script>

<template>
  <div class="kaipin-page">
    <h2>开品模式 <span class="tag">迪士尼素材融合</span></h2>

    <!-- ① 分析输入 -->
    <el-card class="step">
      <template #header>① 上传产品图 + 外观分析</template>
      <el-row :gutter="16">
        <el-col :span="12">
          <div class="img-upload-area" @click="$refs.inputA.click()">
            <img v-if="input.imageAPreview" :src="input.imageAPreview" class="preview-img" />
            <span v-else class="upload-hint">点击上传产品A图片（功能本体）</span>
          </div>
          <input ref="inputA" type="file" accept="image/*" style="display:none" @change="pickImageA" />
          <el-input v-model="input.productA" placeholder="产品A描述（名称/型号/材质等）" class="mt8" />
        </el-col>
        <el-col :span="12">
          <div class="img-upload-area secondary" @click="$refs.inputB.click()">
            <img v-if="input.imageBPreview" :src="input.imageBPreview" class="preview-img" />
            <span v-else class="upload-hint">（可选）产品B：造型灵感/设计语言参考</span>
          </div>
          <input ref="inputB" type="file" accept="image/*" style="display:none" @change="pickImageB" />
          <el-input v-model="input.productB" placeholder="产品B描述（可选）" class="mt8" />
        </el-col>
      </el-row>
      <el-input v-model="input.selling" type="textarea" :rows="2" placeholder="核心卖点/目标人群/使用场景（可选）" class="mt8" />
      <el-row :gutter="12" class="mt8">
        <el-col :span="12">
          <span class="label">方案侧重</span>
          <el-select v-model="input.focus" size="small" style="width:120px">
            <el-option v-for="o in focusOptions" :key="o.value" :value="o.value" :label="o.label" />
          </el-select>
          <el-input v-if="input.focus === 'custom'" v-model="input.focusText" placeholder="自定义侧重" size="small" class="ml8" style="width:160px" />
        </el-col>
        <el-col :span="12">
          <span class="label">视觉风格</span>
          <el-select v-model="input.style" size="small" style="width:120px">
            <el-option v-for="o in styleOptions" :key="o.value" :value="o.value" :label="o.label" />
          </el-select>
          <el-input v-if="input.style === 'custom'" v-model="input.styleText" placeholder="自定义风格" size="small" class="ml8" style="width:160px" />
        </el-col>
      </el-row>
      <div class="actions mt8">
        <el-button type="primary" :loading="analyzing" :disabled="busy" @click="analyze">
          {{ analyzing ? '分析中…' : '开始外观分析' }}
        </el-button>
      </div>
      <p v-if="kpStore.analyzeMsg" :class="['msg', kpStore.analyzeMsgType]">{{ kpStore.analyzeMsg }}</p>
    </el-card>

    <!-- ② 分析卡片编辑 + 选标签 + 生图 -->
    <el-card v-if="kpStore.analyzed" class="step">
      <template #header>② 编辑分析卡片 + 选迪士尼标签 + 生图</template>
      <div class="fields-grid">
        <div v-for="(field, i) in kpStore.fields" :key="i" class="field-item">
          <span class="field-key">{{ field.key }}</span>
          <el-input v-model="field.value" type="textarea" :rows="2" size="small" />
        </div>
      </div>
      <el-divider />
      <el-row :gutter="12" align="middle" class="gen-opts">
        <el-col :span="8">
          <span class="label">迪士尼标签</span>
          <el-select v-model="kpStore.selectedTag" size="small" style="width:160px" placeholder="选标签">
            <el-option v-for="t in tags" :key="t.value" :value="t.value" :label="t.label" />
          </el-select>
          <!-- 拉取失败 ≠ 没素材:失败时给出真实原因+重试,不再误导用户去重新导素材 -->
          <span v-if="tagsErr" class="hint">
            （读取素材库失败：{{ tagsErr }}——素材可能是在的，先确认云端 5020 已启动且连的是同一个 gofu-cloud.db
            <el-button link type="primary" size="small" @click="loadTags">重试</el-button>）
          </span>
          <span v-else-if="!tags.length" class="hint">（素材库为空，请先导入迪士尼素材）</span>
        </el-col>
        <el-col :span="4">
          <span class="label">抽样张数</span>
          <el-input-number v-model="kpStore.n" :min="1" :max="10" size="small" controls-position="right" style="width:80px" />
        </el-col>
        <el-col :span="4">
          <span class="label">生成张数</span>
          <el-input-number v-model="kpStore.genCount" :min="1" :max="20" size="small" controls-position="right" style="width:80px" />
        </el-col>
        <el-col :span="8">
          <div class="actions">
            <el-button type="primary" :loading="generating" :disabled="busy" @click="startGenerate">
              {{ generating ? '生图中…' : '开始生图' }}
            </el-button>
            <el-button v-if="generating && kpStore.cloudTaskId" @click="cancelGen">停止</el-button>
          </div>
        </el-col>
      </el-row>
      <el-input v-model="kpStore.genPrompt" type="textarea" :rows="2" placeholder="（可选）自定义生图 Prompt，留空则按分析卡片自动构造" class="mt8" />
      <el-progress v-if="generating || kpStore.genPct > 0" :percentage="kpStore.genPct" :status="kpStore.genDone ? 'success' : ''" class="mt8" />
      <p v-if="kpStore.genPhase && generating" class="phase">{{ kpStore.genPhase }}</p>
      <p v-if="kpStore.genMsg" :class="['msg', kpStore.genMsgType]">{{ kpStore.genMsg }}</p>
    </el-card>

    <!-- ③ 筛图 -->
    <el-card v-if="mainImages.length" class="step">
      <template #header>③ 筛选主图（已选 {{ kpStore.picked.length }} / {{ maxPick }}，共 {{ mainImages.length }} 张）</template>
      <div class="grid">
        <div v-for="(m, i) in mainImages" :key="i" :class="['cell', { on: isPicked(m) }]" @click="toggle(m)">
          <el-image :src="imgUrl(m)" fit="cover" loading="lazy" />
          <span v-if="isPicked(m)" class="badge">{{ kpStore.picked.indexOf(m) + 1 }}</span>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.kaipin-page { padding: 16px; }
.tag { font-size: 12px; background: #fef0f0; color: #e6553a; padding: 2px 8px; border-radius: 10px; vertical-align: middle; }
.step { margin-bottom: 16px; }
.mt8 { margin-top: 8px; }
.ml8 { margin-left: 8px; }
.label { font-size: 13px; color: #606266; margin-right: 6px; }
.hint { font-size: 12px; color: #f56c6c; margin-left: 6px; }
.img-upload-area { border: 2px dashed #dcdfe6; border-radius: 8px; height: 140px; display: flex; align-items: center; justify-content: center; cursor: pointer; overflow: hidden; background: #fafafa; }
.img-upload-area.secondary { background: #f5f7fa; }
.img-upload-area:hover { border-color: #409eff; }
.preview-img { width: 100%; height: 100%; object-fit: contain; }
.upload-hint { font-size: 13px; color: #909399; text-align: center; padding: 8px; }
.fields-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 10px; }
.field-item { display: flex; flex-direction: column; gap: 4px; }
.field-key { font-size: 12px; font-weight: 600; color: #303133; }
.gen-opts { align-items: center; }
.actions { display: flex; gap: 8px; align-items: center; }
.actions.foot { margin-top: 12px; }
.phase { color: #909399; font-size: 13px; margin: 6px 0 0; }
.msg { margin: 8px 0 0; font-size: 13px; }
.msg.ok { color: #67c23a; }
.msg.err { color: #f56c6c; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(120px, 1fr)); gap: 8px; margin-top: 8px; }
.cell { position: relative; cursor: pointer; border: 3px solid transparent; border-radius: 6px; overflow: hidden; aspect-ratio: 1; }
.cell.on { border-color: #67c23a; }
.cell .el-image { width: 100%; height: 100%; }
.badge { position: absolute; top: 4px; right: 4px; background: #67c23a; color: #fff; width: 22px; height: 22px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 13px; font-weight: bold; }</style>

