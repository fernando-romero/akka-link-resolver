import akka.actor.Status.Failure
import akka.actor.{Stash, PoisonPill, Actor, Props}
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpResponse, HttpRequest, Uri}
import akka.pattern.pipe
import akka.stream.ActorMaterializer

/**
  * Created by fer on 7/7/16.
  */
object PathActor {
  def props(uri: Uri) = Props(new PathActor(uri))
}

class PathActor(uri: Uri) extends Actor with Stash {

  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = context.dispatcher

  val http = Http(context.system)
  val log = Logging(context.system, this)

  var resOpt: Option[Resolved] = None
  var failOpt: Option[Failure] = None

  override def preStart: Unit = {
    http.singleRequest(HttpRequest(uri = uri)) pipeTo self
  }

  def resolved: Receive = {
    case GetResponseFromCache => {
      resOpt match {
        case Some(res) => sender ! res
        case None => {
          log.error("no resolution found in resolved state")
          context.become(failed)
          self ! PoisonPill
        }
      }
    }
  }

  def failed: Receive = {
    case GetResponseFromCache => {
      failOpt match {
        case None => log.error("no failure saved")
        case Some(fail) => {
          log.debug("sending failure to " + sender.toString)
          sender ! fail
        }
      }
      self ! PoisonPill
    }
  }

  def receive = {
    case resp: HttpResponse => {
      log.debug("got response: " + resp.toString)
      resOpt = Some(Resolved(resp))
      context.become(resolved)
    }
    case fail: Failure => {
      log.error(fail.cause, "can't resolve " + uri.toString)
      failOpt = Some(fail)
      context.become(failed)
    }
    case GetResponseFromCache => sender ! Resolving
  }
}
