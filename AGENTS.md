# PDFBooklet / PdfSplitterAndroid — Architecture Overview

This repository builds a single-module Android app (Gradle root project name: `PdfSplitterAndroid`, module: `:app`). Despite the README title (“PDF 分割器”), the current implementation is closer to a *print/booklet generator*: it can impose pages for booklet printing, optionally split each input page into two logical pages (left/right or top/bottom), preview the imposed sheets, export a new PDF, and generate a high-resolution PDF for printing.

## 1) Project Overview

### Runtime architecture (high level)

- **App entry**: `SplashActivity` shows a Compose splash screen and launches `MainActivity` after a delay.
- **UI layer**: `MainActivity` hosts the entire Compose UI and wires file pickers, export, printing, dialogs (settings/changelog/update) and snackbar notifications.
- **State & orchestration**: `BookletViewModel` (an `AndroidViewModel`) owns the app state as a `StateFlow<BookletUiState>`. It persists:
  - last opened document URI (`SharedPreferences`),
  - selected theme (`SharedPreferences`),
  - update state from the update checker.
- **PDF processing core**: `PdfBookletProcessor` is responsible for:
  - opening PDFs via `PdfRenderer` (input `content://` URIs),
  - generating preview bitmaps for sheet view and reader/spread view,
  - writing output PDFs via `android.graphics.pdf.PdfDocument`.
- **Update flow**: `UpdateManager` checks `version.json` over HTTP(S) and can download an APK to the app’s external files directory and trigger installation via `ACTION_VIEW`.
- **Analytics**: `PdfApplication` initializes Umeng SDK.

### Important modules/files to start from

- UI + printing integration: `app/src/main/java/com/example/pdfbuilder/MainActivity.kt`
- State model + user actions: `app/src/main/java/com/example/pdfbuilder/BookletViewModel.kt`
- PDF algorithm & rendering: `app/src/main/java/com/example/pdfbuilder/PdfBookletProcessor.kt`
- Update check/download/install: `app/src/main/java/com/example/pdfbuilder/utils/UpdateManager.kt`
- SAF helpers: `app/src/main/java/com/example/pdfbuilder/util/SafUtils.kt`

### Key domain concepts (used across UI/ViewModel/processor)

- `BookletConfig`: user-selected config such as `layoutMode` (`BOOKLET` vs `NORMAL`), `splitMode` (`VERTICAL`, `HORIZONTAL`, `NONE`), `paperOrientation`, `innerMarginMm`, etc.
- “Logical pages”: if `splitMode != NONE`, each input page becomes 2 logical pages.
- “Sheets”: in booklet mode, the processor maps logical pages to a 4-up saddle-stitch order (front/back sides), including auto-added blanks to fill to a multiple of 4.

## 2) Build & Commands

### Toolchain & build settings

- Android Gradle Plugin: `8.2.2` (root `build.gradle`)
- Kotlin: `1.9.22` (root `build.gradle`)
- Java/Kotlin target: `17` (`app/build.gradle`)
- Compose enabled (`app/build.gradle`) with compiler extension `1.5.8`
- SDK levels: `compileSdk 34`, `minSdk 21`, `targetSdk 34` (`app/build.gradle`)

### Common Gradle commands

Use the Gradle wrapper from the repo root:

- Build debug APK: `./gradlew :app:assembleDebug`
- Install debug build to a device/emulator: `./gradlew :app:installDebug`
- Build release APK (signing is conditional; see Configuration): `./gradlew :app:assembleRelease`
- Run unit tests (if/when `app/src/test` exists): `./gradlew :app:testDebugUnitTest`
- Run instrumented tests on a connected device (if/when `app/src/androidTest` exists): `./gradlew :app:connectedDebugAndroidTest`
- Run Android lint: `./gradlew :app:lint`

### Release output naming

`app/build.gradle` rewrites the APK file name to `PDF小册子-v<versionName>.apk` for all variants.

## 3) Code Style

This codebase does not define additional formatter/linter tooling (no repo-level ktlint/detekt/spotless config found in Gradle scripts). When making changes, follow the patterns already present:

