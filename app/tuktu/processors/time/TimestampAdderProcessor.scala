package tuktu.processors.time

import java.text.SimpleDateFormat
import play.api.libs.json.JsObject
import tuktu.api._

/**
 * Adds a simple timestamp to the data packet
 */
class TimestampAdderProcessor(config: JsObject) extends BaseProcessor(config) {
    val format = (config \ "format").asOpt[String]

    override def processDatum(datum: Datum, any: Any): Datum = {
        format match {
            case None => datum + (result_path -> System.currentTimeMillis)
            case Some(frmt) => {
                val dateFormat = new SimpleDateFormat(frmt)
                datum + (result_path -> dateFormat.format(System.currentTimeMillis))
            }
        }
    }
}