# 构建说明

## 项目状态

✅ **项目已完成基本实现**

所有核心功能已实现：
- MediaPipe 姿态检测集成
- 图片浏览和排序
- 自动裁剪框生成（支持多人检测）
- 手动裁剪框调整
- 图片裁剪和保存
- Material Design 3 深色主题

## 文件结构

```
auto_clip_album/
├── app/
│   ├── src/main/
│   │   ├── assets/
│   │   │   └── pose_landmarker_heavy.task  (30MB - 已下载)
│   │   ├── java/com/autocrop/album/
│   │   │   ├── data/                        # 数据层
│   │   │   ├── domain/                      # 业务逻辑
│   │   │   └── ui/                          # UI 层
│   │   ├── res/                             # 资源文件
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── README.md                                 # 用户文档
├── SETUP.md                                  # 设置指南
├── TECHNICAL_DESIGN.md                       # 技术设计
└── BUILD_NOTES.md                            # 本文件
```

## 快速开始

### 前置条件

- Android Studio (Electric Eel+)
- JDK 17+
- Android SDK (API 24-34)

### 构建步骤

1. **打开项目**
   ```bash
   # 在 Android Studio 中打开项目
   File → Open → 选择 auto_clip_album 目录
   ```

2. **Gradle 同步**
   - Android Studio 会自动提示同步
   - 或手动点击 `File → Sync Project with Gradle Files`

3. **构建 Debug APK**
   ```bash
   ./gradlew assembleDebug
   ```
   输出：`app/build/outputs/apk/debug/app-debug.apk`

4. **构建 Release APK**
   ```bash
   ./gradlew assembleRelease
   ```
   输出：`app/build/outputs/apk/release/app-release.apk`

### 安装到设备

```bash
# 通过 ADB 安装
adb install app/build/outputs/apk/debug/app-debug.apk

# 或通过 Gradle
./gradlew installDebug
```

## 已知问题和注意事项

### ⚠️ 必须完成的步骤

1. **模型文件**: ✅ 已下载 `pose_landmarker_heavy.task` (30MB)

2. **Launcher Icons**: ⚠️ 当前使用占位图标
   - 建议使用 Android Studio 的 Image Asset Studio 生成正式图标
   - 或从 `SETUP.md` 获取详细说明

3. **签名配置**: ⚠️ Release 版本未配置签名
   - 当前 Release APK 使用 debug 签名
   - 生产环境需要配置正式签名密钥

### 🔧 可能需要调整的配置

1. **依赖版本**: 当前使用的库版本（2024年1月）
   - MediaPipe: 0.10.9
   - Material Design 3: 1.11.0
   - Glide: 4.16.0
   - 可根据需要更新

2. **架构支持**: 仅支持 arm64-v8a
   - 如需支持其他架构，修改 `app/build.gradle.kts`:
   ```kotlin
   ndk {
       abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
   }
   ```

3. **ProGuard**: 当前 Release 版本未启用
   - 修改 `app/build.gradle.kts` 启用代码混淆和资源压缩

## 测试

### 单元测试

当前未包含单元测试。建议添加：
- `PoseDetectorTest`
- `CropCalculatorTest`
- `ImageProcessorTest`

### UI 测试

建议使用 Espresso 添加 UI 测试：
- Gallery 浏览测试
- 裁剪框交互测试
- 权限处理测试

### 手动测试清单

- [ ] 权限授予流程
- [ ] 图片加载和排序
- [ ] 骨骼检测（单人）
- [ ] 骨骼检测（多人）
- [ ] 裁剪框拖动
- [ ] 裁剪框缩放
- [ ] 手动裁剪框创建
- [ ] 图片裁剪（覆盖原图）
- [ ] 图片裁剪（新建文件）
- [ ] 左右滑动切换图片

## 性能优化建议

1. **图片加载优化**
   - Glide 已配置缓存
   - 可考虑添加缩略图预加载

2. **MediaPipe 优化**
   - 当前使用 `heavy` 模型（准确但较慢）
   - 可尝试 `lite` 或 `full` 模型平衡性能

3. **内存管理**
   - 大图片及时回收
   - 监控 Bitmap 内存使用

## 常见构建错误

### Error: pose_landmarker_heavy.task not found
**解决**: 确认模型文件在 `app/src/main/assets/` 目录中

### Error: ViewBinding not found
**解决**:
```bash
./gradlew clean
./gradlew build
```

### Error: Execution failed for ':app:mergeDebugNativeLibs'
**解决**: MediaPipe 库较大，需要增加 Gradle 内存
```properties
# gradle.properties
org.gradle.jvmargs=-Xmx4096m
```

## 下一步

1. **添加测试**: 编写单元测试和 UI 测试
2. **性能优化**: 分析和优化应用性能
3. **功能增强**: 参考 README.md 中的开发计划
4. **发布准备**: 配置签名、ProGuard、上架准备

## 技术支持

- 技术设计: 参考 `TECHNICAL_DESIGN.md`
- 设置指南: 参考 `SETUP.md`
- 用户文档: 参考 `README.md`
- GitHub Issues: 提交问题和建议

---

**构建日期**: 2024-11-14
**版本**: 1.0
**状态**: ✅ 可构建并运行
