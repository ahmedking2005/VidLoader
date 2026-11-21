<div align="center">

# VidLoader | منزّل الفيديو

**منزّل فيديو شخصي لأندرويد — الصق أي رابط، اختر الجودة، ونزّل.**

[![Build APK](https://github.com/ahmedking2005/VidLoader/actions/workflows/build-apk.yml/badge.svg)](https://github.com/ahmedking2005/VidLoader/actions/workflows/build-apk.yml)
[![Latest Release](https://img.shields.io/github/v/release/ahmedking2005/VidLoader)](https://github.com/ahmedking2005/VidLoader/releases/latest)
[![Android](https://img.shields.io/badge/Android-API%2026%2B-green)](https://github.com/ahmedking2005/VidLoader)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-blueviolet)](https://kotlinlang.org)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

</div>

تطبيق أندرويد لتنزيل الفيديوهات والصوتيات من يوتيوب ومئات المواقع الأخرى، يعمل بمحرك **yt-dlp**، بواجهة عربية كاملة (RTL) بسيطة وغير مشتّتة. بدون حسابات، بدون إعلانات، بدون تتبّع.

## المميزات

### التنزيل
- الصق أي رابط أو شاركه من أي تطبيق — أو انسخ روابط متعددة دفعة واحدة.
- اختيار الجودة حتى **1080p** مع الحجم التقريبي لكل جودة، أو «صوت فقط» بصيغة MP3 أو M4A.
- **تنزيل قوائم التشغيل كاملة** مع اختيار العناصر واحدًا واحدًا.
- **تنزيل سريع**: جودة محفوظة مسبقًا، والتنزيل يبدأ في الخلفية فور المشاركة أو اللصق.
- **قصّ المقاطع**: نزّل جزءًا من الفيديو فقط (من وقت لوقت) بدل الفيديو كله.
- **SponsorBlock**: إزالة الإعلانات والرعاة والمقدمات من فيديوهات يوتيوب تلقائيًا.
- **الترجمة**: تنزيل ترجمة عربية/إنجليزية (.srt) بجانب الفيديو.
- ملف **كوكيز** اختياري للمحتوى الذي يتطلب تسجيل الدخول — يبقى على جهازك فقط.

### إدارة التنزيلات
- **إيقاف مؤقت واستئناف** حقيقيان — الاستئناف يكمل من حيث توقّف، حتى بعد إغلاق التطبيق.
- **طابور محفوظ** ينجو من إغلاق التطبيق، مع **إعادة محاولة تلقائية** وقائمة بالتنزيلات الفاشلة.
- **تنزيلات متوازية** بحدّ منفصل للـ Wi-Fi وبيانات الهاتف، و**حدّ لسرعة التنزيل**.
- سياسة بيانات الهاتف: التنزيلات تنتظر الـ Wi-Fi وتكمل تلقائيًا عند اتصاله.
- **مجلد تنزيل مخصص** تختاره بنفسك، أو الافتراضي `Downloads/VidLoader`.
- مكتبة بقائمتين — «قيد التنزيل» و«المكتملة» — مع فرز وإحصائيات مساحة وحذف جماعي.

### داخل التطبيق
- **متصفح مدمج** يكتشف فيديو الصفحة (يوتيوب، إنستجرام، تيك توك، فيسبوك وغيرها) ويعرض تنزيله مباشرة.
- **مشغّل وسائط مدمج** (media3) لتشغيل ما نزّلته دون مغادرة التطبيق.
- بلاطة **إعدادات سريعة** واختصار شاشة رئيسية لـ«تنزيل سريع» من أي مكان.
- **تحديثات داخل التطبيق**: تنبيه عند صدور نسخة أحدث، وزر لتحديث محرك الاستخراج (yt-dlp) نفسه.
- السمات: نظام / فاتح / داكن.

## التحميل والتثبيت

من صفحة **[Releases](https://github.com/ahmedking2005/VidLoader/releases/latest)** حمّل الملف المناسب لهاتفك:

| الملف | لمن؟ |
|---|---|
| `VidLoader-arm64-v8a.apk` | أغلب الهواتف الحديثة (64-bit) — **هذا هو الاختيار الصحيح لمعظم الناس** |
| `VidLoader-armeabi-v7a.apk` | الهواتف القديمة (32-bit) فقط |

1. افتح ملف الـ APK على الهاتف واسمح بالتثبيت من هذا المصدر عند الطلب (تثبيت جانبي — التطبيق غير موجود على Google Play).
2. يتطلب **أندرويد 8.0 (API 26) أو أحدث**.
3. التطبيق للاستخدام الشخصي.

## الاستخدام

1. انسخ رابط أي فيديو (أو شاركه إلى VidLoader من تطبيق آخر).
2. الصقه في الشاشة الرئيسية واضغط «جلب المعلومات».
3. اختر الجودة — أو «صوت فقط» — واضغط تنزيل.
4. تابع التقدّم من تبويب «المكتبة ← قيد التنزيل»، وشغّل الملف من «المكتملة».

## البناء من المصدر

```bash
git clone https://github.com/ahmedking2005/VidLoader.git
```

- افتح المشروع في **Android Studio** أو ابنِ بسطر الأوامر: `./gradlew assembleDebug`
- بناء الـ Release الموقّع يحتاج مفتاح التوقيع الخاص بالمشروع (محفوظ مشفّرًا في المستودع وتفكّه أسرار GitHub Actions) — البناء الرسمي يتم تلقائيًا عبر **GitHub Actions** عند كل دفعة إلى `main`، وتُنشر الـ APK في Releases.

## التقنيات

- **Kotlin** + واجهات Material 3
- محرك الاستخراج: **yt-dlp** عبر مكتبة [youtubedl-android](https://github.com/yausername/youtubedl-android) `0.18.1`
- **FFmpeg** للدمج والتحويل وقصّ المقاطع
- **media3 (ExoPlayer)** للمشغّل المدمج

---

## English

**VidLoader** is a personal Android video downloader with a full Arabic RTL interface. Paste or share any link (YouTube and hundreds of other sites), pick a quality up to 1080p or audio-only (MP3/M4A), and download — powered by **yt-dlp** via youtubedl-android, with FFmpeg for merging and conversion.

Highlights: full playlist downloads with per-item selection, background quick-download at a preset quality, real pause/resume, persistent queue with auto-retry, parallel downloads with per-network limits and a speed cap, clip cutting (download sections), SponsorBlock removal on YouTube, optional cookies file, a built-in browser that detects the page's video, a built-in media3 player, and in-app update checks. No accounts, no ads, no tracking.

**Download:** grab `VidLoader-arm64-v8a.apk` (most phones) or `VidLoader-armeabi-v7a.apk` (old 32-bit devices) from the [latest release](https://github.com/ahmedking2005/VidLoader/releases/latest). Requires Android 8.0 (API 26)+. Sideload only — not on Google Play.

See [CHANGELOG.md](CHANGELOG.md) for version history.

---

مشروع شخصي من تطوير **Ahmed** — للاستخدام الشخصي، ومتاح بموجب رخصة [GPL-3.0](LICENSE).
