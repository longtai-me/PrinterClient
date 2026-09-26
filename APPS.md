# 手持 POS 應用程式：收銀 POS ＆ 票券驗票

本專案在原廠印表機 SDK 範例（`app/`）之外，提供兩支可直接部署到 NYX 手持 POS（內建熱感應印表機／標籤機、NFC、條碼掃描頭）的正式 App：

| App | 模組 | 套件名稱 | 用途 |
|---|---|---|---|
| 收銀 POS | `pos-app` | `me.longtai.pos` | 零售結帳收銀、會員、庫存、交班報表 |
| 票券驗票 | `ticket-app` | `me.longtai.ticket` | 票券發行、入出場核銷、門禁人數控管 |
| 簡訊轉發 | `sms-forward-app` | `me.longtai.smsforward` | 把本機收到的簡訊自動轉發到自己的另一支門號 |

兩支 App 都能**完全離線運作**，資料存在本機（Room / SQLite），並可匯出 CSV。

---

## 功能

### 收銀 POS（`pos-app`）

- **登入與權限**：首次啟動建立管理員；人員以 4–8 位 PIN 登入（PBKDF2 雜湊、連錯 5 次鎖定 5 分鐘）；角色分為管理員／一般人員；退貨需管理員授權。
- **開班／交班**：輸入開班零用金才能收銀；交班時清點現金，自動算出應有現金、短溢收差額，列印 **Z 報表**後登出；班中可隨時列印 **X 報表**。
- **收銀畫面**
  - 掃描頭、相機掃描 App、外接鍵盤式掃描槍都可直接加入商品；也能輸入品名／SKU 搜尋。
  - 同商品重複掃描自動累加數量；單品折扣、整單折扣（9 折、85 折或固定金額）。
  - 感應 **NFC 會員卡**或輸入會員編號／手機號帶入會員，自動套用會員折扣、累積點數。
  - 查無條碼時，管理員可一鍵新增該商品；未登記的會員卡可一鍵建立會員。
- **結帳**：支援現金、信用卡、行動支付、禮券、其他，並可**混合付款**；現金快速面額按鈕與找零計算；完成後自動列印收據、現金交易自動開錢箱。
- **稅額**：內含（台灣營業稅預設 5%）、外加、免稅三種模式，折扣依比例分攤到應稅／免稅商品。
- **商品管理**：新增／編輯／下架、庫存與安全庫存提醒、EAN-13 檢查碼驗證、**列印價格標籤**（標籤機）、CSV 匯入／匯出。
- **會員管理**：會員編號、手機、折扣、點數，感應卡片即可綁定。
- **交易紀錄**：依日期瀏覽，**掃描收據上的條碼**即可開啟該筆交易；補印收據；整筆退貨（自動回補庫存、扣回點數、列印退貨單）。
- **報表**：班別報表、營業日報表（付款方式、熱銷商品、折扣、稅額、客單價），可列印；每月交易明細 CSV 供會計對帳。

### 票券驗票（`ticket-app`）

- **驗票主畫面**：大面積綠色「通過」／紅色「拒絕」全螢幕提示＋提示音與震動，數秒後自動回到待掃描；即時顯示今日通過／拒絕／**場內人數**。
- **輸入方式**：掃描 QR／條碼、相機掃描、感應 NFC 卡（依卡號 UID，或卡片 NDEF 內存的票號）、手動輸入票號（自動修正 O/0、I/1 易混淆字元）。
- **驗票規則**
  - 單次票、多次票（N 次）、期間通行證（不限次數）。
  - 有效期間（開始／結束時間）、作廢票券、可入區域（閘口區域比對，可多區）。
  - **防回傳**：同一張票 N 分鐘內不可再次入場。
  - **入場／出場模式**與「須先出場才能再入場」的門禁人數控管；過期或作廢的票仍可正常出場（不會把人關在場內）。
- **離線簽章 QR 票券**：發行的 QR 碼以 HMAC-SHA256 簽章，任何共用同一把金鑰的驗票機不需連網即可驗證，無法偽造；首次在某台機器掃到時自動登錄以追蹤使用次數。
- **票券管理**：批次發行（1–200 張）、綁定 NFC 卡、補印票券 QR（收據紙或標籤紙）、作廢／恢復與使用次數歸零（需管理員授權）、每張票的驗票歷史、CSV 匯入／匯出。
- **紀錄與統計**：依日期與結果篩選、閘口日報列印、驗票紀錄 CSV 匯出。
- **多台閘機共用金鑰**：在設定中列印「金鑰 QR」，另一台在設定畫面直接掃描即可匯入，並以金鑰指紋確認一致。

### 簡訊轉發（`sms-forward-app`）

把這台裝置收到的簡訊，自動轉發到你自己的另一支門號（個人備份／雙機情境）。刻意設計成**透明、由裝置持有者控制**：

