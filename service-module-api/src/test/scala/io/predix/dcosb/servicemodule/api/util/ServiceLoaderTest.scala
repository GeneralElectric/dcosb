package io.predix.dcosb.servicemodule.api.util

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRefFactory, Props}
import akka.testkit.{CallingThreadDispatcher, TestActorRef}
import akka.util.Timeout
import io.predix.dcosb.dcos.DCOSProxy
import io.predix.dcosb.util.actor.ConfiguredActor
import io.predix.dcosb.util.{ActorSuite, DCOSBSuite}

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

class ServiceLoaderTest extends ActorSuite {

  implicit val executionContext = system.dispatchers.lookup(CallingThreadDispatcher.Id)
  implicit val timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))

  "A ServiceLoader instance" - {

    val childMaker = (f:ActorRefFactory, a:Class[_ <: Actor], name: String) => {
      a match {
        case actorClass: Class[_] if actorClass == classOf[DCOSProxy] => TestActorRef(Props(new Actor {
          override def receive: Receive = {
            case DCOSProxy.Configuration(_, _, _, _, _, _) => sender() ! Success(ConfiguredActor.Configured())
          }
        }).withDispatcher(CallingThreadDispatcher.Id))
        case actorClass => TestActorRef(Props(actorClass).withDispatcher(CallingThreadDispatcher.Id), name)
      }

    }

    val serviceLoader = TestActorRef(Props(new ServiceLoader()).withDispatcher(CallingThreadDispatcher.Id))
    val aksm = TestActorRef(Props(new Actor {
      override def receive = {
        case _ =>
      }
    }).withDispatcher(CallingThreadDispatcher.Id))
    serviceLoader ! ServiceLoader.Configuration(childMaker, stub[DCOSProxy.HttpClientFactory], aksm)

    "with a ServiceModule implementation available on the classpath" - {

      val serviceModuleFQN= "io.predix.dcosb.servicemodule.api.util.StubServiceModule"

      "it can instantiate the ServiceModule by it's fully qualified class name" in {

        val serviceModule = TestActorRef(Props(classOf[StubServiceModule]).withDispatcher(CallingThreadDispatcher.Id))
        Await.result(serviceLoader.underlyingActor.asInstanceOf[ServiceLoader].loadService("test-service", serviceModuleFQN), timeout.duration) match {
          case actorRef: TestActorRef[StubServiceModule] => actorRef.underlyingActor.getClass shouldEqual classOf[StubServiceModule]
        }



      }

    }

  }

}
