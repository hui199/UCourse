# UCourse - 课程助手

UCourse是一款为学生设计的课程管理应用，帮助用户便捷地管理和查看课程信息。

## 📋 功能特点

- 📅 **课表管理**：支持导入和查看课表
- 🔍 **课程搜索**：快速搜索和筛选课程信息
- ⏰ **上课提醒**：设置课程提醒，不再错过重要课程
- 📱 **友好界面**：简洁直观的用户界面，操作便捷
- 🔐 **数据安全**：本地存储课程数据，保护用户隐私

## 🚀 安装说明

### 方法一：直接安装APK
1. 下载最新版APK文件：`release/UCourse.apk`
2. 在Android设备上允许安装未知来源的应用
3. 点击安装包进行安装

### 方法二：从源代码构建
1. 克隆项目到本地：
   ```bash
   git clone <repository-url>
   ```
2. 确保已安装Android Studio和JDK
3. 使用Android Studio打开项目
4. 连接Android设备或启动模拟器
5. 点击运行按钮构建并安装应用

## 📁 项目结构

```
UCourse/
├── app/                    # 主应用模块
│   ├── src/main/           # 主要源代码
│   ├── build.gradle        # 模块构建配置
│   └── work/               # 资源工作目录
├── courses/                # 课程数据文件
├── gradle/                 # Gradle包装器
├── release/                # 发布目录
│   ├── UCourse.apk         # 正式版APK
│   ├── build_release.sh    # 构建脚本
│   └── release-key.jks     # 签名密钥
├── build.gradle            # 项目构建配置
├── settings.gradle         # 项目设置
└── README.md               # 项目说明文档
```

## 🔧 构建指南

### 生成正式版APK

使用项目提供的构建脚本可以快速生成签名的正式版APK：

```bash
# 方法一：使用构建脚本
./release/build_release.sh

# 方法二：手动执行Gradle命令
./gradlew assembleRelease
```

生成的APK将位于：`release/UCourse.apk`

### 调试版本

```bash
./gradlew assembleDebug
```

## 📝 使用说明

1. **导入课表**：在应用中选择导入功能，选择相应的课表文件
2. **查看课表**：在主界面查看每日课程安排
3. **搜索课程**：使用搜索功能查找特定课程
4. **设置提醒**：为重要课程设置提醒时间

## 🤝 贡献指南

欢迎贡献代码或提出建议！

1. Fork项目
2. 创建特性分支：`git checkout -b feature/new-feature`
3. 提交更改：`git commit -m 'Add new feature'`
4. 推送分支：`git push origin feature/new-feature`
5. 创建Pull Request

## 📄 许可证

本项目采用MIT许可证，详情请查看LICENSE文件。

## 📧 联系方式

如有问题或建议，请通过以下方式联系：

- 项目地址：https://github.com/hui199/UCourse
- 邮箱：dhzhou25@stu.pku.edu.cn

---

**UCourse - 让课程管理更简单** 🎓