package tuktu.processors.cache

import play.api.libs.json.JsObject
import play.api.cache.Cache
import play.api.Play.current
import play.api.libs.concurrent.Akka
import akka.actor.Props
import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import tuktu.api._
import tuktu.processors.meta.ParallelProcessorActor

/**
 * Requests something from cache, but if not present, executes an embedded processor to populate the cache
 */
class CachingProcessor(config: JsObject) extends BaseProcessor(config) {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)

    // The key name of the
    val cacheKey = (config \ "cache_key").as[String]
    // Get the starting processor of our flow
    val startProcessor = (config \ "start").as[String]

    // Get the processor flow
    val processors = (config \ "processors").as[List[JsObject]]
    val processorMap = (for (processor <- processors) yield {
        // Get all fields
        val processorId = (processor \ "id").as[String]
        val processorName = (processor \ "name").as[String]
        val processorConfig = (processor \ "config").as[JsObject]
        val next = (processor \ "next").as[List[String]]

        // Create processor definition
        val procDef = new ProcessorDefinition(
            processorId,
            processorName,
            processorConfig,
            next)

        // Return map
        processorId -> procDef
    }).toMap

    // Build the processor pipeline for this generator
    val proc = controllers.Dispatcher.buildEnums(List(startProcessor), processorMap, "TuktuMonitor", None).head

    // Set up the single actor that will execute this processor
    val actor = Akka.system.actorOf(Props(classOf[ParallelProcessorActor], processor))

    override def processDataPacket(data: DataPacket): DataPacket = {
        data.map(datum => {
            // Consult our cache first
            Cache.get(cacheKey) match {
                case Some(value) => {
                    // It was cached, include it
                    datum + (result_path -> value)
                }
                case None => {
                    // The value was not cached yet, let's compute it
                    val result = Await.result((actor ? data).asInstanceOf[Future[DataPacket]], timeout.duration)

                    // Terminate actor
                    actor ! new StopPacket

                    datum + (result_path -> result)
                }
            }
        })
    }
}