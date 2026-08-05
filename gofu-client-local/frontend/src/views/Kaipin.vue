<script setup>
// 【开品模式】迪士尼素材库融合生图。物理隔离独立页面，删除时一把摘干净。
// 流程:上传产品图+填写信息 → 外观分析(kaipin_analyze) → 确认/编辑分析卡片
//      → 选迪士尼素材标签+生图(kaipin-generate) → 筛图（仅出图，不上新）
import { reactive, computed, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api.js'
import { useContextStore } from '@/stores/context.js'
import { useKaipinStore } from '@/stores/kaipin.js'
import { useImageDownload } from '@/composables/useImageDownload.js'

const ctxStore = useContextStore()
const kpStore = useKaipinStore()
const { downloadMany, downloading } = useImageDownload()

const imgUrl = (r) => '/api/gen/img?ref=' + encodeURIComponent(r)

// ─── 分析输入 ───
// 08.05 修（用户反馈"上传白底图点分析后，切到别的模式再回来白底图不见了"）：
//   原来整个 input 是组件内的 reactive，切页组件卸载就全丢（图片和文字字段一起丢）。
//   现在都放进 kpStore —— 切页回来照旧；store 只在内存、不持久化，F5 仍是干净重来
//   （L6 决定，见 kaipin.js 末尾注释）。
//   存的是 b64 而非 File 对象：File 没法从 store 恢复，而上传只需要 b64+扩展名，
//   analyze 的 FormData 可由 b64 重建 File（见 b64ToFile）。
//   `input` 保留为指向 store 的代理，模板里的 input.xxx 一律不用改。
const input = kpStore
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
// 读 conceptImages(开品设计提案)，不是 mainImages(电商主图)——开品产出的是"还不存在的产品"的
// 设计方案图，字段语义见 gofu-shared VisualContent.conceptImages。
const conceptImages = computed(() => (ctxStore.currentFor('kaipin')?.visual?.conceptImages || []).filter(Boolean))
// 筛图不再限 6 张：开品是挑设计方案，生 100 张只能选 6 张没道理（原来写死 Math.min(6,...)）。
const maxPick = computed(() => conceptImages.value.length)
// 大图预览列表提到 computed 算一次。原来写在 v-for 的 :preview-src-list 里，
// 每个格子每次渲染都重算一遍全量数组——100 张就是每次 tick 一万次 encodeURIComponent，
// 而生成期间轮询 2.5s 一跳，页面会明显卡。
const previewList = computed(() => conceptImages.value.map(imgUrl))
// 粗估耗时：实测单张 quality=low 80~150s(取中值~115s)，后端 8 张一批并行 → 批数 × 约2分钟。
const etaMinutes = computed(() => Math.max(2, Math.ceil(kpStore.genCount / 8) * 2))
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
    // 不再自动选中首个标签——那会让「不使用贴纸」这个选项永远选不上，
    // 用户想走「产品A+产品B 碰撞」的原始路径就被贴纸抢走了。默认留空，用户自己选。
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
// 只存 b64 + 扩展名 + 原文件名（不存 File 对象，它没法跨切页从 store 恢复）
async function pickImageA(e) {
  const f = e.target.files[0]; if (!f) return
  input.imageAPreview = await fileToB64(f)
  input.imageAExt = f.name.toLowerCase().endsWith('.png') ? 'png' : 'jpg'
  input.imageAName = f.name
}
async function pickImageB(e) {
  const f = e.target.files[0]; if (!f) return
  input.imageBPreview = await fileToB64(f)
  input.imageBExt = f.name.toLowerCase().endsWith('.png') ? 'png' : 'jpg'
  input.imageBName = f.name
}

/** 从 b64 dataURL 重建 File，供 analyze 的 FormData 用（切页回来后已无原 File 对象）。 */
function b64ToFile(dataUrl, name, ext) {
  if (!dataUrl) return null
  const b64 = dataUrl.replace(/^data:[^;]+;base64,/, '')
  const bin = atob(b64)
  const buf = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) buf[i] = bin.charCodeAt(i)
  return new File([buf], name || ('image.' + ext), { type: ext === 'png' ? 'image/png' : 'image/jpeg' })
}

