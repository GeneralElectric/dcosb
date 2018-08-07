package io.predix.dcosb.dcos

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.testkit.{CallingThreadDispatcher, TestActorRef}
import io.predix.dcosb.dcos.DCOSProxy._
import akka.pattern._
import io.predix.dcosb.util.actor.ConfiguredActor
import org.joda.time.DateTime

import scala.util.Success

object DCOSProxyMock {

  /**
    * Create an [[Actor]] that will always OK a [[DCOSProxy.Heartbeat]]
    * and invoke a list of provided functions to handle a [[DCOSProxy.Forward]]
    * @param handlers list of functions to handle a [[DCOSProxy.Forward]]
    * @return
    */
  def apply(handlers: ((ActorRef, Target.Value, Any) => Any)*)(implicit system: ActorSystem):ActorRef = {

    TestActorRef(Props(new Actor with ActorLogging {
      override def receive = {
        case DCOSProxy.Forward(target, message) =>
          for(handler <- handlers) { handler(sender(), target, message) }
        case _: DCOSProxy.Heartbeat => sender() ! DCOSProxy.HeartbeatOK(DateTime.now())
        case _: DCOSProxy.Configuration => sender() ! Success(ConfiguredActor.Configured())
        case m => log.error(s"DCOSProxyMock received unexpected message $m")

      }


    }).withDispatcher(CallingThreadDispatcher.Id))

  }

  def apply()(implicit system: ActorSystem):ActorRef = {
    TestActorRef(Props(new Actor with ActorLogging {
      override def receive = {
        case _: DCOSProxy.Heartbeat => sender() ! DCOSProxy.HeartbeatOK(DateTime.now())
        case _: DCOSProxy.Configuration => sender() ! Success(ConfiguredActor.Configured())
        case m => log.error(s"DCOSProxyMock received unexpected message $m")

      }
    }).withDispatcher(CallingThreadDispatcher.Id))
  }

}
