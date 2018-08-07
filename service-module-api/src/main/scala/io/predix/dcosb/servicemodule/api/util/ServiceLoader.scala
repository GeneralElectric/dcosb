package io.predix.dcosb.servicemodule.api.util

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorRefFactory}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import io.predix.dcosb.servicemodule.api.{ServiceModule, ServiceModuleConfiguration}
import io.predix.dcosb.dcos.{DCOSCommon, DCOSProxy}
import io.predix.dcosb.servicemodule.api.{ServiceModule, ServiceModuleConfiguration}
import io.predix.dcosb.util.actor.ConfiguredActor
import pureconfig.syntax._

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

object ServiceLoader {
  type ProvisionInstanceParameters =
    ServiceModuleConfiguration.OpenServiceBrokerApi.ProvisionInstanceParameters
  type UpdateInstanceParameters = ServiceModuleConfiguration.OpenServiceBrokerApi.UpdateInstanceParameters
  type BindParameters = ServiceModuleConfiguration.OpenServiceBrokerApi.BindParameters
  type PackageOptions = DCOSCommon.PackageOptions
  type BindResponse = ServiceModuleConfiguration.OpenServiceBrokerApi.BindResponse
  type ServiceMap = mutable.HashMap[
    String,
    (ServiceModuleConfiguration[_ <: ProvisionInstanceParameters, _ <: UpdateInstanceParameters, _ <: BindParameters, _ <: BindResponse, _ <: PackageOptions],
     ActorRef)]
  type ServiceList = List[
    (String,
     (ServiceModuleConfiguration[_ <: ProvisionInstanceParameters, _ <: UpdateInstanceParameters, _ <: BindParameters, _ <: BindResponse, _ <: PackageOptions],
      ActorRef))]

  case class Configuration(
      childMaker: (ActorRefFactory, Class[_ <: Actor], String) => ActorRef, httpClientFactory: DCOSProxy.HttpClientFactory, aksm: ActorRef)

  // handled messages
  case class RegisterService(id: String, clazz: String)
  case class GetServices()

  // responses
  case class Services(services: ServiceList)

  case class ServiceClassLoadingException(message: String, cause: Throwable)
      extends Exception(cause)

  val name = "service-loader"

}

class ServiceLoader
    extends ConfiguredActor[ServiceLoader.Configuration] {
  import ServiceLoader._

  private val tConfig = ConfigFactory.load()
  private val _services: ServiceMap = mutable.HashMap()
  private var childMaker
    : Option[(ActorRefFactory, Class[_ <: Actor], String) => ActorRef] = None
  private var httpClientFactory: Option[DCOSProxy.HttpClientFactory] = None
  private var aksm: Option[ActorRef] = None

  private val serviceGetConfigTimeout = tConfig
    .getValue("dcosb.service-loader.get-config-timeout")
    .toOrThrow[FiniteDuration]
  private val serviceActorConfigTimeout = tConfig
    .getValue("dcosb.service-loader.actor-config-timeout")
    .toOrThrow[FiniteDuration]

  override def configure(
      configuration: Configuration): Future[ConfiguredActor.Configured] = {
    childMaker = Some(configuration.childMaker)
    httpClientFactory = Some(configuration.httpClientFactory)
    aksm = Some(configuration.aksm)
    super.configure(configuration)
  }

  override def configuredBehavior: Receive = {
    case RegisterService(id, clazz) =>
      val originalSender = sender()
      loadService(id, clazz) onComplete {
        case Success(service: ActorRef) =>
          // get service's configuration..
          import akka.pattern.ask
          implicit val askTimeout: Timeout = Timeout(serviceGetConfigTimeout)

          (service ? ServiceModule.GetServiceModuleConfiguration(id)) onComplete {
            case Success(Success(configuration: ServiceModuleConfiguration[_, _, _, _, _])) =>
              // set on map
              _services += ((id, (configuration, service)))
              originalSender ! Success(service)

            case Success(Failure(e: Throwable)) =>
              log.error(
                s"Failed to get configuration for ServiceModule($id, $clazz): $e")
              originalSender ! Failure(e)
            case Failure(e: Throwable) =>
              originalSender ! Failure(e)
          }
        case Failure(e: Throwable) =>
          originalSender ! Failure(e)

      }

    case GetServices() => sender() ! Services(services())
  }

  /**
    * Provides an immutable snapshot of loaded @{link ServiceModule} implementations.
    * @return
    */
  def services(): ServiceList = {
    _services.toList
  }

  def loadService(id: String, implementingClassName: String): Future[ActorRef] = {
    import akka.pattern.ask

    implicit val askTimeout: Timeout = Timeout(serviceActorConfigTimeout)

    val promise = Promise[ActorRef]()
    (childMaker, httpClientFactory, aksm) match {

      case (Some(c), Some(h), Some(k)) =>
        try {
          val serviceClass =
            Class.forName(implementingClassName).asInstanceOf[Class[_ <: Actor]]

          val service = c(context, serviceClass, id)
          // configure service..
          (service ? ServiceModule.ActorConfiguration(c, h, k, id)) onComplete {
            case Success(Success(ConfiguredActor.Configured())) =>
              promise.success(service)
            case Success(Failure(e:Throwable)) => promise.failure(e)
            case Failure(e) => promise.failure(e)
          }

        } catch {
          case e: ClassNotFoundException =>
            promise.failure(
              new ServiceClassLoadingException(
                s"Configured class ${implementingClassName} could not be found on classpath",
                e))
          case e: ClassCastException =>
            promise.failure(
              new ServiceClassLoadingException(
                s"Configured class ${implementingClassName} is not an Actor",
                e))

        }

      case _ => promise.failure(new ConfiguredActor.ActorConfigurationException {})

    }

    promise.future

  }

}
