# Auto Crop Album - 自动裁剪相册

基于 MediaPipe 人物检测的智能相册裁剪应用。

## 功能特性

- 📸 **图片浏览**：从系统相册加载图片，支持多种排序方式
- 🧍 **人物检测**：使用 MediaPipe Object Detector（EfficientDet）检测人物并直接得到边界框
- ✂️ **智能裁剪**：自动生成裁剪框，支持多人检测
- 🎨 **区域分割**：编辑界面左下角图标显示当前窗口的彩色分割区域，点击生成矩形裁剪框；旁边箭头切换 DeepLab、Selfie、Multiclass 三个离线模型，记住选择，默认 Multiclass。
- 🖐️ **手动调整**：拖动、缩放裁剪框，或手动创建新裁剪框
- 📐 **比例限制**：设置中的 `x:9` 控制所有裁剪框的长边/短边上限（默认 `20:9`，后项固定为 9）。去黑边、人物检测、seg、手动创建、拖动缩放及导出统一执行此限制，导出整数像素也会再次校正。
- 💾 **灵活保存**：可选择覆盖原图或保存为新文件
- 🎨 **时尚界面**：Material Design 3 深色主题

## 技术栈

- **语言**: Kotlin
- **最小 SDK**: API 24 (Android 7.0)
- **目标 SDK**: API 34 (Android 14)
- **架构**: arm64-v8a only
- **核心库**:
  - MediaPipe Tasks Vision (Object Detector / 人物检测)
  - Glide (图片加载)
  - ViewPager2 (图片滑动)
  - Material Design 3 (UI)
  - Kotlin Coroutines (异步处理)

## 构建步骤

### 1. 下载 MediaPipe 模型文件

应用需要 MediaPipe Object Detector（EfficientDet）模型文件。请按照以下步骤操作：

1. 访问 [MediaPipe Object Detector Models](https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector#models)
2. 下载 `efficientdet_lite0.tflite` 模型文件
3. 将文件放置到 `app/src/main/assets/` 目录

```bash
# 创建 assets 目录（如果不存在）
mkdir -p app/src/main/assets

# 下载模型文件
cd app/src/main/assets
wget https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/latest/efficientdet_lite0.tflite
```

> **注意**: 模型文件约 4–7MB，不包含在 Git 仓库中。若需更高精度可改用 `efficientdet_lite2.tflite`（同时修改 `PersonDetector.MODEL_ASSET_PATH`）。

### 2. 同步依赖

使用 Android Studio 打开项目，等待 Gradle 同步完成。

### 3. 构建 APK

```bash
./gradlew assembleRelease
```

生成的 APK 位于：`app/build/outputs/apk/release/`

## 项目结构

```
app/
├── src/main/
│   ├── java/com/autocrop/album/
│   │   ├── data/              # 数据层
│   │   │   ├── models/        # 数据模型
│   │   │   └── MediaStoreRepository.kt
│   │   ├── domain/            # 业务逻辑
│   │   │   ├── PersonDetector.kt
│   │   │   ├── LetterboxDetector.kt
│   │   │   ├── CropCalculator.kt
│   │   │   └── ImageProcessor.kt
│   │   └── ui/                # UI 层
│   │       ├── gallery/       # 图片浏览
│   │       ├── editor/        # 编辑器
│   │       └── common/        # 通用组件
│   ├── res/                   # 资源文件
│   ├── assets/                # 模型文件 (需手动添加)
│   └── AndroidManifest.xml
└── build.gradle.kts
```

## 使用说明

### 基本流程

1. **授予权限**: 首次启动时授予读取图片权限
2. **浏览图片**: 在网格视图中选择排序方式并浏览图片
3. **编辑图片**: 点击图片进入全屏编辑模式
4. **自动检测**: 应用自动检测人物并生成裁剪框
5. **调整裁剪**: 拖动或缩放裁剪框，或双击创建手动裁剪框
6. **保存图片**: 选择保存选项（覆盖/新建）并裁剪

### 手势操作

- **单击图片**: 在相册中打开编辑器
- **左右滑动**: 在编辑器中切换图片
- **双击**: 创建手动裁剪框
- **拖动裁剪框**: 移动位置
- **拖动角点**: 调整大小
- **点击裁剪框**: 选择不同的裁剪框（多人检测时）

### 排序选项

- **文件名**: 按文件名字母顺序
- **修改时间**: 按最后修改时间（默认降序）
- **拍摄时间**: 按拍摄时间（如果有 EXIF 信息）

## 权限说明

应用需要以下权限：

- **Android 13+**: `READ_MEDIA_IMAGES`
- **Android 10-12**: `READ_EXTERNAL_STORAGE`
- **Android 9 及以下**: `READ_EXTERNAL_STORAGE` + `WRITE_EXTERNAL_STORAGE`

## 已知限制

- 仅支持 arm64-v8a 架构（大多数现代 Android 设备）
- 不支持视频文件
- 不预加载相邻图片的检测结果（为保证性能）
- 大图片不压缩（保持原始质量，可能消耗内存）

## 开发计划

- [ ] 支持批量裁剪
- [ ] 添加更多滤镜和编辑工具
- [ ] 优化大图片处理性能
- [ ] 支持更多架构 (armeabi-v7a, x86_64)
- [ ] 添加云端备份功能

## 许可证

MIT License

## 贡献

欢迎提交 Issue 和 Pull Request！

## 联系方式

如有问题，请通过 GitHub Issues 联系。
