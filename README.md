# PDF 分割器 (PDF Splitter for Android)

一个简单的 Android 应用，用于将 PDF 文件的每一页对半分割成两页。本应用采用 Jetpack Compose 构建 UI，并使用 Android 平台内置的 API（`PdfRenderer` 和 `PdfDocument`）处理 PDF，完全兼容 Android 10+ 的分区存储（Scoped Storage）。

## 功能特性

- **PDF 导入**: 通过 Android 的存储访问框架 (Storage Access Framework, SAF) 安全地选择本地 PDF 文件。
- **页面分割**:
  - **垂直分割**: 将每页从中间垂直切开，生成左右两页，适用于竖排双栏的文档。
  - **水平分割**: 将每页从中间水平切开，生成上下两页，适用于横排并列的内容。
- **PDF 导出**: 将分割后的所有页面合并为一个新的 PDF 文件，并使用 SAF 让用户选择保存位置和文件名。
- **现代化 UI**: 使用 Jetpack Compose 构建的简洁、直观的用户界面。
- **实时进度反馈**: 在处理过程中显示总页数、当前处理页数和进度条，完成后提供成功提示。
- **错误处理**: 对文件读取失败、渲染异常、写入失败等情况提供清晰的错误提示。
- **轻量级实现**: 未使用任何第三方 PDF 处理库，仅依赖平台 API，保证了应用的轻量和安全性。

## 开发环境要求

- **Android Studio**: Iguana | 2023.2.1 或更高版本
- **Gradle**: 8.2 或更高版本
- **Kotlin**: 1.9.22
- **Compile SDK**: 34
- **Min SDK**: 21 (Android 5.0 Lollipop)

## 构建与运行步骤

1. **克隆或下载工程**:
   将此工程代码下载到本地。

2. **在 Android Studio 中打开**:
   - 启动 Android Studio。
   - 选择 `File -> Open` (或 `Open an existing project`)。
   - 导航到工程的根目录 `pdfsplitter-android/` 并打开。

3. **同步 Gradle**:
   Android Studio 会自动触发 Gradle Sync。等待其完成，以下载所有必要的依赖。

4. **构建并运行**:
   - 连接一台 Android 设备（真机或模拟器），确保其系统版本不低于 Android 5.0 (API 21)。
   - 点击 Android Studio 工具栏中的 `Run 'app'` 按钮 (绿色三角形图标)。
   - Gradle 将编译代码并安装 APK 到目标设备上。

## 如何测试

1. 应用启动后，点击 **“选择 PDF 文件”** 按钮。
2. 在系统的文件选择器中，找到并选择一个你想要分割的 PDF 文档。
3. 选择 **“垂直分割”** 或 **“水平分割”** 单选按钮。
4. 点击 **“导出新 PDF”** 按钮。
5. 在系统的文件保存对话框中，为新文件命名并选择一个保存位置，然后点击 **“保存”**。
6. 应用将开始处理，界面会显示进度。
7. 处理完成后，会弹出 Snackbar 提示“PDF 分割完成”，并显示新文件的 URI。你可以通过系统的文件管理器找到并查看这个新生成的 PDF。

## 技术实现细节

- **UI**: 整个界面由 Jetpack Compose 构建，包含一个 `MainActivity` 和相关的 `@Composable` 函数，状态管理由 `PdfSplitViewModel` 负责。
- **文件 I/O**:
  - **读取**: 使用 `ActivityResultContracts.OpenDocument` 获取用户选择的 PDF 文件的 `content://` URI。通过 `ContentResolver` 打开 `ParcelFileDescriptor` 来初始化 `PdfRenderer`。
  - **写入**: 使用 `ActivityResultContracts.CreateDocument` 获取目标文件的 `content://` URI，并通过 `ContentResolver` 打开 `OutputStream` 来写入 `PdfDocument` 的内容。
  - 这种方式完全遵循 SAF 规范，无需请求 `READ_EXTERNAL_STORAGE` 或 `WRITE_EXTERNAL_STORAGE` 权限，并天然兼容 Android 10 及以上版本的分区存储。
- **PDF 处理**:
  - **渲染**: `PdfRenderer` 逐页将 PDF 页面渲染成 `Bitmap`。为了防止超大页面导致内存溢出 (OOM)，我们设置了一个渲染尺寸上限（默认为 2000px），对过大的页面进行等比缩放。
  - **裁剪**: 根据用户选择的分割方向（垂直或水平），将渲染出的 `Bitmap` 对半裁剪成两个新的 `Bitmap` 对象。
  - **生成**: `android.graphics.pdf.PdfDocument` 用于创建一个新的 PDF。我们将裁剪后的两个半页 `Bitmap` 分别绘制到新 PDF 的两个独立页面上。
- **后台处理**:
  - 所有耗时的 PDF 读写和渲染操作都在 `viewModelScope` 启动的协程中执行，并切换到 `Dispatchers.IO` 线程池，避免阻塞主线程。
  - 进度通过 `StateFlow` 从 `ViewModel` 推送到 UI，驱动进度条和文本的更新。
- **内存管理**:
  - 采用逐页流式处理（`for` 循环遍历页面），每次只在内存中持有一页的 `Bitmap` 及其裁剪后的半页 `Bitmap`。
  - 在每个半页 `Bitmap` 被绘制到新 PDF 页面后，以及原始页面 `Bitmap` 处理完成后，都立即调用 `recycle()` 方法释放其占用的内存，最大限度地降低了 OOM 风险。

## 注意事项与潜在局限

- **性能与内存**:
  - 处理非常大或页面复杂的 PDF（如图形、高分辨率图像密集）可能会比较耗时。
  - 虽然已进行内存优化，但如果设备内存极低，处理超高分辨率的 PDF 仍有 OOM 风险。建议在主流设备上使用。
- **页面尺寸适配**:
  - 渲染出的位图尺寸是基于原始页面尺寸和内存限制（最大边长）计算得出的。分割是严格基于此位图尺寸的对半操作，不考虑 PDF 的内部 Box（如 CropBox, MediaBox）。对于非标准或裁剪不规则的 PDF，分割线可能不完全符合视觉中心。
- **质量**:
  - 渲染过程是有损的，从 PDF 页面到 `Bitmap` 再写回 PDF 会损失矢量信息，文本和图形会变成位图格式。对于需要无限放大的场景，质量会有所下降。
- **加密或损坏的 PDF**:
  - 应用无法处理受密码保护或已损坏的 PDF 文件。遇到此类文件时，会提示读取或渲染失败。

## 依赖说明

本项目**仅使用平台内置 API**及 Android Jetpack 核心库，未引入任何第三方 PDF 处理库。主要依赖包括：

- `androidx.core:core-ktx`
- `androidx.activity:activity-compose`
- `androidx.lifecycle:*`
- `androidx.compose.ui:*`
- `androidx.compose.material3:material3`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android`
- `androidx.documentfile:documentfile` (用于辅助 SAF 操作)
