package dev.tradescanner.model

data class TextTriggerConfig(
    var buyKeywords: String = "Buy, Long, BUY, خرید", // comma separated
    var sellKeywords: String = "Sell, Short, SELL, فروش",
    var detectionMode: DetectionMode = DetectionMode.TEXT_TRIGGER, // TEXT, IMAGE, COLOR, HYBRID
    var decimalRegex: String = """\d+\.\d+""", // regex for decimal price extraction, user can customize
    var searchRadiusPx: Int = 500, // how far to look for number near keyword
    var minConfidence: Float = 0.6f,
    var caseSensitive: Boolean = false
)

enum class DetectionMode {
    TEXT_TRIGGER, // فقط متن - پیشنهاد جدید کاربر
    IMAGE_TEMPLATE, // الگوی تصویر
    COLOR_DETECTION, // رنگ
    HYBRID // همه روش‌ها ترکیبی
}

fun TextTriggerConfig.parseBuyKeywords(): List<String> {
    return buyKeywords.split(",").map { it.trim() }.filter { it.isNotBlank() }
}
fun TextTriggerConfig.parseSellKeywords(): List<String> {
    return sellKeywords.split(",").map { it.trim() }.filter { it.isNotBlank() }
}
