#!/usr/bin/env bash
# ============================================================
# OmniForge Linux 打包（Phase 3 Step 7）：
#   mvn 构建 → 依赖复制 → jlink 最小运行时 → jpackage .deb
# 要求：Linux x64 + JDK 21（jpackage/jlink）+ Maven 3.9+
# 产物：omniforge-app/target/package/omniforge_0.1.0_amd64.deb
#       （deb 内 postinst 自动注册 systemd 服务 omniforge.service）
# ============================================================
set -euo pipefail

cd "$(dirname "$0")/.."

JAVA_HOME="${JAVA_HOME:?请设置 JAVA_HOME 指向 JDK 21}"

echo "==> 构建模块（跳过测试）"
mvn -q install -DskipTests

echo "==> 复制运行时依赖"
# -pl 子模块下 -DoutputDirectory 相对路径按模块目录解析（会嵌套错位成
# omniforge-app/omniforge-app/target/lib），必须用绝对路径
LIB_DIR="$(pwd)/omniforge-app/target/lib"
mvn -q -pl omniforge-app dependency:copy-dependencies \
    -DincludeScope=runtime -DoutputDirectory="$LIB_DIR" \
    -DexcludeArtifactIds=commons-logging

echo "==> jpackage .deb（含 systemd 服务注册）"
# 注：最小运行时（jlink）需 javafx-jmods 构件（当前镜像缺失）；
# 不传 --runtime-image 时 jpackage 使用完整 JDK 运行时。镜像补齐后：
#   解包 org.openjfx:javafx-jmods:21.0.5:zip → 执行 jlink 生成 target/jlink-runtime → 传 --runtime-image。
# 主 jar 必须位于 --input 目录内并以相对名引用（jpackage 语义），
# 否则 cfg 记录构建机绝对路径，产物在用户机器上无法启动
cp omniforge-app/target/omniforge-app-*.jar "$LIB_DIR"/
MAIN_JAR=$(basename "$(ls "$LIB_DIR"/omniforge-app-*.jar | head -1)")

"$JAVA_HOME/bin/jpackage" \
    --type deb \
    --name omniforge \
    --app-version 0.1.0 \
    --vendor OmniForge \
    --description "OmniForge Core 社区版 - 跨平台多智能体协作 AI 工作台" \
    --input "$LIB_DIR" \
    --main-jar "$MAIN_JAR" \
    --main-class com.omniforge.app.OmniForgeLauncher \
    --java-options "-Djdk.httpclient.allowRestrictedHeaders=connection" \
    --dest omniforge-app/target/package \
    --resource-dir scripts/linux-resource \
    --linux-package-name omniforge \
    --linux-app-category Utility \
    --linux-shortcut

echo "==> 完成：ls omniforge-app/target/package/"
ls -lh omniforge-app/target/package/
