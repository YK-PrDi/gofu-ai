#!/usr/bin/env bash
# 迪士尼贴纸素材批量导入脚本
# 用法：bash import-disney.sh [云端地址，默认 http://localhost:5020]
set -e

BASE_URL="${1:-http://localhost:5020}"
ROOT="C:/Users/20739/Desktop/测试文件夹/迪士尼贴纸_分类"

echo "======== 迪士尼素材批量导入 ========"
echo "数据来源: $ROOT"
echo "目标端点: $BASE_URL/api/disney/import"
echo ""

TOTAL_OK=0
TOTAL_FAIL=0

for tag_dir in "$ROOT"/*/; do
  tag=$(basename "$tag_dir")
  # 跳过空目录（如"待定"）
  img_count=$(find "$tag_dir" -maxdepth 1 \( -iname "*.jpg" -o -iname "*.jpeg" -o -iname "*.png" -o -iname "*.webp" \) 2>/dev/null | wc -l)
  if [ "$img_count" -eq 0 ]; then
    echo "  跳过「$tag」（目录为空）"
    continue
  fi

  echo "  导入「$tag」$img_count 张…"
  # 每次最多 20 张批量上传（避免单次请求过大）
  find "$tag_dir" -maxdepth 1 \( -iname "*.jpg" -o -iname "*.jpeg" -o -iname "*.png" -o -iname "*.webp" \) | sort | \
  while IFS= read -r img; do
    result=$(curl -s -X POST "$BASE_URL/api/disney/import" \
      -F "files=@$(cygpath -w "$img" 2>/dev/null || echo "$img")" \
      -F "tag=$tag")
    imported=$(echo "$result" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('imported',0))" 2>/dev/null || echo "0")
    failed=$(echo "$result" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('failed',0))" 2>/dev/null || echo "0")
    TOTAL_OK=$((TOTAL_OK + imported))
    TOTAL_FAIL=$((TOTAL_FAIL + failed))
    if [ "$failed" -gt 0 ]; then
      echo "    ⚠ 有失败: $result"
    fi
  done

  # 查一下入库数量
  count_in_db=$(curl -s "$BASE_URL/api/disney/tags" | python3 -c "
import sys,json
d=json.load(sys.stdin)
for t in d.get('tags',[]):
    if t['tag']=='$tag':
        print(t['count'])
        break
else:
    print('?')
" 2>/dev/null || echo "?")
  echo "    └─ 「$tag」数据库中当前共 $count_in_db 张"
done

echo ""
echo "======== 导入完成 ========"
echo "本次成功: $TOTAL_OK 张，失败: $TOTAL_FAIL 张"
echo ""
echo "验证标签列表:"
curl -s "$BASE_URL/api/disney/tags" | python3 -c "
import sys,json
d=json.load(sys.stdin)
for t in d.get('tags',[]):
    print(f\"  {t['tag']}: {t['count']} 张\")
" 2>/dev/null || echo "  （查询失败，请确认 5020 已启动）"
