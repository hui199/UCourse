#!/bin/bash

# 设置Android SDK路径
if [ -z "$ANDROID_HOME" ]; then
    # 使用常见的Android SDK路径
    if [ -d "/Users/hui/Library/Android/sdk" ]; then
        export ANDROID_HOME="/Users/hui/Library/Android/sdk"
    elif [ -d "/Users/hui/Library/Developer/Xamarin/android-sdk-macosx" ]; then
        export ANDROID_HOME="/Users/hui/Library/Developer/Xamarin/android-sdk-macosx"
    fi
fi

# 将emulator目录添加到PATH
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH"

# 获取脚本的绝对路径
SCRIPT_PATH="$(readlink -f "$0")"
# 切换到项目根目录
cd "$(dirname "$(dirname "$SCRIPT_PATH")")"

# 检查是否有连接的设备
check_devices() {
    local device_count=$(adb devices | grep -v "List of devices attached" | grep -v "^$" | wc -l)
    echo $device_count
}

# 检查设备是否已完全就绪（处于device状态）
check_device_ready() {
    local ready_count=$(adb devices | grep -v "List of devices attached" | grep "device$" | wc -l)
    echo $ready_count
}

# 等待设备完全就绪
wait_for_device_ready() {
    echo "等待设备完全就绪..."
    local max_wait=120  # 最长等待120秒
    local wait_count=0
    
    while [ $(check_device_ready) -eq 0 ] && [ $wait_count -lt $max_wait ]; do
        sleep 3  # 更长的间隔，因为设备完全就绪需要时间
        ((wait_count++))
        echo "等待中... ($wait_count/$max_wait)"
    done
    
    if [ $(check_device_ready) -eq 0 ]; then
        echo "设备就绪超时！"
        return 1
    fi
    
    echo "设备已完全就绪！"
    return 0
}

# 启动模拟器
start_emulator() {
    echo "正在启动模拟器..."
    # 查找可用的模拟器 AVD 名称
    local avd_name=$(emulator -list-avds | head -1)
    
    if [ -z "$avd_name" ]; then
        echo "未找到可用的模拟器！请创建模拟器。"
        return 1
    fi
    
    echo "启动模拟器: $avd_name"
    # 在后台启动模拟器（带窗口显示）
    emulator -avd "$avd_name" &
    
    # 等待模拟器完全启动
    echo "等待模拟器启动完成..."
    local max_wait=60
    local wait_count=0
    
    while [ $(check_devices) -eq 0 ] && [ $wait_count -lt $max_wait ]; do
        sleep 2
        ((wait_count++))
        echo "等待中... ($wait_count/$max_wait)"
    done
    
    if [ $(check_devices) -eq 0 ]; then
        echo "模拟器启动超时！"
        return 1
    fi
    
    # 等待设备完全就绪
    wait_for_device_ready
    if [ $? -ne 0 ]; then
        return 1
    fi
    
    echo "模拟器启动成功！"
    return 0
}

# 检查并启动模拟器
echo "检查模拟器状态..."
if [ $(check_devices) -eq 0 ]; then
    echo "未检测到连接的设备，正在尝试启动模拟器..."
    start_emulator
    
    if [ $? -ne 0 ]; then
        echo "无法启动模拟器，构建流程终止！"
        exit 1
    fi
else
    echo "检测到连接的设备，检查设备是否完全就绪..."
    wait_for_device_ready
    if [ $? -ne 0 ]; then
        echo "设备未就绪，构建流程终止！"
        exit 1
    fi
    echo "设备已就绪，继续构建..."
fi

# 构建 debug 版本
echo "正在构建 debug 版本..."
./gradlew assembleDebug

if [ $? -eq 0 ]; then
    echo "构建成功！"
    echo "正在安装应用到模拟器..."
    # 安装到模拟器
    adb install -t app/build/outputs/apk/debug/app-debug.apk
    
    if [ $? -eq 0 ]; then
        echo "安装成功！"
        echo "正在启动应用..."
        # 启动应用
        adb shell am start -n com.pku.or.ucourse/.MainActivity
        if [ $? -eq 0 ]; then
            echo "应用启动成功！"
        else
            echo "应用启动失败！"
        fi
    else
        echo "安装失败！"
    fi
else
    echo "构建失败！"
fi

# 等待用户按回车键退出
#read -p "按回车键退出..."