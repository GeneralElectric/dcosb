package io.predix.dcosb.util.actor

import akka.actor.ActorSystem
import akka.http.scaladsl.Http.HostConnectionPool
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object AkkaHttpClient {

  def runWith[T](clientFlow: Flow[(HttpRequest, T), (Try[HttpResponse], T), HostConnectionPool], system: ActorSystem)(request: HttpRequest, context: T): Future[(HttpResponse, T)] = {
    implicit val s = system
    implicit val executor = system.dispatcher
    implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system))
    Source.single(request -> context)
      .via(clientFlow)
      .runWith(Sink.head).flatMap {
      case (Success(r: HttpResponse), c: T) => Future.successful((r, c))
      case (Failure(f), _) => Future.failed(f)
    }
  }

}