// 迪士尼素材批量导入脚本 (Node.js)
// 用法: node import-disney.mjs
import { readdir, readFile, stat } from 'node:fs/promises';
import { join, extname, basename } from 'node:path';

const BASE_URL = 'http://localhost:5020';
// 从环境变量读，或直接改这里
const ROOT = process.argv[2] || 'C:/Users/20739/Desktop/kaipin';

async function findAssetsRoot(dir) {
  // 如果 dir 下只有一个子目录，自动进入（处理 kaipin/迪士尼贴纸_分类 的情况）
  const entries = await readdir(dir, { withFileTypes: true });
  const dirs = entries.filter(e => e.isDirectory());
  const hasImages = entries.some(e => /\.(png|jpg|jpeg|webp)$/i.test(e.name));
  if (!hasImages && dirs.length === 1) {
    return join(dir, dirs[0].name);
  }
  return dir;
}

async function importFile(filePath, tag) {
  const bytes = await readFile(filePath);
  const filename = basename(filePath);
  const mime = /\.png$/i.test(filename) ? 'image/png' : 'image/jpeg';
  const boundary = `----FormBoundary${Date.now()}`;
  const CRLF = '\r\n';
  const enc = s => Buffer.from(s, 'utf8');

  const body = Buffer.concat([
    enc(`--${boundary}${CRLF}`),
    enc(`Content-Disposition: form-data; name="files"; filename="${filename}"${CRLF}`),
    enc(`Content-Type: ${mime}${CRLF}${CRLF}`),
    bytes,
    enc(`${CRLF}--${boundary}${CRLF}`),
    enc(`Content-Disposition: form-data; name="tag"${CRLF}${CRLF}`),
    enc(tag),
    enc(`${CRLF}--${boundary}--${CRLF}`),
  ]);

  const resp = await fetch(`${BASE_URL}/api/disney/import`, {
    method: 'POST',
    headers: { 'Content-Type': `multipart/form-data; boundary=${boundary}` },
    body,
  });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  const json = await resp.json();
  if (json.failed > 0) throw new Error(`server failed=${json.failed}: ${json.errors?.join(', ')}`);
  return json.imported;
}

async function main() {
  const root = await findAssetsRoot(ROOT);
  console.log(`Root: ${root}`);

  const tagDirs = (await readdir(root, { withFileTypes: true })).filter(e => e.isDirectory());
  let totalOk = 0, totalFail = 0;

  for (const tagDir of tagDirs) {
    const tag = tagDir.name;
    const tagPath = join(root, tag);
    const files = (await readdir(tagPath)).filter(f => /\.(png|jpg|jpeg|webp)$/i.test(f));
    if (!files.length) { console.log(`  Skip ${tag} (empty)`); continue; }

    process.stdout.write(`  [${tag}] ${files.length} files... `);
    let ok = 0, fail = 0;
    for (const f of files) {
      try {
        ok += await importFile(join(tagPath, f), tag);
      } catch (e) {
        fail++;
        if (fail <= 3) process.stderr.write(`\n    ERR ${f}: ${e.message}`);
      }
    }
    totalOk += ok; totalFail += fail;
    console.log(`ok=${ok} fail=${fail}`);
  }

  console.log(`\nDone: ok=${totalOk} fail=${totalFail}`);

  // 验证
  const r = await (await fetch(`${BASE_URL}/api/disney/tags`)).json();
  console.log('Tags in DB:');
  r.tags.forEach(t => console.log(`  ${t.tag}: ${t.count}`));
}

main().catch(e => { console.error(e); process.exit(1); });
