package tuktu.processors.json

import play.api.libs.json.JsObject
import play.api.libs.json.JsValue
import tuktu.api._

/**
 * Takes a generic JSON field and turns it into the scala equivalent
 */
class ConvertFromJson(config: JsObject) extends BaseProcessor(config) {
    override def processDatum(datum: Datum, any: Any): Datum =
        datum + (result_path -> utils.JsValueToAny(any.asInstanceOf[JsValue]))
}