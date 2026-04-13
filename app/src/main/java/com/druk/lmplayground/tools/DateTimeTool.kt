package com.druk.lmplayground.tools

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DateTimeTool : Tool {
    override val name = "get_current_datetime"
    override val description = "Get the current date, time, day of week, and timezone"
    override val parametersSchema = """{"type":"object","properties":{},"required":[]}"""

    override fun execute(arguments: String): String {
        val now = Date()
        val cal = Calendar.getInstance()
        cal.time = now
        val tz = TimeZone.getDefault()
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val dayFmt = SimpleDateFormat("EEEE", Locale.US)
        return """{"date":"${dateFmt.format(now)}","time":"${timeFmt.format(now)}","day_of_week":"${dayFmt.format(now)}","timezone":"${tz.id}","utc_offset":"${tz.getDisplayName(tz.inDaylightTime(now), TimeZone.SHORT, Locale.US)}"}"""
    }
}
