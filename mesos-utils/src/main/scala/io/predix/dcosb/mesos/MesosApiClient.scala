package io.predix.dcosb.mesos

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.event.LoggingReceive
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import io.predix.dcosb.config.model.DCOSClusterConnectionParameters
import io.predix.dcosb.mesos.MesosApiClient.Master.{FrameworkNotFound, ResourceUnreservationResults}
import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel.Master.Slaves.{ResourceReservation, SlavesResponse}
import io.predix.dcosb.util.{AsyncUtils, JsonFormats}
import io.predix.dcosb.util.actor.HttpClientActor
import io.predix.dcosb.util.actor.ConfiguredActor.Configured
import io.predix.dcosb.util.actor.ConfiguredActor
import spray.json.DefaultJsonProtocol
import spray.json._

import scala.collection.immutable.HashMap
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

object MesosApiClient {

  case class Configuration(httpClient: (HttpRequest, String) => Future[(HttpResponse, String)])

  class MesosApiClientNotFoundException extends Exception

  case class UnexpectedResponse(resp: HttpResponse) extends Throwable {
    override def toString: String = {
      super.toString + s", $resp"
    }
  }

  object Master {

    // handled messages
    case class GetSlaves()
    case class GetRoles()
    case class GetFrameworks()
    case class GetFramework(frameworkId: String)
    case class GetStateSummary()
    case class Unreserve(resourceReservations: Map[String, Seq[ResourceReservation]])

    // response messages
    case class StateSummary(frameworks: Seq[MesosApiModel.Master.StateSummary.FrameworkSummary])
    trait ResourceUnreservationResult
    case class ResourceUnreserved(reservation: ResourceReservation) extends ResourceUnreservationResult
    case class ReservationNotFound(reservation: ResourceReservation) extends ResourceUnreservationResult
    case class InsufficientPermissionsToUnreserve(reservation: ResourceReservation) extends ResourceUnreservationResult
    case class UnexpectedResponseWhileUnreserving(reservation: ResourceReservation, response: HttpResponse) extends ResourceUnreservationResult
    case class ExceptionWhileUnreserving(e: Throwable) extends ResourceUnreservationResult
    case class ResourceUnreservationResults(resourceReservations: Iterable[_ >: ResourceUnreservationResult])

    case class FrameworkNotFound(frameworkId: String) extends Throwable

  }



  object MesosApiModel {

    object Master {

      object Slaves {

        // mesos API response objects
        case class Label(key: String, value: String)

        case class Labels(labels: Seq[Label])

        case class PrincipalAndLabels(principal: Option[String], labels: Option[Labels])

        case class Range(begin: Int, end: Int)

        case class Ranges(range: Seq[Range])

        case class Scalar(value: Double)

        case class Volume(mode: String, container_path: String)

        case class Persistence(id: String, principal: Option[String])

        case class Disk(persistence: Persistence, volume: Volume)

        case class ResourceReservation(
                                        name: String,
                                        `type`: String,
                                        scalar: Option[Scalar] = None,
                                        ranges: Option[Ranges] = None,
                                        role: String,
                                        reservation: Option[PrincipalAndLabels],
                                        disk: Option[Disk] = None
                                      )

        case class Slave(
                          id: String,
                          pid: String,
                          hostname: String,
                          reserved_resources_full: Map[String, Seq[ResourceReservation]]
                        )

        case class SlavesResponse(slaves: Seq[Slave])

        trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {

          implicit val labelFormat = jsonFormat2(Label)
          implicit val labelsFormat = jsonFormat1(Labels)
          implicit val principalAndLabelsFormat = jsonFormat2(PrincipalAndLabels)
          implicit val rangeFormat = jsonFormat2(Range)
          implicit val rangesFormat = jsonFormat1(Ranges)
          implicit val scalarFormat = jsonFormat1(Scalar)
          implicit val volumeFormat = jsonFormat2(Volume)
          implicit val persistenceFormat = jsonFormat2(Persistence)
          implicit val diskFormat = jsonFormat2(Disk)
          implicit val resourceReservationFormat = jsonFormat7(ResourceReservation)
          implicit val slaveFormat = jsonFormat4(Slave)
          implicit val slavesResponseFormat = jsonFormat1(SlavesResponse)

        }

      }

      object Roles {

        case class Resources(cpus: Double = 0d,
                             disk: Double = 0d,
                             gpus: Double = 0d,
                             mem: Double = 0d,
                             ports: Option[String] = None)

        case class Role(frameworks: Seq[String],
                        name: String,
                        weight: Double,
                        resources: Resources)
        case class RolesResponse(roles: Seq[Role])

        trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {

          implicit val resourcesFormat = jsonFormat5(Resources)
          implicit val roleFormat = jsonFormat4(Role)
          implicit val rolesResponseFormat = jsonFormat1(RolesResponse)

        }

      }

      object Frameworks {

        case class ContainerId(value: String)
        case class ContainerStatus(container_id: ContainerId)

        case class Status(state: String,
                          container_status: ContainerStatus)

        object Visibility extends Enumeration {
          val CLUSTER = Value("CLUSTER")
          val EXTERNAL = Value("EXTERNAL")
          val FRAMEWORK = Value("FRAMEWORK")
        }

        object Protocol extends Enumeration {
          val TCP = Value("tcp")
          val UDP = Value("udp")
        }

        case class Port(number: Int, name: Option[String] = None, protocol: Protocol.Value, visibility: Option[Visibility.Value] = None)
        case class Ports(ports: Option[List[Port]])

        case class Discovery(visibility: Option[Visibility.Value] = None, name: String, ports: Option[Ports] = None)

        case class Task(id: String,
                        name: String,
                        slave_id: String,
                        state: String,
                        statuses: Seq[Status],
                        discovery: Option[Discovery] = None)

        case class Framework(id: String,
                             name: String,
                             pid: String,
                             active: Boolean,
                             role: String,
                             principal: Option[String],
                             tasks: Seq[Task],
                             unreachable_tasks: Seq[Task])

        case class FrameworksResponse(frameworks: Seq[Framework])

        trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {

          implicit val containerIdFormat = jsonFormat1(ContainerId)
          implicit val containerStatusFormat = jsonFormat1(ContainerStatus)
          implicit val statusFormat = jsonFormat2(Status)
          implicit val protocolFormat = new JsonFormats.EnumJsonConverter(Protocol)
          implicit val visibilityFormat = new JsonFormats.EnumJsonConverter(Visibility)
          implicit val portFormat = jsonFormat4(Port)
          implicit val portsFormat = jsonFormat1(Ports)
          implicit val discoveryFormat = jsonFormat3(Discovery)
          implicit val taskFormat = jsonFormat6(Task)
          implicit val frameworkFormat = jsonFormat8(Framework)
          implicit val frameworksResponseFormat = jsonFormat1(FrameworksResponse)

        }


      }

      object StateSummary {

        case class FrameworkSummary(id: String, name: String)
        case class StateSummaryResponse(frameworks: Seq[FrameworkSummary])

        trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {

          implicit val frameworkSummaryFormat = jsonFormat2(FrameworkSummary)
          implicit val stateSummaryResponseFormat = jsonFormat1(StateSummaryResponse)

        }

      }

      trait JsonSupport extends Slaves.JsonSupport with Frameworks.JsonSupport with Roles.JsonSupport with StateSummary.JsonSupport

    }

    trait JsonSupport extends Master.JsonSupport

  }

  val name = "mesos-api-client"


}

class MesosApiClient extends ConfiguredActor[MesosApiClient.Configuration] with ActorLogging with HttpClientActor with MesosApiClient.MesosApiModel.JsonSupport {
  import MesosApiClient._

  implicit val ec = context.dispatcher
  implicit val mat = ActorMaterializer(ActorMaterializerSettings(context.system))

  override def configure(configuration: MesosApiClient.Configuration): Future[Configured] = {
    super.configure(configuration)
    this.httpClient = Some(configuration.httpClient)

    Future.successful(Configured())
  }

  final def configuredBehavior: Receive = LoggingReceive {

    case c: Configuration => configure(c)

    case _: Master.GetSlaves => broadcastFuture(getSlaves(), sender())
    case _: Master.GetRoles => broadcastFuture(getRoles(), sender())
    case _: Master.GetFrameworks => broadcastFuture(getFrameworks(), sender())
    case Master.GetFramework(frameworkId: String) => broadcastFuture(getFramework(frameworkId), sender())
    case _: Master.GetStateSummary => broadcastFuture(getStateSummary(), sender())
    case Master.Unreserve(reservations: Map[String, Seq[ResourceReservation]]) => broadcastFuture(unreserve(reservations), sender())

  }

