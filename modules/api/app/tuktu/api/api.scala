package tuktu.api

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import akka.actor.Actor
import akka.actor.ActorIdentity
import akka.actor.ActorLogging
import akka.actor.Identify
import akka.actor.PoisonPill
import akka.actor.actorRef2Scala
import akka.pattern.ask
import akka.util.Timeout
import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.iteratee.Concurrent
import play.api.libs.iteratee.Enumeratee
import play.api.libs.iteratee.Iteratee
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import akka.actor.ActorRef
import play.api.libs.json.JsObject
import play.api.cache.Cache
import scala.collection.GenTraversableOnce
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

class Datum(datum: Map[String, Any]) extends java.io.Serializable {
    // Constructors
    def this() = this(Map[String, Any]())

    // Methods
    def +(kv: (List[String], Any))(implicit append: Boolean): Datum = this.updated(kv._1, kv._2)
    def ++(l: List[(List[String], Any)])(implicit append: Boolean): Datum = l match {
        case Nil          => this
        case head :: tail => this + head ++ tail
    }
    def -(path: List[String]): Datum = new Datum(utils.removePath(datum, path))
    def apply(key: String): Option[Any] = datum.get(key).map(_ match {
        case str: String => utils.evaluateTuktuString(str, this)
        case a: Any      => a
    })
    def apply(path: List[String]): Option[Any] = utils.getPathValue(datum, path).map(_ match {
        case str: String => utils.evaluateTuktuString(str, this)
        case a: Any      => a
    })
    def contains(key: String): Boolean = datum.contains(key)
    def contains(path: List[String]): Boolean = utils.getPathValue(datum, path) != None
    def isEmpty: Boolean = datum.isEmpty
    def prettyPrint: String = Json.prettyPrint(utils.MapToJsObject(datum))
    def toSeq: Seq[(String, Any)] = datum.toSeq
    def updated(key: String, value: Any)(implicit append: Boolean): Datum = this.updated(List(key), value)
    def updated(path: List[String], value: Any)(implicit append: Boolean): Datum = new Datum(utils.setPathValue(datum, path, value))
    def value: Map[String, Any] = datum
}

class DataPacket(data: List[Datum]) extends java.io.Serializable {
    // Constructors
    def this() = this(Nil)
    def this(data: Map[String, Any]*) = this(data.toList.map(elem => new Datum(elem)))

    // Methods
    def isEmpty: Boolean = data.isEmpty
    def filter(f: Datum => Boolean): DataPacket = new DataPacket(data.filter(f))
    def filterNot(f: Datum => Boolean): DataPacket = new DataPacket(data.filterNot(f))
    def foreach(f: Datum => Unit): Unit = data.foreach(f)
    def head: Datum = data.head
    def map(f: Datum => Datum): DataPacket = new DataPacket(data.map(f))
    def map[B](f: Datum => B): List[B] = data.map(f)
    def flatMap(f: Datum => GenTraversableOnce[Datum]): DataPacket = new DataPacket(data.flatMap(f))
    def size: Int = data.size
}

case class InitPacket()

case class StopPacket()

case class ResponsePacket(json: JsValue)

/**
 * Monitor stuff
 */

sealed abstract class MPType
case object BeginType extends MPType
case object EndType extends MPType
case object CompleteType extends MPType

case class MonitorPacket(
    typeOf: MPType,
    actorName: String,
    branch: String,
    amount: Integer)

case class MonitorOverviewPacket()

class AppMonitorObject(name: String, startTime: Long) {
    def getName = name
    def getStartTime = startTime
}

case class AppMonitorPacket(
    name: String,
    timestamp: Long,
    status: String)
/**
 * End monitoring stuff
 */
abstract class BaseProcessor(config: JsObject) {
    val source_path = (config \ "source_path").asOpt[List[String]].getOrElse(Nil)
    val result_path = (config \ "result_path").asOpt[List[String]].getOrElse(Nil)
    val default_val = (config \ "default_value").asOpt[JsValue]
    val filter_empty_data_packets = (config \ "filter_empty_data_packets").asOpt[Boolean].getOrElse(false)
    implicit val append = (config \ "append").asOpt[Boolean].getOrElse(false)

    val compositions = ListBuffer[Enumeratee[DataPacket, DataPacket]]()
    if (filter_empty_data_packets)
        compositions.prepend(Enumeratee.filterNot((data: DataPacket) => data.isEmpty))

    val main_enumeratee = Enumeratee.mapM((data: DataPacket) => Future { processDataPacket(data) })

    def processor: Enumeratee[DataPacket, DataPacket] = compositions.foldLeft(main_enumeratee)(_ compose _)

    def processDataPacket(data: DataPacket): DataPacket = {
        data.map(datum => datum(source_path) match {
            case None => default_val match {
                case None          => datum
                case Some(default) => processDatum(datum, default)
            }
            case Some(any) => processDatum(datum, any)
        })
    }

    def processDatum(datum: Datum, any: Any): Datum = datum + (result_path -> processAny(any))

    def processAny(any: Any): Any = any
}

/**
 * Definition of a processor
 */
case class ProcessorDefinition(
    id: String,
    name: String,
    config: JsObject,
    next: List[String])

abstract class BufferProcessor(config: JsObject, genActor: ActorRef) extends BaseProcessor(config: JsObject)

abstract class BaseGenerator(resultName: String, processors: List[Enumeratee[DataPacket, DataPacket]], senderActor: Option[ActorRef]) extends Actor with ActorLogging {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)
    val (enumerator, channel) = Concurrent.broadcast[DataPacket]

    // Set up pipeline, either one that sends back the result, or one that just sinks
    val sinkIteratee: Iteratee[DataPacket, Unit] = Iteratee.ignore
    senderActor match {
        case Some(ref) => {
            // Set up enumeratee that sends the result back to sender
            val sendBackEnumeratee: Enumeratee[DataPacket, DataPacket] = Enumeratee.map(dp => {
                ref ! dp
                dp
            })
            processors.foreach(processor => enumerator |>> (processor compose sendBackEnumeratee) &>> sinkIteratee)
        }
        case _ => processors.foreach(processor => enumerator |>> processor &>> sinkIteratee)
    }

    def cleanup() = {
        // Send message to the monitor actor
        Akka.system.actorSelection("user/TuktuMonitor") ! new AppMonitorPacket(
            self.path.toStringWithoutAddress,
            System.currentTimeMillis / 1000L,
            "done")

        channel.eofAndEnd
        //context.stop(self)
        self ! PoisonPill
    }

    def setup() = {
        // Send the monitoring actor notification of start
        Akka.system.actorSelection("user/TuktuMonitor") ! new AppMonitorPacket(
            self.path.toStringWithoutAddress,
            System.currentTimeMillis() / 1000L,
            "start")
    }

    def receive() = {
        case ip: InitPacket  => setup
        case config: JsValue => ???
        case sp: StopPacket  => cleanup
        case _               => {}
    }
}

abstract class DataMerger() {
    def merge(packets: List[DataPacket]): DataPacket = ???
}