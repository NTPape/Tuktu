package tuktu.processors

import java.io._
import java.lang.reflect.Method
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import akka.actor._
import akka.pattern.ask
import au.com.bytecode.opencsv.CSVWriter
import groovy.util.Eval
import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.iteratee.Concurrent
import play.api.libs.iteratee.Enumeratee
import play.api.libs.iteratee.Iteratee
import play.api.libs.json._
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import tuktu.api._
import java.text.SimpleDateFormat
import tuktu.api.utils.evaluateTuktuString
import scala.collection.mutable.ListBuffer
import scala.collection.GenTraversableOnce
import scala.collection.GenTraversable

/**
 * Renames a field
 */
class FieldRenameProcessor(config: JsObject) extends BaseProcessor(config) {
    override def processDatum(datum: Datum, any: Any): Datum =
        datum - source_path + (result_path -> any)
}

/**
 * Filters a field
 */
class FieldFilterProcessor(config: JsObject) extends BaseProcessor(config) {
    override def processDatum(datum: Datum, any: Any): Datum =
        new Datum() + (result_path -> any)
}

/**
 * Removes a field
 */
class FieldRemoveProcessor(config: JsObject) extends BaseProcessor(config) {
    override def processDatum(datum: Datum, any: Any): Datum =
        datum - source_path
}

/**
 * Adds a running count integer to data coming in
 */
class RunningCountProcessor(config: JsObject) extends BaseProcessor(config) {
    var cnt = (config \ "start_at").asOpt[Int].getOrElse(0)
    val perBlock = (config \ "per_block").asOpt[Boolean].getOrElse(false)
    val stepSize = (config \ "step_size").asOpt[Int].getOrElse(1)

    override def processDataPacket(data: DataPacket): DataPacket = {
        if (perBlock) {
            // Update counter for each DataPacket
            val res = data.map(datum => datum + (result_path -> cnt))
            cnt += stepSize
            res
        } else {
            data.map(datum => {
                // Update counter for each Datum
                val r = datum + (result_path -> cnt)
                cnt += stepSize
                r
            })
        }
    }
}

/**
 * Replaces one string for another (could be regex)
 */
class StringReplaceProcessor(config: JsObject) extends BaseProcessor(config) {
    val replacements = (config \ "replacements").as[List[JsObject]]

    def replaceHelper(accum: String, replacements: List[JsObject]): String = {
        replacements match {
            case Nil => accum
            case head :: tail =>
                replaceHelper(accum.replaceAll((head \ "source").as[String], (head \ "target").as[String]), tail)
        }
    }

    override def processAny(any: Any): Any = replaceHelper(any.toString, replacements)
}

/**
 * Includes or excludes specific datapackets
 */
class InclusionProcessor(config: JsObject) extends BaseProcessor(config) {
    // Get the groovy expression that determines whether to include or exclude
    val expression = (config \ "expression").as[String]
    // See if this is a simple or groovy expression
    val expressionType = (config \ "type").as[String]
    // Set and/or
    val andOr = (config \ "and_or").asOpt[String] match {
        case Some("or") => "or"
        case _          => "and"
    }

    override def processDataPacket(data: DataPacket): DataPacket = {
        data.filter(datum =>
            // See if we need to include this
            expressionType match {
                case "groovy" => {
                    // Replace expression with values
                    val replacedExpression = evaluateTuktuString(expression, datum)

                    try {
                        Eval.me(replacedExpression).asInstanceOf[Boolean]
                    } catch {
                        case _: Throwable => true
                    }
                }
                case "negate" => {
                    // This is a comma-separated list of field=val statements
                    val matches = expression.split(",").map(m => m.trim)
                    val evals = for (m <- matches) yield {
                        val split = m.split("=").map(s => s.trim)
                        // Get field and value and see if they match
                        datum(split(0)) == split(1)
                    }
                    // See if its and/or
                    if (andOr == "or") !evals.exists(elem => elem)
                    else evals.exists(elem => !elem)
                }
                case _ => {
                    // This is a comma-separated list of field=val statements
                    val matches = expression.split(",").map(m => m.trim)
                    val evals = (for (m <- matches) yield {
                        val split = m.split("=").map(s => s.trim)
                        // Get field and value and see if they match
                        datum(evaluateTuktuString(split(0), datum)) == evaluateTuktuString(split(1), datum)
                    }).toList
                    // See if its and/or
                    if (andOr == "or") evals.exists(elem => elem)
                    else !evals.exists(elem => !elem)
                }
            })
    }
}

/**
 * Adds a field with a constant (static) String / Long value
 */
class FieldConstantAdderProcessor(config: JsObject) extends BaseProcessor(config) {
    val value = (config \ "value").as[String]
    val isNumeric = (config \ "is_numeric").asOpt[Boolean].getOrElse(false)

    override def processDatum(datum: Datum, any: Any): Datum =
        if (!isNumeric)
            datum + (result_path -> evaluateTuktuString(value, datum))
        else
            datum + (result_path -> evaluateTuktuString(value, datum).toLong)
}

/**
 * Dumps the data to console
 */
class ConsoleWriterProcessor(config: JsObject) extends BaseProcessor(config) {
    // Turn Data to JSON and prettyPrint that, or just print Scala's standard toString of DataPacket
    val prettify = (config \ "prettify").asOpt[Boolean].getOrElse(false)

    override def processDataPacket(data: DataPacket): DataPacket = {
        if (prettify)
            data.foreach(datum => println(datum.prettyPrint))
        else {
            println(data)
            println
        }

        data
    }
}

