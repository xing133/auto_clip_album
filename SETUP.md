# 项目设置指南

## 必要步骤

### 1. 下载 MediaPipe 模型文件

这是最重要的步骤！应用无法在没有模型文件的情况下运行。

```bash
# 创建 assets 目录
mkdir -p app/src/main/assets

# 使用 wget 下载模型
cd app/src/main/assets
wget https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_heavy/float16/latest/pose_landmarker_heavy.task

# 或使用 curl
curl -L -o pose_landmarker_heavy.task https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_heavy/float16/latest/pose_landmarker_heavy.task
```

模型文件大小约 30MB。

### 2. 生成 Launcher Icons

项目需要应用图标。您可以：

**选项 A: 使用 Android Studio 自带工具**
1. 右键点击 `app/src/main/res`
2. 选择 `New` → `Image Asset`
3. 配置图标并生成

**选项 B: 使用在线工具**
1. 访问 [Android Asset Studio](https://romannurik.github.io/AndroidAssetStudio/icons-launcher.html)
2. 上传图片或选择 Material Icon
3. 下载生成的资源文件
4. 解压到 `app/src/main/res/` 目录

**选项 C: 手动创建占位图标**
```bash
# 为每个密度创建简单的占位图标
# 使用 ImageMagick 或其他工具创建不同尺寸的图标
# 或暂时使用 Android 默认图标
```

### 3. Gradle 同步

在 Android Studio 中：
1. 点击 `File` → `Sync Project with Gradle Files`
2. 等待依赖下载完成

### 4. 构建项目

```bash
# Debug 版本
./gradlew assembleDebug

# Release 版本
./gradlew assembleRelease
```

## 可选步骤

### 配置签名密钥 (Release)

1. 生成签名密钥：
```bash
keytool -genkey -v -keystore auto-crop-album.jks -keyalg RSA -keysize 2048 -validity 10000 -alias auto-crop-album
```

2. 在 `app/build.gradle.kts` 中添加签名配置：
```kotlin
signingConfigs {
    create("release") {
        storeFile = file("../auto-crop-album.jks")
        storePassword = "YOUR_PASSWORD"
        keyAlias = "auto-crop-album"
        keyPassword = "YOUR_PASSWORD"
    }
}

buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
        isMinifyEnabled = true
        // ...
    }
}
```

### 启用 ProGuard (Release)

在 `app/build.gradle.kts` 中：
```kotlin
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }
}
```

## 常见问题

### Q: 构建失败，提示找不到模型文件
A: 确保 `pose_landmarker_heavy.task` 文件在 `app/src/main/assets/` 目录中。

### Q: 应用运行时崩溃，提示找不到 pose_landmarker_heavy.task
A: 检查文件名是否正确，路径是否正确。

### Q: ViewBinding 报错
A: 确保 `build.gradle.kts` 中启用了 `viewBinding = true`，并且 Gradle 同步成功。

### Q: MediaPipe 初始化失败
A:
- 确保设备是 arm64-v8a 架构
- 检查 Android 版本 >= 7.0
- 确认模型文件完整下载

### Q: 权限被拒绝
A: 在设备设置中手动授予应用读取图片的权限。

## 开发环境要求

- **Android Studio**: Electric Eel 或更高版本
- **JDK**: 17 或更高
- **Gradle**: 8.0+
- **设备要求**:
  - Android 7.0+
  - arm64-v8a 架构
  - 至少 100MB 可用空间

## 依赖下载镜像 (可选，国内用户)

如果依赖下载速度慢，可以在 `build.gradle.kts` 中添加阿里云镜像：

```kotlin
repositories {
    maven { url = uri("https://maven.aliyun.com/repository/google") }
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    google()
    mavenCentral()
}
```

## 调试技巧

### 查看日志
```bash
adb logcat | grep -i "autocrop\|mediapipe\|pose"
```

### 检查 APK 内容
```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep assets
```

应该能看到 `assets/pose_landmarker_heavy.task`。

### 测试权限
```bash
adb shell pm grant com.autocrop.album android.permission.READ_MEDIA_IMAGES
```

## 下一步

完成设置后，请阅读 [README.md](README.md) 了解使用说明。