- **Kotlin + Compose-first**: UI is written in Compose and largely co-located in `MainActivity.kt`.
- **StateFlow-driven UI**: UI reads a single `BookletUiState` via `collectAsState()` and mutates state through `BookletViewModel` functions.
- **Immutable state updates**: state is updated via `copy(...)` on `BookletUiState`.
- **Coroutines for I/O**:
  - heavy PDF work runs on `Dispatchers.IO` (`PdfBookletProcessor` and printing/export paths),
  - UI triggers work from the `viewModelScope`.
- **Package layout conventions in this repo**:
  - `com.example.pdfbuilder` contains activities + ViewModel + PDF processor,
  - `com.example.pdfbuilder.data` contains JSON/data models and the embedded changelog list,
  - `com.example.pdfbuilder.util` / `com.example.pdfbuilder.utils` contain small helpers.

## 4) Testing

- Test dependencies are declared (JUnit4, AndroidX test, Espresso, Compose UI test) in `app/build.gradle`.
- No test source sets currently exist in this repo (`app/src/test` and `app/src/androidTest` are absent), so Gradle test tasks may be no-ops until tests are added.

When adding tests:

- Prefer pure unit tests for deterministic logic (e.g., booklet page ordering / index mapping) rather than UI tests.
- PDF rendering via `PdfRenderer` requires Android runtime; keep those in instrumented tests if needed.

## 5) Security

Security-relevant parts of this repo are tied to how it handles documents, networking, and signing:

- **Scoped Storage / SAF**: PDFs are accessed via `content://` URIs using the Storage Access Framework. `MainActivity` calls `takePersistableUriPermission(...)` for read access to the chosen source document.
- **Network permissions**: the app requests `INTERNET` and network state permissions in `AndroidManifest.xml` to support update checking and analytics.
- **In-app updates & installation**:
  - `AndroidManifest.xml` includes `REQUEST_INSTALL_PACKAGES`.
  - `UpdateManager` downloads an APK from the URL in `version.json` (with multiple proxy candidates) and triggers installation via `ACTION_VIEW` + `FileProvider`.
  - Treat `version.json` (and any hosting that serves it) as a security boundary: a changed `downloadUrl` changes what the app will download and prompt users to install.
- **Signing material is present in-repo**:
  - `gradle.properties` currently contains `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_PASSWORD` and points at a `*.jks` file.
  - Multiple keystore files are committed under the repo root.
  - Anyone working on releases should treat these as sensitive and avoid copying/logging them.
- **File sharing**: printing and update flows use `FileProvider` (`app/src/main/res/xml/file_paths.xml`) to share files from app-controlled directories.

## 6) Configuration

### App identity & versions

- `applicationId`: `com.dabaicai.pdfxiaocezi` (`app/build.gradle`)
- `versionName`/`versionCode`: set in `app/build.gradle`
- `version.json`: remote update descriptor consumed by `UpdateManager` (also contains a `downloadUrl` for the APK)

### Release signing configuration

Release signing is optional and only enabled when all four values are present (`app/build.gradle`):

- `RELEASE_STORE_FILE`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

They can come from environment variables or Gradle properties.

### Android manifest & providers

- App class: `.PdfApplication` (initializes Umeng)
- Launcher activity: `.SplashActivity` (starts `.MainActivity`)
- `FileProvider` authority: `${applicationId}.fileprovider` with paths in `app/src/main/res/xml/file_paths.xml`

## 7) 版本发布 Skill（自动发版 + 应用内更新）

本项目的“自动更新”是一个**元数据驱动**的方案：App 端只认一个远端 `version.json`，发现版本变更后下载 APK 并调起系统安装；同时仓库也提供了 GitHub Actions 的“打 tag 自动构建并创建 GitHub Release”。两者目前是**并行的分发渠道**（App 默认下载链接来自 `version.json.downloadUrl`，并不自动指向 GitHub Release 资产）。

### 7.1 App 端自动更新：它是怎么工作的

