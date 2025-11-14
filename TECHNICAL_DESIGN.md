# 自动裁剪相册 - 技术设计文档

## 项目概述
基于骨骼识别的智能相册裁剪应用，使用 MediaPipe 实现人物检测和自动裁剪建议。

## 技术栈

### 开发环境
- 语言：Kotlin
- 最小 SDK：API 24 (Android 7.0)
- 目标 SDK：API 34 (Android 14)
- 目标架构：arm64-v8a only

### 核心依赖
- AndroidX Core KTX
- AndroidX AppCompat
- Material Design 3
- ViewPager2（图片滑动）
- Glide（图片加载）
- MediaPipe Pose Detection
- Kotlin Coroutines（异步处理）

## 架构设计

### 模块划分

#### 1. UI Layer
- `GalleryActivity`: 图片网格浏览界面
  - RecyclerView 展示图片缩略图
  - 排序选项：文件名、修改时间、拍摄时间
  - Toolbar 包含排序菜单

- `EditorActivity`: 全屏编辑界面
  - ViewPager2 支持左右滑动
  - 自定义 CropOverlayView 绘制裁剪框和骨骼点
  - Bottom Sheet 包含操作按钮

#### 2. Domain Layer
- `PoseDetector`: MediaPipe 封装类
  - 检测图片中的人物姿态
  - 返回骨骼关键点列表
  - 支持多人检测

- `CropCalculator`: 裁剪框计算逻辑
  - 基于骨骼点生成裁剪矩形
  - 添加边距（20% padding）
  - 宽高比检查（避免细长条，限制 1:3 到 3:1）

- `ImageProcessor`: 图片处理
  - 裁剪图片
  - 保存文件（覆盖/新建）
  - 保持 EXIF 信息

#### 3. Data Layer
- `MediaStoreRepository`: 媒体库访问
  - 查询图片列表
  - 读取图片数据
  - 保存裁剪结果

## 关键功能设计

### 多人物检测
```kotlin
data class DetectedPerson(
    val personId: Int,
    val landmarks: List<PoseLandmark>,
    val boundingBox: RectF,
    val confidence: Float
)

// 为每个人生成独立的裁剪框
// UI 上显示编号，允许用户选择
```

### 裁剪框逻辑
1. 计算骨骼点边界框
2. 添加 20% 边距
3. 检查宽高比（1:3 ≤ ratio ≤ 3:1）
4. 如果过窄，扩展短边
5. 确保不超出图片边界

### 手动裁剪
- 无人物时自动进入手动模式
- 双击创建新裁剪框
- 拖动角落调整大小
- 拖动中心移动位置

### 文件命名
```kotlin
// 格式：原文件名_cropped_时间戳.jpg
// 示例：IMG_20231114_123456_cropped_1699923456789.jpg
fun generateCroppedFileName(originalPath: String): String {
    val timestamp = System.currentTimeMillis()
    val name = File(originalPath).nameWithoutExtension
    val ext = File(originalPath).extension
    return "${name}_cropped_${timestamp}.${ext}"
}
```

## UI/UX 设计

### 主题
- Material Design 3
- 深色主题（Dark Mode）
- 色彩方案：
  - Primary: Teal (#00BCD4)
  - Secondary: Amber (#FFC107)
  - 骨骼点：Neon Green (#76FF03)
  - 骨骼连接线：Cyan (#00E5FF)
  - 裁剪框：Yellow (#FFEB3B) with 60% opacity

### 交互流程
1. 启动 → 请求权限 → Gallery
2. 点击图片 → Editor（全屏）
3. 自动检测骨骼 → 显示裁剪框
4. 用户调整/选择裁剪框
5. 选择保存选项 → 裁剪
6. 显示裁剪结果 → 可继续滑动

### 手势
- 左右滑动：切换图片
- 单击：切换 UI 显示/隐藏
- 双指缩放：预览图片（不影响裁剪）
- 拖动裁剪框：移动
- 拖动角落：调整大小

## 权限管理
```xml
<!-- Android 13+ -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />

<!-- Android 10-12 -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />

<!-- Android 9 及以下 + 写入 -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="29" />
```

## 性能考虑
- 使用 Glide 的缓存机制
- MediaPipe 检测在后台线程执行
- 不预加载相邻图片的检测结果
- 大图不压缩，保持原始质量

## 项目结构
```
app/
├── src/
│   └── main/
│       ├── java/com/autocrop/album/
│       │   ├── ui/
│       │   │   ├── gallery/
│       │   │   │   ├── GalleryActivity.kt
│       │   │   │   ├── GalleryAdapter.kt
│       │   │   │   └── ImageSorter.kt
│       │   │   ├── editor/
│       │   │   │   ├── EditorActivity.kt
│       │   │   │   ├── ImagePagerAdapter.kt
│       │   │   │   └── CropOverlayView.kt
│       │   │   └── common/
│       │   │       └── PermissionHelper.kt
│       │   ├── domain/
│       │   │   ├── PoseDetector.kt
│       │   │   ├── CropCalculator.kt
│       │   │   └── ImageProcessor.kt
│       │   ├── data/
│       │   │   ├── MediaStoreRepository.kt
│       │   │   └── models/
│       │   │       ├── ImageItem.kt
│       │   │       ├── CropBox.kt
│       │   │       └── DetectedPerson.kt
│       │   └── MainActivity.kt
│       ├── res/
│       │   ├── layout/
│       │   ├── values/
│       │   │   ├── colors.xml
│       │   │   ├── themes.xml
│       │   │   └── strings.xml
│       │   └── drawable/
│       └── AndroidManifest.xml
└── build.gradle.kts
```

## 构建配置
```kotlin
android {
    namespace = "com.autocrop.album"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildFeatures {
        viewBinding = true
    }
}
```
