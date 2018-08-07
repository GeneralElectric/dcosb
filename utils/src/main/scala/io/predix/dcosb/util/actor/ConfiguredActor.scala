package io.predix.dcosb.util.actor

import akka.actor.{Actor, ActorLogging, Stash}
import akka.event.LoggingReceive

import scala.concurrent.{Future, Promise}
import scala.reflect.ClassTag

object ConfiguredActor {

  case class Configured()
  trait ActorConfigurationException extends Throwable

}

abstract class ConfiguredActor[C]()(implicit ctag: ClassTag[C]) extends Actor with ActorUtils with ActorLogging with Stash {
  import ConfiguredActor._

  // do not access this directly, use configured() below
  private var configuration: Option[C] = None
  implicit val executor = context.dispatcher

  def configure(configuration: C): Future[Configured] = {

      this.configuration = Some(configuration)
      log.debug(s"Configured with $configuration")
      Future.successful(Configured())
  }

  def configuredBehavior: Actor.Receive

  def unconfiguredBehavior: Actor.Receive = LoggingReceive {
    case configuration: C =>
      context.become(configuredBehavior)
      unstashAll()

      broadcastFuture(configure(configuration), sender())
    case _ => stash()
  }

  def configured[R](f: ((C) => R)): R = {
    configuration match {
      case Some(c: C) => f(c)
      case None => throw new IllegalStateException("No configuration was found")
    }
  }

  def configuredOrFailPromise(f: ((C) => _), promise: Promise[_]):Unit = {
    configuration match {
      case Some(c: C) => f(c)
      case None => promise.failure(new IllegalStateException("No configuration was found"))
    }
  }

  override def receive: Actor.Receive = unconfiguredBehavior

}
