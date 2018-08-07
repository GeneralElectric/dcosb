package io.predix.dcosb.dcos.security

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef}
import akka.util.Timeout
import akka.pattern.ask

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

trait TokenAuthenticatingActor extends Actor {

  var tokenKeeper:Option[ActorRef] = None

  def tokenAuthenticated(f:(Either[Option[TokenKeeper.Token], Throwable]) => _): Unit = {
    tokenKeeper match {
      case Some(tk: ActorRef) =>
        // ask for a Token
        implicit val executionContext = context.dispatcher
        implicit val timeout = Timeout(FiniteDuration(10, TimeUnit.SECONDS)) // TODO: configure..

        (tk ? TokenKeeper.GetOrRefreshToken()) onComplete {
          case Success(Success(token: TokenKeeper.Token)) => f(Left(Some(token)))
          case Failure(e: Throwable) => Right(e)
          case Success(Failure(e: Throwable)) => Right(e)
          case _ => Left(None) // .. giving up?
        }

      case None => f(Left(None))
    }
  }

}
