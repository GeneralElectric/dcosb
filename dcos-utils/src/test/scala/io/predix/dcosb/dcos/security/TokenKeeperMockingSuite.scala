package io.predix.dcosb.dcos.security

import akka.actor.{Actor, ActorRef, Props}
import akka.testkit.{CallingThreadDispatcher, TestActorRef}
import io.predix.dcosb.util.{ActorSuite}

import scala.util.Try

trait TokenKeeperMockingSuite extends ActorSuite {

  object TokenKeeperMock {

    private val _getOrRefreshToken = mockFunction[Try[TokenKeeper.Token]]

    private val actor = TestActorRef(Props(new Actor {
      override def receive: Receive = {
        case _: TokenKeeper.GetOrRefreshToken =>
          system.log.debug("TokenKeeperMock received GetOrRefreshToken!")
          sender() ! _getOrRefreshToken()
      }
    }).withDispatcher(CallingThreadDispatcher.Id))

    def getActor(): ActorRef = actor
    def getOrRefreshToken() = (_getOrRefreshToken, actor)

  }


}
