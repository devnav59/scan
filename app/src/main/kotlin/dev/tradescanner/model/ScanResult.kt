package dev.tradescanner.model

data class DetectedSignal(
    val type: SignalType,
    val price: Double,
    val confidence: Float,
    val boundingBox: android.graphics.Rect,
    val rawOcrText: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class SignalType {
    BUY, SELL
}

data class OrderConfig(
    var stopLoss: String = "", // can be pips e.g. "100" or price "2045.5"
    var takeProfit: String = "",
    var lotSize: Double = 0.01,
    var symbol: String = "",
    var orderTypePref: PendingOrderType = PendingOrderType.AUTO,
    var scanIntervalMs: Long = 800,
    var priceTolerance: Double = 0.01, // if price changes less than this, ignore
    var deleteOnDisappear: Boolean = true
)

enum class PendingOrderType {
    AUTO, BUY_LIMIT, SELL_LIMIT, BUY_STOP, SELL_STOP
}

data class PendingOrderState(
    var placedPrice: Double? = null,
    var orderType: PendingOrderType? = null,
    var placedTime: Long = 0,
    var mt5OrderTicket: String? = null
)

sealed class AutomationResult {
    object Success : AutomationResult()
    data class Failure(val reason: String) : AutomationResult()
    object NotFound : AutomationResult()
}
