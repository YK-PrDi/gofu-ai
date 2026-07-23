<script setup>
import { ref, reactive, watch, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { useGen } from '@/composables/useGen.js'

// 局部重绘弹框:底层<img>展示大图,上覆<canvas>画选区。选区=要重画的区(OpenAI edits: mask透明区=重绘)。
// 矩形框选 / 画笔涂抹 两种工具 + 纯指令(不选区=整图按指令,走 regenImage)。
const props = defineProps({
  modelValue: Boolean,
  imgRef: String,          // 当前图的 COS key/路径
  kind: { type: String, default: 'main' },  // main/detail
  index: { type: Number, default: 0 },
  aspect: { type: String, default: 'auto' },
})
const emit = defineEmits(['update:modelValue', 'done'])

const { gen, inpaintImage } = useGen()
const imgUrl = (r) => '/api/gen/img?ref=' + encodeURIComponent(r)

const canvasRef = ref(null)
const imgEl = ref(null)
const tool = ref('rect')        // rect | brush
const brushSize = ref(40)
const prompt = ref('')
const submitting = ref(false)
const natW = ref(0), natH = ref(0)   // 原图真实像素尺寸(mask 必须与原图同尺寸)
let imageBlob = null                  // 原图字节(传给 inpaint 的 image)

// 选区记录:矩形 {x,y,w,h}(真实像素坐标) 或 画笔路径点集 [{x,y,r}]
const rects = reactive([])
const strokes = reactive([])         // 每笔:{points:[{x,y}], r}
let drawing = false, curStroke = null, startPt = null

// 打开时加载原图:fetch 代理URL拿blob,取真实像素尺寸
watch(() => props.modelValue, async (open) => {
  if (!open) return
  rects.length = 0; strokes.length = 0; prompt.value = ''; imageBlob = null
  try {
    const res = await fetch(imgUrl(props.imgRef))
    if (!res.ok) throw new Error('原图加载失败 ' + res.status)
    imageBlob = await res.blob()
    const bmp = await createImageBitmap(imageBlob)
    natW.value = bmp.width; natH.value = bmp.height
    bmp.close?.()
    await nextTick()
    redraw()
  } catch (e) { ElMessage.error('加载原图失败：' + e.message) }
})

// 展示层坐标 → 原图真实像素坐标(canvas.width=natW,CSS宽由布局定,按比例换算)
function toNat(ev) {
  const c = canvasRef.value
  const rect = c.getBoundingClientRect()
  return {
    x: (ev.clientX - rect.left) / rect.width * natW.value,
    y: (ev.clientY - rect.top) / rect.height * natH.value,
  }
}

function onDown(ev) {
  if (!natW.value) return
  drawing = true
  const p = toNat(ev)
  if (tool.value === 'rect') { startPt = p; rects.push({ x: p.x, y: p.y, w: 0, h: 0 }) }
  else { curStroke = { points: [p], r: brushSize.value / 2 * (natW.value / (canvasRef.value.getBoundingClientRect().width || 1)) }; strokes.push(curStroke) }
}
function onMove(ev) {
  if (!drawing || !natW.value) return
  const p = toNat(ev)
  if (tool.value === 'rect') {
    const r = rects[rects.length - 1]
    r.x = Math.min(startPt.x, p.x); r.y = Math.min(startPt.y, p.y)
    r.w = Math.abs(p.x - startPt.x); r.h = Math.abs(p.y - startPt.y)
  } else { curStroke.points.push(p) }
  redraw()
}
function onUp() { drawing = false; curStroke = null; startPt = null; redraw() }

// 展示层重绘:原图为底 + 选区半透明红(仅展示,不用于导出)
function redraw() {
  const c = canvasRef.value; if (!c) return
  c.width = natW.value; c.height = natH.value
  const ctx = c.getContext('2d')
  ctx.clearRect(0, 0, c.width, c.height)
  ctx.fillStyle = 'rgba(255,64,64,0.4)'
  ctx.strokeStyle = 'rgba(255,64,64,0.9)'; ctx.lineWidth = Math.max(2, natW.value / 300)
  for (const r of rects) { ctx.fillRect(r.x, r.y, r.w, r.h); ctx.strokeRect(r.x, r.y, r.w, r.h) }
  for (const s of strokes) {
    ctx.lineWidth = s.r * 2; ctx.lineCap = 'round'; ctx.lineJoin = 'round'
    ctx.beginPath()
    s.points.forEach((p, i) => i ? ctx.lineTo(p.x, p.y) : ctx.moveTo(p.x, p.y))
    if (s.points.length === 1) { ctx.arc(s.points[0].x, s.points[0].y, s.r, 0, Math.PI * 2); ctx.fill() }
    else ctx.stroke()
  }
}

function clearSel() { rects.length = 0; strokes.length = 0; redraw() }
function undo() { if (tool.value === 'rect') rects.pop(); else strokes.pop(); redraw() }
const hasSel = () => rects.length > 0 || strokes.length > 0

// 生成 mask PNG:离屏canvas(natW×natH),全不透明黑=保留,选区抠透明=重绘(destination-out)。
// allTransparent=true(无选区/纯指令):整张透明=整图按指令重绘(原图仍作image传入保基底)。
function buildMask(allTransparent = false) {
  return new Promise((resolve) => {
    const c = document.createElement('canvas')
    c.width = natW.value; c.height = natH.value
    const ctx = c.getContext('2d')
    if (allTransparent) { c.toBlob((b) => resolve(b), 'image/png'); return }  // 空canvas=全透明
    ctx.fillStyle = '#000'; ctx.fillRect(0, 0, c.width, c.height)   // 保留区(不透明)
    ctx.globalCompositeOperation = 'destination-out'                // 后续绘制抠成透明
    ctx.fillStyle = '#000'; ctx.strokeStyle = '#000'
    for (const r of rects) ctx.fillRect(r.x, r.y, r.w, r.h)
    for (const s of strokes) {
      ctx.lineWidth = s.r * 2; ctx.lineCap = 'round'; ctx.lineJoin = 'round'
      ctx.beginPath()
      s.points.forEach((p, i) => i ? ctx.lineTo(p.x, p.y) : ctx.moveTo(p.x, p.y))
      if (s.points.length === 1) { ctx.arc(s.points[0].x, s.points[0].y, s.r, 0, Math.PI * 2); ctx.fill() }
      else ctx.stroke()
    }
    c.toBlob((b) => resolve(b), 'image/png')
  })
}

async function submit() {
  if (!prompt.value.trim()) { ElMessage.warning('请输入重绘指令'); return }
  submitting.value = true
  try {
    // 无选区=全透明mask(整图按指令重绘),有选区=选区抠透明(局部)。两者都走 inpaint,弹框即时指令均生效。
    const maskBlob = await buildMask(!hasSel())
    await inpaintImage(props.kind, props.index, imageBlob, maskBlob, prompt.value.trim(), props.aspect)
    ElMessage.success(hasSel() ? '局部重绘完成' : '整图按指令重绘完成')
    emit('done')
    emit('update:modelValue', false)
  } catch (e) { ElMessage.error('重绘失败：' + (e.message || e)) }
  finally { submitting.value = false }
}
</script>

<template>
  <el-dialog :model-value="modelValue" @update:model-value="$emit('update:modelValue', $event)"
    title="局部重绘" width="80%" top="4vh" append-to-body destroy-on-close class="inpaint-dlg">
    <div class="toolbar">
      <el-radio-group v-model="tool" size="small">
        <el-radio-button value="rect">矩形框选</el-radio-button>
        <el-radio-button value="brush">画笔涂抹</el-radio-button>
      </el-radio-group>
      <span v-if="tool === 'brush'" class="brush">
        笔刷 <el-slider v-model="brushSize" :min="10" :max="120" style="width:120px" />
      </span>
      <el-button size="small" @click="undo">撤销</el-button>
      <el-button size="small" @click="clearSel">清除选区</el-button>
      <span class="tip">框/涂的区域 = 要重画的部分</span>
    </div>

    <div class="stage">
      <img ref="imgEl" :src="imgUrl(imgRef)" class="base" />
      <canvas ref="canvasRef" class="overlay"
        @mousedown="onDown" @mousemove="onMove" @mouseup="onUp" @mouseleave="onUp" />
    </div>

    <template #footer>
      <div class="footer">
        <el-input v-model="prompt" placeholder="重绘指令,如:把这块背景换成浅灰、这里改成金色边框" clearable />
        <el-button type="primary" :loading="submitting || gen.imgBusy" @click="submit">开始重绘</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; flex-wrap: wrap; }
.brush { display: flex; align-items: center; gap: 6px; font-size: 13px; color: #606266; }
.tip { font-size: 12px; color: #909399; margin-left: auto; }
.stage { position: relative; display: inline-block; max-width: 100%; max-height: 68vh; line-height: 0; }
.base { max-width: 100%; max-height: 68vh; display: block; user-select: none; -webkit-user-drag: none; }
.overlay { position: absolute; left: 0; top: 0; width: 100%; height: 100%; cursor: crosshair; }
.footer { display: flex; gap: 10px; align-items: center; }
.footer :deep(.el-input) { flex: 1; }
</style>
