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
exec "$JAVA" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
     -cp "$CP" com.gofu.cloud.promptlab.PromptLab "$@"
