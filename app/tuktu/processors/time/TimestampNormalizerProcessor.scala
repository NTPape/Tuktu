package tuktu.processors.time

import com.github.nscala_time.time.Imports._
import java.util.Locale
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import tuktu.api._

/**
 * Floors a given datetimeField, based on the timeframes. Only one timeframe is used, e.g. only years or months,
 * in which a higher timeframe has preference.
 */
class TimestampNormalizerProcessor(config: JsObject) extends BaseProcessor(config) {

    val datetimeFormat = (config \ "datetime_format").as[String]
    val datetimeLocale = (config \ "datetime_locale").as[String]
    val overwrite = (config \ "overwrite").asOpt[Boolean].getOrElse(false)
    val millis = (config \ "time" \ "millis").asOpt[Int].getOrElse(0)
    var seconds = (config \ "time" \ "seconds").asOpt[Int].getOrElse(0)
    val minutes = (config \ "time" \ "minutes").asOpt[Int].getOrElse(0)
    val hours = (config \ "time" \ "hours").asOpt[Int].getOrElse(0)
    val days = (config \ "time" \ "days").asOpt[Int].getOrElse(0)
    val months = (config \ "time" \ "months").asOpt[Int].getOrElse(0)
    val years = (config \ "time" \ "years").asOpt[Int].getOrElse(0)

    // Make sure at least a timeframe is set
    if (seconds + minutes + hours + days + months + years == 0)
        seconds = 1

    val dateTimeFormatter = DateTimeFormat.forPattern(datetimeFormat).withLocale(Locale.forLanguageTag(datetimeLocale))

    override def processDatum(datum: Datum, any: Any): Datum = {
        // Make string of it
        val str = any match {
            case a: JsString => a.value
            case _           => any.toString
        }

        // Parse
        val dt = dateTimeFormatter.parseDateTime(tuktu.api.utils.evaluateTuktuString(str, datum))
        val newDate = {
            if (years > 0) {
                val currentYear = dt.year.roundFloorCopy
                currentYear.minusYears(currentYear.year.get % years)
            } else if (months > 0) {
                val currentMonth = dt.monthOfYear.roundFloorCopy
                currentMonth.minusMonths(currentMonth.month.get % months)
            } else if (days > 0) {
                val currentDay = dt.dayOfYear.roundFloorCopy
                currentDay.minusDays(currentDay.dayOfYear.get % days)
            } else if (hours > 0) {
                val currentHours = dt.hourOfDay.roundFloorCopy
                currentHours.minusHours(currentHours.hourOfDay.get % hours)
            } else if (minutes > 0) {
                val currentMinutes = dt.minuteOfDay.roundFloorCopy
                currentMinutes.minusMinutes(currentMinutes.minuteOfDay.get % minutes)
            } else if (seconds > 0) {
                val currentSeconds = dt.secondOfDay.roundFloorCopy
                currentSeconds.minusSeconds(currentSeconds.secondOfDay.get % seconds)
            } else {
                val currentMillis = dt.millisOfDay.roundFloorCopy
                currentMillis.minusMillis(currentMillis.millisOfDay.get % millis)
            }
        }

        datum + (result_path -> newDate)
    }
}