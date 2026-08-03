#!/usr/bin/env bash
# GOFU-AI 便携版 exe 打包脚本（jlink 精简JRE + jpackage app-image）。
# 产物：packaging/dist/GOFU/GOFU.exe（双击即起两个服务+开浏览器）。
set -e

JHOME="/c/Users/20739/.jdks/ms-21.0.10"
ROOT="/d/code/gofu-ai"
PKG="$ROOT/packaging"
DIST="$PKG/dist"
INPUT="$PKG/_input"
RUNTIME="$PKG/_runtime"

echo "===================== [0/5] 重编 launcher.jar ====================="
# ⚠️ launcher 不在 maven reactor，mvn package 不会编它。过去只校验 jar 存在→改了
# GofuLauncher.java 却不重编，旧 jar 被打进包(#9B 等修复丢失)。此处强制重编，确保源码进 jar。
LSRC="$PKG/launcher/src/GofuLauncher.java"
LBUILD="$PKG/launcher/build"
[ -f "$LSRC" ] || { echo "缺少 $LSRC"; exit 1; }
mkdir -p "$LBUILD/classes"
"$JHOME/bin/javac.exe" -encoding UTF-8 -d "$LBUILD/classes" "$LSRC"
printf 'Main-Class: GofuLauncher\n' > "$LBUILD/MANIFEST.MF"
"$JHOME/bin/jar.exe" cfm "$LBUILD/launcher.jar" "$LBUILD/MANIFEST.MF" -C "$LBUILD/classes" .
echo "launcher.jar 已用最新源码重编: $(stat -c '%y' "$LBUILD/launcher.jar" | cut -d. -f1)"

echo "===================== [1/5] 校验前置 ====================="
CLOUD="$ROOT/gofu-server-cloud/target/app.jar"
LOCAL="$ROOT/gofu-client-local/target/app.jar"
LAUNCHER="$PKG/launcher/build/launcher.jar"
SEED="$ROOT/kuaimai-config.json"
for f in "$CLOUD" "$LOCAL" "$LAUNCHER"; do
  [ -f "$f" ] || { echo "缺少 $f，先 mvn package + 编译 launcher"; exit 1; }
done
echo "cloud.jar / local.jar / launcher.jar 就绪"

echo "===================== [2/5] jlink 精简JRE ====================="
rm -rf "$RUNTIME"
# Spring Boot fat jar 非模块化，jdeps 无法精确分析，直接纳入运行期常用模块（覆盖 web/jpa/xml/crypto/desktop/sql 等）
# jdk.charsets 必带：中文Windows下 AWT 托盘菜单/原生文字走 GBK(MS936)，缺它托盘右键菜单变豆腐块(#9)
MODS="java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,jdk.charsets,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.management,jdk.jfr,jdk.zipfs,jdk.httpserver,jdk.naming.dns"
"$JHOME/bin/jlink.exe" \
  --add-modules "$MODS" \
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
  --output "$RUNTIME"
echo "精简JRE 大小: $(du -sh "$RUNTIME" | cut -f1)"

echo "===================== [3/5] 组织 input 目录 ====================="
rm -rf "$INPUT"; mkdir -p "$INPUT"
cp "$LAUNCHER" "$INPUT/launcher.jar"
cp "$CLOUD"    "$INPUT/cloud.jar"
cp "$LOCAL"    "$INPUT/local.jar"
[ -f "$SEED" ] && cp "$SEED" "$INPUT/kuaimai-config.json" && echo "已附带快麦凭证种子"
ls -la "$INPUT"

echo "===================== [4/5] jpackage app-image ====================="
rm -rf "$DIST"; mkdir -p "$DIST"
"$JHOME/bin/jpackage.exe" \
  --type app-image \
  --name GOFU \
  --input "$INPUT" \
  --main-jar launcher.jar \
  --main-class GofuLauncher \
  --runtime-image "$RUNTIME" \
  --dest "$DIST" \
  --java-options "-Dfile.encoding=UTF-8" \
  --app-version "1.0.0"
echo "app-image 产出: $DIST/GOFU"

