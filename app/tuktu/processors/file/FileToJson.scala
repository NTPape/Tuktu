package tuktu.processors.file

import java.nio.file.Path
import java.io.File
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import scala.io.Source
import tuktu.api._

/**
 * Converts a field to JSON.
 */
class FileToJson(config: JsObject) extends BaseProcessor(config) {
    /** The charset used to decode the bytes of the incoming files. */
    val charset = (config \ "charset").asOpt[String].getOrElse("utf-8")

    override def processDatum(datum: Datum, any: Any): Datum = {
        // Get file contents
        val file =
            if (any.isInstanceOf[Path])
                Source.fromFile(any.asInstanceOf[Path].toFile, charset)
            else
                Source.fromFile(any.asInstanceOf[File], charset)
        val content = file.getLines.mkString
        file.close

        // Parse JSON and update
        datum + (result_path -> Json.parse(content))
    }
}