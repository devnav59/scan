package dev.tradescanner.automation

import android.os.SystemClock
import android.util.Log
import dev.tradescanner.model.AutomationResult
import dev.tradescanner.model.OrderConfig
import dev.tradescanner.model.PendingOrderType
import dev.tradescanner.service.TradeScannerAccessibilityService
import kotlinx.coroutines.delay
import java.lang.ref.WeakReference

/**
 * High-level automator that uses TradeScannerAccessibilityService to place/delete pending orders in MT5
 * This class runs in the Scanner service context and sends commands to the accessibility service
 */
class MT5Automator private constructor() {

    companion object {
        private const val TAG = "MT5Automator"
        @Volatile
        private var instance: MT5Automator? = null

        fun getInstance(): MT5Automator {
            return instance ?: synchronized(this) {
                instance ?: MT5Automator().also { instance = it }
            }
        }
    }

    private var serviceRef: WeakReference<TradeScannerAccessibilityService>? = null
    private var orderConfig: OrderConfig = OrderConfig()
    private var coordinates = MT5Coordinates()

    fun attachAccessibilityService(service: TradeScannerAccessibilityService) {
        serviceRef = WeakReference(service)
        Log.d(TAG, "Accessibility service attached")
    }

    fun detachAccessibilityService() {
        serviceRef?.clear()
        serviceRef = null
    }

    fun updateConfig(config: OrderConfig) {
        this.orderConfig = config
    }

    private fun getService(): TradeScannerAccessibilityService? = serviceRef?.get()