  def getRoles() : Future[Seq[MesosApiModel.Master.Roles.Role]] = {
    log.debug("Handling getRoles()")
    val promise = Promise[Seq[MesosApiModel.Master.Roles.Role]]()

    def `sendRequest, unmarshall and fulfill promise`(request: HttpRequest, p: Promise[Seq[MesosApiModel.Master.Roles.Role]]): Unit = {

      `sendRequest and handle response`(request, {
        case Success(HttpResponse(StatusCodes.OK, _, re, _)) =>
          Unmarshal(re).to[MesosApiModel.Master.Roles.RolesResponse] onComplete {
            case Success(rolesResponse: MesosApiModel.Master.Roles.RolesResponse) => promise.success(rolesResponse.roles)
            case Failure(e: Throwable) =>
              re.discardBytes()
              promise.failure(e)
          }
        case Success(r: HttpResponse) =>
          r.entity.discardBytes()
          promise.failure(new UnexpectedResponse(r))
        case Failure(e: Throwable) =>
          promise.failure(e)
      })

    }

    configured((configuration) => {

      `sendRequest, unmarshall and fulfill promise`(HttpRequest(
        method = HttpMethods.GET,
        uri = "/mesos/roles"), promise)


    })

    promise.future

  }

  def getSlaves() : Future[Seq[MesosApiModel.Master.Slaves.Slave]] = {
    log.debug("Handling getSlaves()")
    val promise = Promise[Seq[MesosApiModel.Master.Slaves.Slave]]()

    def `sendRequest, unmarshall and fulfill promise`(request: HttpRequest, p: Promise[Seq[MesosApiModel.Master.Slaves.Slave]]): Unit = {

      `sendRequest and handle response`(request, {
        case Success(HttpResponse(StatusCodes.OK, _, re, _)) =>
          Unmarshal(re).to[MesosApiModel.Master.Slaves.SlavesResponse] onComplete {
            case Success(slavesResponse: MesosApiModel.Master.Slaves.SlavesResponse) => promise.success(slavesResponse.slaves)
            case Failure(e: Throwable) =>
              re.discardBytes()
              promise.failure(e)
          }
        case Success(r: HttpResponse) =>
          r.entity.discardBytes()
          promise.failure(new UnexpectedResponse(r))
        case Failure(e: Throwable) =>
          promise.failure(e)
      })

    }

    configured((configuration) => {

      `sendRequest, unmarshall and fulfill promise`( HttpRequest(
        method = HttpMethods.GET,
        uri = "/mesos/slaves"), promise )

    })

  promise.future


  }

  def getFrameworks(): Future[Seq[MesosApiModel.Master.Frameworks.Framework]] = {
    log.debug("Handling getFrameworks()")

    val promise = Promise[Seq[MesosApiModel.Master.Frameworks.Framework]]()
    def `sendRequest, unmarshall and fulfill promise`(request: HttpRequest, p: Promise[Seq[MesosApiModel.Master.Frameworks.Framework]]): Unit = {

      `sendRequest and handle response`(request, {
        case Success(HttpResponse(StatusCodes.OK, _, re, _)) =>
          Unmarshal(re).to[MesosApiModel.Master.Frameworks.FrameworksResponse] onComplete {
            case Success(frameworksResponse: MesosApiModel.Master.Frameworks.FrameworksResponse) => promise.success(frameworksResponse.frameworks)
            case Failure(e: Throwable) =>
              re.discardBytes()
              promise.failure(e)
          }
        case Success(r: HttpResponse) =>
          r.entity.discardBytes()
          promise.failure(new UnexpectedResponse(r))
        case Failure(e: Throwable) =>
          promise.failure(e)
      })

    }

    configured((configuration) => {

      `sendRequest, unmarshall and fulfill promise`( HttpRequest(
        method = HttpMethods.GET,
        uri = "/mesos/frameworks"), promise )

    })

    promise.future
  }

  def getFramework(frameworkId: String) = {
    log.debug(s"Handling getFrameworks($frameworkId)")
    val promise = Promise[MesosApiModel.Master.Frameworks.Framework]()
    def `sendRequest, unmarshall and fulfill promise`(request: HttpRequest, p: Promise[MesosApiModel.Master.Frameworks.Framework]): Unit = {

      `sendRequest and handle response`(request, {
        case Success(HttpResponse(StatusCodes.OK, _, re, _)) =>
          Unmarshal(re).to[MesosApiModel.Master.Frameworks.FrameworksResponse] onComplete {
            case Success(frameworksResponse: MesosApiModel.Master.Frameworks.FrameworksResponse) if frameworksResponse.frameworks.size > 0 => promise.success(frameworksResponse.frameworks.head)
            case Success(frameworksResponse: MesosApiModel.Master.Frameworks.FrameworksResponse) => promise.failure(FrameworkNotFound(frameworkId))
            case Failure(e: Throwable) =>
              re.discardBytes()
              promise.failure(e)
          }
        case Success(r: HttpResponse) =>
          r.entity.discardBytes()
          promise.failure(new UnexpectedResponse(r))
        case Failure(e: Throwable) =>
          promise.failure(e)
      })

    }

    configured((configuration) => {

      `sendRequest, unmarshall and fulfill promise`( HttpRequest(
        method = HttpMethods.GET,
        uri = s"/mesos/frameworks?framework_id=$frameworkId"), promise )

    })

    promise.future


  }

