# Workflows - راهنمای انتقال

طبق درخواست، فایل ورکفلو در پوشه جداگانه قرار داده شده است.

## نحوه انتقال به پوشه اصلی

```bash
mkdir -p .github/workflows
cp workflows_to_move/github-workflows/android-release.yml .github/workflows/android-release.yml
git add .github/workflows/android-release.yml
git commit -m "Add Android release workflow"
git push
```

## قابلیت‌های ورکفلو

- بیلد خودکار APK Release با امضای عمومی
- ساخت keystore عمومی اگر وجود نداشته باشد
- آپلود Artifact در هر Push
- ساخت GitHub Release خودکار هنگام Push Tag مثل `v1.0.0`
- امکان اجرای دستی از تب Actions

### ساخت Tag برای Release

```bash
git tag v1.0.0
git push origin v1.0.0
```

### امضای عمومی

فایل keystore در مسیر `app/keystore/tradescanner.jks` با مشخصات:

- storePassword: tradescanner
- keyAlias: tradescanner
- keyPassword: tradescanner

این keystore صرفا برای تست و بیلد عمومی است. برای انتشار در Google Play باید keystore شخصی امن بسازید و در Secrets گیت‌هاب قرار دهید.

### تنظیمات اختیاری برای Play Store (امن)

اگر خواستید keystore خصوصی استفاده کنید:

1. keystore خود را base64 کنید: `base64 -w 0 my-release.jks > keystore.b64`
2. در GitHub Repo > Settings > Secrets and variables > Actions سه Secret بسازید:
   - `KEYSTORE_BASE64`: محتوای base64
   - `KEYSTORE_PASSWORD`
   - `KEY_PASSWORD`
   - `KEY_ALIAS`
3. ورکفلو را ویرایش کنید تا از Secrets استفاده کند.
