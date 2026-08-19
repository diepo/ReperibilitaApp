package it.reperibilita.app.data.log

import androidx.room.TypeConverter
import it.reperibilita.app.model.LogAction
import it.reperibilita.app.model.LogResult

class Converters {
    @TypeConverter
    fun fromLogAction(value: LogAction): String = value.name

    @TypeConverter
    fun toLogAction(value: String): LogAction = LogAction.valueOf(value)

    @TypeConverter
    fun fromLogResult(value: LogResult): String = value.name

    @TypeConverter
    fun toLogResult(value: String): LogResult = LogResult.valueOf(value)
}
