import akka.actor.{Actor, Props}
import akka.http.scaladsl.model.Uri

/**
  * Created by fer on 7/7/16.
  */
class Router extends Actor {

  def receive = {
    case uri: Uri => {
      val domain = uri.authority.host.address
      val resolver = context.child(domain).getOrElse(context.actorOf(Props[Resolver], domain))
      resolver forward uri
    }
  }
}
