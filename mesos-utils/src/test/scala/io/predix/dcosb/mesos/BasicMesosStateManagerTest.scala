package io.predix.dcosb.mesos

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, Props}
import akka.testkit.{CallingThreadDispatcher, TestActor, TestActorRef}
import akka.pattern.ask
import akka.util.Timeout
import io.predix.dcosb.mesos.MesosApiClient.Master.{GetFrameworks, GetRoles, GetSlaves}
import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel.Master
import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel.Master.Roles
import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel.Master.Roles.Role
import io.predix.dcosb.mesos.cleanup.CleanupPolicy
import io.predix.dcosb.mesos.MesosApiClient.Master.{GetFrameworks, GetRoles, GetSlaves}
import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel
import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel.Master.Frameworks.{Framework, Task}
import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel.Master.Roles.{Resources, Role}
import io.predix.dcosb.mesos.cleanup.CleanupPolicy
import io.predix.dcosb.util.ActorSuite
import io.predix.dcosb.util.actor.ConfiguredActor
import org.scalatest.OneInstancePerTest

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Future}
import scala.util.{Success, Try}
import scala.util.matching.Regex

class BasicMesosStateManagerTest extends ActorSuite {
  implicit val timeout: Timeout = Timeout(FiniteDuration(1, TimeUnit.SECONDS))


  "A configured BasicMesosStateManager" - {


    "upon receiving an IdentifyOrphanedReservations message " - {

      "when a role name pattern is present in the message" - {


        "hands only roles with matching names to the CleanupPolicy contained within the message" in {
          val stateManager = TestActorRef(Props(classOf[BasicMesosStateManager]).withDispatcher(CallingThreadDispatcher.Id))

          val cleanupPolicy = mock[CleanupPolicy]
          val cleanupMessage = BasicMesosStateManager.IdentifyOrphanedReservations(Some("""^predix-foo-.*""".r), cleanupPolicy)

          // the client will return the full list as normal
          val apiClient = TestActorRef(Props(new Actor {
            override def receive: Receive = {
              case _: GetRoles => sender() ! Success(List(
                Roles.Role(
                  frameworks = List(),
                  name = "predix-do-not-touch",
                  weight = 1.0d,
                  resources = Master.Roles.Resources()),
                Master.Roles.Role(
                  frameworks = List(),
                  name = "predix-foo-do-touch",
                  weight = 1.0d,
                  resources = Master.Roles.Resources()),
                Master.Roles.Role(
                  frameworks = List(),
                  name = "predix-foo-also-touch",
                  weight = 1.0d,
                  resources = Master.Roles.Resources())
              ))

              case _: GetFrameworks => sender() ! Success(List())
              case _: GetSlaves => sender() ! Success(List())
            }
          }).withDispatcher(CallingThreadDispatcher.Id))

          // however the policy should receive matching roles only
          (cleanupPolicy.execute _).expects(List(Master.Roles.Role(
            frameworks = List(),
            name = "predix-foo-do-touch",
            weight = 1.0d,
            resources = Master.Roles.Resources()),
            Master.Roles.Role(
              frameworks = List(),
              name = "predix-foo-also-touch",
              weight = 1.0d,
              resources = Master.Roles.Resources())), *, *)

          Await.result(stateManager ? BasicMesosStateManager.Configuration(apiClient), timeout.duration) shouldEqual Success(ConfiguredActor.Configured())

          stateManager ! cleanupMessage

        }

      }

      "pulls mesos framework state via it's configured MesosApiClient to execute the CleanupPolicy contained within the message" in {

        val stateManager = TestActorRef(Props(classOf[BasicMesosStateManager]).withDispatcher(CallingThreadDispatcher.Id))

        val getRoles = mockFunction[Seq[Role]]
        getRoles.expects() returning List[MesosApiModel.Master.Roles.Role]() once()

        val getFrameworks = mockFunction[Seq[MesosApiModel.Master.Frameworks.Framework]]
        getFrameworks.expects() returning List[MesosApiModel.Master.Frameworks.Framework]() once()

        val getSlaves = mockFunction[Seq[MesosApiModel.Master.Slaves.Slave]]
        getSlaves.expects() returning List[MesosApiModel.Master.Slaves.Slave]() once()

        val cleanupPolicy = mock[CleanupPolicy]
        (cleanupPolicy.execute _).expects(*, *, *) once()

        val mesosApiClient = TestActorRef(Props(new Actor {
          override def receive: Receive = {
            case _: GetRoles => sender() ! Success(getRoles())
            case _: GetFrameworks => sender() ! Success(getFrameworks())
            case _: GetSlaves => sender() ! Success(getSlaves())
          }
        }).withDispatcher(CallingThreadDispatcher.Id))
        Await.result(stateManager ? BasicMesosStateManager.Configuration(mesosApiClient), timeout.duration) shouldEqual Success(ConfiguredActor.Configured())

        stateManager ! BasicMesosStateManager.IdentifyOrphanedReservations(None, cleanupPolicy)


      }


    }

  }

}
