package io.predix.dcosb.util

import akka.actor.ActorSystem
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import org.scalamock.scalatest.MockFactory
import org.scalatest.{BeforeAndAfterAll, FreeSpecLike, Matchers, OneInstancePerTest}

import collection.JavaConverters._

trait RouteSuite
    extends DCOSBSuite
    with ActorSystemProvider
    with ScalatestRouteTest
    with OneInstancePerTest
    with BeforeAndAfterAll {

  // override def testConfigSource: String = "{akka.loglevel=DEBUG}"
  override def getActorSystem() = system

}