// ─── 外观分析 ───
async function analyze() {
  // 判"有没有图"改看 b64（不再看 File 对象，切页回来后只剩 b64）
  if (!input.productA && !input.imageAPreview) { ElMessage.warning('请至少填写产品A描述或上传图片'); return }
  analyzing.value = true; kpStore.analyzed = false; kpStore.analyzeMsg = '分析中（调用 Gemini，约 15-30 秒）…'; kpStore.analyzeMsgType = ''
  try {
    const form = new FormData()
    // 由 b64 重建 File（切页回来后原 File 已不在）
    const fA = b64ToFile(input.imageAPreview, input.imageAName, input.imageAExt)
    const fB = b64ToFile(input.imageBPreview, input.imageBName, input.imageBExt)
    if (fA) form.append('imageA', fA)
    if (fB) form.append('imageB', fB)
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

// ─── 上传图片到云端，获取 imageRef。产品A、产品B 共用。 ───
// 08.05：入参从 (File, b64) 改成 (b64, ext)——切页回来后已无 File 对象，扩展名单独存在 store。
async function uploadImage(previewB64, ext, label) {
  if (!previewB64) throw new Error(`请先上传${label}`)
  const b64 = previewB64.replace(/^data:[^;]+;base64,/, '')
  const d = await api.post('/api/gen/upload-image', { base64: b64, ext })
  if (!d.imageRef) throw new Error(`${label}上传失败`)
  return d.imageRef
}

// ─── 先建 context 再生图 ───
async function startGenerate() {
  // 标签不再必选：开品的原始玩法是「产品A + 产品B 碰撞出新产品」，贴纸是另一条路。
  // 两条至少要有一条，否则没有任何可碰撞的参考。
  if (!kpStore.selectedTag && !input.imageBPreview) {
    ElMessage.warning('请选择迪士尼标签，或上传产品B参考图（至少要有一个碰撞参考）'); return
  }
  if (!input.imageAPreview) { ElMessage.warning('请上传产品A白底图（生图必须有产品本体）'); return }
  generating.value = true; kpStore.genDone = false; kpStore.picked = []; kpStore.cloudTaskId = ''
  kpStore.genPct = 0; kpStore.genPhase = '准备中…'; kpStore.genMsg = ''; kpStore.genMsgType = ''
  try {
    // 1) 上传产品A（必须）+ 产品B（可选，作造型语言参考进生图）
    kpStore.genPhase = '上传产品图…'; kpStore.genPct = 5
    const userImageRef = await uploadImage(input.imageAPreview, input.imageAExt, '产品A图')
    const refImageRef = input.imageBPreview
      ? await uploadImage(input.imageBPreview, input.imageBExt, '产品B图') : ''

    // 2) 建 context（用卖点/品类信息）
    kpStore.genPhase = '建立商品档案…'; kpStore.genPct = 15
    const ctx = await api.post('/api/context', {
      mainItem: input.productA || '开品商品',
      visual: {
        mainImages: [], conceptImages: [], whiteImages: [userImageRef],
        title: input.productA || '开品商品', sellingPoints: []
      }
    })
    const contextId = ctx.id
    kpStore.contextId = contextId
    await ctxStore.adopt('kaipin', contextId)

    // 3) 调云端生图
    // 08.04 D-2：**不再由前端拼 prompt**，只把用户手改过的分析卡原文传过去，组装全交后端。
    //   为什么改：原来前端 buildGenPrompt() 拼好整段传过来，后端一收到 prompt 就把自己那几个
    //   KAIPIN_* 常量整段替换掉 → 那些常量实际是死的，两边"改一处要同步另一处"的注释保护不了任何东西，
    //   已经漂移出三处（第三张图说明的位置、多出的【外观设计参考】段、分析卡被砍到 200 字）。
    //   最要紧的是第三处：分析卡是唯一承载你设计意图的文本，却被挂在整串最末尾（权威最低位）
    //   且砍到 200 字，多字段的卡后面几个字段直接丢——这很可能就是"改了分析卡但出图不体现"的真因。
    //   现在后端把它放在主体锁之后、按字段边界截断（见 FlowController.clampDesignNote）。
    // 各字段用**换行**连接（不是分号）：后端按行截断，换行才能保证不把某个字段砍成半句。
    kpStore.genPhase = '生成中…'; kpStore.genPct = 20
    const designNote = (Array.isArray(kpStore.fields) ? kpStore.fields : [])
      .filter((f) => f && f.value && String(f.value).trim())
      .map((f) => f.key + '：' + String(f.value).trim())
      .join('\n')
    const d = await api.post('/api/flow/kaipin-generate', {
      contextId, userImageRef, refImageRef, tag: kpStore.selectedTag,
      count: kpStore.genCount, designNote,
      // 高级自定义（kpStore.genPrompt）仍走 prompt 入参：显式传了后端就整段照用、不插分析卡。
      // 目前界面没有这个输入框，故实际恒为空；留着接口是为了直连 API 调试时能整段覆盖。
      ...(kpStore.genPrompt?.trim() ? { prompt: kpStore.genPrompt.trim() } : {}),
    })
    if (d.error) throw new Error(d.error)
    kpStore.cloudTaskId = d.taskId
    // 后端降级提示（如标签下没素材、只用产品B跑）要让人看见，别静默改变行为
    if (d.warning) ElMessage.warning(d.warning)
    if (kpStore.selectedTag && typeof d.sampledCount === 'number' && d.sampledCount < kpStore.genCount) {
      ElMessage.info(`该标签实际抽到 ${d.sampledCount} 张素材（少于 ${kpStore.genCount} 张），素材会被循环使用`)
    }

    // 4) 轮询
    await pollGenTask(d.taskId, d.total || kpStore.genCount)
  } catch (e) {
    kpStore.genMsg = '生图失败：' + e.message; kpStore.genMsgType = 'err'
  } finally { generating.value = false }
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
      kpStore.genMsg = `✓ 生图完成，共 ${conceptImages.value.length} 张。请在下方筛选并下载。`
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
          <el-select v-model="kpStore.selectedTag" size="small" style="width:160px" placeholder="不使用贴纸" clearable>
            <!-- 空值=不用贴纸,走「产品A+产品B 碰撞」这条原始路径 -->
            <el-option value="" label="不使用贴纸（用产品B碰撞）" />
            <el-option v-for="t in tags" :key="t.value" :value="t.value" :label="t.label" />
          </el-select>
          <!-- 拉取失败 ≠ 没素材:失败时给出真实原因+重试,不再误导用户去重新导素材 -->
          <span v-if="tagsErr" class="hint">
            （读取素材库失败：{{ tagsErr }}——素材可能是在的，先确认云端 5020 已启动且连的是同一个 gofu-cloud.db
            <el-button link type="primary" size="small" @click="loadTags">重试</el-button>）
          </span>
          <span v-else-if="!tags.length" class="hint">（素材库为空，可上传产品B图走碰撞路径）</span>
        </el-col>
        <el-col :span="8">
          <span class="label">生成张数</span>
          <el-input-number v-model="kpStore.genCount" :min="1" :max="100" :step="1" :value-on-clear="6"
            size="small" controls-position="right" style="width:90px" />
          <span class="hint-gray ml8">参考素材按张数自动抽取，不重样</span>
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

    <!-- ③ 筛图 + 下载。生成中也要渲染这张卡片,否则 100 张时长时间整块不出现,像卡死 -->
    <el-card v-if="conceptImages.length || generating" class="step">
      <template #header>③ 筛选设计方案（已选 {{ kpStore.picked.length }} / {{ maxPick }}，共 {{ conceptImages.length }} 张）</template>
      <div v-if="!conceptImages.length" class="gen-empty">
        生成中，首张约需 1~2 分钟（{{ kpStore.genCount }} 张分批并行跑，预计 {{ etaMinutes }} 分钟左右）…
        出图后会逐张出现在这里，不用等全部跑完。
      </div>
      <div v-else class="grid">
        <div v-for="(m, i) in conceptImages" :key="i" :class="['cell', { on: isPicked(m) }]">
          <el-image :src="imgUrl(m)" fit="cover" loading="lazy" :preview-src-list="previewList"
            :initial-index="i" preview-teleported hide-on-click-modal />
          <el-checkbox class="pickbox" :model-value="isPicked(m)" @click.stop @change="toggle(m)" />
          <span v-if="isPicked(m)" class="badge">{{ kpStore.picked.indexOf(m) + 1 }}</span>
        </div>
      </div>
      <div class="actions foot">
        <el-button type="primary" :disabled="!kpStore.picked.length" :loading="downloading"
          @click="downloadMany(kpStore.picked, '开品设计方案')">
          下载选中（{{ kpStore.picked.length }} 张）
        </el-button>
        <el-button :disabled="!conceptImages.length" :loading="downloading"
          @click="downloadMany(conceptImages, '开品设计方案')">
          下载全部（{{ conceptImages.length }} 张）
        </el-button>
        <span class="hint-gray">图片存到浏览器默认下载目录</span>
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
.hint-gray { font-size: 12px; color: #909399; }
.gen-empty { padding: 24px 12px; text-align: center; color: #909399; font-size: 13px; line-height: 1.8; }
.msg { margin: 8px 0 0; font-size: 13px; }
.msg.ok { color: #67c23a; }
.msg.err { color: #f56c6c; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(120px, 1fr)); gap: 8px; margin-top: 8px; }
.cell { position: relative; cursor: zoom-in; border: 3px solid transparent; border-radius: 6px; overflow: hidden; aspect-ratio: 1; }
.cell.on { border-color: #67c23a; }
.cell .el-image { width: 100%; height: 100%; }
.pickbox { position: absolute; top: 2px; left: 6px; z-index: 2; background: rgba(255,255,255,.85); border-radius: 4px; padding: 0 4px; height: 22px; }
.badge { position: absolute; top: 4px; right: 4px; background: #67c23a; color: #fff; width: 22px; height: 22px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 13px; font-weight: bold; }</style>

