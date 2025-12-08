#!/bin/bash

# 切换到项目根目录
cd "$(dirname "$0")/.."

# 生成release版本APKecho "正在生成release版本APK..."
./gradlew assembleRelease

# 检查构建是否成功
if [ $? -eq 0 ]; then
    echo "构建成功！"
    
    # 复制APK到release目录并重命名
    cp app/build/outputs/apk/release/app-release.apk release/UCourse.apk
    
    echo "APK已复制到: release/UCourse.apk"
    echo "构建完成！"
else
    echo "构建失败！请检查错误信息。"
    exit 1
fi