package tuktu.api

import java.util.regex.Pattern
import play.api.libs.json._
import java.util.Date
import org.joda.time.DateTime
import scala.collection.GenSeq

object utils {
    val pattern = Pattern.compile("\\$\\{(.*?)\\}")

    /**
     * Evaluates a Tuktu string to resolve variables in the actual string
     */
    def evaluateTuktuString(str: String, vars: Datum) = {
        // Set up matcher and string buffer
        val matcher = pattern.matcher(str)
        val buff = new StringBuffer(str.length)

        // Replace with vars
        while (matcher.find)
            matcher.appendReplacement(buff, vars(matcher.group(1)).toString)
        matcher.appendTail(buff)

        // Return buffer
        buff.toString
    }

    /**
     * Checks if a string contains variables that can be populated using evaluateTuktuString
     */
    def containsTuktuStringVariable(str: String) = {
        val matcher = pattern.matcher(str)
        matcher.find()
    }

    /**
     * Recursively traverses a path of keys to find its value
     * On empty path, the whole map will be returned
     */
    def getPathValue(input: Map[String, Any], path: List[String]): Option[Any] = path match {
        case Nil => Some(input)
        case someKey :: trailPath => {
            if (input.contains(someKey)) {
                // See if we can cast it
                try {
                    if (input(someKey).isInstanceOf[JsValue])
                        getPathValue(input(someKey).asInstanceOf[JsValue], trailPath)
                    else
                        getPathValue(input(someKey).asInstanceOf[Map[String, Any]], trailPath)
                } catch {
                    case e: ClassCastException => None
                }
            } else {
                // Couldn't find it
                None
            }
        }
    }

    /**
     * Recursively traverses a JSON object of keys to find its value
     * On empty path, the whole JsObject will be returned
     */
    def getPathValue(json: JsValue, jsPath: List[String]): Option[JsValue] = jsPath match {
        case Nil => Some(json)
        case js :: trailPath => {
            // Get the remaining value from the json
            val newJson = (json \ js).asOpt[JsValue]
            newJson match {
                case Some(nj) => getPathValue(nj, trailPath)
                case None     => None
            }
        }
    }

    /**
     * Recursively traverses a path of keys to set a value
     * The new Value will append/overwrite the input if both are Map[String, Any], otherwise it will just be overwritten
     */
    def setPathValue(input: Map[String, Any], path: List[String], newValue: Any)(implicit append: Boolean): Map[String, Any] = {
        // See if newValue is a Map
        val newValueAsMap = try {
            Some(newValue.asInstanceOf[Map[String, Any]])
        } catch {
            case e: ClassCastException => None
        }
        path match {
            // This case will only fire if path is empty, ie. if we are top-level in a datum
            case Nil =>
                newValueAsMap match {
                    // newValue is not Map, but top-level has to be a map; do nothing
                    case None => input
                    // newValue is Map, so either append or overwrite
                    case Some(map) =>
                        if (append == true)
                            input ++ map
                        else
                            map
                }
            case head :: Nil => {
                if (input.contains(head) && input(head).isInstanceOf[JsValue])
                    // If newInput is JsValue, let other function handle appending / overwriting
                    input + (head -> setPathValue(input(head).asInstanceOf[JsValue], Nil, newValue))
                else if (!input.contains(head) || append == false)
                    // If input doesn't contain head or append is false, overwrite
                    input + (head -> newValue)
                else {
                    // input already contains head and we shall append, see if newInput is also Map or GenSeq and append; otherwise overwrite
                    val newInputAsMap = try {
                        Some(input(head).asInstanceOf[Map[String, Any]])
                    } catch {
                        case e: ClassCastException => None
                    }
                    newInputAsMap match {
                        // newInput is also Map and append is true, append
                        case Some(map) => input + (head -> (map ++ newValueAsMap.get))
                        // newInput is no Map, check if GenSeq
                        case None => {
                            val newInputAsGenSeq = try {
                                Some(input(head).asInstanceOf[GenSeq[Any]])
                            } catch {
                                case e: ClassCastException => None
                            }
                            newInputAsGenSeq match {
                                // Neither Map nor GenSeq, overwrite
                                case None      => input + (head -> newValue)
                                // GenSeq, append
                                case Some(seq) => input + (head -> (seq :+ newValue))
                            }

                        }
                    }
                }
            }
            case head :: trail => {
                // Get value at head, or create new Map
                val newInput = input.getOrElse(head, Map[String, Any]())
                if (newInput.isInstanceOf[JsObject])
                    input + (head -> setPathValue(newInput.asInstanceOf[JsObject], trail, newValue))
                else {
                    val cast = try {
                        // Test whether new input is Map
                        newInput.asInstanceOf[Map[String, Any]]
                    } catch {
                        // Overwrite everything else with a new map
                        case e: ClassCastException => Map[String, Any]()
                    }
                    input + (head -> setPathValue(cast, trail, newValue))
                }
            }
        }
    }

