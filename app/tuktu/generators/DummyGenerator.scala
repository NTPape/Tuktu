package tuktu.generators

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import akka.actor.ActorRef
import akka.actor.Cancellable
import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.iteratee.Enumeratee
import play.api.libs.json.JsValue
import akka.actor.Props
import akka.actor.ActorLogging
import akka.actor.Actor
import akka.pattern.ask
import tuktu.api._
import scala.concurrent.duration.Duration

/**
 * Just generates dummy strings every tick
 */
class DummyGenerator(resultName: String, processors: List[Enumeratee[DataPacket, DataPacket]], senderActor: Option[ActorRef]) extends BaseGenerator(resultName, processors, senderActor) {
    var schedulerActor: Cancellable = null
    var message: String = null
    var maxAmount: Option[Int] = None
    var amountSent = 0
    
    override def receive() = {
        case ip: InitPacket => setup
        case config: JsValue => {
            // Get the ticking frequency
            val tickInterval = (config \ "interval").as[Int]
            // Get the message to send
            message = (config \ "message").as[String]
            
            // See if we need to stop at some point
            maxAmount = (config \ "max_amount").asOpt[Int]
            
            // Determine initial waiting time before sending
            val initialDelay = {
              if((config \ "send_immediately").asOpt[Boolean].getOrElse(false))
                Duration.Zero
              else
                tickInterval milliseconds
            }
            
            // Set up the scheduler
            schedulerActor = Akka.system.scheduler.schedule(
                    initialDelay,
                    tickInterval milliseconds,
                    self,
                    message)
        }
        case sp: StopPacket => {
            schedulerActor.cancel
            cleanup
        }
        case msg: String => {
            channel.push(new DataPacket(Map(resultName -> message)))
            // See if we need to stop
            maxAmount match {
                case Some(amnt) => {
                    amountSent += 1
                    if (amountSent >= amnt)
                            self ! new StopPacket
                }
                case None => {}
            }
        }
        case x => println("Dummy generator got unexpected packet " + x + "\r\n")
    }
}

class RandomActor(maxNum: Int) extends Actor with ActorLogging {
    val r = util.Random
    def receive() = {
        case _ => sender ! r.nextInt(maxNum)
    }
}

/**
 * Generates random numbers
 */
class RandomGenerator(resultName: String, processors: List[Enumeratee[DataPacket, DataPacket]], senderActor: Option[ActorRef]) extends BaseGenerator(resultName, processors, senderActor) {
    var schedulerActor: Cancellable = null
    var maxNum = 0
    var randomActor: ActorRef = null
    
    override def receive() = {
        case ip: InitPacket => setup
        case config: JsValue => {
            // Get the ticking frequency
            val tickInterval = (config \ "interval").as[Int]
            maxNum = (config \ "max").as[Int]
            
            // Set up actor that will make random numbers
            randomActor = Akka.system.actorOf(Props(classOf[RandomActor], maxNum))
            
            // Set up the scheduler
            schedulerActor = Akka.system.scheduler.schedule(
                    tickInterval milliseconds,
                    tickInterval milliseconds,
                    self,
                    1)
        }
        case sp: StopPacket => {
            schedulerActor.cancel
            cleanup
        }
        case one: Int => {
            val fut = randomActor ? one
            fut.onSuccess {
                case num: Int => channel.push(new DataPacket(List(new Datum(Map(resultName -> num)))))
            }
        }
        case x => println("Dummy generator got unexpected packet " + x + "\r\n")
    }
}

/**
 * Generates a list of values
 */
class ListGenerator(resultName: String, processors: List[Enumeratee[DataPacket, DataPacket]], senderActor: Option[ActorRef]) extends BaseGenerator(resultName, processors, senderActor) {
    var randomActor: ActorRef = null
    var vals = List[String]()
    
    override def receive() = {
        case ip: InitPacket => setup
        case config: JsValue => {
            // Get the values
            vals = (config \ "values").as[List[String]]
            
            // Send message to self
            self ! 0
        }
        case sp: StopPacket => cleanup
        case num: Int => {
            channel.push(new DataPacket(List(new Datum(Map(resultName -> vals(num))))))
            // See if we're done or not
            if (num < vals.size - 1) self ! (num + 1)
            else self ! new StopPacket
        }
    }
}