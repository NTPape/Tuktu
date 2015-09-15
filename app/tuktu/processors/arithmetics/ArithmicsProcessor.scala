package tuktu.processors.arithmetics

import play.api.libs.json.JsObject
import tuktu.api._

/**
 * Calculates arithmetic
 */
class ArithmeticProcessor(config: JsObject) extends BaseProcessor(config: JsObject) {
    override def processDatum(datum: Datum, any: Any): Datum = {
        val formula = utils.evaluateTuktuString(any.toString, datum)
        tuktu.utils.ArithmeticParser.readExpression(formula) match {
            case None         => datum
            case Some(result) => datum + (result_path -> result)
        }
    }
}