- **預設關閉**，要在 App 內手動開啟，並由 Android 系統要求授予「接收簡訊」「傳送簡訊」權限（無法略過）。
- 開啟期間狀態列顯示一則**常駐通知**「簡訊轉發運作中 → 09xx」，讓使用這台裝置的人都知道簡訊正在被轉發，永遠不會偷偷進行。
- 目標門號、開關、轉發紀錄都在畫面上；每次轉發也會有一則通知。
- **迴圈防護**：不會轉發來自目標門號本身的簡訊；短時間重複的同一則簡訊會自動去重。
- 可選「關鍵字篩選」（例如只轉發含「驗證碼」的簡訊）；留空＝全部轉發。
- 轉發的是副本，**原始簡訊仍會正常留在本機收件匣**。
- 開機後若原本是開啟狀態，會自動恢復常駐通知。
- 內建「傳送測試訊息」確認門號設定正確。

> ⚠️ **使用限制**：請僅在「這台裝置與目標門號都是你本人所有」時使用。轉發他人不知情的簡訊（尤其含驗證碼）在多數地區屬違法，此 App 也刻意不做任何隱藏或背景偷錄的行為。
>
> ⚠️ **上架限制**：Google Play 對簡訊權限（`RECEIVE_SMS`／`SEND_SMS`）審核極嚴，一般 App 無法取得；此 App 適合**自用側載（sideload）**安裝。若要上架需另行向 Google 申請權限用途豁免。
>
> ⚠️ 轉發是透過電信簡訊發送，**每則會依你的資費計費**；請留意量大時的費用。

### 兩支 POS／票券 App 共用

- **裝置設定**：紙寬 58/80mm、列印濃度、標籤尺寸（寬／高／間距 mm）、標籤學習與自動定位、錢箱、提示音／震動、鍵盤式掃描器開關、測試頁／測試標籤、NFC 狀態檢查。
- **模擬列印**：在沒有印表機的一般手機或模擬器上，列印內容會以畫面預覽顯示，方便開發與展示。
- **人員管理**：新增人員、重設 PIN、變更角色、停用帳號（系統會保留至少一位管理員）。

---

## 架構

```
                 ┌──────────┐        ┌─────────────┐
                 │ pos-app  │        │ ticket-app  │      Android App（Compose + Hilt + Room）
                 └────┬─────┘        └──────┬──────┘
          ┌───────────┼───────────┬─────────┼────────────┐
          ▼           ▼           ▼         ▼            ▼
    pos-domain    core-auth    core-ui   ticket-domain              純 Kotlin 業務邏輯（可在 JVM 上單元測試）
          │           │           │         │
          │           └────►──────┤         │
          │                       ▼         │
          │                 core-hardware   │        印表機 AIDL／掃描／NFC／回饋
          │                       │         │
          └──────────►──── core-common ◄────┘        金額、列印文件模型、CSV、雜湊、條碼檢查…
```

| 模組 | 類型 | 內容 |
|---|---|---|
| `core-common` | Kotlin/JVM | `Money`（以最小單位整數計算，不用浮點數）、列印文件模型與等寬預覽排版（中文字寬 2）、CSV（RFC 4180＋公式注入防護）、鍵盤式掃描解碼、PBKDF2、Base64Url、NDEF 解析、印表機錯誤碼中文說明 |
| `pos-domain` | Kotlin/JVM | 購物車、計價（折扣／會員／稅）、混合付款、訂單、班別與日報統計、收據／報表／價格標籤排版、CSV |
| `ticket-domain` | Kotlin/JVM | 驗票規則、簽章票券編解碼、驗票引擎、票券與憑證排版、CSV |
| `core-hardware` | Android lib | 印表機服務綁定（自動重連、列印佇列序列化）、`PrintDocument` → AIDL 呼叫、標籤模式、模擬印表機、掃描頭廣播／相機 App／鍵盤式掃描、NFC Reader Mode、提示音震動 |
| `core-ui` | Android lib | Compose 主題、數字鍵盤、對話框、生命週期感知的掃描／NFC 事件、裝置設定畫面、CSV 檔案讀寫 |
| `core-auth` | Android lib | 人員帳號（Room）、登入／首次設定／人員管理畫面、管理員授權 |
| `app` | Android App | 原廠 SDK 範例（保留，已升級為 AGP 8 可建置） |

技術棧：Kotlin 2.1、Jetpack Compose（Material 3）、MVVM（ViewModel + StateFlow）、Hilt、Room、DataStore、Navigation Compose、Coroutines；Gradle 8.11 / AGP 8.7；minSdk 24、targetSdk 35。

設計重點：

- 所有金額以整數最小單位計算，四捨五入規則集中在 `Rounding`。
- 結帳、退貨、驗票等多表寫入都在單一資料庫交易中完成；驗票以 Mutex 逐筆處理，避免重複掃描同一張票時雙重扣次。
- 掃描與 NFC 事件只送到「目前顯示中的畫面」，背景頁面不會誤收。
- 印表機呼叫全部序列化並在背景執行緒執行；錯誤碼轉為中文訊息（缺紙、紙倉蓋未關、過熱、標籤定位失敗…）。

---

## 建置與安裝

