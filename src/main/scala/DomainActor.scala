import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

import akka.actor.{Stash, Actor, Props}
import akka.event.Logging
import akka.http.scaladsl.model.Uri

import java.time.LocalTime

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.FiniteDuration

/**
  * Created by fer on 7/7/16.
  */
object DomainActor {
  val throttleMillisPeriod = 60000 // 1 minute
  def props(throttleLimit: Int) = Props(new DomainActor(throttleLimit))
}

class DomainActor(throttleLimit: Int) extends Actor with Stash {

  implicit val dispatcher = context.dispatcher

  val times: ArrayBuffer[LocalTime] = ArrayBuffer.empty[LocalTime]
  val log = Logging(context.system, this)

  def throttling: Receive = {
    case WakeUp => {
      log.debug("waking up")
      context.unbecome
      unstashAll
    }
    case uri: Uri => {
      log.debug("stashing " + uri.toString)
      stash
      sender ! Throttling
    }
  }

  def receive = {
    case uri: Uri => {
      val path = uri.path.toString
      val pathName = if (path.length > 1) path.substring(1) else "*"
      context.child(pathName) match {
        case Some(pathActor) => pathActor forward GetResponseFromCache
        case None => {
          log.info("creating actor with name: " + pathName)
          val pathActor = context.actorOf(PathActor.props(uri), pathName)
          pathActor forward GetResponseFromCache
          val now = LocalTime.now
          times += now
          val millis = ChronoUnit.MILLIS.between(times(0), now)
          if (times.length == throttleLimit) {
            if (millis < DomainActor.throttleMillisPeriod) {
              context.become(throttling)
              val pauseMillis = DomainActor.throttleMillisPeriod - millis
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
}
