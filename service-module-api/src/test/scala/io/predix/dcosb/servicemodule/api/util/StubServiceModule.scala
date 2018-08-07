package io.predix.dcosb.servicemodule.api.util

import io.predix.dcosb.dcos.DCOSCommon
import io.predix.dcosb.dcos.service.PlanApiClient
import io.predix.dcosb.servicemodule.api.{ServiceModule, ServiceModuleConfiguration}
import io.predix.dcosb.servicemodule.api.ServiceModule._
import io.predix.dcosb.servicemodule.api.ServiceModuleConfiguration.OpenServiceBrokerApi
import io.predix.dcosb.servicemodule.api.util.StubServiceModule.{PIP, PO}

import scala.concurrent.Future
import scala.util.Try

object StubServiceModule {

  type PIP =
    BasicServiceModule.OpenServiceBrokerModelSupport.CommonProvisionInstanceParameters
  type UIP =
    BasicServiceModule.OpenServiceBrokerModelSupport.CommonUpdateInstanceParameters
  type BP =
    BasicServiceModule.OpenServiceBrokerModelSupport.CommonBindParameters
  type BR = BasicServiceModule.OpenServiceBrokerModelSupport.CommonBindResponse
  type PO = BasicServiceModule.DCOSModelSupport.CommonPackageOptions

}

class StubServiceModule
    extends ServiceModule[
      BasicServiceModule.CommonServiceConfiguration[StubServiceModule.PIP,
                                                    StubServiceModule.UIP,
                                                    StubServiceModule.BP,
                                                    StubServiceModule.BR,
                                                    StubServiceModule.PO]]
    with BasicServiceModule {
  import StubServiceModule._

  override def getConfiguration(serviceId: String)
    : Try[BasicServiceModule.CommonServiceConfiguration[PIP, UIP, BP, BR, PO]] = {
    loadFromTypeSafeConfig(s"dcosb.services.$serviceId")
  }

  override def createServiceInstance(organizationGuid: String,
                                     plan: ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan,
                                     serviceId: String,
                                     spaceGuid: String,
                                     serviceInstanceId: String,
                                     parameters: Option[T] forSome {
                                       type T <: ProvisionInstanceParameters
                                     }): Future[_ <: PackageOptions] = {
    Future.failed(new NotImplementedError())
  }
  override def updateServiceInstance(
                                      serviceId: String,
                                      serviceInstanceId: String,
                                      plan: Option[ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan] = None,
                                      previousValues: Option[OSB.PreviousValues],
                                      parameters: Option[_ <: UpdateInstanceParameters],
                                      endpoints: List[Endpoint],
                                      scheduler: Option[DCOSCommon.Scheduler]): Future[_ <: PackageOptions] = {
    Future.failed(new NotImplementedError())
  }

  override def bindApplicationToServiceInstance(
      serviceId: String,
      plan: ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan,
      bindResource: Option[OSB.BindResource],
      bindingId: String,
      serviceInstanceId: String,
      parameters: Option[_ <: BindParameters],
      endpoints: List[Endpoint],
      scheduler: Option[DCOSCommon.Scheduler]): Future[_ <: BindResponse] = {
    Future.failed(new NotImplementedError())
  }

  override def unbindApplicationFromServiceInstance(serviceId: String,
                                                    plan: ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan,
                                                    bindingId: String,
                                                    serviceInstanceId: String,
                                                    endpoints: List[Endpoint],
                                                    scheduler: Option[DCOSCommon.Scheduler])
    : Future[ServiceModule.ApplicationUnboundFromServiceInstance] = {
    Future.failed(new NotImplementedError())
  }

  override def destroyServiceInstance(
      serviceId: String,
      plan: ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan,
      serviceInstanceId: String,
      endpoints: List[Endpoint],
      scheduler: Option[DCOSCommon.Scheduler]): Future[ServiceInstanceDestroyed] = {
    Future.failed(new NotImplementedError())
  }

  override def lastOperation(serviceId: Option[String],
                             plan: Option[ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan],
                             serviceInstanceId: String,
                             operation: OSB.Operation.Value,
                             endpoints: List[Endpoint],
                             scheduler: Option[DCOSCommon.Scheduler],
                             deployPlan: Try[PlanApiClient.ApiModel.Plan])
    : Future[LastOperationStatus] =
    ???
}