需求：Android Studio（Ladybug 或更新版本）、JDK 17 以上。

```bash
# 除錯版
./gradlew :pos-app:assembleDebug        # 產出 pos-app/build/outputs/apk/debug/
./gradlew :ticket-app:assembleDebug

./gradlew :sms-forward-app:assembleDebug

# 直接安裝到已連線的裝置
./gradlew :pos-app:installDebug
./gradlew :ticket-app:installDebug
./gradlew :sms-forward-app:installDebug

# 業務邏輯單元測試（48 項）
./gradlew :core-common:test :pos-domain:test :ticket-domain:test
```

正式版簽章：在專案根目錄建立 `keystore.properties`（已列入 `.gitignore`，請勿提交）：

```properties
storeFile=release.keystore
storePassword=********
keyAlias=pos
keyPassword=********
```

然後執行 `./gradlew :pos-app:assembleRelease :ticket-app:assembleRelease`。未提供時 release 會以 debug 金鑰簽章，僅供測試。

### 首次使用

1. 開啟 App → 建立管理員（名稱＋PIN）。
2. 進入「設定」填寫商店／活動資料、稅率或閘口規則。
3. 「設定 → 裝置設定」確認印表機狀態、紙寬，列印測試頁；使用標籤紙時設定尺寸並執行「標籤學習」。
4. 收銀 POS：到「商品管理」新增或匯入商品 → 回收銀畫面開班即可開始結帳。
5. 票券驗票：到「發行票券」建立票券（或匯入 CSV）→ 回驗票畫面掃描即可。

---

## 硬體整合說明

| 硬體 | 實作 | 說明 |
|---|---|---|
| 熱感應印表機 | `NyxPrinterService` | 綁定 `net.nyx.printerservice` AIDL 服務；斷線自動重連（指數退避）；每次列印前先檢查印表機狀態 |
| 標籤機 | `PrintMode.Label` | `labelLocate(高, 間距)` → 內容 → `labelPrintEnd()`；已做標籤學習時可改用 `labelLocateAuto` |
| 掃描頭 | `ScannerManager` | 觸發 `triggerQscScan()`，結果由 `com.android.NYX_QSC_DATA` 廣播接收 |
| 相機掃描 | `CameraScanContract` | 呼叫原廠 `net.nyx.scanner` App |
| 鍵盤式掃描槍 | `KeyboardWedgeDecoder` | 依按鍵間隔（< 60ms）判斷為掃描輸入，Enter 結束 |
| NFC | `NfcReader` | Reader Mode（A/B/F/V），讀取卡號 UID 與 NDEF 文字／URI |
| 錢箱 | `openCashBox()` | 需在裝置設定中啟用，現金交易與退貨時開啟 |

Android 11 以上的套件可見性已在 `core-hardware` 的 manifest 以 `<queries>` 宣告，不需要 `QUERY_ALL_PACKAGES` 權限。

---

## CSV 格式

檔案以 UTF-8（含 BOM，Excel 可直接開啟中文）匯出；匯入時也接受繁體中文版 Excel 另存的 Big5 CSV。

**商品**（`sku,name,price` 必填）

```csv
sku,barcode,name,price,category,unit,stock,low_stock,taxable,active
A001,4710088000019,富士蘋果,30,水果,顆,120,20,1,1
B001,,購物袋,2,,個,,,0,1
```

`stock` 空白代表不控管庫存；`taxable`、`active` 接受 1/0、true/false、是/否。以 SKU 比對，已存在者更新、不存在者新增。

**票券**（`code,type` 必填）

```csv
code,type,holder,zone,valid_from,valid_until,max_uses,nfc_uid,status,note
V001,SINGLE,王小明,A,2026-10-01 09:00,2026-10-01,,,ACTIVE,
V002,MULTI,,A,,,10,,ACTIVE,十次券
P001,PASS,林小姐,"A,B",2026-10-01,2026-12-31,,04A1B2C3,ACTIVE,年票
```

`type` 接受 SINGLE/MULTI/PASS（或 單次/多次/通行證）；日期可為 `yyyy-MM-dd` 或 `yyyy-MM-dd HH:mm`，只寫日期的結束時間代表當天 23:59:59。已存在的票券會更新資料但保留使用次數。

---

## 目前限制與後續擴充

- **電子發票**：收據印有「此為交易明細，非統一發票」。若需開立統一發票，需串接財政部電子發票加值中心 API，可在 `PosPrinter`／`SalesRepository` 擴充。
- **信用卡／行動支付**：目前記錄付款方式與授權碼，未直接串接金流 SDK（可在付款畫面加入第三方 SDK 呼叫）。
- **雲端同步**：目前為單機離線版；資料層以 Repository 隔離，可加入同步服務與後端 API。
- **退貨**為整筆退貨；部分退貨可在 `SalesRepository.refund` 延伸。
- **多語系**：介面文字目前為繁體中文。
- 部分硬體行為（掃描頭廣播、標籤定位）依原廠 SDK 文件實作，請以實機驗證並依機型微調「裝置設定」。
