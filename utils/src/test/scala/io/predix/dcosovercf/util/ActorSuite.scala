package io.predix.dcosb.util

import akka.actor.ActorSystem
import akka.testkit.{CallingThreadDispatcher, ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import org.scalatest._

import collection.JavaConverters._

abstract class ActorSuite
    extends TestKit(
      ActorSystem(
        "testSystem"))
    with ActorSystemProvider
    with ImplicitSender
    with DCOSBSuite
    with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  override def getActorSystem() = system

}
