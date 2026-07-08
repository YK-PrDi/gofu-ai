#!/usr/bin/env bash
# 一键打包：mvn package → build-exe.sh → 写使用说明 → zip。由「一键打包.bat」调用。
# 用法：bash 一键打包.sh <版本号>   例：bash 一键打包.sh 1.7.0
set -e

VER="${1:-}"
[ -z "$VER" ] && { echo "缺少版本号参数"; exit 1; }

JHOME="/c/Users/20739/.jdks/ms-21.0.10"
MVN="/d/Program/apache-maven-3.9.15/bin/mvn.cmd"
ROOT="/d/code/gofu-ai"
PKG="$ROOT/packaging"
DIST="$PKG/dist"
ZIP="$DIST/GOFU-AI-测试版-v${VER}.zip"

echo "############ [1/4] Maven 打包三模块 (mvn package) ############"
"$MVN" -q -o -f "$ROOT/pom.xml" package -DskipTests || "$MVN" -q -f "$ROOT/pom.xml" package -DskipTests
echo "Maven 打包完成"

echo "############ [2/4] 构建 exe (build-exe.sh) ############"
bash "$PKG/build-exe.sh"

echo "############ [3/4] 写使用说明 + 清理冒烟数据 ############"
# 清掉可能存在的运行数据(日志/db)，只保留种子配置，避免把登录态/测试数据打进包
rm -rf "$DIST/GOFU/app/data/logs" "$DIST/GOFU/app/data/gofu-cloud.db" 2>/dev/null || true
cat > "$DIST/GOFU/使用说明.txt" << EOF
========================================
  GOFU-AI 商品工作台 · 便携测试版 v${VER}
========================================

【怎么用】
1. 双击本目录下的 GOFU.exe
2. 黑色控制台显示"启动中…"，等约 10~30 秒
3. 自动打开浏览器进入工作台：http://localhost:5021/workbench.html
4. 没自动弹出就手动打开上面地址

【怎么关】关闭黑色控制台窗口，两个服务一起停。

【无需安装】不用装 Java/浏览器，包内已自带。解压到任意可写目录(桌面/D盘)，
不要放 C:\\Program Files 等需管理员权限的目录。

【数据/日志】首次启动在 app\\data\\ 下生成数据库/凭证/缓存/日志。
出问题时日志在 app\\data\\logs\\(cloud.log / local.log)，可发回排查。

⚠️ 【安全提示·重要】本包含真实快麦账号、生图密钥、登录后的拼多多商家态。
请勿公开分发、上传网盘或发外部渠道，仅限内部可信测试机使用。
========================================
EOF
echo "使用说明已写入 (v${VER})"

echo "############ [4/4] 压缩 zip ############"
rm -f "$ZIP"
powershell -NoProfile -Command "Compress-Archive -Path '$(cygpath -w "$DIST/GOFU")' -DestinationPath '$(cygpath -w "$ZIP")' -CompressionLevel Optimal"

echo ""
echo "==================================================="
echo "  打包完成： $ZIP"
ls -la "$ZIP" | awk '{print "  大小:", $5, "字节"}'
echo "==================================================="

# 打开 dist 文件夹
explorer "$(cygpath -w "$DIST")" 2>/dev/null || true
