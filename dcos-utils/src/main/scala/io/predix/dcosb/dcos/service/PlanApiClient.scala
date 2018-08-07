package io.predix.dcosb.dcos.service

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import io.predix.dcosb.dcos.DCOSProxy
import io.predix.dcosb.util.actor.{ConfiguredActor, HttpClientActor}
import spray.json.DefaultJsonProtocol

import scala.collection.immutable.Seq
import scala.concurrent.{Future, Promise}
import scala.util.Success

object PlanApiClient {

  case class Configuration(httpClient: DCOSProxy.HttpClient, planApiCompatible: Boolean = true)

  // handled messages
  case class RetrievePlan(serviceId: String, planName: String)

  // responses

  /**
    * Indicates the service api may not be plan api -compatible
    */
  case class UnexpectedResponse(r: HttpResponse) extends Throwable

  /**
    * The DC/OS proxy returned HTTP 502 Bad Gateway
    * ( the scheduler may be restarting )
    */
  class SchedulerGone extends Throwable
  class ServiceNotFound extends Throwable
  case class PlanNotFound(serviceId: String, planName: String) extends Throwable
  case class NotPlanApiCompatibleService() extends Throwable

  object ApiModel {

    case class Step(id: String, name: String, status: String, message: String)
    case class Phase(id: String, name: String, status: String, steps: Seq[Step])
    case class Plan(status: String, errors: Seq[String], phases: Seq[Phase])

    trait JsonSupport extends DefaultJsonProtocol {

      implicit val stepFormat = jsonFormat4(Step)
      implicit val phaseFormat = jsonFormat4(Phase)
      implicit val planFormat = jsonFormat3(Plan)

    }

  }

  val name = "plan-api"

}

/**
  * Consuming [[https://github.com/mesosphere/dcos-commons/blob/cassandra-1.0.31-3.0.13-beta/sdk/scheduler/src/main/java/com/mesosphere/sdk/api/types/PlanInfo.java]]
  */
class PlanApiClient extends ConfiguredActor[PlanApiClient.Configuration] with HttpClientActor with PlanApiClient.ApiModel.JsonSupport with SprayJsonSupport {
  import PlanApiClient._
  implicit val ec = context.dispatcher
  implicit val mat = ActorMaterializer(ActorMaterializerSettings(context.system))

  private var planApiCompatible = true

  override def configure(configuration: Configuration): Future[ConfiguredActor.Configured] = {
    this.httpClient = Some(configuration.httpClient)
    this.planApiCompatible = configuration.planApiCompatible

    super.configure(configuration)
  }

  override def configuredBehavior: Receive = {
    case RetrievePlan(serviceId: String, planName: String) => broadcastFuture(retrievePlan(serviceId, planName), sender())

  }

  def retrievePlan(serviceId: String, planName: String): Future[ApiModel.Plan] = {

    val promise = Promise[ApiModel.Plan]()

    planApiCompatible match {
      case false =>

        promise.failure(NotPlanApiCompatibleService())

      case true =>

        val listPlans = HttpRequest(method = HttpMethods.GET, uri = s"/service/$serviceId/v1/plans")

        `sendRequest and handle response`(listPlans, {
          case Success(HttpResponse(StatusCodes.OK, _, plansEntity, _)) =>
            Unmarshal(plansEntity).to[Array[String]] onComplete {
              case Success(plans: Array[String]) if plans.contains(planName) =>
                log.debug(s"Plan $planName found in /plans output")
                // plan was found, let's get it..
                val getPlan = HttpRequest(method = HttpMethods.GET, uri = s"/service/$serviceId/v1/plans/$planName")
                `sendRequest and handle response`(getPlan, {
                  case Success(HttpResponse(status, _, planEntity, _)) if status == StatusCodes.OK || status == StatusCodes.Accepted =>
                    Unmarshal(planEntity).to[ApiModel.Plan] onComplete {
                      case Success(plan: ApiModel.Plan) =>
                        promise.success(plan)
                    }
                  case Success(r: HttpResponse) if r.status == StatusCodes.NotFound =>
                    log.warning(s"Plan $planName exists in /plans output but 404 via /plans/$planName ??")
                    promise.failure(new UnexpectedResponse(r))
                  case Success(r: HttpResponse) =>
                    promise.failure(new UnexpectedResponse(r))
                })

              case Success(plans: Array[String]) =>
                plansEntity.discardBytes(mat)
                promise.failure(new PlanNotFound(serviceId, planName))
            }

          case Success(HttpResponse(StatusCodes.NotFound, _, re, _)) =>
            re.discardBytes(mat)
            promise.failure(new ServiceNotFound())

          case Success(HttpResponse(StatusCodes.BadGateway, _, re, _)) =>
            re.discardBytes(mat)
            promise.failure(new SchedulerGone())

          case Success(r: HttpResponse) =>
            r.entity.discardBytes(mat)
            promise.failure(new UnexpectedResponse(r))
        })


    }

    promise.future

  }

}