  def getStateSummary() = {
    log.debug("Handling getStateSummary()")

    val promise = Promise[MesosApiClient.Master.StateSummary]()
    def `sendRequest, unmarshall and fulfill promise`(request: HttpRequest, p: Promise[MesosApiClient.Master.StateSummary]): Unit = {

      `sendRequest and handle response`(request, {
        case Success(HttpResponse(StatusCodes.OK, _, re, _)) =>
          Unmarshal(re).to[MesosApiModel.Master.StateSummary.StateSummaryResponse] onComplete {
            case Success(stateSummaryResponse: MesosApiModel.Master.StateSummary.StateSummaryResponse) => promise.success(MesosApiClient.Master.StateSummary(stateSummaryResponse.frameworks))
            case Failure(e: Throwable) =>
              re.discardBytes()
              promise.failure(e)
          }
        case Success(r: HttpResponse) =>
          r.entity.discardBytes()
          promise.failure(new UnexpectedResponse(r))
        case Failure(e: Throwable) =>
          promise.failure(e)
      })

    }

    configured((configuration) => {

      `sendRequest, unmarshall and fulfill promise`( HttpRequest(
        method = HttpMethods.GET,
        uri = "/mesos/state-summary"), promise )

    })

    promise.future

  }

  def unreserve(r: Map[String, Seq[ResourceReservation]]): Future[Master.ResourceUnreservationResults] = {

    log.debug("Handling unreserve()")
    val promise = Promise[Master.ResourceUnreservationResults]()

    configured((configuration) => {

        // here comes the ugliness..
        val reservationLookup:Map[String, ResourceReservation] = r flatMap { case (_, reservations) =>
          reservations map ( r => r.hashCode().toString -> r ) toMap

        }

        log.debug(s"Reservation lookup: $reservationLookup")

        // marshal reservations to http request entities..
        val marshalFutures:Seq[Future[(RequestEntity, ResourceReservation)]] = (
          for((slave, reservations) <- r)
            yield for (reservation <- reservations)
              yield (AsyncUtils.contextualize(Marshal(FormData(HashMap("slaveId" -> slave, "resources" -> reservation.toJson.compactPrint))).to[RequestEntity], reservation))).flatten.toSeq


            AsyncUtils.waitAll[(RequestEntity, ResourceReservation)](marshalFutures) map { entities =>

              // wrap request entities in requests
              val requests:Iterable[(HttpRequest, String)] = for (e <- entities) yield
                e match {
                  case Success((entity: RequestEntity, reservation: ResourceReservation)) =>
                    (HttpRequest(
                      uri = "/mesos/master/unreserve",
                      method = HttpMethods.POST,
                      entity = entity
                    ), reservation.hashCode().toString)
                    // TODO this ^ just ignores marshalling exceptions :-(
                }

              log.debug(s"Ready to send Requests $requests")

              // send requests
              multipleHttpRequestsSending(requests.toSeq, (responses:Seq[Try[(HttpResponse, String)]]) => {
                log.debug(s"Received responses $responses")
                val unreservationResults = responses map({ response =>
                  log.debug(s"Response: $response")
                  response match {
                    case Success((r: HttpResponse, c)) if r.status == StatusCodes.Accepted => Master.ResourceUnreserved(reservationLookup.get(c).get)
                    case Success((r: HttpResponse, c)) if r.status == StatusCodes.NotFound => Master.ReservationNotFound(reservationLookup.get(c).get)
                    case Success((r: HttpResponse, c)) if r.status == StatusCodes.Unauthorized => Master.InsufficientPermissionsToUnreserve(reservationLookup.get(c).get)
                    case Success((r: HttpResponse, c)) => Master.UnexpectedResponseWhileUnreserving(reservationLookup.get(c).get, r)
                    case Failure(e: Throwable) => Master.ExceptionWhileUnreserving(e)
                  }
                })

                log.debug(s"Unreservation results: $unreservationResults")

                promise.success(Master.ResourceUnreservationResults(unreservationResults))

              })

            }



    })

    // wait(nb) on all futures
    promise.future

  }


}
