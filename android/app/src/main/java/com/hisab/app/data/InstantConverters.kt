package com.hisab.app.data

import androidx.room.TypeConverter
import java.time.Instant

/** Room has no native java.time support — stores every Instant as epoch millis. */
class InstantConverters {
    @TypeConverter
    fun fromEpochMilli(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun toEpochMilli(instant: Instant?): Long? = instant?.toEpochMilli()
}