/**
 * Implodes an array of strings into a string
 */
class ImploderProcessor(config: JsObject) extends BaseProcessor(config) {
    val separator = (config \ "separator").as[String]

    override def processAny(any: Any): Any = {
        val trav =
            if (any.isInstanceOf[JsValue])
                any.asInstanceOf[JsValue].as[TraversableOnce[String]]
            else
                any.asInstanceOf[TraversableOnce[String]]
        trav.mkString(separator)
    }
}

/**
 * Implodes arrays of maps that contain strings at a subpath into a string
 */
class MapImploderProcessor(config: JsObject) extends BaseProcessor(config) {
    val separator = (config \ "separator").as[String]
    val sub_path = (config \ "sub_path").as[List[String]]

    override def processAny(any: Any): Any = {
        if (any.isInstanceOf[JsValue]) {
            any.asInstanceOf[JsValue].as[TraversableOnce[JsObject]].map(v => {
                utils.getPathValue(v, sub_path) match {
                    case None      => None.toString
                    case Some(str) => str.as[JsString].value
                }
            }).mkString(separator)
        } else {
            try {
                any.asInstanceOf[TraversableOnce[Map[String, Any]]].map(v => {
                    utils.getPathValue(v, sub_path) match {
                        case None      => None.toString
                        case Some(str) => str.toString
                    }
                }).mkString(separator)
            } catch {
                case e: ClassCastException => Nil.mkString(separator)
            }
        }
    }
}

/**
 * Flattens a map object
 */
class FlattenerProcessor(config: JsObject) extends BaseProcessor(config) {
    // Get the field to flatten and the separator
    val separator = (config \ "separator").as[String]

    def recursiveFlattener(map: Map[String, Any], keys: List[String]): Map[String, Any] = {
        // Get the values of the map
        (for ((key, value) <- map) yield {
            if (value.isInstanceOf[Map[String, Any]])
                // Get the sub fields recursively
                recursiveFlattener(value.asInstanceOf[Map[String, Any]], key :: keys)
            else
                Map((key :: keys).reverse.mkString(separator) -> value)
        }).foldLeft(Map[String, Any]())(_ ++ _)
    }

    override def processAny(any: Any): Any = {
        val map = try {
            any.asInstanceOf[Map[String, Any]]
        } catch {
            case e: ClassCastException => Map[String, Any]()
        }
        recursiveFlattener(map, source_path.takeRight(1))
    }
}

/**
 * Takes a (JSON) sequence object and returns packets for each of the values in it
 */
class SequenceExploderProcessor(config: JsObject) extends BaseProcessor(config) {
    override def processDataPacket(data: DataPacket): DataPacket = {
        data.flatMap(datum => datum(source_path) match {
            case None => Nil
            case Some(any) =>
                if (any.isInstanceOf[Seq[Any]])
                    for (value <- any.asInstanceOf[Seq[Any]]) yield datum + (result_path -> value)
                else
                    Nil
        })
    }
}

/**
 * Splits a string up into a list of values based on a separator
 */
class StringSplitterProcessor(config: JsObject) extends BaseProcessor(config) {
    val separator = (config \ "separator").as[String]

    override def processAny(any: Any): Any = any.toString.split(separator).toList
}

/**
 * Assumes the data is a List[Map[_]] and gets one specific field from the map to remain in the list
 */
class ListMapFlattenerProcessor(config: JsObject) extends BaseProcessor(config) {
    val mapField = (config \ "map_field").as[String]

    override def processAny(any: Any): Any = {
        try {
            any.asInstanceOf[List[Map[String, Any]]].map(map => map(mapField))
        } catch {
            case e: ClassCastException => Nil
        }
    }
}

/**
 * Assumes the data is a List[Map[_]] and gets specific fields from the map to remain in the list
 */
class MultiListMapFlattenerProcessor(config: JsObject) extends BaseProcessor(config) {
    val mapFields = (config \ "map_fields").as[List[String]]

    override def processDatum(datum: Datum, any: Any): Datum = {
        val listValue = any.asInstanceOf[List[Map[String, Any]]]

        // Keep map of results
        val resultMap = collection.mutable.Map[String, ListBuffer[Any]]().withDefaultValue(ListBuffer[Any]())

        // Get the actual fields of the maps iteratively
        for (listItem <- listValue; field <- mapFields)
            resultMap(field) += listItem(field)

        // Add to our total result
        datum ++ resultMap.map(elem => (result_path ++ List(elem._1)) -> elem._2.toList).toList
    }
}

/**
 * Verifies all fields are present before sending it on
 */
class ContainsAllFilterProcessor(config: JsObject) extends BaseProcessor(config) {
    val field = (config \ "field").as[String]
    val containsField = (config \ "contains_field").as[String]
    val fieldContainingList = (config \ "field_list").as[String]

    override def process: Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM((data: DataPacket) => Future {
        val resultMap = data.map({ datum =>
            // Build the actual set of contain-values
            val containsSet = collection.mutable.Set[Any]() ++ datum(containsField).asInstanceOf[Seq[Any]]

            // Get our record
            val record = datum(fieldContainingList).asInstanceOf[List[Map[String, Any]]]

            // Do the matching
            for (rec <- record if !containsSet.isEmpty)
                containsSet -= rec(field)

            if (containsSet.isEmpty) datum
            else new Datum
        })

        resultMap.filterNot(elem => elem.isEmpty)
    }) compose Enumeratee.filterNot((data: DataPacket) => data.isEmpty)
}