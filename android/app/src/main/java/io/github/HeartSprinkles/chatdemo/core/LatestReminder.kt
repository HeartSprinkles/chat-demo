package io.github.HeartSprinkles.chatdemo.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local device date/weekday; no guessed geolocation. Snapshot is stored with each reply. */
internal fun latestReminder(settings:Settings, now:Date=Date()):String {
    val locale=if(settings.locale=="system") Locale.getDefault() else Locale.forLanguageTag(settings.locale)
    val language=if(locale.language=="zh") "中文" else "English"
    return listOf(SimpleDateFormat("yyyy-MM-dd",locale).format(now),SimpleDateFormat("EEEE",locale).format(now),"","Android",language).joinToString(",")
}