    /**
     * Recursively traverses a path of keys in a JsObject to set / overwrite / append a value
     */
    def setPathValue(json: JsValue, path: List[String], newValue: Any)(implicit append: Boolean): JsValue = path match {
        case Nil => {
            // Convert newValue to JsValue
            val newVal = AnyToJsValue(newValue)

            if (append == false)
                // Overwrite
                newVal
            else {
                if (json.isInstanceOf[JsObject] && newVal.isInstanceOf[JsObject])
                    // Append newValue to JsObject
                    json.as[JsObject] ++ newVal.as[JsObject]
                else if (json.isInstanceOf[JsArray])
                    // Append newValue to JsArray
                    json.asInstanceOf[JsArray] :+ newVal
                else
                    // Cannot be appended; overwrite
                    newVal
            }
        }
        case head :: tail => {
            // See if json is JsObject, otherwise create new empty one
            json.asOpt[JsObject] match {
                case None      => new JsObject(Nil) + (head -> setPathValue(new JsObject(Nil), tail, newValue))
                case Some(obj) => obj + (head -> setPathValue(obj \ head, tail, newValue))
            }
        }
    }

    /**
     * Recursively traverses a path of keys to delete the key at the end
     * On empty path, the whole input will be deleted
     */
    def removePath(input: Map[String, Any], path: List[String]): Map[String, Any] = path match {
        case Nil         => Map[String, Any]()
        case head :: Nil => input - head
        case head :: trail => {
            if (!input.contains(head))
                input
            else {
                // Get value at head, or create new Map
                val newInput = input(head)
                if (newInput.isInstanceOf[JsObject])
                    input.updated(head, removePath(newInput.asInstanceOf[JsObject], trail))
                else {
                    val cast = try {
                        // Test whether new input is Map
                        newInput.asInstanceOf[Map[String, Any]]
                    } catch {
                        // Ignore otherwise
                        case e: ClassCastException => return input
                    }
                    input.updated(head, removePath(cast, trail))
                }
            }
        }
    }

    /**
     * Recursively traverses a path of keys in a JsObject to delete JsValue at the last key
     * On empty path the whole JsObject will be deleted
     */
    def removePath(input: JsObject, path: List[String]): JsObject = path match {
        case Nil         => new JsObject(Nil)
        case head :: Nil => input - head
        case head :: tail => {
            // See if JsValue at head is JsObject, otherwise ignore
            (input \ head).asOpt[JsObject] match {
                case None      => input
                case Some(obj) => input + (head -> removePath(obj, tail))
            }
        }
    }

    /**
     * ---------------------
     * JSON helper functions
     * ---------------------
     */

    /**
     * Turns Any into a JsValueWrapper to use by Json.arr and Json.obj
     */
    private def AnyToJsValueWrapper(a: Any, mongo: Boolean = false): Json.JsValueWrapper = a match {
        case a: Boolean    => a
        case a: String     => a
        case a: Char       => a.toString
        case a: Short      => a
        case a: Int        => a
        case a: Long       => a
        case a: Float      => a
        case a: Double     => a
        case a: BigDecimal => a
        case a: Date       => if (!mongo) a else Json.obj("$date" -> a.getTime)
        case a: DateTime   => if (!mongo) a else Json.obj("$date" -> a.getMillis)
        case a: JsValue    => a
        case a: Seq[_]     => SeqToJsArray(a, mongo)
        case a: Map[_, _]  => MapToJsObject(a, mongo)
        case _             => a.toString
    }

    /**
     * Turns Any into a JsValue
     */
    def AnyToJsValue(a: Any, mongo: Boolean = false): JsValue =
        Json.arr(AnyToJsValueWrapper(a))(0)

    /**
     * Turns any Seq[Any] into a JsArray
     */
    def SeqToJsArray(seq: Seq[Any], mongo: Boolean = false): JsArray =
        Json.arr(seq.map(value => AnyToJsValueWrapper(value, mongo)): _*)

    /**
     * Turns a Map[Any, Any] into a JsObject
     */
    def MapToJsObject(map: Map[_ <: Any, Any], mongo: Boolean = false): JsObject =
        Json.obj(map.map(tuple => tuple._1.toString -> AnyToJsValueWrapper(tuple._2, mongo)).toSeq: _*)

    /**
     * Takes a JsValue and returns a scala object
     */
    def JsValueToAny(json: JsValue): Any = json match {
        case a: JsString  => a.value
        case a: JsNumber  => a.value
        case a: JsBoolean => a.value
        case a: JsObject  => JsObjectToMap(a)
        case a: JsArray   => JsArrayToSeqAny(a)
        case a            => a.toString
    }

    /**
     * Converts a JsArray to a Seq[Any]
     */
    def JsArrayToSeqAny(arr: JsArray): Seq[Any] =
        for (field <- arr.value) yield JsValueToAny(field)

    /**
     * Converts a JsObject to Map[String, Any]
     */
    def JsObjectToMap(json: JsObject): Map[String, Any] =
        json.value.mapValues(jsValue => JsValueToAny(jsValue)).toMap
}