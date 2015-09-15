package tuktu.processors.time

import tuktu.api._
import play.api.libs.json.JsObject
import java.util.Date
import org.joda.time.DateTime
import java.time.LocalDate

/*
 * Converts a date to miliseconds (unix timestamp)
 */
class DateToMillisProcessor(config: JsObject) extends BaseProcessor(config) {
    override def processDatum(datum: Datum, any: Any): Datum = {
        val value = any match {
            case d: Date      => d.getTime
            case d: DateTime  => d.getMillis
            case d: LocalDate => d.toEpochDay * 86400 * 1000
        }
        datum + (result_path -> value)
    }
}