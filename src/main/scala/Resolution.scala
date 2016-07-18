import akka.http.scaladsl.model.HttpResponse

/**
  * Created by fer on 7/12/16.
  */
sealed trait Resolution
case object Resolving extends Resolution
case object Throttling extends Resolution
case class Failed(msg: String) extends Resolution
case class Resolved(response: HttpResponse) extends Resolution
