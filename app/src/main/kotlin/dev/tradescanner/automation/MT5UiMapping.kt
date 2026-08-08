package dev.tradescanner.automation

/**
 * UI mapping for MT5 Android app - contains texts to search for in accessibility tree
 * Supports English and some common translations
 */
object MT5UiMapping {
    val packageNames = listOf("net.metaquotes.metatrader5", "com.metaquotes.metatrader5")

    val newOrderTexts = listOf("New Order", "New order", "سفارش جدید")
    val quotesTabTexts = listOf("Quotes", "قیمت‌ها")
    val tradeTabTexts = listOf("Trade", "معامله", "Trading")

    val marketExecutionTexts = listOf("Market Execution", "At Market", "Instant Execution")
    val pendingOrderOptionTexts = listOf("Pending Order", "Pending order")

    val buyLimitTexts = listOf("Buy Limit", "Buy limit")
    val sellLimitTexts = listOf("Sell Limit", "Sell limit")
    val buyStopTexts = listOf("Buy Stop", "Buy stop")
    val sellStopTexts = listOf("Sell Stop", "Sell stop")

    val priceTexts = listOf("Price", "price", "قیمت")
    val slTexts = listOf("Stop Loss", "SL", "S/L", "Stop loss")
    val tpTexts = listOf("Take Profit", "TP", "T/P", "Take profit")

    val volumeTexts = listOf("Volume", "Lot", "Lots")

    val placeButtonTexts = listOf("Place", "Place order", "Buy", "Sell", "Place Order")
    val deleteTexts = listOf("Delete", "Close", "Cancel", "حذف")

    val modifyTexts = listOf("Modify", "Edit")

    // For dialogs
    val confirmTexts = listOf("Yes", "OK", "Confirm", "Done")
}

/**
 * Coordinates calibration helper - user can optionally set relative coordinates
 * for critical UI elements if accessibility search fails
 */
data class MT5Coordinates(
    var priceFieldX: Float = 0.5f,
    var priceFieldY: Float = 0.4f,
    var slFieldX: Float = 0.5f,
    var slFieldY: Float = 0.5f,
    var tpFieldX: Float = 0.5f,
    var tpFieldY: Float = 0.55f,
    var placeButtonX: Float = 0.5f,
    var placeButtonY: Float = 0.85f,
    var tradeTabX: Float = 0.8f,
    var tradeTabY: Float = 0.95f
)
