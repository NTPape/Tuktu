package tuktu.processors.json

import play.api.libs.json.JsObject
import tuktu.api._

/**
 * Converts a field to JSON.
 */
class ConvertToJson(config: JsObject) extends BaseProcessor(config) {
    override def processDatum(datum: Datum, any: Any): Datum = datum + (result_path -> utils.AnyToJsValue(any))
}