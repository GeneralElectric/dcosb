package io.predix.dcosb.servicemodule.api

import akka.actor.{Actor, ActorLogging, Props}
import akka.testkit.{CallingThreadDispatcher, TestActorRef}
import io.predix.dcosb.dcos.DCOSCommon
import io.predix.dcosb.dcos.service.PlanApiClient
import io.predix.dcosb.servicemodule.api.ServiceModule.{OSB, _}
import io.predix.dcosb.servicemodule.api.util.BasicServiceModule
import io.predix.dcosb.servicemodule.api.util.BasicServiceModule.{
  DCOSModelSupport,
  OpenServiceBrokerModelSupport
}
import io.predix.dcosb.util.actor.{ActorUtils, ConfiguredActor}
import io.predix.dcosb.util.{ActorSuite, ActorSystemProvider, DCOSBSuite}

import scala.concurrent.Future
import scala.util.{Failure, Try}

object ServiceModuleMockingSuite {

  abstract class TestServiceModule
      extends ServiceModule[BasicServiceModule.CommonServiceConfiguration[
        BasicServiceModule.OpenServiceBrokerModelSupport.CommonProvisionInstanceParameters,
        OpenServiceBrokerModelSupport.CommonUpdateInstanceParameters,
        OpenServiceBrokerModelSupport.CommonBindParameters,
        BasicServiceModule.OpenServiceBrokerModelSupport.CommonBindResponse,
        BasicServiceModule.DCOSModelSupport.CommonPackageOptions]]
      with BasicServiceModule
      with Actor {

    override def getConfiguration(
        serviceId: String): Try[BasicServiceModule.CommonServiceConfiguration[
      OpenServiceBrokerModelSupport.CommonProvisionInstanceParameters,
      OpenServiceBrokerModelSupport.CommonUpdateInstanceParameters,
      OpenServiceBrokerModelSupport.CommonBindParameters,
      OpenServiceBrokerModelSupport.CommonBindResponse,
      DCOSModelSupport.CommonPackageOptions]] =
      loadFromTypeSafeConfig("dcosb.services.test-service")
  }

}

/**
  * Mocks for testing ServiceModule dependent Actors
  */
trait ServiceModuleMockingSuite extends DCOSBSuite with ActorSystemProvider {
  import ServiceModuleMockingSuite._

  class TestServiceModuleMock {

    getActorSystem().log.debug("Created TestServiceModuleMock!")

    private val _createServiceInstance =
      mockFunction[String,
                   ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan,
                   String,
                   String,
                   String,
                   Option[T] forSome {
                     type T <: ServiceModuleConfiguration.OpenServiceBrokerApi.ProvisionInstanceParameters
                   },
                   Future[DCOSModelSupport.CommonPackageOptions]]

    private val _updateServiceInstance =
      mockFunction[
        String,
        String,
        Option[ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan],
        Option[ServiceModule.OSB.PreviousValues],
        Option[T] forSome {
          type T <: ServiceModuleConfiguration.OpenServiceBrokerApi.UpdateInstanceParameters
        },
        List[Endpoint],
        Option[DCOSCommon.Scheduler],
        Future[DCOSModelSupport.CommonPackageOptions]]

    private val _bindApplicationToServiceInstance =
      mockFunction[String,
                   ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan,
                   Option[OSB.BindResource],
                   String,
                   String,
                   Option[T] forSome {
                     type T <: ServiceModuleConfiguration.OpenServiceBrokerApi.BindParameters
                   },
                   List[Endpoint],
                   Option[DCOSCommon.Scheduler],
                   Future[_ <: BindResponse]]

    private val _unbindApplicationFromServiceInstance =
      mockFunction[String,
        ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan,
                   String,
                   String,
                   List[Endpoint],
                   Option[DCOSCommon.Scheduler],
                   Future[ApplicationUnboundFromServiceInstance]]

    private val _destroyServiceInstance =
      mockFunction[String,
        ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan,
                   String,
                   Option[List[ServiceModule.Endpoint]],
                   Option[DCOSCommon.Scheduler],
                   Future[ServiceInstanceDestroyed]]

