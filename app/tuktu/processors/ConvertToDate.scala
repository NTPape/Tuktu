package tuktu.processors

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import play.api.libs.iteratee.Enumeratee
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import tuktu.api._
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Converts a String formatted Date, to an actual Java Date object.
 * By default can convert a Java Date toString formatting back to an actual Date object.
 */
class ConvertToDate(config: JsObject) extends BaseProcessor(config) {
    val format = (config \ "format").asOpt[String].getOrElse("EEE MMM dd HH:mm:ss zzz yyyy")
    val locale = (config \ "locale").asOpt[String].getOrElse("US")
    val formatter = DateTimeFormat.forPattern(format).withLocale(Locale.forLanguageTag(locale))

    override def processDatum(datum: Datum, any: Any): Datum = {
        val dateField = utils.evaluateTuktuString(any.toString, datum)
        val dateAsString = any match {
            case g: String   => g
            case g: JsString => g.value
            case g: Any      => g.toString
        }
        datum + (result_path -> formatter.parseDateTime(dateAsString))
    }
}