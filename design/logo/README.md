# v2rayNG logo

علامت، یک سرِ روباه/گرگ زاویه‌دار است که هم‌زمان حرف **M** خوانده می‌شود؛ روی یک
کارت شیشه‌ای (frosted glass) با پس‌زمینهٔ کاملاً شفاف می‌نشیند.

همه چیز از `build.js` ساخته می‌شود؛ فایل‌ها را دستی ویرایش نکنید، اسکریپت را دوباره اجرا کنید:

```powershell
npm install sharp        # تنها وابستگی
node design/logo/build.js
```

هندسهٔ علامت در `build.js` به صورت مختصات چندضلعی آمده — از رندر اصلی trace و بعد
دوباره روی همان رندر fit شده (IoU ≈ ۰٫۹۵) و دقیقاً حول محور عمودی قرینه است.
حلقهٔ بیرونی ساعت‌گرد و همهٔ حفره‌ها پادساعت‌گرد نوشته می‌شوند، پس قانون پیش‌فرض
non-zero خودش حفره‌ها را خالی می‌کند و به `evenOdd` نیازی نیست.

## svg/  — منبع وکتور
| فایل | کاربرد |
|---|---|
| `logo-glass.svg` | لوگوی اصلی: کارت شیشه‌ای squircle + علامت، پس‌زمینه شفاف |
| `logo-glass-round.svg` | همان روی دیسک گرد — برای لانچرهایی که آیکون دایره‌ای می‌خواهند |
| `logo-mark.svg` | فقط علامت، بدون کارت — برای داخل برنامه |
| `logo-mono-white.svg` | تک‌رنگ سفید — نوار وضعیت، نوتیفیکیشن، themed icon |
| `logo-mono-black.svg` | تک‌رنگ مشکی — چاپ و پس‌زمینهٔ روشن |
| `logo-solid.svg` | همان لوگوی شیشه‌ای روی پس‌زمینهٔ مات — استور و مستندات |
| `adaptive-foreground.svg` | لایهٔ جلوی adaptive icon (علامت داخل safe zone، ۵۰dp از ۱۰۸dp) |
| `adaptive-background.svg` | لایهٔ پشت adaptive icon، full-bleed |
| `tv-banner.svg` | بنر Android TV، ۳۲۰×۱۸۰ |

## android/  — آمادهٔ کپی در `app/src/main/res`
- `drawable/ic_logo.xml` — vector drawable با گرادیان، ۲۴dp، برای layout و منو
- `drawable/ic_logo_large.xml` — همان، ۹۶dp، برای splash و صفحهٔ About
- `drawable/ic_logo_mono.xml` — سیلوئت سفید ۱۰۸dp؛ لایهٔ `<monochrome>` و آیکون tint‌پذیر
- `drawable/ic_launcher_foreground.xml` + `ic_launcher_background.xml` — لایه‌های وکتور adaptive icon
- `mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml` — تعریف adaptive icon (با `<monochrome>`)
- `mipmap-*/ic_launcher.png` — fallback رستری برای اندروید < ۸ (squircle)
- `mipmap-*/ic_launcher_round.png` — نسخهٔ دایره‌ای همان fallback
- `mipmap-*/ic_launcher_background.png`, `ic_launcher_foreground.png` — نسخهٔ رستری لایه‌ها
- `drawable-*/ic_logo.png` — fallback رستری علامت، ۲۴dp در هر تراکم
- `mipmap-anydpi-v26/ic_banner.xml` + `mipmap-xhdpi/ic_banner*.png` — بنر Android TV
  (مانیفست با `android:banner` به آن اشاره می‌کند)
- `values/logo_colors.xml` — پالت رنگ
- `values/ic_banner_background.xml` — رنگ پس‌زمینهٔ بنر TV

نصب:
```powershell
$src = "design/logo/android"
$dst = "V2rayNG/app/src/main/res"
Copy-Item "$src/*" $dst -Recurse -Force
```
`logo_colors.xml` عمداً فقط رنگ‌های `logo_*` را دارد و `ic_launcher_background` را تعریف نمی‌کند،
چون آن نام در `values/colors.xml` پروژه از قبل هست و تعریف دوباره‌اش build را می‌شکند.

نکته: `AndroidManifest.xml` به `@mipmap/ic_launcher` اشاره می‌کند و همان درست است.
آیکون نوار وضعیت (`ic_stat_name`) جدا از این مجموعه است و دست‌نخورده مانده.

## png/  — رستر شفاف
- `png/icon/logo-glass-{16..1024}.png`
- `png/mark/logo-mark-{16..1024}.png`
- `png/mono/logo-mono-{24,48,96,192,512}.png` (سفید)

زیر ۲۴px خطوط علامت نازک‌تر از یک پیکسل می‌شوند؛ برای favicon و آیکون‌های ریز
`logo-mono` خواناتر از `logo-glass` است.

## store/
- `play-store-512.png` — آیکون Google Play (۵۱۲×۵۱۲ مات)
- `logo-1024.png` — نسخهٔ شفاف بزرگ

## پالت
| نقش | کد |
|---|---|
| آبی روشن (بالای علامت) | `#4C8AC9` |
| آبی | `#2A62A8` |
| آبی میانی | `#123E7E` |
| آبی عمیق | `#08275F` |
| ink (پایین علامت) | `#041D50` |
| نقره‌ای شیشه | `#F4F6FA` → `#DCE2EC` → `#B3BFD3` |
| فولادی | `#8494AE` |

علامت دو تُن دارد: `deep` که روی کارت روشن می‌نشیند، و `lit` که روشن‌تر است تا
نسخهٔ بدون کارت روی تم تیرهٔ برنامه هم خوانده شود. روی سطح تیره، `ic_logo_mono`
با tint همیشه امن‌ترین انتخاب است.
