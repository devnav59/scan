# TradeScanner Floating - اسکنر شناور تریدینگ‌ویو به متاتریدر ۵

اپلیکیشن اندروید که به صورت پنجره شناور روی صفحه می‌آید، کل صفحه را اسکن می‌کند و دنبال ۲ علامت (سیگنال خرید/فروش) می‌گردد، عدد قیمت آن را استخراج کرده و به صورت خودکار در متاتریدر ۵ پندینگ اوردر قرار می‌دهد.

## ویژگی‌ها

### ۱. پنجره شناور (Floating Window)
- با دسترسی `SYSTEM_ALERT_WINDOW` پنجره شناور قابل جابجایی روی تمام اپ‌ها نمایش داده می‌شود
- قابلیت Drag & Drop برای جابجایی
- دابل‌تپ برای حالت Mini
- نمایش قیمت شناسایی شده، وضعیت اسکن، تعداد اسکن‌ها، SL/TP تنظیم شده

### ۲. اسکن تمام صفحه
- **روش اصلی**: MediaProjection + VirtualDisplay + ImageReader - گرفتن اسکرین‌شات هر 800ms (قابل تنظیم)
- **روش Fallback**: برای اندروید ۱۱+ از `AccessibilityService.takeScreenshot()` استفاده می‌کند که بدون نمایش نوتیفیکیشن ضبط صفحه کار می‌کند
- در حالت Split Screen یا Floating Window هم کار می‌کند (تریدینگ‌ویو و متاتریدر ۵ کنار هم)

### ۳. تشخیص ۲ علامت و استخراج عدد
دو حالت:

**حالت A: تشخیص بر اساس الگو (Template Matching)**
- کاربر می‌تواند در صفحه اصلی اپ، یک اسکرین‌شات از علامت خرید و یک اسکرین‌شات از علامت فروش (مثلا علامت اندیکاتور در تریدینگ‌ویو) انتخاب کند
- اپ با الگوریتم Normalized Cross-Correlation شبیه‌سازی می‌کند
- اگر OpenCV موجود باشد (`com.quickbirdstudios:opencv`) از `Imgproc.matchTemplate` استفاده می‌کند، در غیر این صورت پیاده‌سازی Pure Kotlin انجام می‌دهد

**حالت B: تشخیص بر اساس رنگ (پیش‌فرض، بدون نیاز به الگو)**
- اگر الگویی انتخاب نشده باشد، به صورت خودکار از تحلیل رنگ HSV استفاده می‌کند:
  - سیگنال خرید: رنگ سبز `#26A69A` یا آبی `#2962FF` (Long Position در تریدینگ‌ویو)
  - سیگنال فروش: رنگ قرمز `#EF5350`
- با Flood Fill نواحی رنگی را پیدا کرده و کاندیدها را به OCR می‌دهد

**استخراج عدد با OCR**
- بعد از یافتن bounding box علامت، ۵ ناحیه (ROI) اطراف آن (راست، چپ، بالا، خود علامت و ...) crop می‌شود
- پیش‌پردازش: upscale 3x + grayscale + binary threshold
- شناسایی متن با **ML Kit Text Recognition**
- regex برای استخراج قیمت: `\d+\.\d+` و ...

### ۴. اتوماسیون متاتریدر ۵

از طریق **AccessibilityService** انجام می‌شود:

**قرار دادن پندینگ اوردر:**
1. رفتن به تب Quotes و کلیک روی نماد (یا اولین نماد)
2. کلیک New Order
3. تغییر از Market Execution به Pending Order
4. انتخاب نوع (Buy Limit / Sell Limit / Buy Stop / Sell Stop) - حالت Auto: سیگنال BUY -> Buy Limit، SELL -> Sell Limit
5. وارد کردن Price (قیمت استخراج شده از تریدینگ‌ویو)
6. وارد کردن Volume/Lot (از تنظیمات اپ)
7. محاسبه SL/TP:
   - اگر کاربر عدد کوچک (مثل 100) وارد کند به عنوان Points/Pips در نظر گرفته می‌شود و به قیمت تبدیل می‌شود (pointSize بر اساس قیمت asset: برای طلا 0.01، برای فارکس 0.0001)
   - اگر عدد نزدیک به قیمت Entry باشد به عنوان offset مطلق
   - اگر عدد بزرگ (>50% قیمت Entry) به عنوان قیمت مطلق SL/TP
