#!/usr/bin/env bash
# 离线 prompt 台入口（生图质量攻关用）。见 gofu-server-cloud/src/test/java/com/gofu/cloud/promptlab/PromptLab.java
#
#   ./promptlab.sh --cat "家装主材>厨房>厨房挂件>锅盖架" --sku 小熊落地锅盖架 --kind main --n 3
#   ./promptlab.sh --cat ... --white D:/path/白底图.jpg --n 2 --gen --variant new
#
# 不加 --gen 就是零成本：只打印最终 prompt，不调任何 API。
set -euo pipefail
cd "$(dirname "$0")"

JAVA="${JAVA_BIN:-C:/Users/20739/.jdks/ms-21.0.10/bin/java.exe}"
CPFILE=.promptlab-cp.txt

# 依赖 classpath 变动不频繁，缓存一份；删掉该文件即重新解析。
if [ ! -f "$CPFILE" ]; then
  echo "[promptlab] 解析依赖 classpath…" >&2
  mvn -o -q -pl gofu-server-cloud dependency:build-classpath \
      -Dmdep.outputFile="$PWD/$CPFILE" -Dmdep.includeScope=test >&2
fi

echo "[promptlab] 编译…" >&2
mvn -o -q -pl gofu-server-cloud -am test-compile >&2

CP="gofu-server-cloud/target/test-classes;gofu-server-cloud/target/classes;gofu-shared/target/classes;$(cat "$CPFILE")"

# 输出编码要跟**终端**一致，否则中文全是乱码。
# Windows 的 Git Bash / cmd 默认代码页 936(GBK)，而 Java 默认按 UTF-8 输出 → 终端按 GBK 解码就成"鍒嗘瀽鍗"。
# 故按 chcp 报的代码页决定 stdout.encoding：936→GBK、65001→UTF-8、取不到就 UTF-8。
# 可用 PROMPTLAB_ENC 覆盖（如想强制 UTF-8 重定向到文件：PROMPTLAB_ENC=UTF-8 ./promptlab.sh ... > out.txt）
ENC="${PROMPTLAB_ENC:-}"
if [ -z "$ENC" ]; then
  CODEPAGE="$(chcp.com 2>/dev/null | tr -dc '0-9')"
  case "$CODEPAGE" in
    *936)   ENC=GBK ;;
    *65001) ENC=UTF-8 ;;
    *)      ENC=UTF-8 ;;
  esac
fi

exec "$JAVA" -Dfile.encoding=UTF-8 -Dstdout.encoding="$ENC" -Dstderr.encoding="$ENC" \
     -cp "$CP" com.gofu.cloud.promptlab.PromptLab "$@"
