package io.predix.dcosb.servicebroker

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorRefFactory, Props}
import akka.testkit.{CallingThreadDispatcher, TestActorRef}
import io.predix.dcosb.util.ActorSuite
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.duration.FiniteDuration

class OpenServiceBrokerApiTest extends ActorSuite {
  implicit val executionContext = system.dispatchers.lookup(CallingThreadDispatcher.Id)
  implicit val timeout = Timeout(FiniteDuration(1, TimeUnit.SECONDS))

  "A configured OpenServiceBrokerApi instance" - {

    val serviceBroker =
      TestActorRef(Props(classOf[OpenServiceBrokerApi]).withDispatcher(CallingThreadDispatcher.Id))

    val childMaker = mockFunction[ActorRefFactory, Class[_ <: Actor], String, ActorRef]
    val configResult = (serviceBroker ? OpenServiceBrokerApi.Configuration(childMaker))

  }

}
