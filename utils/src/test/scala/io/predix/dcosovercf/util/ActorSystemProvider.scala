package io.predix.dcosb.util

import akka.actor.ActorSystem

/**
  * Since both {@link RouteTest} and {@link TestKit} provide their own {@link ActorSystem},
  * it is impossible to mix them both in the same hierarchy.
  *
  * This simple trait allows tests extending one of our two base test classes/traits, {@link ActorSuite} (that extends {@link TestKit}),
  * and {@link RouteSuite} (that extends {@link RouteTest}), to mix in other traits
  * that only require access to an {@link ActorSystem}
  */
trait ActorSystemProvider {

  def getActorSystem(): ActorSystem

}
