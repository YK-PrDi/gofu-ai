import { ref } from 'vue'
import { ElMessage } from 'element-plus'

// 图片下载（全模式共用）。
//
// 走 /api/gen/img?ref= 这个同源字节代理拿图，不直连 COS——
// 云端 GenController.img 已经用凭证在服务端 getObject 再吐字节（绕防盗链/ACL），
// 前端 fetch 它是同源请求，没有 CORS 问题（InpaintDialog 已在用同一条路）。
//
// 落地方式：blob + <a download>，图进浏览器的默认下载目录。
// ⚠️ 让用户自选保存路径需要原生文件对话框（Electron 的 dialog.showSaveDialog），
// 而 gofu-client-local/electron/ 目前是空目录、壳还没做，纯浏览器做不到。等壳补上再换。
const imgUrl = (r) => '/api/gen/img?ref=' + encodeURIComponent(r)

// 扩展名只认**真实字节的魔数**，ref 后缀和响应 Content-Type 都不可信。
// 实测(08.03)：COS key 叫 xxx.jpg，对象里却是 PNG 字节；而云端 GenController.img 的 Content-Type
// 也是照 ref 后缀猜的(ref.contains(".png")?png:jpeg)，于是「.jpg 名 + image/jpeg 头 + PNG 内容」三者全对不上。
// 存成错扩展名的文件，部分看图/设计软件会拒开——开品图要发给工厂，不能让它开不了。
async function sniffExt(blob) {
  try {
    const b = new Uint8Array(await blob.slice(0, 12).arrayBuffer())
    if (b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47) return 'png'
    if (b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return 'jpg'
    // RIFF....WEBP
    if (b[0] === 0x52 && b[1] === 0x49 && b[2] === 0x46 && b[3] === 0x46
        && b[8] === 0x57 && b[9] === 0x45 && b[10] === 0x42 && b[11] === 0x50) return 'webp'
  } catch (_) { /* 读不到就退回按 MIME 猜 */ }
  const m = String(blob.type || '').toLowerCase()
  if (m.includes('png')) return 'png'
  if (m.includes('webp')) return 'webp'
  return 'jpg'
}

// Windows 文件名非法字符换下划线，避免 a.download 静默失败。
function safeName(name) {
  return String(name || 'image').replace(/[\\/:*?"<>|]/g, '_').trim() || 'image'
}

function triggerBlobDownload(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  // 立刻 revoke 会让部分浏览器拿不到数据，给一点余量再释放
  setTimeout(() => URL.revokeObjectURL(url), 4000)
}

export function useImageDownload() {
  const downloading = ref(false)

  /** 下载单张。filename 不带扩展名，函数按 ref 补。 */
  async function downloadOne(r, filename) {
    if (!r) return false
    const res = await fetch(imgUrl(r))
    if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
    const blob = await res.blob()
    triggerBlobDownload(blob, `${safeName(filename)}.${await sniffExt(blob)}`)
    return true
  }

  /**
   * 批量下载。**必须顺序 + 留间隔**：浏览器会把瞬间连续触发的多个下载判为弹窗滥用而拦掉，
   * 只落下第一张（这不是代码报错，是静默丢失，所以宁可慢一点）。
   * @param refs   图片 ref 列表
   * @param prefix 文件名前缀，最终形如 `开品设计方案-3.jpg`
   * @returns {Promise<number>} 成功张数
   */
  async function downloadMany(refs, prefix = 'image') {
    const list = (refs || []).filter(Boolean)
    if (!list.length) { ElMessage.warning('没有可下载的图片'); return 0 }
    downloading.value = true
    let ok = 0
    const failed = []
    try {
      for (let i = 0; i < list.length; i++) {
        try {
          await downloadOne(list[i], `${prefix}-${i + 1}`)
          ok++
        } catch (e) {
          failed.push(i + 1)
        }
        if (i < list.length - 1) await new Promise((r) => setTimeout(r, 300))
      }
      // 如实报告：失败的张号说出来，不要只报成功数
      if (failed.length) {
        ElMessage.warning(`已下载 ${ok}/${list.length} 张，第 ${failed.join('、')} 张失败`)
      } else {
        ElMessage.success(`已下载 ${ok} 张到浏览器下载目录`)
      }
    } finally {
      downloading.value = false
    }
    return ok
  }

  return { downloadOne, downloadMany, downloading }
}
