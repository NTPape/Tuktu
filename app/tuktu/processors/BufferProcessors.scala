package tuktu.processors

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import play.api.libs.iteratee.Enumeratee
import play.api.libs.json.JsValue
import play.api.libs.concurrent.Akka
import play.api.Play.current
import tuktu.api._
import play.api.libs.json.JsObject
import play.api.cache.Cache
import scala.collection.mutable.ListBuffer

/**
 * This actor is used to buffer stuff in
 */
class BufferActor(remoteGenerator: ActorRef) extends Actor with ActorLogging {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)

    var buffer = collection.mutable.ListBuffer[Datum]()

    def receive() = {
        case "release" => {
            // Create datapacket and clear buffer
            val dp = new DataPacket(buffer.toList)
            buffer.clear

            // Push forward to remote generator
            remoteGenerator ! dp

            sender ! "ok"
        }
        case sp: StopPacket => {
            remoteGenerator ! sp
            self ! PoisonPill
        }
        case item: Datum => {
            buffer += item
            sender ! "ok"
        }
    }
}

/**
 * This actor is used to buffer grouped stuff in
 */
class GroupedBufferActor(remoteGenerator: ActorRef, fields: List[String]) extends Actor with ActorLogging {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)

    val buffer = collection.mutable.Map[List[Any], ListBuffer[Map[String, Any]]]()

    def receive() = {
        case "release" => {
            // Create datapackets and clear buffer
            buffer.foreach(item => remoteGenerator ! new DataPacket(item._2: _*))
            buffer.clear

            sender ! "ok"
        }
        case sp: StopPacket => {
            remoteGenerator ! sp
            self ! PoisonPill
        }
        case item: Map[String, Any] => {
            val key = fields.map(field => item(field))

            // make sure a ListBuffer exists for this key
            if (!buffer.contains(key)) buffer += (key -> ListBuffer[Map[String, Any]]())

            buffer(key) += item
            sender ! "ok"
        }
    }

}

/**
 * Buffers datapackets until we have a specific amount of them
 */
class SizeBufferProcessor(config: JsObject, genActor: ActorRef) extends BufferProcessor(config, genActor) {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)

    var curCount = 0

    // Set up the buffering actor
    val bufferActor = Akka.system.actorOf(Props(classOf[BufferActor], genActor))

    val maxSize = (config \ "size").asOpt[Int].getOrElse(-1)

    val main_enumeratee: Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM((data: DataPacket) => {
        // Iterate over our data and add to the buffer
        val fut = Future.sequence(for (datum <- data) yield bufferActor ? datum)

        // Wait for all of them to finish
        Await.result(fut, 30 seconds)

        // Increase counter
        curCount += 1

        // See if we need to release
        if (curCount == maxSize) {
            // Send the relase but forget the result
            curCount = 0
            val dummyFut = bufferActor ? "release"
            dummyFut.onComplete {
                case _ => {}
            }
        }

        Future { data }
    })

    compositions.prepend(Enumeratee.onEOF(() => {
        Await.result(bufferActor ? "release", Cache.getAs[Int]("timeout").getOrElse(5) seconds)
        bufferActor ! new StopPacket
    }))
}

/**
 * Buffers datapackets for a given amount of time and then releases the buffer for processing
 */
class TimeBufferProcessor(config: JsObject, genActor: ActorRef) extends BufferProcessor(config, genActor) {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)

    // Set up the buffering actor
    val bufferActor = Akka.system.actorOf(Props(classOf[BufferActor], genActor))

    val interval = (config \ "interval").asOpt[Int].getOrElse(-1)

    // Schedule periodic release
    val cancellable =
        Akka.system.scheduler.schedule(interval milliseconds,
            interval milliseconds,
            bufferActor,
            "release")

    val main_enumeratee: Enumeratee[DataPacket, DataPacket] = Enumeratee.mapM((data: DataPacket) => {
        // Iterate over our data and add to the buffer
        data.foreach(datum => bufferActor ! datum)

        Future { data }
    })

    compositions.prepend(Enumeratee.onEOF(() => {
        cancellable.cancel
        Await.result(bufferActor ? "release", Cache.getAs[Int]("timeout").getOrElse(5) seconds)
        bufferActor ! new StopPacket
    }))
}

/**
 * Buffers until EOF (end of data stream) is found
 */
class EOFBufferProcessor(config: JsObject, genActor: ActorRef) extends BufferProcessor(config, genActor) {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)

    // Set up the buffering actor
    val bufferActor = Akka.system.actorOf(Props(classOf[BufferActor], genActor))

    val main_enumeratee: Enumeratee[DataPacket, DataPacket] = Enumeratee.map((data: DataPacket) => {
        // Iterate over our data and add to the buffer
        val fut = Future.sequence(for (datum <- data) yield bufferActor ? datum)

        // Wait for all of them to finish
        Await.result(fut, Cache.getAs[Int]("timeout").getOrElse(5) * 2 seconds)
        data
    })

    compositions.prepend(Enumeratee.onEOF(() => {
        Await.result(bufferActor ? "release", Cache.getAs[Int]("timeout").getOrElse(5) seconds)
        bufferActor ! new StopPacket
    }))
}

/**
 * Buffers and Groups data
 */
class GroupByBuffer(config: JsObject, genActor: ActorRef) extends BufferProcessor(config, genActor) {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)

    // Get fields to group on and set up the buffering actor
    val fields: List[String] = (config \ "fields").as[List[String]]
    val bufferActor: ActorRef = Akka.system.actorOf(Props(classOf[GroupedBufferActor], genActor, fields))

    val main_enumeratee: Enumeratee[DataPacket, DataPacket] = Enumeratee.map((data: DataPacket) => {
        // Iterate over our data and add to the buffer
        val fut = Future.sequence(for (datum <- data) yield bufferActor ? datum)

        // Wait for all of them to finish
        Await.result(fut, Cache.getAs[Int]("timeout").getOrElse(5) seconds)

        data
    })

    compositions.prepend(Enumeratee.onEOF(() => {
        Await.result(bufferActor ? "release", Cache.getAs[Int]("timeout").getOrElse(5) seconds)
        bufferActor ! new StopPacket
    }))

}