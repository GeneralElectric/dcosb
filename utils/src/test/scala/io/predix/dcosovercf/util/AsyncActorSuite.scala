package io.predix.dcosovercf.util

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import io.predix.dcosb.util.{ActorSystemProvider, AsyncDCOSBSuite}
import org.scalatest._

abstract class AsyncActorSuite
    extends TestKit(
      ActorSystem(
        "testSystem"))
    with ActorSystemProvider
    with ImplicitSender
    with AsyncDCOSBSuite
    with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  override def getActorSystem() = system

}
