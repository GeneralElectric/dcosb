package io.predix.dcosb.servicebroker

import java.security.MessageDigest
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef, ActorRefFactory, Stash}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{HttpEntity, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.Credentials
import akka.http.scaladsl.server.{RequestContext, Route, RouteConcatenation, RouteResult}
import akka.util.Timeout
import akka.pattern.ask
import com.typesafe.config.Config
import io.predix.dcosb.dcos.DCOSCommon
import io.predix.dcosb.dcos.service.PlanApiClient
import io.predix.dcosb.servicebroker.OpenServiceBrokerApi.APIModel.{BindApplicationToServiceInstance, CreateInstance, UpdateInstance}
import io.predix.dcosb.servicemodule.api.ServiceModule.{InsufficientApplicationPermissions, MalformedRequest, OperationDenied}
import io.predix.dcosb.util.actor.HttpClientActor
import io.predix.dcosb.servicemodule.api.util.ServiceLoader
import spray.json._
import io.predix.dcosb.servicemodule.api.{ServiceModule, ServiceModuleConfiguration}
import io.predix.dcosb.util.JsonFormats
import io.predix.dcosb.util.actor.ConfiguredActor
import org.apache.commons.codec.binary.Hex
import pureconfig.syntax._

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

object OpenServiceBrokerApi {

  type InstanceParameters =
    ServiceModuleConfiguration.OpenServiceBrokerApi.ProvisionInstanceParameters
  type PackageOptions = DCOSCommon.PackageOptions

  case class Configuration(
      childMaker: (ActorRefFactory, Class[_ <: Actor], String) => ActorRef)

  // handled messages
  case class RouteForServiceModules(modules: ServiceLoader.ServiceList)

  // service broker API types
  object APIModel {

    // requests
    case class CreateInstance(organization_guid: String,
                              plan_id: String,
                              service_id: String,
                              space_guid: String,
                              parameters: Option[JsValue])

    case class UpdateInstance(service_id: String,
                              plan_id: Option[String],
                              parameters: Option[JsValue],
                              previous_values: Option[PreviousValues])
    case class PreviousValues(plan_id: String)

    case class BindResource(app_guid: String)
    case class BindApplicationToServiceInstance(
        service_id: String,
        plan_id: String,
        parameters: Option[JsValue],
        bind_resource: Option[BindResource])

    // OSB HTTP API responses
    case class DefaultEmptyApiResponse()
    case class OperationResponse(operation: String)
    case class ServiceBrokerError(description: String)

    trait JsonSupport extends DefaultJsonProtocol with SprayJsonSupport {

      implicit val bindResourceFormat = jsonFormat1(BindResource)
      implicit val bindApplicationToInstanceFormat = jsonFormat4(
        BindApplicationToServiceInstance)
      implicit val operationStateFormat =
        new JsonFormats.EnumJsonConverter(ServiceModule.OperationState)
      implicit val lastOperationStatusFormat = jsonFormat2(
        ServiceModule.LastOperationStatus)
      implicit val createInstanceFormat = jsonFormat5(CreateInstance)
      implicit val previousValuesFormat = jsonFormat1(PreviousValues)
      implicit val updateInstanceFormat = jsonFormat4(UpdateInstance)
      implicit val statusResponseFormat = jsonFormat1(OperationResponse)
      implicit val defaultEmptyApiResponseFormat = jsonFormat0(
        DefaultEmptyApiResponse)
      implicit val serviceBrokerErrorFormat = jsonFormat1(ServiceBrokerError)
    }

  }

  case class ServiceLoaderNotConfigured()
      extends ConfiguredActor.ActorConfigurationException

  val name = "service-broker"
}

/**
  * Creates a {@link Route} (via akka-http) that implements [[https://docs.cloudfoundry.org/services/api.html the Open Service Broker API]]
  * Also installs {@link ServiceModule} implementations it discovers via {@link ServiceModuleStack} in to the Actor System, and manages their lifecycle.
  *
  * Overall, it's singular purpose is to translate Service Broker HTTP Requests to messages towards the {@link ServiceModule}/s
  * n.b.: All the handling or messages sent from here should happen in the abstract class @{link ServiceModule} and not in it's implementation/s
  */
class OpenServiceBrokerApi
    extends ConfiguredActor[OpenServiceBrokerApi.Configuration]
    with ActorLogging
    with Stash
    with HttpClientActor
    with ServiceModuleConfiguration.OpenServiceBrokerApi.JsonSupport
    with SprayJsonSupport
    with OpenServiceBrokerApi.APIModel.JsonSupport {
  import OpenServiceBrokerApi._

  private val username =
    tConfig.getString("dcosb.service-broker.authentication.username")
  private val passwordHash =
    tConfig.getString("dcosb.service-broker.authentication.password-hash")

  private val serviceActionTimeout = tConfig
    .getValue("dcosb.service-broker.service-timeout")
    .toOrThrow[FiniteDuration]

  private var childMaker
    : Option[(ActorRefFactory, Class[_ <: Actor], String) => ActorRef] = None
  private var serviceLoader: Option[ActorRef] = None

  override def configure(
      configuration: Configuration): Future[ConfiguredActor.Configured] = {

    childMaker = Some(configuration.childMaker)

    super.configure(configuration)

  }

  override def configuredBehavior: Receive = {

    case RouteForServiceModules(serviceList) =>
      sender() ! routeForServiceModules(serviceList)
  }

  def routeForServiceModules(
      serviceModules: ServiceLoader.ServiceList): Route = {

    // http basic authentication..
    val authenticator: (Credentials => Option[String]) =
      credentials => {

        val hasher: (String => String) = (pw: String) => {
          val md = MessageDigest.getInstance("SHA-256")
          md.update(pw.getBytes())
          Hex.encodeHexString(md.digest())
        }

        credentials match {
          case p @ Credentials.Provided(id)
              if id == username && p.verify(passwordHash, hasher) =>
            Some(id)
          case _ => None
        }
      }

    // the route itself
    authenticateBasic("dcosb", authenticator) { apiUser =>
      pathPrefix("dcosb") {

        RouteConcatenation.concat(
          (serviceModules map {
            case (id, (config, service)) =>
              routeForServiceModule(config, service)
          }).toSeq: _*
        )

      }

    }

  }

  def routeForServiceModule(configuration: ServiceModuleConfiguration[
                              _ <: ServiceModule.ProvisionInstanceParameters,
                              _ <: ServiceModule.UpdateInstanceParameters,
                              _ <: ServiceModule.BindParameters,
                              _ <: ServiceModule.BindResponse,
                              _ <: ServiceModule.PackageOptions],
                            serviceModule: ActorRef) = {

    implicit val timeout = Timeout(serviceActionTimeout)

    pathPrefix(configuration.serviceId) {
      log.debug(s"Serving ${configuration.serviceId}")
      pathPrefix("broker") {
        pathPrefix("v2") {
          path("catalog") {
            pathEnd {
              get {
                val catalogJson =
                  catalogFormat(configuration.openService)
                complete(StatusCodes.OK, catalogJson)
              }
            }
          } ~
            pathPrefix("service_instances") {
              pathPrefix("""[\w|\-]+""".r) { serviceInstanceId =>
                {
                  pathEndOrSingleSlash {
                    // service instance management related routes
                    put {
                      entity(as[CreateInstance]) { createInstance =>
                        // have the parameter reader ( parser )
                        // specified in configuration deserialize this
                        val instanceParameters =
                          configuration.openService
                            .provisionInstanceParametersReader(
                              createInstance.parameters)

                        instanceParameters match {
                          case Success(p) =>
                            // instance params object was successfully deserialized
                            log.debug(s"Parsed instance parameters $p")
                            requirePlan(createInstance.plan_id, configuration, { plan =>
                              val createServiceInstance =
                                ServiceModule.CreateServiceInstance(
                                  createInstance.organization_guid,
                                  plan,
                                  createInstance.service_id,
                                  createInstance.space_guid,
                                  serviceInstanceId,
                                  p)

                              log.debug(
                                s"Sending $createServiceInstance to ${configuration.serviceId}")
                              onComplete((serviceModule ? createServiceInstance)
                                .mapTo[Try[_ <: DCOSCommon.PackageOptions]]) {

                                case Success(Success(packageOptions: Any)) =>
                                  complete(StatusCodes.Accepted,
                                    APIModel.OperationResponse(
                                      ServiceModule.OSB.Operation.CREATE
                                        .toString()))

                                case Success(Failure(InsufficientApplicationPermissions(message))) =>
                                  complete(StatusCodes.Unauthorized, APIModel.ServiceBrokerError(message))
                                case Success(Failure(MalformedRequest(message))) =>
                                  complete(StatusCodes.BadRequest, APIModel.ServiceBrokerError(message))
                                case Success(Failure(e: Throwable)) =>
                                  log.error(
                                    s"CreateServiceInstance for ServiceModule(${configuration.serviceId} sent failure $e")
                                  complete(
                                    StatusCodes.ServiceUnavailable,
                                    APIModel.ServiceBrokerError(
                                      "Service module failed to create service instance, contact support."))
                                case r =>
                                  log.error(
                                    s"CreateServiceInstance for ServiceModule(${configuration.serviceId} sent unexpected response $r")
                                  complete(
                                    StatusCodes.InternalServerError,
                                    APIModel.ServiceBrokerError(
                                      "Service module error, contact support."))

                              }

                            })


                          case Failure(e: DeserializationException) =>
                            // deserialization failure
                            log.warning(
                              s"ServiceModule(${configuration.serviceId}) failed to deserialize instance parameter ${createInstance.parameters}, failure was $e")
                            complete(StatusCodes.BadRequest,
                                     APIModel.ServiceBrokerError(e.getMessage))

                          case Failure(e: Throwable) =>
                            // any other failure while deserializing
                            log.error(
                              s"ServiceModule(${configuration.serviceId}) failed to deserialize instance parameter ${createInstance.parameters}, failure was $e")
                            complete(
                              StatusCodes.InternalServerError,
                              APIModel.ServiceBrokerError(
                                "ServiceModule implementation failed to parse request. Check logs."))

                        }

                      }
                    } ~
                      patch {
                        entity(as[UpdateInstance]) { updateInstance =>
                          // let's see if there's a new plan coming in, and what our stance towards
                          // updating plans is ..
                          (updateInstance.plan_id,
                           configuration.openService.plan_updateable) match {

                            // no-can't-do
                            case (Some(_), false) =>
                              complete(
                                StatusCodes.BadRequest,
                                APIModel.ServiceBrokerError(
                                  s"Unfortunately, ${configuration.serviceId} does not support updating of service instance plans"))

                            case _ =>
                              (updateInstance.plan_id,
                               updateInstance.parameters) match {

                                case (None, None) =>
                                  complete(
                                    StatusCodes.BadRequest,
                                    APIModel.ServiceBrokerError(
                                      "No updated plan or parameters were provided - not sure what you need changed"))

                                case _ =>
                                  validatePlan(updateInstance.plan_id, configuration, (plan => {

                                    val instanceParameters =
                                      configuration.openService
                                        .updateInstanceParametersReader(
                                          updateInstance.parameters)

                                    instanceParameters match {
                                      case Success(p) =>
                                        // instance params object was successfully deserialized
                                        log.debug(
                                          s"Parsed instance parameters $p")
                                        val updateServiceInstance =
                                          ServiceModule.UpdateServiceInstance(
                                            updateInstance.service_id,
                                            plan,
                                            updateInstance.previous_values match {
                                              case Some(
                                              pV: APIModel.PreviousValues) =>
                                                Some(ServiceModule.OSB
                                                  .PreviousValues(pV.plan_id))
                                              case _ => None
                                            },
                                            serviceInstanceId,
                                            p
                                          )

                                        log.debug(
                                          s"Sending $instanceParameters to ${configuration.serviceId}")
                                        onComplete(
                                          (serviceModule ? updateServiceInstance)
                                            .mapTo[Try[
                                            _ <: DCOSCommon.PackageOptions]]) {

                                          case Success(
                                          Success(packageOptions: Any)) =>
                                            complete(
                                              StatusCodes.Accepted,
                                              APIModel.OperationResponse(
                                                ServiceModule.OSB.Operation.UPDATE
                                                  .toString()))
                                          case Success(Failure(InsufficientApplicationPermissions(message))) =>
                                            complete(StatusCodes.Unauthorized, APIModel.ServiceBrokerError(message))
                                          case Success(Failure(MalformedRequest(message))) =>
                                            complete(StatusCodes.BadRequest, APIModel.ServiceBrokerError(message))
                                          case Success(Failure(e: Throwable)) =>
                                            log.error(
                                              s"UpdateServiceInstance for ServiceModule(${configuration.serviceId} sent failure $e")
                                            complete(
                                              StatusCodes.ServiceUnavailable,
                                              APIModel.ServiceBrokerError(
                                                "Service module failed to update service instance, contact support."))
                                          case r =>
                                            log.error(
                                              s"UpdateServiceInstance for ServiceModule(${configuration.serviceId} sent unexpected response $r")
                                            complete(
                                              StatusCodes.InternalServerError,
                                              APIModel.ServiceBrokerError(
                                                "Service module error, contact support."))

                                        }

                                      case Failure(e: DeserializationException) =>
                                        // deserialization failure
                                        log.warning(
                                          s"ServiceModule(${configuration.serviceId}) failed to deserialize instance parameter ${updateInstance.parameters}, failure was $e")
                                        complete(StatusCodes.BadRequest,
                                          APIModel.ServiceBrokerError(
                                            e.getMessage))

                                      case Failure(e: Throwable) =>
                                        // any other failure while deserializing
                                        log.error(
                                          s"ServiceModule(${configuration.serviceId}) failed to deserialize instance parameter ${updateInstance.parameters}, failure was $e")
                                        complete(
                                          StatusCodes.InternalServerError,
                                          APIModel.ServiceBrokerError(
                                            "ServiceModule implementation failed to parse request. Check logs."))

                                    }
                                  }))


                              }

                          }

                        }
                      } ~
                      delete {
                        parameters('service_id,
                                   'plan_id,
                                   'accepts_incomplete.as[Boolean].?) {
                          (serviceId, planId, acceptsIncomplete) =>

                            requirePlan(planId, configuration, (plan => {

                              val destroyServiceInstance =
                                ServiceModule.DestroyServiceInstance(
                                  serviceId,
                                  plan,
                                  serviceInstanceId)
                              onComplete((serviceModule ? destroyServiceInstance)
                                .mapTo[Try[
                                ServiceModule.ServiceInstanceDestroyed]]) {
                                case Success(Success(
                                destroyed: ServiceModule.ServiceInstanceDestroyed)) =>
                                  complete(StatusCodes.Accepted,
                                    APIModel.OperationResponse(
                                      ServiceModule.OSB.Operation.DESTROY
                                        .toString()))
                                case Success(Failure(InsufficientApplicationPermissions(message))) =>
                                  complete(StatusCodes.Unauthorized, APIModel.ServiceBrokerError(message))
                                case Success(Failure(MalformedRequest(message))) =>
                                  complete(StatusCodes.BadRequest, APIModel.ServiceBrokerError(message))
                                case Success(Failure(e: Throwable)) =>
                                  log.error(
                                    s"ServiceModule(${configuration.serviceId}) failed to destroy service instance $serviceInstanceId, failure was $e")
                                  complete(
                                    StatusCodes.ServiceUnavailable,
                                    APIModel.ServiceBrokerError(
                                      s"Service failed to destroy service instance, contact support."))
                                case Failure(e: Throwable) =>
                                  log.error(
                                    s"Failed to process DestroyServiceInstance response from ServiceModule(${configuration.serviceId}), failure was $e")
                                  complete(
                                    StatusCodes.InternalServerError,
                                    APIModel.ServiceBrokerError(
                                      s"Service failed to destroy service instance, contact support."))
                                case r =>
                                  log.error(
                                    s"Unexpected response to DestroyServiceInstance from ServiceModule(${configuration.serviceId}), response was $r")
                                  complete(
                                    StatusCodes.InternalServerError,
                                    APIModel.ServiceBrokerError(
                                      s"Service module error, contact support."))
                              }
                            }))

                        }
                      }
                  } ~
                    pathPrefix("service_bindings") {
                      pathPrefix("""[\w|\-]+""".r) { bindingId =>
                        put {
                          entity(as[BindApplicationToServiceInstance]) { bind =>
                            requirePlan(bind.plan_id, configuration, (plan => {
                              val bindParameters =
                                configuration.openService
                                  .bindParametersReader(bind.parameters)

                              bindParameters match {
                                case Success(p) =>

                                  val bindApplicationToServiceInstance =
                                    ServiceModule
                                      .BindApplicationToServiceInstance(
                                        bind.service_id,
                                        plan,
                                        bind.bind_resource map {
                                          case OpenServiceBrokerApi.APIModel
                                          .BindResource(appId: String) =>
                                            ServiceModule.OSB.BindResource(appId)
                                        },
                                        bindingId,
                                        serviceInstanceId,
                                        p
                                      )

                                  onComplete(
                                    (serviceModule ? bindApplicationToServiceInstance)
                                      .mapTo[Try[
                                      _ <: ServiceModule.BindResponse]]) {
                                    case Success(Success(bindResponse)) =>
                                      val bindResponseJson =
                                        configuration.openService.bindResponseWriter
                                          .asInstanceOf[
                                          (ServiceModule.BindResponse => JsValue)](
                                          bindResponse)

                                      complete(StatusCodes.OK, bindResponseJson)
                                    case Success(Failure(InsufficientApplicationPermissions(message))) =>
                                      complete(StatusCodes.Unauthorized, APIModel.ServiceBrokerError(message))
                                    case Success(Failure(MalformedRequest(message))) =>
                                      complete(StatusCodes.BadRequest, APIModel.ServiceBrokerError(message))
                                    case Success(Failure(ServiceModule.ServiceInstanceNotFound(message))) =>
                                      complete(StatusCodes.NotFound, APIModel.ServiceBrokerError(message))
                                    case Success(Failure(e: Throwable)) =>
                                      log.error(
                                        s"Bind application to service instance returned failure: $e")
                                      complete(
                                        StatusCodes.ServiceUnavailable,
                                        APIModel.ServiceBrokerError(
                                          s"Service module failed to create binding, contact support."))

                                    case Failure(e: Throwable) =>
                                      log.error(
                                        s"Failed to bind response response from service module, failure was: $e")
                                      complete(
                                        StatusCodes.InternalServerError,
                                        APIModel.ServiceBrokerError(
                                          s"Service module error, contact support."))

                                  }

                                case Failure(e: DeserializationException) =>
                                  // deserialization failure
                                  log.warning(
                                    s"ServiceModule(${configuration.serviceId}) failed to deserialize binding parameters ${bind.parameters}, failure was $e")
                                  complete(
                                    StatusCodes.BadRequest,
                                    APIModel.ServiceBrokerError(e.getMessage))

                                case Failure(e: Throwable) =>
                                  // any other failure while deserializing
                                  log.error(
                                    s"ServiceModule(${configuration.serviceId}) failed to deserialize binding parameters ${bind.parameters}, failure was $e")
                                  complete(
                                    StatusCodes.InternalServerError,
                                    APIModel.ServiceBrokerError(
                                      "ServiceModule implementation failed to parse request. Check logs."))
                              }
                            }))


                          }
                        } ~
                          delete {
                            parameters('service_id, 'plan_id) {
                              (serviceId, planId) =>
                                requirePlan(planId, configuration, (plan => {

                                  val unbindApplicationFromServiceInstance =
                                    ServiceModule
                                      .UnbindApplicationFromServiceInstance(
                                        serviceId,
                                        plan,
                                        bindingId,
                                        serviceInstanceId)

                                  onComplete((serviceModule ? unbindApplicationFromServiceInstance)
                                    .mapTo[Try[ServiceModule.ApplicationUnboundFromServiceInstance]]) {
                                    case Success(Success(unbound)) =>
                                      complete(StatusCodes.OK,
                                        APIModel.DefaultEmptyApiResponse())
                                    case Success(Failure(InsufficientApplicationPermissions(message))) =>
                                      complete(StatusCodes.Unauthorized, APIModel.ServiceBrokerError(message))
                                    case Success(Failure(MalformedRequest(message))) =>
                                      complete(StatusCodes.BadRequest, APIModel.ServiceBrokerError(message))
                                    case Success(Failure(e: Throwable)) =>
                                      log.error(
                                        s"Unbind application from service instance returned failure: $e")
                                      complete(
                                        StatusCodes.ServiceUnavailable,
                                        APIModel.ServiceBrokerError(
                                          s"Service module failed to erase binding, contact support."))
                                    case Failure(e: Throwable) =>
                                      log.error(
                                        s"Failed to get unbind response response from service module, failure was: $e")
                                      complete(
                                        StatusCodes.InternalServerError,
                                        APIModel.ServiceBrokerError(
                                          s"Service module error, contact support."))
                                  }
                                }))

                            }
                          }
                      }
                    } ~
                    pathPrefix("last_operation") {
                      pathEndOrSingleSlash {
                        get {
                          parameters('service_id.?, 'plan_id.?, 'operation) {
                            (serviceId, planId, operation) =>
                              validatePlan(planId, configuration, (plan => {

                                try {
                                  val lastOperation = ServiceModule.LastOperation(
                                    serviceId,
                                    plan,
                                    serviceInstanceId,
                                    ServiceModule.OSB.Operation
                                      .withName(operation))
                                  onComplete(
                                    (serviceModule ? lastOperation).mapTo[Try[
                                      ServiceModule.LastOperationStatus]]) {
                                    case Success(Success(
                                    status: ServiceModule.LastOperationStatus)) =>
                                      complete(StatusCodes.OK, status)

                                    case Success(Failure(
                                    _: PlanApiClient.ServiceNotFound)) | Success(Failure(_: ServiceModule.ServiceInstanceNotFound)) =>
                                      complete(
                                        StatusCodes.NotFound,
                                        APIModel.ServiceBrokerError(
                                          s"Service with id $serviceInstanceId was not found (it may have been deleted)"))
                                    case Success(Failure(InsufficientApplicationPermissions(message))) =>
                                      complete(StatusCodes.Unauthorized, APIModel.ServiceBrokerError(message))
                                    case Success(Failure(MalformedRequest(message))) =>
                                      complete(StatusCodes.BadRequest, APIModel.ServiceBrokerError(message))
                                    case Success(Failure(e: Throwable)) =>
                                      log.error(
                                        s"Last operation returned failure: $e")
                                      complete(
                                        StatusCodes.ServiceUnavailable,
                                        APIModel.ServiceBrokerError(
                                          s"Service module failed to retrieve last operation, contact support."))
                                    case Failure(e: Throwable) =>
                                      log.error(
                                        s"Failed to process last operation response from service module, failure was: $e")
                                      complete(
                                        StatusCodes.InternalServerError,
                                        APIModel.ServiceBrokerError(
                                          s"Service module error, contact support."))
                                  }

                                } catch {
                                  case e: NoSuchElementException =>
                                    complete(StatusCodes.BadRequest,
                                      APIModel.ServiceBrokerError(
                                        s"Invalid operation: $operation"))
                                }
                              }))
                          }
                        }
                      }
                    }
                }
              }
            }

        }

      }

    }

  }

  def serviceModules(): List[ActorRef] = {

    serviceLoader match {

      case Some(s) =>
        // TODO: make configurable
        implicit val timeout = Timeout(FiniteDuration(1, TimeUnit.SECONDS))

        Await.result(s ? ServiceLoader.GetServices(), timeout.duration) match {

          case ServiceLoader.Services(services: List[ActorRef]) => services
          case r =>
            log.error(
              s"Received unexpected response from ServiceLoader while trying to get loaded services: $r")
            List.empty[ActorRef]

        }

      case None =>
        log.error("No ServiceLoader was found")
        List.empty[ActorRef]

    }

  }

  private def validatePlan(planId: Option[String], configuration: ServiceModuleConfiguration[_, _, _, _, _], f:(Option[ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan] => Route)): Route = {
    planId match {
      case Some(id) =>
        f(planLookup(id, configuration))
      case None =>
        f(None)
    }
  }

  private def requirePlan(planId: String, configuration: ServiceModuleConfiguration[_, _, _, _, _], f:(ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan => Route)): Route = {
    planLookup(planId, configuration) match {
      case Some(plan) =>
        f(plan)
      case None =>
        complete(
          StatusCodes.BadRequest,
          APIModel.ServiceBrokerError(s"Invalid plan: $planId"))
    }
  }

  private def planLookup(planId: String, configuration: ServiceModuleConfiguration[_, _, _, _, _]): Option[ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan] = {
    configuration.openService.plans find { _.id == planId }
  }

}
