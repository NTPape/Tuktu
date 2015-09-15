package tuktu.processors.arithmetics

import play.api.libs.json.JsObject
import tuktu.api._

class NumberToNumberProcessor(config: JsObject) extends BaseProcessor(config: JsObject) {
    val targetType = (config \ "target_type").as[String]

    override def processDatum(datum: Datum, any: Any): Datum = datum + (result_path -> convert(any))

    /**
     * Converts a field to the required target type
     */
    private def convert(value: Any): Any = {
        value match {
            case a: String => targetType match {
                case "Long"       => a.toLong
                case "Double"     => a.toDouble
                case "Float"      => a.toFloat
                case "BigDecimal" => BigDecimal(a)
                case _            => a.toInt
            }
            case a: Int => targetType match {
                case "Long"       => a.toLong
                case "Double"     => a.toDouble
                case "Float"      => a.toFloat
                case "BigDecimal" => BigDecimal(a)
                case _            => a.toInt
            }
            case a: Long => targetType match {
                case "Long"       => a
                case "Double"     => a.toDouble
                case "Float"      => a.toFloat
                case "BigDecimal" => BigDecimal(a)
                case _            => a.toInt
            }
            case a: Double => targetType match {
                case "Long"       => a.toLong
                case "Double"     => a
                case "Float"      => a.toFloat
                case "BigDecimal" => BigDecimal(a)
                case _            => a.toInt
            }
            case a: Float => targetType match {
                case "Long"       => a.toLong
                case "Double"     => a.toDouble
                case "Float"      => a
                case "BigDecimal" => BigDecimal(a.toDouble)
                case _            => a.toInt
            }
            case a: BigDecimal => targetType match {
                case "Long"       => a.toLong
                case "Double"     => a.toDouble
                case "Float"      => a.toFloat
                case "BigDecimal" => a
                case _            => a.toInt
            }
            case a: Map[_, _]   => a.mapValues(v => convert(v))
            case a: Iterable[_] => a.map(v => convert(v))
        }
    }
}