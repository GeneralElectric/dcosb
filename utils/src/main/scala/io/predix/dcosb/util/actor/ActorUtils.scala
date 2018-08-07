package io.predix.dcosb.util.actor

import akka.actor.{Actor, ActorLogging, ActorRef}

import scala.concurrent.{ExecutionContext, Future}

trait ActorUtils extends Actor with ActorLogging {

  def broadcastFuture[_](f: Future[_], recepients: ActorRef*)(implicit executor: ExecutionContext) = {
    f onComplete {
      case r => for (recepient <- recepients) {
        log.debug(s"Sending $r to $recepient")
        recepient ! r
      }
    }
  }

}
