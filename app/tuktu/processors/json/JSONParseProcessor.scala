package tuktu.processors.json

import play.api.libs.json.JsObject
import play.api.libs.json.Json
import tuktu.api._

/**
 * Turns a field into a valid JSON object
 */
class JSONParseProcessor(config: JsObject) extends BaseProcessor(config) {
    override def processDatum(datum: Datum, any: Any): Datum = 
        datum + (result_path -> Json.parse(any.asInstanceOf[String]))
}