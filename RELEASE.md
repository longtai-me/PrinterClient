# 打包與安裝指南（Android 16 / 17）

本專案包含四個可獨立安裝的 App，皆以 **Android 16（API 36）** 為目標。
一個 `targetSdk 36` 的 APK 在 **Android 16 與 Android 17** 裝置上都能正常安裝執行
（Android 向前相容，不需要、也不建議特別去編譯 API 37 的預覽版）。

| 模組 | App | applicationId | 說明 |
|------|-----|---------------|------|
| `pos-app` | 零售結帳收銀 | `me.longtai.pos` | 離線單機 POS，整合標籤/收據列印、條碼掃描 |
| `ticket-app` | 票券／門禁核銷 | `me.longtai.ticket` | HMAC 簽章票券、NFC/條碼核銷 |
| `sms-forward-app` | 簡訊自動轉發 | `me.longtai.smsforward` | 本機簡訊轉發到門號／Telegram，附 Telegram→簡訊指令通道 |
| `nfc-card-app` | NFC 卡片模擬 | `me.longtai.nfccard` | HCE NDEF 標籤模擬器，測試 NFC 讀取流程用 |

---

## 工具鏈需求

本次已升級到 Android 16，請確認工具版本（在本機建置時需要）：

| 工具 | 版本 |
|------|------|
| Android Gradle Plugin | **8.9.1**（已設定於 `gradle/libs.versions.toml`） |
| Gradle | **8.11.1**（已設定於 wrapper） |
| Android Studio | **Meerkat 2024.3.1 Patch 1** 以上 |
| SDK Platform | **Android 16（API 36）** |
| JDK | **17** |

> 若你本機的 Android Studio 版本較舊，請先更新，否則 Gradle sync 會失敗。
> **不想升級本機工具的話，直接用下面的「方法 A：GitHub Actions」最省事** —
> 由雲端建置出 APK，本機完全不需要安裝 SDK。

---

## 方法 A：GitHub Actions 自動打包（推薦）

本專案已內建 workflow：[`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml)。
它會在 GitHub 的雲端 runner（已有 Android SDK 與網路）上建置四個 App 的 APK。

### 取得 APK

1. 把分支推上 GitHub（本工作分支 `claude/**` 已設定會觸發）。
2. 到 GitHub 專案頁 → **Actions** → 點最新一次 **Build APKs** 執行。
3. 於頁面底部 **Artifacts** 下載 `printerclient-apks.zip`，解壓縮即得：
   - `pos-app-debug.apk` / `pos-app-release.apk`
   - `ticket-app-debug.apk` / `ticket-app-release.apk`
   - `sms-forward-app-debug.apk` / `sms-forward-app-release.apk`
   - `nfc-card-app-debug.apk` / `nfc-card-app-release.apk`

> **debug 版**：未經壓縮混淆，安裝後一定能跑，適合直接灌進裝置試用。
> **release 版**：開啟 R8 壓縮/縮減，體積較小；未設定簽章祕鑰時以 debug key 簽章（仍可安裝）。
> 建議先用 **debug** 版確認功能正常，正式上線再用 release。

### 發佈成 Release（可選）

推一個 `v` 開頭的 tag，workflow 會自動把 APK 掛到 GitHub Release：

```bash
git tag v1.0.0
git push origin v1.0.0
```

---

## 方法 B：Android Studio 建置

1. 以 Android Studio（Meerkat 以上）開啟專案，等 Gradle sync 完成。
2. SDK Manager 安裝 **Android 16（API 36）** 平台。
3. 右上角選擇要建置的模組（例如 `pos-app`）。
4. 選單 **Build → Build App Bundle(s) / APK(s) → Build APK(s)**。
5. 完成後點通知的 **locate** 連結開啟輸出資料夾。

輸出路徑：`<模組>/build/outputs/apk/debug/<模組>-debug.apk`

---

## 方法 C：命令列建置

需先在本機安裝 Android SDK 並設定 `ANDROID_HOME`（或在專案根目錄建 `local.properties`，
內容 `sdk.dir=/path/to/Android/sdk`）。

```bash
# 四個 App 的 debug APK
./gradlew :pos-app:assembleDebug :ticket-app:assembleDebug \
          :sms-forward-app:assembleDebug :nfc-card-app:assembleDebug

# 單一 App 的 release APK
./gradlew :pos-app:assembleRelease
```

---

## 正式簽章（Release signing）

release 版預設在沒有祕鑰時會退回用 debug key 簽章（可安裝、但非正式簽章）。
要用自己的金鑰簽章：

### 本機

1. 產生金鑰（只需一次）：
   ```bash
   keytool -genkeypair -v -keystore release.keystore \
     -alias my-release -keyalg RSA -keysize 2048 -validity 10000
   ```
2. 複製範本並填入你的值：
   ```bash
   cp keystore.properties.example keystore.properties
   ```
   `keystore.properties` 與 `*.keystore`/`*.jks` 已被 `.gitignore` 忽略，不會被提交。
3. 再跑 `./gradlew :pos-app:assembleRelease`，即以你的金鑰簽章。

### GitHub Actions（讓雲端也用你的金鑰簽章）

在 GitHub 專案 **Settings → Secrets and variables → Actions** 新增：

| Secret | 值 |
|--------|----|
| `KEYSTORE_BASE64` | `base64 -w0 release.keystore` 的輸出 |
| `KEYSTORE_PASSWORD` | keystore 密碼 |
| `KEY_ALIAS` | 金鑰別名（如 `my-release`） |
| `KEY_PASSWORD` | 金鑰密碼 |

設定後，workflow 的 release APK 就會用你的金鑰簽章；沒設定則維持 debug 簽章。

---

## 安裝到裝置

1. 裝置開啟「允許安裝未知來源 / 安裝未知應用程式」。
2. 用 adb：
   ```bash
   adb install -r pos-app-debug.apk
   ```
   或把 APK 傳到手機後直接點開安裝。

### 各 App 需要的權限
- **pos-app / ticket-app / nfc-card-app**：安裝即用；NFC 功能需裝置支援 NFC。
- **sms-forward-app**：首次開啟會要求簡訊（接收/傳送）、通知權限；Telegram 指令通道預設關閉，
  開啟後會常駐一個前景通知（透明化設計）。詳見 [`APPS.md`](APPS.md)。

> ⚠️ 四個 applicationId 皆不同，可同時安裝、互不衝突。
