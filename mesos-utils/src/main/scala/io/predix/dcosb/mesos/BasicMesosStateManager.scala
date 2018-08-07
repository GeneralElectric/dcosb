package io.predix.dcosb.mesos

import java.util.regex.Pattern

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.ask
import akka.event.LoggingReceive
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import io.predix.dcosb.config.model.DCOSClusterConnectionParameters
import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel.Master.Slaves.ResourceReservation
import io.predix.dcosb.mesos.cleanup.CleanupPolicy
import io.predix.dcosb.util.actor.ActorUtils
import io.predix.dcosb.util.actor.ConfiguredActor
import spray.json.DefaultJsonProtocol

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}
import scala.util.matching.Regex
import pureconfig.syntax._

object BasicMesosStateManager {

  // BasicHttpMesosStateManager messages
  case class Configuration(mesosApiClient: ActorRef)
  case class Configured()
  class ConfigurationException(cause: Throwable) extends Exception(cause)
  case class IdentifyOrphanedReservations(rolePattern: Option[Regex], policy: CleanupPolicy)

}

class BasicMesosStateManager extends ConfiguredActor[BasicMesosStateManager.Configuration] with ActorLogging with ActorUtils {
  import BasicMesosStateManager._

  val tConfig = ConfigFactory.load()
  val prefix = tConfig.getString("io.predix.dcosb.mesos.utils.cleanup.force-role-prefix")
  var mesosApiClient: Option[ActorRef] = None

  final def configuredBehavior: Receive = LoggingReceive {

    case c: Configuration => configure(c)

    case IdentifyOrphanedReservations(rolePattern, policy) => broadcastFuture(identifyOrphanedReservations(rolePattern, policy), sender())

  }

  override def configure(configuration: Configuration): Future[ConfiguredActor.Configured] = {
    mesosApiClient = Some(configuration.mesosApiClient)
    super.configure(configuration)
  }

  /**
    * Discover orphaned reservations in
    * the configured mesos cluster.
    *
    * @return
    */
  def identifyOrphanedReservations(rolePattern: Option[Regex], policy: CleanupPolicy): Future[Map[String, Seq[ResourceReservation]]] = {
    implicit val timeout = Timeout(tConfig.getValue("io.predix.dcosb.mesos.utils.api-timeout").toOrThrow[FiniteDuration])

    mesosApiClient match {

      case Some(client: ActorRef) =>

        val promise = Promise[Map[String, Seq[ResourceReservation]]]()

        // 1 : find roles matching expression
        // check if we need to build a regular expression
        val regex: Option[Regex] = rolePattern match {
          case Some(p: Regex) =>
            if (prefix.isEmpty == false) {
              val regex = (for ((c, i) <- p.regex.zipWithIndex; if !(i==0&&c=='^')) yield c).mkString
              p.regex.charAt(0) match {
                case '^'  => Some(s"""^$prefix${regex}""".r)
                case _    => Some(s"""$prefix${regex}""".r)
              }

            } else {
              Some(p)

            }
          case None if prefix.isEmpty == false => Some(s"""^${prefix}.*""".r)
          case None => None
        }

        log.debug(s"Regular expression to filter role names with: $regex")

        // get all roles from the API
        (client ? MesosApiClient.Master.GetRoles()) onComplete {
          case Success(Success(roles: Seq[MesosApiClient.MesosApiModel.Master.Roles.Role])) =>
          // 2 : pull frameworks&tasks for matched roles
            log.debug(s"Received roles from client: $roles")
            val rolesFiltered = regex match {
              case Some(p: Regex) => roles.filter( _.name.matches(p.regex) )
              case None => roles
            }

            log.debug(s"Roles after filtering: $rolesFiltered")

            (client ? MesosApiClient.Master.GetFrameworks()) onComplete {
              case Success(Success(frameworks: Seq[MesosApiClient.MesosApiModel.Master.Frameworks.Framework])) =>
                log.debug(s"Received frameworks from client $frameworks")

                (client ? MesosApiClient.Master.GetSlaves()) onComplete {

                  case Success(Success(slaves: Seq[MesosApiClient.MesosApiModel.Master.Slaves.Slave])) =>
                    log.debug(s"Received slaves from client: $slaves")

                    promise.success(policy.execute(rolesFiltered, frameworks, slaves))

                  case Success(Failure(e: Throwable)) => promise.failure(e)
                  case Failure(e: Throwable) => promise.failure(e)


                }

              case Success(Failure(e: Throwable)) => promise.failure(e)
              case Failure(e: Throwable) => promise.failure(e)

            }

          // 3 : find and eliminate reservations for roles with de-registered/non-existent/cleaned-up frameworks
          case Success(Failure(e: Throwable)) => promise.failure(e)
          case Failure(e: Throwable) => promise.failure(e)

        }




        promise.future

      case None => Future.failed(new IllegalStateException("No MesosAPIClient was found"))
    }


  }

}