8. کلیک Place
9. تایید Confirm

**حذف پندینگ اوردر:**
- رفتن به تب Trade
- پیدا کردن اردر بر اساس قیمت (جستجوی text حاوی قیمت)
- کلیک و انتخاب Delete و تایید

**Gesture Fallback**
اگر جستجوی Node ناموفق بود، از `dispatchGesture` با مختصات نسبی استفاده می‌کند (قابل کالیبره در `MT5Coordinates`)

### ۵. منطق اصلی مدیریت اردر (در FloatingScannerService)

```kotlin
if (signals.isEmpty()) {
  noSignalCount++
  if (lastDetectedPrice != null && noSignalCount >= 4 && deleteOnDisappear) {
    deletePendingOrder(lastDetectedPrice)
    lastDetectedPrice = null
  }
} else {
  best = signals.maxByConfidence
  if (priceChangedBeyondTolerance) {
    if (hadPreviousOrder) deletePrevious()
    placeNewPendingOrder(best.price, best.type)
  }
}
```

- اگر عدد علامت تغییر کرد: اردر قبلی پاک و جدید گذاشته می‌شود
- اگر علامت کامل از صفحه رفت (۴ اسکن متوالی بدون سیگنال ~3 ثانیه): اردر حذف و منتظر علامت جدید
- Tolerance قابل تنظیم (پیش‌فرض 0.01)

### ۶. تنظیمات دستی در اپ

- Stop Loss / Take Profit (می‌تواند pip یا قیمت باشد)
- Lot Size
- Symbol Filter (اگر خالی باشد هر نمادی)
- Scan Interval (ms)
- نوع پندینگ: Auto / Buy Limit / Sell Limit / Buy Stop / Sell Stop
- انتخاب الگوهای خرید/فروش از گالری

## ساختار پروژه

```
app/src/main/kotlin/dev/tradescanner/
├── MainActivity.kt - UI تنظیمات + درخواست مجوزها
├── ScannerApplication.kt - Init OpenCV
├── service/
│   ├── FloatingScannerService.kt - ForegroundService + MediaProjection + اسکن لوپ
│   └── TradeScannerAccessibilityService.kt - اتوماسیون کلیک و تایپ در MT5
├── analyzer/
│   ├── ScreenAnalyzer.kt - ارکستریتور تشخیص
│   ├── TemplateMatcher.kt - Pure Kotlin NCC + OpenCV fallback با Reflection
│   └── PriceOcr.kt - MLKit OCR + استخراج قیمت
├── automation/
│   ├── MT5Automator.kt - منطق قرار دادن/حذف اردر
│   └── MT5UiMapping.kt - دیکشنری متون UI متاتریدر
├── model/
│   └── ScanResult.kt - DetectedSignal, OrderConfig
├── util/
│   ├── PreferencesManager.kt - ذخیره تنظیمات + Template base64
│   └── BitmapUtils.kt - color detection, flood fill, preprocess
└── ui/
    ├── MediaProjectionPermissionActivity.kt
    └── overlay/FloatingWindowManager.kt
```

## مجوزهای لازم

