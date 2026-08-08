# Keystore

این پوشه شامل امضای عمومی پروژه است.

برای بیلد ریلیز:

- فایل tradescanner.jks در همین پوشه قرار دارد (یا توسط CI ساخته می‌شود)
- مشخصات:

```
storeFile=keystore/tradescanner.jks
storePassword=tradescanner
keyAlias=tradescanner
keyPassword=tradescanner
```

برای ساخت keystore جدید به صورت دستی:
```bash
keytool -genkeypair -v -keystore tradescanner.jks -keyalg RSA -keysize 2048 -validity 10000 -alias tradescanner -storepass tradescanner -keypass tradescanner -dname "CN=TradeScanner, OU=Dev, O=TradeScanner, L=City, ST=State, C=US"
```

این keystore صرفا برای تست و بیلد عمومی است - برای انتشار در Play Store باید keystore امن خودتان را بسازید.