echo "===================== [5/6] 拷贝上新运行时 tools ====================="
# 上新要真实浏览器(反风控 headless=false)。Launcher 设 app.resources-path=app/，
# ListingService 找 app/tools/{pdd_listing.js, node/node.exe, browsers/chromium-*, node_modules}。
# tools/ 里 node/browsers/node_modules 是指向 LY-Automation 的软链接，必须解引用成真实文件。
TOOLS_SRC="$ROOT/gofu-client-local/tools"
TOOLS_DST="$DIST/GOFU/app/tools"
mkdir -p "$TOOLS_DST"
echo "拷贝 pdd_listing.js + node + node_modules（解引用软链接，cp -rL）…"
cp -L "$TOOLS_SRC/pdd_listing.js" "$TOOLS_DST/pdd_listing.js"
cp -rL "$TOOLS_SRC/node"          "$TOOLS_DST/node"
cp -rL "$TOOLS_SRC/node_modules"  "$TOOLS_DST/node_modules"
# 补发运行时(0722新增):ReshipService 找 app/tools/reship/reship-cli.js,依赖同目录多个模块+共用 node_modules。
# 整目录拷,别只拷单文件(reship-cli.js require 了 order-workbook/erp-browser-runner/wps-cloud-gateway 等)。
[ -d "$TOOLS_SRC/reship" ] && cp -rL "$TOOLS_SRC/reship" "$TOOLS_DST/reship" && echo "拷贝 reship/（$(ls "$TOOLS_SRC/reship" | wc -l) 个文件）"
echo "拷贝 browsers（只要 chromium+ffmpeg+winldd，砍冗余 headless_shell 省268M）…"
mkdir -p "$TOOLS_DST/browsers"
for d in "$TOOLS_SRC"/browsers/chromium-* "$TOOLS_SRC"/browsers/ffmpeg-* "$TOOLS_SRC"/browsers/winldd-* "$TOOLS_SRC"/browsers/.links; do
  [ -e "$d" ] || continue
  case "$(basename "$d")" in
    chromium_headless_shell-*) echo "  跳过 $(basename "$d")（headless 无头版，反风控用不到）" ;;
    *) cp -rL "$d" "$TOOLS_DST/browsers/$(basename "$d")" ;;
  esac
done
echo "tools 大小: $(du -sh "$TOOLS_DST" | cut -f1)"

echo "===================== [5.5/6] 拷贝底层参考文件 ====================="
# 商品关键属性/标题等底层参考。ListingService 从 app.resources-path(=app/) 或 user.dir 读
# 「产品信息填写参考.xlsx」等；不拷则新店无「一键复用」时属性填不上（换店铺就空）。
# classpath 已内置 product-info-presets.json 作兜底，此处再拷 xlsx 让运营可用 Excel 现场改。
REF_SRC="$ROOT/底层信息"
REF_DST="$DIST/GOFU/app"
if [ -d "$REF_SRC" ]; then
  for x in "产品信息填写参考.xlsx" "商品标题库.xlsx"; do
    [ -f "$REF_SRC/$x" ] && cp "$REF_SRC/$x" "$REF_DST/$x" && echo "  拷贝 $x"
  done
else
  echo "  ⚠ 未找到 $REF_SRC，跳过（打包版将靠 classpath JSON 兜底）"
fi

echo "===================== [5.6/6] 拷云端库种子(迪士尼素材等) ====================="
# 08.02 修：云端库路径是 jdbc:sqlite:${user.dir}/gofu-cloud.db，而 Launcher 把工作目录设成
# app/data/ → 打包版首启会建一个**全新空库**。以前不拷 .db，导致源码态导好的迪士尼素材
# (disney_asset 表)在打包版里查不到，开品模式报"未找到标签，请先导入素材"(用户08.02反馈)。
# 现在把仓库根那个库作种子拷进 app/data/。图片本体在 COS(cos_key 是 COS key 不是本地路径,
# COS 凭据随 application-local.yml 打进 cloud.jar)，所以换机器也能取到图。
# 注意：只在目标不存在时拷，避免覆盖用户已积累的数据(重复打包/升级场景)。
SEED_DB="$ROOT/gofu-cloud.db"
SEED_DB_DST="$DIST/GOFU/app/data/gofu-cloud.db"
if [ -f "$SEED_DB" ]; then
  mkdir -p "$(dirname "$SEED_DB_DST")"
  cp "$SEED_DB" "$SEED_DB_DST"
  echo "  已拷云端库种子: $(du -h "$SEED_DB_DST" | cut -f1)"
  # 报一下种子库里有什么，避免"拷了个空库"还以为齐了。
  # 注意：Windows 的 python 打不开 git-bash 的 /d/... 路径，故先 cd 进目录再用相对文件名。
  if command -v python >/dev/null 2>&1; then
    ( cd "$(dirname "$SEED_DB_DST")" && python - <<'PYEOF' 2>/dev/null || true
import sqlite3
c = sqlite3.connect('gofu-cloud.db')
tabs = [r[0] for r in c.execute("select name from sqlite_master where type='table'")]
for t in ('disney_asset', 'product_context'):
    n = c.execute('select count(*) from "%s"' % t).fetchone()[0] if t in tabs else None
    print('    %s = %s' % (t, ('%d 行' % n) if n is not None else '表不存在'))
c.close()
PYEOF
    )
  fi
else
  echo "  ⚠ 未找到 $SEED_DB —— 打包版将是空库，开品模式会提示「素材库为空」，"
  echo "    需打包后对 exe 的 5020 再跑一次: node packaging/import-disney.mjs <素材根目录>"
fi

echo "===================== [6/6] 完成 ====================="
echo "exe 路径: $DIST/GOFU/GOFU.exe"
du -sh "$DIST/GOFU"
ls -la "$DIST/GOFU/app/tools/"