- `SYSTEM_ALERT_WINDOW` - پنجره شناور
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` - سرویس پیش‌زمینه و ضبط صفحه
- `POST_NOTIFICATIONS` - نوتیفیکیشن سرویس
- `BIND_ACCESSIBILITY_SERVICE` - اتوماسیون MT5

## نحوه استفاده

1. اپ را نصب کن
2. دسترسی Overlay را بده
3. دسترسی Accessibility را برای TradeScanner فعال کن
4. دسترسی MediaProjection (ضبط صفحه) را بده (اختیاری ولی پیشنهادی - اگر ندادی از Accessibility screenshot استفاده می‌کند)
5. در تنظیمات اپ SL/TP و Lot را وارد کن
6. (اختیاری) الگوی خرید/فروش را از گالری انتخاب کن - اگر نکنی تشخیص رنگ کار می‌کند
7. دکمه شروع اسکن شناور را بزن
8. تریدینگ‌ویو و متاتریدر ۵ را در حالت Split Screen باز کن یا MT5 را به صورت پنجره شناور درآور
9. در تریدینگ‌ویو اندیکاتور سیگنال‌ده را روشن کن که علامت با قیمت نمایش دهد
10. پنجره شناور وضعیت را نشان می‌دهد و به صورت خودکار اردر می‌گذارد

## بیلد و امضای عمومی

پروژه دارای امضای عمومی برای Release است:

- `app/keystore/tradescanner.jks`
- `storePassword=tradescanner`
- `keyAlias=tradescanner`
- `keyPassword=tradescanner`

### بیلد محلی

```bash
./gradlew assembleRelease
```

اگر keystore وجود ندارد:

```bash
bash app/keystore/generate_keystore.sh
```

### بیلد با GitHub Actions

طبق درخواست، ورکفلو در پوشه جداگانه `workflows_to_move/github-workflows/` قرار داده شده تا خودتان به صورت دستی به `.github/workflows/` منتقل کنید:

```bash
mkdir -p .github/workflows
cp workflows_to_move/github-workflows/android-release.yml .github/workflows/android-release.yml
```

ورکفلو شامل:

- ساخت خودکار keystore اگر نباشد
- بیلد Release APK با امضا
- آپلود APK به عنوان Artifact
- ساخت GitHub Release هنگام push تگ `v*` یا اجرای دستی

## نکات فنی مهم برای MT5

- زبان MT5 را انگلیسی کنید تا mapping بهتر کار کند
- One-Click Trading را در MT5 فعال کنید (Settings -> One Click Trading)
- برای بهترین نتیجه، MT5 را به صورت Portrait باز کنید و در تب Trade باشید وقتی اردر حذف می‌شود
- اگر اتوماسیون کار نکرد، می‌توانید در `MT5Coordinates` مختصات نسبی فیلدها را کالیبره کنید (در نسخه بعدی UI کالیبراسیون اضافه می‌شود)

## محدودیت‌ها و بهبودهای آینده

- [ ] کالیبراسیون مختصات با UI کشیدن روی صفحه
- [ ] پشتیبانی از تشخیص جفت ارز از روی علامت
- [ ] استفاده از TradingView webhook به جای OCR برای دقت بیشتر (اگر کاربر دسترسی داشته باشد)
- [ ] ذخیره لاگ کامل OCR و اسکرین‌شات برای دیباگ
- [ ] پشتیبانی از چند نماد همزمان
- [ ] تست با MT5 نسخه‌های مختلف (ساختار UI ممکن است فرق کند)

## وابستگی‌ها

- `androidx.core:core-ktx`
- `androidx.appcompat:appcompat`
- `com.google.android.material:material`
- `com.google.mlkit:text-recognition` برای OCR
- `com.quickbirdstudios:opencv:4.8.0` برای Template Matching (اختیاری، fallback Kotlin موجود است)
- `org.jetbrains.kotlinx:kotlinx-coroutines-android`

## لایسنس

MIT - استفاده عمومی

## دیسکلایمر

این اپ برای اهداف آموزشی و اتوماسیون شخصی است. استفاده از اتوماسیون در معاملات ریسک دارد. مسئولیت معاملات با خود کاربر است. اتوماسیون Accessibility ممکن است در برخی گوشی‌ها توسط سازنده محدود شده باشد (مثلاً MIUI).