    private val _lastOperation = mockFunction[Option[String],
                                              Option[ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan],
                                              String,
                                              OSB.Operation.Value,
                                              Option[List[Endpoint]],
                                              Option[DCOSCommon.Scheduler],
                                              Try[PlanApiClient.ApiModel.Plan],
                                              Future[LastOperationStatus]]

    val serviceModule =
      TestActorRef(Props(new Actor with ActorLogging with ActorUtils {
        implicit val ec = getActorSystem().dispatcher
        override def receive: Receive = {

          case ServiceModule.ActorConfiguration(_, _, _, _) =>
            sender() ! ConfiguredActor.Configured()
          case ServiceModule.GetServiceModuleConfiguration(serviceId) =>
            val configurationReader = new BasicServiceModule {}
            sender() ! configurationReader.loadFromTypeSafeConfig(
              s"dcosb.services.$serviceId")
          case ServiceModule.CreateServiceInstance(organizationGuid,
                                                   plan,
                                                   serviceId,
                                                   spaceGuid,
                                                   serviceInstanceId,
                                                   parameters) =>
            log.debug(
              s"CreateServiceInstance($organizationGuid, $plan, $serviceId, $spaceGuid, $serviceInstanceId, $parameters) received!")
            broadcastFuture(_createServiceInstance(organizationGuid,
                                                   plan,
                                                   serviceId,
                                                   spaceGuid,
                                                   serviceInstanceId,
                                                   parameters),
                            sender())

          case ServiceModule.UpdateServiceInstance(serviceId,
                                                   plan,
                                                   previousValues,
                                                   serviceInstanceId,
                                                   parameters) =>
            log.debug(
              s"UpdateServiceInstance($serviceId, $plan, $previousValues, $serviceInstanceId, $parameters) received!")
            broadcastFuture(_updateServiceInstance(serviceId,
                                                   serviceInstanceId,
                                                   plan,
                                                   previousValues,
                                                   parameters,
                                                   List.empty,
                                                   None),
                            sender())

          case ServiceModule.BindApplicationToServiceInstance(serviceId,
                                                              plan,
                                                              bindResource,
                                                              bindingId,
                                                              serviceInstanceId,
                                                              parameters) =>
            broadcastFuture(
              _bindApplicationToServiceInstance(serviceId,
                                                plan,
                                                bindResource,
                                                bindingId,
                                                serviceInstanceId,
                                                parameters,
                                                List.empty,
                                                None),
              sender()
            )

          case ServiceModule.UnbindApplicationFromServiceInstance(
              serviceId,
              plan,
              bindingId,
              serviceInstanceId) =>
            broadcastFuture(
              _unbindApplicationFromServiceInstance(serviceId,
                                                    plan,
                                                    bindingId,
                                                    serviceInstanceId,
                                                    List.empty,
                                                    None),
              sender())

          case ServiceModule.DestroyServiceInstance(serviceId,
                                                    plan,
                                                    serviceInstanceId) =>
            log.debug(
              s"DestroyServiceInstance($serviceId, $plan, $serviceInstanceId) received!")
            broadcastFuture(_destroyServiceInstance(serviceId,
                                                    plan,
                                                    serviceInstanceId,
                                                    None,
                                                    None),
                            sender())
          case ServiceModule.LastOperation(serviceId,
                                           plan,
                                           serviceInstanceId,
                                           operation) =>
            log.debug(
              s"LastOperation($serviceId, $plan, $serviceInstanceId, $operation) received!")
            broadcastFuture(_lastOperation(serviceId,
                                           plan,
                                           serviceInstanceId,
                                           operation,
                                           None,
                                           None,
                                           Failure(new Throwable {})),
                            sender())
        }

      }).withDispatcher(CallingThreadDispatcher.Id))(getActorSystem())

    def createServiceInstance() = (_createServiceInstance, serviceModule)
    def updateServiceInstance() = (_updateServiceInstance, serviceModule)
    def bindApplicationToServiceInstance() =
      (_bindApplicationToServiceInstance, serviceModule)
    def unbindApplicationFromServiceInstance() =
      (_unbindApplicationFromServiceInstance, serviceModule)
    def destroyServiceInstance() = (_destroyServiceInstance, serviceModule)
    def lastOperation() = (_lastOperation, serviceModule)

  }
}
