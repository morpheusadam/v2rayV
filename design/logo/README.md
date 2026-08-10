# v2rayNG logo

همه چیز از `build.js` ساخته می‌شود؛ فایل‌ها را دستی ویرایش نکنید، اسکریپت را دوباره اجرا کنید.

## svg/  — منبع وکتور
| فایل | کاربرد |
|---|---|
| `logo-glass.svg` | لوگوی اصلی: کارت شیشه‌ای + دست، پس‌زمینه کاملاً شفاف |
| `logo-mark.svg` | فقط علامت دست، بدون کارت، شفاف — برای داخل برنامه |
| `logo-mono-white.svg` | تک‌رنگ سفید — نوار وضعیت، نوتیفیکیشن، themed icon |
| `logo-mono-black.svg` | تک‌رنگ مشکی — چاپ و پس‌زمینهٔ روشن |
| `logo-solid.svg` | همان لوگوی شیشه‌ای روی پس‌زمینهٔ مات — استور و مستندات |
| `adaptive-foreground.svg` | لایهٔ جلوی adaptive icon (محتوا داخل safe zone) |
| `adaptive-background.svg` | لایهٔ پشت adaptive icon |

## android/  — آمادهٔ کپی در `app/src/main/res`
- `drawable/ic_logo.xml` — vector drawable با گرادیان، ۲۴dp، برای استفاده در layout و منو
- `drawable/ic_logo_large.xml` — همان، ۹۶dp، برای splash و صفحهٔ About
- `drawable/ic_logo_mono.xml` — تک‌رنگ و tint‌پذیر (`android:tint`)
- `drawable/ic_launcher_foreground.xml` + `ic_launcher_background.xml` — لایه‌های وکتور adaptive icon
- `mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml` — تعریف adaptive icon
- `mipmap-*/ic_launcher*.png` — fallback رستری برای اندروید < ۸
- `drawable-*/ic_logo.png` — fallback رستری علامت، ۲۴dp در هر تراکم
- `values/logo_colors.xml` — پالت رنگ

نصب:
```powershell
$src = "design/logo/android"
$dst = "V2rayNG/app/src/main/res"
Copy-Item "$src/*" $dst -Recurse -Force
```
`logo_colors.xml` عمداً فقط رنگ‌های `logo_*` را دارد و `ic_launcher_background` را تعریف نمی‌کند،
چون آن نام در `values/colors.xml` پروژه از قبل هست و تعریف دوباره‌اش build را می‌شکند.

نکته: `AndroidManifest.xml` فعلاً به `@mipmap/ic_launcher` اشاره می‌کند و همان درست است — فایل‌های بالا جای قبلی‌ها را می‌گیرند.

## png/  — رستر شفاف
- `png/icon/logo-glass-{16..1024}.png`
- `png/mark/logo-mark-{16..1024}.png`
- `png/mono/logo-mono-{24,48,96,192,512}.png`

## store/
- `play-store-512.png` — آیکون Google Play (۵۱۲×۵۱۲ مات)
- `logo-1024.png` — نسخهٔ شفاف بزرگ

## پالت
| نقش | کد |
|---|---|
| بنفش (نوک انگشت‌ها) | `#8B6BFF` |
| نیلی | `#6A4BF5` |
| فیروزه‌ای | `#35D9A8` |
| سبز (کف دست) | `#2EE59D` |
| خط دور | `#221B47` |