- **检查更新入口**：`BookletViewModel.checkForUpdates()` 在初始化时调用 `UpdateManager.checkForUpdate(...)`（`app/src/main/java/com/example/pdfbuilder/BookletViewModel.kt:116`）。UI 里“设置 → 检查更新”会打开更新弹窗并触发下载（`app/src/main/java/com/example/pdfbuilder/MainActivity.kt:1187` 附近）。
- **版本元数据来源**：`UpdateManager` 读取 GitHub 仓库根目录的 `version.json`（`app/src/main/java/com/example/pdfbuilder/utils/UpdateManager.kt:20`）。
- **多通道 + 竞速**：检查更新会并发请求多个候选地址（含 `ghproxy.net`、`raw.kkgithub.com`、直连 `raw.githubusercontent.com`），并用“最快返回者”作为结果（`UpdateManager.raceRequests()`）。请求 URL 会加 `?t=<timestamp>` 用于降低缓存干扰。
- **版本比较策略**：`UpdateManager.isNewVersion()` 将 `BuildConfig.VERSION_NAME` 与远端 `version` 做按段数字比较（`UpdateManager.kt:110`）。
- **下载策略**：下载同样支持多个候选地址：先 HEAD 竞速选择最快连通的 URL（`raceForBestUrl()`），失败再依次回退（`downloadAndInstall()`）。
- **安装策略**：下载到 `context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)`，再通过 `FileProvider` 生成 `content://` 并 `ACTION_VIEW` 调起安装（`UpdateManager.installApk()`）。Manifest 里包含 `REQUEST_INSTALL_PACKAGES`（`app/src/main/AndroidManifest.xml:7`）。

### 7.2 仓库端自动发版：GitHub Actions 做了什么

- Workflow：`.github/workflows/android-release.yml`
  - 触发条件：push tag `v*` 或手动触发 `workflow_dispatch`
  - 构建：`./gradlew assembleRelease`
  - 签名材料：从 `secrets.KEYSTORE_BASE64` 解码生成 `app/pdfbuilder-release.jks`
  - 发布：在 tag 构建时用 `softprops/action-gh-release@v1` 创建 GitHub Release，并上传 `app/build/outputs/apk/release/*.apk`

### 7.3 “一次版本发布”需要同步哪些地方（以当前实现为准）

- **版本号**：`app/build.gradle` 里的 `versionName` / `versionCode`
- **应用内更新元数据**：仓库根目录 `version.json`（字段包括 `version`、`versionCode`、`changelog`、`downloadUrl`、`date`）
  - 注意：`downloadUrl` 当前指向仓库内的 `releases/` 目录下 APK 的 raw 地址（示例见 `version.json:5`）。如果你想让 App 直接下载 GitHub Release 资产，需要把这里改成 Release 资产的 `browser_download_url`（代码不会自动改）。
- **应用内更新日志展示**：`app/src/main/java/com/example/pdfbuilder/data/ChangelogData.kt` 是内置更新日志数据源（UI 的“更新日志”弹窗会读取它）。
- **APK 命名约定**：`app/build.gradle` 会把产物命名为 `PDF小册子-v<versionName>.apk`（`app/build.gradle:57`），与仓库 `releases/` 目录现有文件命名一致。

### 7.4 发布与推送流程（保证旧版本可检测更新）

旧版本能否检测到更新，关键取决于它们能否从远端拿到最新的 `version.json`，以及 `downloadUrl` 是否指向一个在 `main` 上可下载的 APK。

- 1) 修改版本：更新 `app/build.gradle` 的 `versionName` / `versionCode`
- 2) 构建产物：本地或 CI 执行 `./gradlew :app:assembleRelease` 生成 `app/build/outputs/apk/release/PDF小册子-v<version>.apk`
- 3) 归档与下载地址：把 APK 放入 `releases/` 并更新 `version.json.downloadUrl` 指向 `raw.githubusercontent.com/.../releases/PDF小册子-v<version>.apk`
- 4) 更新日志：同步更新 `ChangelogData.kt` 与 `version.json.changelog`
- 5) 提交并 push 到 `main`：确保 `version.json` 和 `releases/` 下 APK 都进入远端（否则老版本即使检测到更新也无法下载）

### 7.5 releases 目录保留策略（最多保留 15 个版本）

- 规则：`releases/` 仅保留最新 15 个 `PDF小册子-v*.apk`；更老版本直接从仓库删除并随提交 push。
- 目的：控制仓库体积，同时保证 `version.json.downloadUrl` 始终指向仓库内可下载的最新 APK。

### 7.4 风险点（与当前仓库状态强相关）

- `gradle.properties` 内包含 `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_PASSWORD` 等敏感字段；仓库内也存在多个 `*.jks` 文件。任何自动化发版流程都应避免在日志/产物中泄露这些内容。
