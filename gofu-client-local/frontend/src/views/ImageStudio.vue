<script setup>
import { computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useContextStore } from '@/stores/context.js'
import { useGen } from '@/composables/useGen.js'

// P2-f:生图工作室。对当前商品已有成品图(主图/详情)整套换风格,或单张重生。
// 与单品页不同:生图页对【任何已加载的商品】操作(含导入流建的),不做 origin 门控——
// 换风格/重生本就是"对已有图动手",导入的商品正需要在这里换基调。
import { useSettingsStore } from '@/stores/settings.js'
const ctxStore = useContextStore()
const settings = useSettingsStore()
const { style, gen, runStyleTransfer, regenImage } = useGen()

const styleOptions = [
  { id: 'original', name: '原图延展' }, { id: 'tech-blue', name: '科技蓝' }, { id: 'girl-pink', name: '少女粉' },
  { id: 'premium-gray', name: '高级灰' }, { id: 'natural-green', name: '自然绿' }, { id: 'sunset-orange', name: '暖阳橙' },
  { id: 'khaki', name: '卡其色' }, { id: 'light-yellow', name: '淡黄色' }, { id: 'beige', name: '米黄色' },
]

const ctx = computed(() => ctxStore.current)
const mainImages = computed(() => ctx.value?.visual?.mainImages || [])
const detailImages = computed(() => ctx.value?.visual?.detailImages || [])
const hasImages = computed(() => mainImages.value.length || detailImages.value.length)

function imgUrl(ref) { return '/api/gen/img?ref=' + encodeURIComponent(ref) }

onMounted(() => settings.init())

async function regen(kind, i) {
  await regenImage(kind, i)
  if (gen.msg) ElMessage.info(gen.msg)
}
</script>

<template>
  <div class="studio">
    <h2>生图工作室</h2>

    <el-empty v-if="!ctx" description="请先通过顶部切换器载入商品，或在导入建品/单品上新生成后再来换风格" />

    <template v-else>
      <!-- 风格迁移 -->
      <el-card class="sec">
        <template #header>🎨 整套换风格</template>
        <p class="hint">对当前商品「{{ ctxStore.title }}」整套换视觉基调（产品/构图/文案不变，只换风格），换完自动重新生成 SKU 图+详情图（跟随新风格），可直接上架。测哪种风格销量好就靠它。</p>
        <div v-if="!hasImages" class="warn">当前商品还没有成品图，先去生成或导入成品图。</div>
        <div v-else class="style-bar">
          <el-select v-model="style.styleId" placeholder="选择目标风格…" style="width:200px">
            <el-option v-for="s in styleOptions" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
          <el-button type="primary" :disabled="!style.styleId || style.running" :loading="style.running" @click="runStyleTransfer(settings.antipriceTemplates)">
            {{ style.running ? '处理中…' : '整套换风格并生成SKU' }}
          </el-button>
        </div>
        <el-progress v-if="style.running" :percentage="gen.progress" style="margin-top:10px" />
        <el-alert v-if="style.msg" :title="style.msg" :closable="false" style="margin-top:10px"
          :type="style.msgType === 'ok' ? 'success' : style.msgType === 'err' ? 'error' : 'info'" />
      </el-card>

      <!-- 生成SKU已并入上方"整套换风格并生成SKU"一个按钮,不再单列 -->

      <!-- 主图:单张重生 -->
      <el-card v-if="mainImages.length" class="sec">
        <template #header>主图（{{ mainImages.length }}）· 点单张可重生</template>
        <div class="grid">
          <div v-for="(m, i) in mainImages" :key="'m' + i" class="cell">
            <img :src="imgUrl(m)" />
            <el-button size="small" :loading="gen.imgBusy" @click="regen('main', i)">重生</el-button>
          </div>
        </div>
      </el-card>

      <!-- 详情图 -->
      <el-card v-if="detailImages.length" class="sec">
        <template #header>详情图（{{ detailImages.length }}）</template>
        <div class="grid">
          <div v-for="(d, i) in detailImages" :key="'d' + i" class="cell">
            <img :src="imgUrl(d)" />
            <el-button size="small" :loading="gen.imgBusy" @click="regen('detail', i)">重生</el-button>
          </div>
        </div>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.studio { max-width: 1200px; }
.sec { margin-bottom: 16px; }
.hint { font-size: 13px; color: #606266; margin: 0 0 10px; }
.warn { color: #e6a23c; font-size: 13px; }
.style-bar { display: flex; gap: 12px; align-items: center; }
.grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.cell { display: flex; flex-direction: column; gap: 6px; }
.cell img { width: 100%; border: 1px solid #ebeef5; border-radius: 4px; }
</style>
