package io.predix.dcosb.mesos

import akka.actor.{Actor, ActorRef, Props}
import akka.testkit.{CallingThreadDispatcher, TestActorRef}
import io.predix.dcosb.util.ActorSuite

import scala.concurrent.Future
import scala.util.Try

trait MesosApiClientMockingSuite extends ActorSuite {

  object MesosApiClientMock {

    private val _getFrameworks = mockFunction[Try[Seq[MesosApiClient.MesosApiModel.Master.Frameworks.Framework]]]

    private val actor = TestActorRef(Props(new Actor() {
      override def receive: Receive = {
        case _ : MesosApiClient.Master.GetFrameworks =>
          system.log.debug("MesosApiClientMock received MesosApiClient.Master.GetFrameworks!")
          sender() ! _getFrameworks()
        case m => system.log.error(s"MesosApiClientMock received unexpected message $m")

      }
    }).withDispatcher(CallingThreadDispatcher.Id))

    def getActor() = actor
    def getFrameworks() = (_getFrameworks, actor)

  }

}