    /**
     * Main entry: place pending order with given price and signal type
     */
    suspend fun placePendingOrder(signalPrice: Double, signalType: dev.tradescanner.model.SignalType): AutomationResult {
        val service = getService() ?: return AutomationResult.Failure("Accessibility service not connected. لطفا دسترسی Accessibility را فعال کنید")

        Log.d(TAG, "placePendingOrder price=$signalPrice type=$signalType config=$orderConfig")

        // Determine order type logic
        val orderType = determineOrderType(signalPrice, signalType)

        // Try to navigate and place
        return try {
            // Step 1: Ensure we are in MT5 and go to New Order screen
            val navigated = navigateToNewOrderScreen(service)
            if (!navigated) {
                // Fallback trying direct approach
                Log.w(TAG, "navigateToNewOrderScreen failed, trying fallback gestures")
            }

            delay(600)

            // Step 2: Set order type to pending
            service.findAndClickByTexts(MT5UiMapping.marketExecutionTexts) // open selector
            delay(400)
            service.findAndClickByTexts(MT5UiMapping.pendingOrderOptionTexts)
            delay(600)

            // Step 3: Select specific pending type
            val typeTexts = when (orderType) {
                PendingOrderType.BUY_LIMIT -> MT5UiMapping.buyLimitTexts
                PendingOrderType.SELL_LIMIT -> MT5UiMapping.sellLimitTexts
                PendingOrderType.BUY_STOP -> MT5UiMapping.buyStopTexts
                PendingOrderType.SELL_STOP -> MT5UiMapping.sellStopTexts
                else -> MT5UiMapping.buyLimitTexts
            }
            service.findAndClickByTexts(typeTexts)
            delay(500)

            // Step 4: Input Price
            val priceSet = service.findAndInputText(MT5UiMapping.priceTexts, signalPrice.toString())
            if (!priceSet) {
                Log.w(TAG, "Price input via accessibility node failed, trying gesture coordinate")
                service.clickAtRelative(coordinates.priceFieldX, coordinates.priceFieldY)
                delay(300)
                service.inputTextInFocusedField(signalPrice.toString())
                delay(300)
            }
            delay(400)

            // Step 5: Input Volume / Lot
            val lotText = orderConfig.lotSize.toString()
            service.findAndInputText(MT5UiMapping.volumeTexts, lotText)
            delay(300)

            // Step 6: Calculate and input SL/TP
            // SL/TP can be given as pips (e.g., 100) or absolute price
            val slPrice = calculateSLTP(signalPrice, orderConfig.stopLoss, isSL = true, isBuy = (signalType == dev.tradescanner.model.SignalType.BUY))
            val tpPrice = calculateSLTP(signalPrice, orderConfig.takeProfit, isSL = false, isBuy = (signalType == dev.tradescanner.model.SignalType.BUY))

            if (slPrice != null) {
                val slSet = service.findAndInputText(MT5UiMapping.slTexts, slPrice.toString())
                if (!slSet) {
                    service.clickAtRelative(coordinates.slFieldX, coordinates.slFieldY)
                    delay(200)
                    service.inputTextInFocusedField(slPrice.toString())
                }
                delay(300)
            }

            if (tpPrice != null) {
                val tpSet = service.findAndInputText(MT5UiMapping.tpTexts, tpPrice.toString())
                if (!tpSet) {
                    service.clickAtRelative(coordinates.tpFieldX, coordinates.tpFieldY)
                    delay(200)
                    service.inputTextInFocusedField(tpPrice.toString())
                }
                delay(300)
            }

            // Step 7: Place order
            val placed = service.findAndClickByTexts(MT5UiMapping.placeButtonTexts)
            if (!placed) {
                Log.w(TAG, "Place button not found via node, using coordinates")
                service.clickAtRelative(coordinates.placeButtonX, coordinates.placeButtonY)
            }
            delay(1200)

            // Check if order placed? We could look for confirmation dialog and confirm
            service.findAndClickByTexts(MT5UiMapping.confirmTexts)
            delay(300)

            Log.d(TAG, "placePendingOrder success")
            AutomationResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "placePendingOrder error", e)
            AutomationResult.Failure(e.message ?: "Unknown error")
        }
    }

    /**
     * Delete pending order - try to find order in Trade tab that matches price
     */
    suspend fun deletePendingOrder(targetPrice: Double?): AutomationResult {
        val service = getService() ?: return AutomationResult.Failure("Accessibility service not connected")

        return try {
            Log.d(TAG, "deletePendingOrder targetPrice=$targetPrice")

            // Go to Trade tab
            val tradeClicked = service.findAndClickByTexts(MT5UiMapping.tradeTabTexts)
            if (!tradeClicked) {
                service.clickAtRelative(coordinates.tradeTabX, coordinates.tradeTabY)
            }
            delay(800)

            // Find pending order entry - accessibility tree may have list with price texts
            // We will search for nodes containing target price or context menu
            var deleted = false

            if (targetPrice != null) {
                // Try to find node with price text and click it
                val priceString = targetPrice.toString()
                // Search for near price (allow partial)
                val shortPrice = priceString.take(6) // e.g., 1934.5
                val found = service.findNodeContainingText(shortPrice)
                if (found != null) {
                    service.clickNode(found)
                    delay(600)
                    // After clicking order, delete option should appear
                    deleted = service.findAndClickByTexts(MT5UiMapping.deleteTexts)
                    delay(500)
                    if (deleted) {
                        service.findAndClickByTexts(MT5UiMapping.confirmTexts)
                        delay(400)
                    }
                }
            }

            if (!deleted) {
                // Fallback: try to find any pending order and delete latest
                // In MT5, pending orders have option to delete via long press or tap
                // We'll try to click first order with delete context
                Log.d(TAG, "Fallback delete: trying to find delete button directly")
                // Perform swipe or search delete text already visible
                deleted = service.findAndClickByTexts(MT5UiMapping.deleteTexts)
                if (deleted) {
                    delay(400)
                    service.findAndClickByTexts(MT5UiMapping.confirmTexts)
                }
            }

            // Even if not found, we consider success to allow placing new order
            AutomationResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "deletePendingOrder error", e)
            AutomationResult.Failure(e.message ?: "Delete failed")
        }
    }

    private suspend fun navigateToNewOrderScreen(service: TradeScannerAccessibilityService): Boolean {
        // If we are already in New Order screen (price field visible), return true
        if (service.findNodeContainingText("Price") != null || service.findNodeContainingText("Stop Loss") != null) {
            Log.d(TAG, "Already in New Order screen")
            return true
        }
        // Try quotes tab
        service.findAndClickByTexts(MT5UiMapping.quotesTabTexts)
        delay(400)

        // Try to find symbol and click for context menu
        if (orderConfig.symbol.isNotBlank()) {
            val symbolNode = service.findNodeContainingText(orderConfig.symbol)
            if (symbolNode != null) {
                service.clickNode(symbolNode)
                delay(500)
            } else {
                // click first quote entry (heuristic: click at 0.5, 0.3)
                service.clickAtRelative(0.5f, 0.3f)
                delay(500)
            }
        } else {
            // Click around middle of quotes list
            service.clickAtRelative(0.5f, 0.35f)
            delay(500)
        }

        // Look for New Order
        val newOrderClicked = service.findAndClickByTexts(MT5UiMapping.newOrderTexts)
        return newOrderClicked
    }

    private fun determineOrderType(signalPrice: Double, signalType: dev.tradescanner.model.SignalType): PendingOrderType {
        if (orderConfig.orderTypePref != PendingOrderType.AUTO) {
            return orderConfig.orderTypePref
        }
        // Auto logic: We don't have current market price here (could be fetched via MT5 but for simplicity)
        // Use signal type mapping: BUY -> Buy Limit, SELL -> Sell Limit as default per requirement
        return when (signalType) {
            dev.tradescanner.model.SignalType.BUY -> PendingOrderType.BUY_LIMIT
            dev.tradescanner.model.SignalType.SELL -> PendingOrderType.SELL_LIMIT
        }
        // Enhanced logic with market price comparison could be added later if we parse MT5 price
    }

    private fun calculateSLTP(entryPrice: Double, valueStr: String, isSL: Boolean, isBuy: Boolean): Double? {
        if (valueStr.isBlank()) return null
        return try {
            val value = valueStr.toDouble()
            // If valueStr is small (< 1000) treat as pips/points? Heuristic:
            // For XAUUSD ~2000, pips logic: if value < 500, it's points/pips
            // For forex ~1.08, if value < 5, consider as price offset? We'll treat < 1000 as points
            val isPoints = when {
                entryPrice > 1000 -> value < 500 // gold: 100 points = $1? but we treat as points
                entryPrice > 100 -> value < 100
                entryPrice > 10 -> value < 10
                else -> value < 1.0
            }

            if (isPoints) {
                // Convert points to price: need pip size estimation
                // Simplistic: 1 point = 0.01 for gold? Actually gold 0.1 = 10 cents.
                // We'll assume 1 point = 0.01 for assets >1000, 0.0001 for forex
                val pointSize = when {
                    entryPrice > 1000 -> 0.01
                    entryPrice > 100 -> 0.01
                    entryPrice > 10 -> 0.01
                    else -> 0.0001
                }
                if (isSL) {
                    if (isBuy) entryPrice - value * pointSize else entryPrice + value * pointSize
                } else {
                    if (isBuy) entryPrice + value * pointSize else entryPrice - value * pointSize
                }
            } else {
                // absolute price offset? Actually user gave absolute SL/TP? If it's close to entry, treat as offset else absolute?
                // If value > entry*0.5, treat as absolute price
                if (value > entryPrice * 0.5) value else {
                    // offset
                    if (isSL) {
                        if (isBuy) entryPrice - value else entryPrice + value
                    } else {
                        if (isBuy) entryPrice + value else entryPrice - value
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
