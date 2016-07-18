import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

import akka.actor.Status.Failure
import akka.actor.{Actor, Stash}
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.pattern.pipe
import akka.stream.ActorMaterializer

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.FiniteDuration

/**
  * Created by fer on 7/14/16.
  */
class Resolver extends Actor with Stash {

  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = context.dispatcher

  val http = Http(context.system)
  val log = Logging(context.system, this)
  val resolutions = mutable.HashMap.empty[Uri, Resolution]
  val times: ArrayBuffer[LocalTime] = ArrayBuffer.empty[LocalTime]
  val throttleMaxOfMessages = 1
  val throttlePeriodInMillis = 60000

  def throttling: Receive = {
    case uri: Uri => {
      resolutions.get(uri) match {
        case Some(r) => sender ! r
        case None => sender ! Throttling
      }
    }
    case WakeUp => {
      log.debug("waking up")
      context.unbecome
    }
  }

  def receive = {
    case uri: Uri => {
      // check domain
      val domain = uri.authority.host.address
      if (domain != self.path.name) {
        val msg = "wrong domain: " + domain
        log.error(msg)
        sender ! Failed(msg)
      } else {
        // check cache
        resolutions.get(uri) match {
          case Some(r) => sender ! r
          case None => {
            val fut = http.singleRequest(HttpRequest(uri = uri)).map(resp => (uri, resp))
            pipe(fut).to(self, sender)
            resolutions.update(uri, Resolving)
            // throttle logic
            val now = LocalTime.now
            times += now
            val millis = ChronoUnit.MILLIS.between(times(0), now)
            if (times.length == throttleMaxOfMessages) {
              if (millis < throttlePeriodInMillis) {
                log.warning("throttling")
                context.become(throttling)
                val pauseMillis = throttlePeriodInMillis - millis
                val pauseDuration = FiniteDuration(pauseMillis, TimeUnit.MILLISECONDS)
                val scheduler = context.system.scheduler
                scheduler.scheduleOnce(pauseDuration, self, WakeUp)
              }
              times.remove(0)
            }
          }
        }
      }
    }
    case (uri: Uri, resp: HttpResponse) => {
      val resolution = Resolved(resp)
      resolutions.update(uri, resolution)
      sender ! resolution
    }
    case fail: Failure => {
      log.error(fail.cause, "can't resolve uri")
      sender ! Failed(fail.cause.getMessage)
    }
  }
}
