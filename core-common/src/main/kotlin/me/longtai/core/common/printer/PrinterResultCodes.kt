package me.longtai.core.common.printer

/** Result codes returned by the NYX printer service (see vendor SdkResult). */
object PrinterResultCodes {
    const val OK = 0

    const val SDK_SENT_ERR = -1001
    const val SDK_PARAM_ERR = -1002
    const val SDK_TIMEOUT = -1003
    const val SDK_RECV_ERR = -1004
    const val SDK_UNKNOWN_ERR = -1005
    const val SDK_CMD_ERR = -1006
    const val SDK_UNKNOWN_CMD = -1015
    const val SDK_FEATURE_NOT_SUPPORT = -1099

    const val DEVICE_NOT_CONNECT = -1100
    const val DEVICE_DISCONNECT = -1101
    const val DEVICE_CONNECTED = -1102
    const val DEVICE_CONN_ERR = -1103
    const val DEVICE_NOT_SUPPORT = -1104
    const val DEVICE_NOT_FOUND = -1105
    const val DEVICE_OPEN_ERR = -1106
    const val DEVICE_NO_PERMISSION = -1107

    const val PRN_COVER_OPEN = -1201
    const val PRN_PARAM_ERR = -1202
    const val PRN_NO_PAPER = -1203
    const val PRN_OVERHEAT = -1204
    const val PRN_UNKNOWN_ERR = -1205
    const val PRN_PRINTING = -1206
    const val PRN_NO_NFC = -1207
    const val PRN_NFC_NO_PAPER = -1208
    const val PRN_LOW_BATTERY = -1209
    const val PRN_UNKNOWN_CMD = -1215
    const val PRN_LBL_LOCATE_ERR = -1290
    const val PRN_LBL_DETECT_ERR = -1291
    const val PRN_LBL_NO_DETECT = -1292

    /** Codes produced by this app (not by the service). */
    const val APP_SERVICE_UNAVAILABLE = -9001
    const val APP_REMOTE_EXCEPTION = -9002
    const val APP_BUSY_TIMEOUT = -9003

    fun describe(code: Int): String = when (code) {
        OK -> "成功"
        SDK_SENT_ERR -> "資料傳送錯誤"
        SDK_PARAM_ERR -> "資料參數錯誤"
        SDK_TIMEOUT -> "通訊逾時"
        SDK_RECV_ERR -> "資料接收錯誤"
        SDK_UNKNOWN_ERR -> "未知錯誤"
        SDK_CMD_ERR -> "收發指令不一致"
        SDK_UNKNOWN_CMD, PRN_UNKNOWN_CMD -> "未知指令"
        SDK_FEATURE_NOT_SUPPORT -> "此印表機服務版本不支援該功能"
        DEVICE_NOT_CONNECT -> "印表機未連線"
        DEVICE_DISCONNECT -> "印表機已中斷連線"
        DEVICE_CONNECTED -> "印表機已連線"
        DEVICE_CONN_ERR -> "印表機連線失敗"
        DEVICE_NOT_SUPPORT -> "裝置不支援"
        DEVICE_NOT_FOUND -> "找不到印表機"
        DEVICE_OPEN_ERR -> "印表機開啟失敗"
        DEVICE_NO_PERMISSION -> "沒有印表機權限"
        PRN_COVER_OPEN -> "印表機紙倉蓋未關閉"
        PRN_PARAM_ERR -> "列印參數錯誤"
        PRN_NO_PAPER -> "印表機缺紙"
        PRN_OVERHEAT -> "印表機過熱，請稍候再試"
        PRN_UNKNOWN_ERR -> "印表機未知異常"
        PRN_PRINTING -> "印表機忙碌中"
        PRN_NO_NFC -> "印表機無 NFC 標籤"
        PRN_NFC_NO_PAPER -> "印表機 NFC 標籤已無剩餘次數"
        PRN_LOW_BATTERY -> "電量不足，無法列印"
        PRN_LBL_LOCATE_ERR -> "標籤定位失敗，請確認標籤紙與尺寸設定"
        PRN_LBL_DETECT_ERR -> "標籤紙偵測錯誤"
        PRN_LBL_NO_DETECT -> "未偵測到標籤紙，請先執行標籤學習"
        APP_SERVICE_UNAVAILABLE -> "找不到印表機服務（net.nyx.printerservice）"
        APP_REMOTE_EXCEPTION -> "印表機服務通訊異常"
        APP_BUSY_TIMEOUT -> "等待印表機逾時"
        else -> "印表機錯誤（代碼 $code）"
    }

    /** Errors the operator can fix on the device (paper, cover, heat, battery). */
    fun isRecoverableByOperator(code: Int) = code in setOf(
        PRN_COVER_OPEN, PRN_NO_PAPER, PRN_OVERHEAT, PRN_LOW_BATTERY, PRN_PRINTING,
        PRN_LBL_LOCATE_ERR, PRN_LBL_DETECT_ERR, PRN_LBL_NO_DETECT,
    )
}
