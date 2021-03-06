package controllers

import java.io.File
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import play.api.Play
import play.api.Play.current
import play.api.data._
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.concurrent.Akka
import play.api.mvc.Action
import play.api.mvc.Controller
import tuktu.api._
import tuktu.utils.util
import play.api.cache.Cache

object Monitor extends Controller {
    implicit val timeout = Timeout(Cache.getAs[Int]("timeout").getOrElse(5) seconds)
    /**
     * Fetches the monitor's info
     */
    def fetchLocalInfo() = Action.async { implicit request =>
        // Get the monitor
        val fut = (Akka.system.actorSelection("user/TuktuMonitor") ? new MonitorOverviewPacket()).asInstanceOf[Future[Map[String, AppMonitorObject]]]
        fut.map(res =>
            Ok(views.html.monitor.showApps(
                    res.toList.sortBy(elem => elem._2.getStartTime),
                    util.flashMessagesToMap(request)
            ))
        )
    }
    
    /**
     * Terminates a Tuktu job
     */
    def terminate(name: String, force: Boolean) = Action {
        // Send stop packet to actor
        if (force)
            Akka.system.actorSelection(name) ! PoisonPill
        else 
            Akka.system.actorSelection(name) ! new StopPacket
            
        // Inform the monitor since the generator won't do it itself
        val generatorName = Akka.system.actorSelection(name) ? Identify(None)
        generatorName.onSuccess {
            case generator: ActorRef => {
                Akka.system.actorSelection("user/TuktuMonitor") ! new AppMonitorPacket(
                        generator.path.toStringWithoutAddress,
                        System.currentTimeMillis / 1000L,
                        "done"
                )
            } 
        }
        
        Redirect(routes.Monitor.fetchLocalInfo()).flashing("success" -> ("Successfully " + {
            force match {
                case true => "terminated"
                case _ => "stopped"
            }
        } + " job " + name))
    }
    
    /**
     * Shows the form for getting configs
     */
    def showConfigs() = Action { implicit request => {
        val body = request.body.asFormUrlEncoded.getOrElse(Map[String, Seq[String]]())
        
        // Get the path from the body
        val path = body("path").head.split("/").filter(elem => !elem.isEmpty)
        
        // Load the files and folders from the config repository
        val configRepo = {
            val location = Play.current.configuration.getString("tuktu.configrepo").getOrElse("configs")
            if (location.last != '/') location + "/"
            else location
        }
        val files = new File(configRepo + path.mkString("/")).listFiles
        
        // Get configs
        val configs = files.filter(!_.isDirectory).map(cfg => cfg.getName.take(cfg.getName.size - 5))
        // Get subfolders
        val subfolders = files.filter(_.isDirectory).map(fldr => fldr.getName)
        
        // Invoke view
        Ok(views.html.monitor.showConfigs(
                path, configs, subfolders
        ))
    }}
    
    /**
     * Shows the start-job view
     */
    def startJobView() = Action { implicit request => {
            Ok(views.html.monitor.startJob(util.flashMessagesToMap(request)))
        }
    }
    
    case class job(
        name: String
    )
    
    val jobForm = Form(
        mapping(
            "name" -> text.verifying(nonEmpty, minLength(1))
        ) (job.apply)(job.unapply)
    )
    
    /**
     * Actually starts a job
     */
    def startJob() = Action { implicit request => {
            // Bind
            jobForm.bindFromRequest.fold(
                formWithErrors => {
                    Redirect(routes.Monitor.startJobView).flashing("error" -> "Invalid job name or instances")
                },
                job => {
                    Akka.system.actorSelection("user/TuktuDispatcher") ! new DispatchRequest(job.name, None, false, false, false, None)
                    Redirect(routes.Monitor.fetchLocalInfo).flashing("success" -> ("Successfully started job " + job.name))
                }
            )
        }
    }
}