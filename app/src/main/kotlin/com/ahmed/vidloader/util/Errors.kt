package com.ahmed.vidloader.util

/** Maps raw yt-dlp / library errors to friendly Arabic messages. */
fun friendlyError(e: Throwable): String {
    val raw = (e.message ?: "").trim()
    val m = raw.lowercase()
    return when {
        m.contains("unsupported url") ->
            "هذا الموقع أو الرابط غير مدعوم من محرك الاستخراج."
        m.contains("private video") || m.contains("video is private") || m.contains("this video is private") ->
            "هذا الفيديو خاص ولا يمكن تنزيله."
        m.contains("confirm your age") || m.contains("age-restricted") || m.contains("age restricted") ->
            "هذا الفيديو مقيّد بالعمر ويتطلب تسجيل الدخول، فلا يمكن تنزيله."
        m.contains("sign in to confirm") || m.contains("not a bot") || m.contains("captcha") ->
            "الموقع يطلب تحققًا إضافيًا حاليًا. جرّب تحديث محرك الاستخراج من الإعدادات ثم أعد المحاولة."
        m.contains("requested format is not available") ->
            "الجودة المطلوبة غير متاحة لهذا الفيديو، اختر جودة أخرى."
        m.contains("video unavailable") || m.contains("this video is no longer available") || m.contains("removed") ->
            "هذا الفيديو غير متاح أو تمت إزالته."
        m.contains("unable to download") || m.contains("timed out") || m.contains("timeout") ||
            m.contains("failed to resolve") || m.contains("network is unreachable") ||
            m.contains("connection reset") || m.contains("connection refused") ||
            m.contains("unreachable host") || m.contains("http error 5") ->
            "خطأ في الاتصال أو في الموقع. تحقق من الإنترنت وحاول مجددًا."
        m.contains("http error 404") ->
            "لم يتم العثور على هذا الفيديو (404)."
        m.contains("http error 403") ->
            "الموقع رفض الطلب (403). جرّب تحديث محرك الاستخراج من الإعدادات."
        m.contains("ffmpeg") ->
            "حدثت مشكلة أثناء دمج أو تحويل الملف بعد التنزيل."
        m.contains("engine") && m.contains("init") ->
            "محرك التنزيل غير جاهز بعد، انتظر ثواني وحاول مجددًا."
        raw.isBlank() -> "حدث خطأ غير متوقع."
        else -> {
            val errorLine = raw.lineSequence()
                .firstOrNull { it.contains("ERROR", ignoreCase = true) }
                ?.substringAfter("ERROR:")?.substringAfter("ERROR")?.trim()
            if (!errorLine.isNullOrBlank()) "حدث خطأ: ${errorLine.take(180)}"
            else "حدث خطأ غير متوقع."
        }
    }
}
