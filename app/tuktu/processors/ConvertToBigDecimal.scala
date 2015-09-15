package tuktu.processors

import scala.BigDecimal
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import play.api.libs.iteratee.Enumeratee
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import tuktu.api.BaseProcessor
import tuktu.api.DataPacket

/**
 * Converts a field to a BigDecimal.
 */
class ConvertToBigDecimal(config: JsObject) extends BaseProcessor(config) {
    override def process: Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM(data => Future {
        data.map(datum => datum(source_path) match {
            case None => datum
            case Some(value) => {
                val bigDecimalVal = value match {
                    case g: Traversable[Any] => g.map(AnyToBigDecimal)
                    case _                   => AnyToBigDecimal(value)
                }
                datum + (result_path -> bigDecimalVal)
            }
        })
    })

    def AnyToBigDecimal(any: Any): BigDecimal = any match {
        case g: String   => BigDecimal(g)
        case g: Integer  => BigDecimal(g)
        case g: Long     => BigDecimal(g)
        case g: Double   => BigDecimal(g)
        case g: Char     => BigDecimal(g)
        case g: JsString => BigDecimal(g.value)
        case _           => BigDecimal(any.toString)
    }
}