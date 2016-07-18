import akka.actor.{ActorSystem, Props}
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object Main extends App {

  implicit val system = ActorSystem()
  implicit val executor = system.dispatcher
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(5.seconds)

  val config = ConfigFactory.load()
  val router = system.actorOf(Props[Router], "router")
  val log = Logging(system, "main")

  val routes = {
    parameter("url") { url =>
      Try(Uri(url)) match {
        case Success(uri) => {
          val f = (router ? uri).mapTo[Resolution]
          onComplete(f) {
            case Success(resolution) => {
              resolution match {
                case Resolving => {
                  respondWithHeader(RawHeader("X-Resolution", "resolving")) {
                    complete(StatusCodes.NoContent)
                  }
                }
                case Throttling => {
                  respondWithHeader(RawHeader("X-Resolution", "throttling")) {
                    complete(StatusCodes.NoContent)
                  }
                }
                case f: Failed => {
                  respondWithHeader(RawHeader("X-Resolution", "failed")) {
                    log.error(f.msg)
                    complete(StatusCodes.InternalServerError)
                  }
                }
                case r: Resolved => {
                  respondWithHeader(RawHeader("X-Resolution", "resolved")) {
                    complete(r.response)
                  }
                }
              }
            }
            case Failure(ex) => {
              log.error(ex, ex.getMessage)
              complete(StatusCodes.InternalServerError)
            }
          }
        }
        case Failure(e) => complete(StatusCodes.BadRequest, "bar url")
      }
    }
  }

  Http().bindAndHandle(routes, config.getString("http.interface"), config.getInt("http.port"))
}
