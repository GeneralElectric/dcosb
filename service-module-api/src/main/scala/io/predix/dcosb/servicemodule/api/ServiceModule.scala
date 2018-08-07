package io.predix.dcosb.servicemodule.api

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef, ActorRefFactory, Cancellable, Stash, Terminated}
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import io.predix.dcosb.dcos.cosmos.CosmosApiClient
import io.predix.dcosb.dcos.marathon.MarathonApiClient
import io.predix.dcosb.dcos.service.PlanApiClient
import io.predix.dcosb.dcos.service.PlanApiClient.ApiModel
import pureconfig.syntax._
import io.predix.dcosb.dcos.{DCOSCommon, DCOSProxy}
import io.predix.dcosb.mesos.MesosApiClient
import io.predix.dcosb.mesos.MesosApiClient.Master.FrameworkNotFound
import io.predix.dcosb.mesos.MesosApiClient.MesosApiModel.Master.Frameworks.Framework
import io.predix.dcosb.servicemodule.api.ServiceModule.OSB.PreviousValues
import io.predix.dcosb.util.actor.ActorUtils
import io.predix.dcosb.util.actor.ConfiguredActor
import org.joda.time.DateTime
import spray.json.JsValue

import scala.collection.immutable.Seq
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

object ServiceModule {

  type ProvisionInstanceParameters =
    ServiceModuleConfiguration.OpenServiceBrokerApi.ProvisionInstanceParameters
  type UpdateInstanceParameters =
    ServiceModuleConfiguration.OpenServiceBrokerApi.UpdateInstanceParameters
  type BindParameters =
    ServiceModuleConfiguration.OpenServiceBrokerApi.BindParameters
  type PackageOptions = DCOSCommon.PackageOptions
  type BindResponse =
    ServiceModuleConfiguration.OpenServiceBrokerApi.BindResponse

  /**
    * If there are service discovery mechanisms available in the
    * DC/OS cluster, the application endpoints / service endpoints of
    * the service instance will be presented to the API methods whenever
    * possible via this type
    */
  case class Endpoint(name: String, ports: List[Port] = List.empty)

  case class Port(name: Option[String] = None, address: InetSocketAddress)

  object OSB {

    case class BindResource(appGuid: String)
    case class PreviousValues(planId: String)

    object Operation extends Enumeration {
      val CREATE = Value("create")
      val UPDATE = Value("update")
      val DESTROY = Value("destroy")
    }

  }

  trait OperationDenied extends Throwable

  /* Special Throwables
  ----------------------- */
  case class InsufficientApplicationPermissions(message: String) extends OperationDenied
  case class MalformedRequest(message: String) extends OperationDenied
  case class ServiceInstanceNotFound(serviceInstanceId: String) extends OperationDenied

  /* Internals
  -------------------- */

  /**
    * Not part of the API & do not confuse with {@link ServiceModuleConfiguration}
    *
    * @param childMaker        function used to create actor(reference)s from actor classes
    * @param httpClientFactory function used to create a [[DCOSProxy.HttpClient]] from [[DCOSCommon.Connection]] information
    * @param aksm              Actor reference to the system AkkaKeyStoreManager
    * @param serviceId         the configuration string used in creating this service module instance. Use with [[ServiceModule.getConfiguration()]] to
    *                          retrieve [[ServiceModuleConfiguration]] from this instance
    */
  case class ActorConfiguration(
      childMaker: (ActorRefFactory, Class[_ <: Actor], String) => ActorRef,
      httpClientFactory: DCOSProxy.HttpClientFactory,
      aksm: ActorRef,
      serviceId: String)

  /* SM API related messages ( requests )
  ---------------------------------------- */
  case class GetServiceModuleConfiguration(serviceId: String)

  case class CreateServiceInstance(organizationGuid: String,
                                   plan: ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan,
                                   serviceId: String,
                                   spaceGuid: String,
                                   serviceInstanceId: String,
                                   parameters: Option[T] forSome {
                                     type T <: ProvisionInstanceParameters
                                   })

  case class UpdateServiceInstance(serviceId: String,
                                   plan: Option[ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan],
                                   previousValues: Option[OSB.PreviousValues],
                                   serviceInstanceId: String,
                                   parameters: Option[T] forSome {
                                     type T <: UpdateInstanceParameters
                                   })

  case class DestroyServiceInstance(serviceId: String,
                                    plan: ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan,
                                    serviceInstanceId: String)

  case class BindApplicationToServiceInstance(
      serviceId: String,
      plan: ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan,
      bindResource: Option[OSB.BindResource],
      bindingId: String,
      serviceInstanceId: String,
      parameters: Option[T] forSome {
        type T <: BindParameters
      })

  case class UnbindApplicationFromServiceInstance(serviceId: String,
                                                  plan: ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan,
                                                  bindingId: String,
                                                  serviceInstanceId: String)

  case class LastOperation(serviceId: Option[String],
                           plan: Option[ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan],
                           serviceInstanceId: String,
                           operation: OSB.Operation.Value)

  /* SM API related messages ( responses )
  ----------------------------------------- */
  case class ServiceInstanceDestroyed(serviceInstanceId: String)

  case class ApplicationUnboundFromServiceInstance(serviceInstanceId: String,
                                                   bindingId: String)

  case class LastOperationStatus(state: OperationState.Value,
                                 description: Option[String] = None)

  object OperationState extends Enumeration {
    val IN_PROGRESS = Value("in progress")
    val SUCCEEDED = Value("succeeded")
    val FAILED = Value("failed")
  }

  object PlanProcessors {

    // helpers to traverse Plan objects

    def stepMessages(phases: Iterable[PlanApiClient.ApiModel.Phase],
                             phaseName: String = "node-deploy") = {
      phases.filter(_.name == phaseName) flatMap { phase =>
        for (step <- phase.steps)
          yield s"${step.name} -> ${step.status}"

      }
    }

    def countIncompleteSteps(
                                      phases: Iterable[PlanApiClient.ApiModel.Phase],
                                      phaseName: String): Int = {
      (phases.filter(_.name == phaseName) flatMap { phase =>
        for (step <- phase.steps; if step.status != "COMPLETE")
          yield step

      }).size
    }

    def countSteps(phases: Iterable[PlanApiClient.ApiModel.Phase],
                           phaseName: String): Int = {
      ((phases.filter(_.name == phaseName)) flatMap { _.steps }).size
    }

  }

}

/**
  * The subclasses of this abstract class represent DC/OS Services towards the toolkit.
  *
  * @tparam C The configuration implementation type this service uses
  */
abstract class ServiceModule[
    C <: ServiceModuleConfiguration[
      _ <: ServiceModuleConfiguration.OpenServiceBrokerApi.ProvisionInstanceParameters,
      _ <: ServiceModuleConfiguration.OpenServiceBrokerApi.UpdateInstanceParameters,
      _ <: ServiceModuleConfiguration.OpenServiceBrokerApi.BindParameters,
      _ <: ServiceModuleConfiguration.OpenServiceBrokerApi.BindResponse,
      _ <: DCOSCommon.PackageOptions]]
    extends ConfiguredActor[ServiceModule.ActorConfiguration]
    with ActorLogging
    with Stash
    with ActorUtils {

  import ServiceModule._

  /**
    * Deliver configuration information about this [[ServiceModule]] to the toolkit. The minimal contract of a [[ServiceModule]]'s configuration
    * is expressed as the abstract class [[ServiceModuleConfiguration]]
    *
    * @return A [[ServiceModuleConfiguration]] implementation that represents the configuration state for this [[ServiceModule]]
    */
  def getConfiguration(serviceId: String): Try[C]

  /**
    * Handle a service instance creation request. For field documentation, see [[https://docs.cloudfoundry.org/services/api.html the CloudFoundry Service Broker API Docs]],
    * for a complete overview on compatibility with this API, see the README
    *
    * @param organizationGuid
    * @param plan
    * @param serviceId
    * @param spaceGuid
    * @param parameters
    * @return A [[Future]] of an object that will be serialized using [[ServiceModuleConfiguration.dcosService.pkgOptionsWriter]] and sent to DC/OS Cosmos
    */
  def createServiceInstance(organizationGuid: String,
                            plan: ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan,
                            serviceId: String,
                            spaceGuid: String,
                            serviceInstanceId: String,
                            parameters: Option[T] forSome {
                              type T <: ProvisionInstanceParameters
                            } = None): Future[_ <: PackageOptions]

  def updateServiceInstance(serviceId: String,
                            serviceInstanceId: String,
                            plan: Option[ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan] = None,
                            previousValues: Option[OSB.PreviousValues],
                            parameters: Option[T] forSome {
                              type T <: UpdateInstanceParameters
                            } = None,
                            endpoints: List[Endpoint],
                            scheduler: Option[DCOSCommon.Scheduler])
    : Future[_ <: PackageOptions]

  def bindApplicationToServiceInstance(serviceId: String,
                                       plan: ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan,
                                       bindResource: Option[OSB.BindResource],
                                       bindingId: String,
                                       serviceInstanceId: String,
                                       parameters: Option[T] forSome {
                                         type T <: BindParameters
                                       } = None,
                                       endpoints: List[Endpoint],
                                       scheduler: Option[DCOSCommon.Scheduler])
    : Future[_ <: BindResponse]

  def unbindApplicationFromServiceInstance(
      serviceId: String,
      plan: ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan,
      bindingId: String,
      serviceInstanceId: String,
      endpoints: List[Endpoint],
      scheduler: Option[DCOSCommon.Scheduler])
    : Future[ApplicationUnboundFromServiceInstance]

  def destroyServiceInstance(serviceId: String,
                             plan: ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan,
                             serviceInstanceId: String,
                             endpoints: List[Endpoint],
                             scheduler: Option[DCOSCommon.Scheduler])
    : Future[ServiceInstanceDestroyed]

  def lastOperation(
      serviceId: Option[String],
      plan: Option[ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan],
      serviceInstanceId: String,
      operation: OSB.Operation.Value,
      endpoints: List[Endpoint],
      scheduler: Option[DCOSCommon.Scheduler],
      deployPlan: Try[PlanApiClient.ApiModel.Plan]): Future[LastOperationStatus]

  /* Helpers for SM implementations
  ----------------------------------- */

  protected val tConfig = ConfigFactory.load()
  implicit val dcosProxyTimeout = Timeout(
    tConfig.getValue("dcosb.dcos.proxy-timeout").toOrThrow[FiniteDuration])

  /**
    * Fails the promise unless [[ServiceModule.getConfiguration()]] successfully returns
    * a ServiceModuleConfiguration
    * @param serviceId
    * @param f
    * @param promise
    * @tparam T
    * @return
    */
  def withServiceModuleConfigurationOrFailPromise[T](
      serviceId: String,
      f: (ServiceModuleConfiguration[_, _, _, _, _] => _),
      promise: Promise[T]) = {
    getConfiguration(serviceId) match {
      case Success(configuration) =>
        f(configuration)
      case Failure(e: Throwable) => promise.failure(e)
    }

  }

  /**
    * Fails the promise unless a [[ServiceModule.ActorConfiguration]] is set
    * @param f
    * @param promise
    * @tparam T
    * @return
    */
  def withActorConfigurationOrFailPromise[T](
      f: (ServiceModule.ActorConfiguration => _),
      promise: Promise[T]) = {
    try {
      configured((c: ServiceModule.ActorConfiguration) => { f(c) })
    } catch {
      case e: IllegalArgumentException => promise.failure(e)
    }

  }

  /**
    * Fails the promise unless a DCOSProxy is available ( this can happen when, for example,
    * the cluster connection is failing or connection parameters are invalid )
    * @param f
    * @param promise
    * @tparam T
    * @return
    */
  def withDCOSProxyOrFailPromise[T](f: (ActorRef => _), promise: Promise[T]) = {
    dcosProxy match {
      case Some(p: ActorRef) => f(p)
      case None =>
        promise.failure(
          DCOSProxy.ClusterUnavailable(
            "No DC/OS Proxy was available to serve this request",
            None,
            None))
    }
  }

  def withAkkaKeyStoreManagerOrFailPromise[T](f: (ActorRef => _), promise: Promise[T]) = {
    aksm match {
      case Some(p: ActorRef) => f(p)
      case None =>
        promise.failure(
          DCOSProxy.ClusterUnavailable(
            "The system AkkaKeyStoreManager was available to serve this request",
            None,
            None))
    }
  }
  /**
    * Fails the promise if there is no plan named planName for service instance serviceInstanceId
    * @param serviceInstanceId
    * @param planName
    * @param f
    * @param promise
    * @tparam T
    * @return
    */
  def retrievePlanOrFailPromise[T](serviceInstanceId: String,
                                   planName: String,
                                   f: (Try[PlanApiClient.ApiModel.Plan] => _),
                                   promise: Promise[T]) = {
    withDCOSProxyOrFailPromise(
      (dcosProxy: ActorRef) => {
        (dcosProxy ? DCOSProxy.Forward(
          DCOSProxy.Target.PLAN,
          PlanApiClient.RetrievePlan(serviceInstanceId, planName))) onComplete {
          case Success(Success(plan: PlanApiClient.ApiModel.Plan)) =>
            f(Success(plan))
          case Success(Failure(e: PlanApiClient.ServiceNotFound)) =>
            f(Failure(e))
          case Success(Failure(e: PlanApiClient.SchedulerGone)) =>
            f(Failure(e))
          case Success(Failure(e: Throwable)) =>
            promise.failure(e)
          case Failure(e: Throwable) =>
            promise.failure(e)
        }
      },
      promise
    )
  }

  def retrievePlan(serviceInstanceId: String,
                   planName: String,
                   f: (Try[PlanApiClient.ApiModel.Plan] => _)) = {
    dcosProxy match {
      case Some(p: ActorRef) =>
        (p ? DCOSProxy.Forward(
          DCOSProxy.Target.PLAN,
          PlanApiClient.RetrievePlan(serviceInstanceId, planName))) onComplete {
          case Success(r: Try[PlanApiClient.ApiModel.Plan]) => f(r)
          case Failure(e: Throwable)                        => f(Failure(e))
        }

      case None =>
        f(
          Failure(
            DCOSProxy.ClusterUnavailable(
              "No DC/OS Proxy was available to serve this request",
              None,
              None)))
    }
  }

  /**
    * Encapsulates basic processing of a deploy [[ApiModel.Plan]] object to glean
    * status of a create/update/destroy operation
    * @param operation
    * @param scheduler
    * @param deployPlan
    * @return
    */
  def operationStatusFrom(
    serviceInstanceId: String,
    operation: OSB.Operation.Value,
    scheduler: Option[DCOSCommon.Scheduler],
    deployPlan: Try[ApiModel.Plan],
    inProgressStates: List[String] = List("WAITING", "PENDING", "STARTING", "IN_PROGRESS"),
    msgDestroyPending:(Seq[ApiModel.Phase] => String),
    msgCreateUpdatePending:(Seq[ApiModel.Phase] => String),
    msgCreateUpdateComplete:(Seq[ApiModel.Phase] => String)): Option[LastOperationStatus] = {
    import PlanProcessors._

    (operation, scheduler, deployPlan) match {

      /* CREATE
      ----------- */
      case (operation, None, _)
        if operation == OSB.Operation.CREATE || operation == OSB.Operation.UPDATE =>
        Some(
          LastOperationStatus(
            OperationState.IN_PROGRESS,
            Some(
              s"Scheduler for service instance with id $serviceInstanceId is being re/started. Please re-try later for operation details.")))

      case (operation, Some(_), Failure(_))
        if operation == OSB.Operation.CREATE || operation == OSB.Operation.UPDATE =>
        Some(
          LastOperationStatus(
            OperationState.IN_PROGRESS,
            Some(
              s"Scheduler for service instance with id $serviceInstanceId has started but monitoring is not yet available. Please re-try later for operation details.")
          ))

      case (operation,
      Some(_),
      Success(PlanApiClient.ApiModel.Plan(status, _, phases)))
        if (operation == OSB.Operation.CREATE || operation == OSB.Operation.UPDATE) && inProgressStates
          .contains(status) =>
        Some(
          LastOperationStatus(
            OperationState.IN_PROGRESS,
            Some(msgCreateUpdatePending(phases))
          ))

      case (operation,
      Some(_),
      Success(PlanApiClient.ApiModel.Plan("COMPLETE", _, phases)))
        if operation == OSB.Operation.CREATE || operation == OSB.Operation.UPDATE =>
        Some(
          LastOperationStatus(
            OperationState.SUCCEEDED,
            Some(msgCreateUpdateComplete(phases))
          ))

      /* DESTROY
      ----------- */

      case (OSB.Operation.DESTROY, None, _) =>
        Some(
          LastOperationStatus(
            OperationState.SUCCEEDED,
            Some(
              s"Service instance with id $serviceInstanceId not found (it may have never existed)")
          ))

      case (OSB.Operation.DESTROY, Some(_), Failure(_)) =>
        Some(
          LastOperationStatus(
            OperationState.IN_PROGRESS,
            Some(
              s"Scheduler for service instance with id $serviceInstanceId has restarted but monitoring is not yet available. Please re-try later for operation details.")
          ))

      case (OSB.Operation.DESTROY,
      Some(_),
      Success(PlanApiClient.ApiModel.Plan(status, _, phases)))
        if inProgressStates.contains(status) =>
        Some(
          LastOperationStatus(
            OperationState.IN_PROGRESS,
            Some(
              msgDestroyPending(phases))
          ))

      case (OSB.Operation.DESTROY,
      Some(_),
      Success(PlanApiClient.ApiModel.Plan("COMPLETE", _, phases))) =>
        Some(
          LastOperationStatus(
            OperationState.SUCCEEDED,
            Some(s"Cluster with id $serviceInstanceId destroyed")
          ))

      case _ => None

    }

  }

  /* Internals,
  override, invoke at own risk
  ----------------------------- */

  private var dcosProxy: Option[ActorRef] = None
  var actorConfiguration: Option[ServiceModule.ActorConfiguration] = None
  var heartbeatSchedule: Option[Cancellable] = None
  val configurationTimeout = Timeout(
    tConfig
      .getValue("dcosb.dcos.configuration-timeout")
      .toOrThrow[FiniteDuration])

  val heartbeatEnabled = tConfig
    .getBoolean("dcosb.dcos.connection.heartbeat")

  private[this] var childMaker
    : Option[(ActorRefFactory, Class[_ <: Actor], String) => ActorRef] = None
  private var aksm: Option[ActorRef] = None

  override def configure(configuration: ServiceModule.ActorConfiguration)
    : Future[ConfiguredActor.Configured] = {
    val promise = Promise[ConfiguredActor.Configured]()

    childMaker = Some(configuration.childMaker)
    aksm = Some(configuration.aksm)

    if (stopDCOSProxy() == false)
      promise.completeWith(startDCOSProxy(configuration))

    promise.future
  }

  /**
    * If there's a [[DCOSProxy]] running, stop it
    *
    * @return
    */
  private def stopDCOSProxy(): Boolean = {

    dcosProxy match {
      case Some(a: ActorRef) =>
        context.stop(a)
        true
      case None => false
    }

  }

  /**
    * Start and configure a [[DCOSProxy]]
    *
    * @param configuration
    * @return
    */
  private def startDCOSProxy(configuration: ServiceModule.ActorConfiguration)
    : Future[ConfiguredActor.Configured] = {

    val promise = Promise[ConfiguredActor.Configured]()

    val dcosProxy =
      configuration.childMaker(context, classOf[DCOSProxy], DCOSProxy.name)
    log.debug(s"Created DCOSProxy($dcosProxy)")
    this.dcosProxy = Some(dcosProxy)
    context.watch(dcosProxy)

    getConfiguration(configuration.serviceId) match {
      case Success(
          smConfiguration: ServiceModuleConfiguration[_, _, _, _, _]) =>
        (dcosProxy ? DCOSProxy.Configuration(
          configuration.childMaker,
          configuration.aksm,
          configuration.httpClientFactory,
          smConfiguration.dcosService.connection,
          DCOSProxy.optimisticConnectionParametersVerifier,
          smConfiguration.dcosService.pkg))(
          configurationTimeout) onComplete {
          case Success(Success(ConfiguredActor.Configured())) =>
            log.debug(s"Configured DCOSProxy($dcosProxy)")
            // schedule heartbeat

            if (heartbeatEnabled) {
              val heartbeatFreq = tConfig
                .getValue("dcosb.dcos.connection.heartbeat-frequency")
                .toOrThrow[FiniteDuration]

              heartbeatSchedule = Some(
                context.system.scheduler.schedule(
                  FiniteDuration(1, TimeUnit.SECONDS),
                  heartbeatFreq,
                  dcosProxy,
                  DCOSProxy.Heartbeat()))

            }
            promise.completeWith(super.configure(configuration))

          case Success(Failure(e: Throwable)) => promise.failure(e)
          case Failure(e: Throwable)          => promise.failure(e)
        }
      case Failure(e: Throwable) => promise.failure(e)
    }

    promise.future
  }

  override def configuredBehavior: Receive = {

    case Terminated(actor) if dcosProxy.nonEmpty && actor == dcosProxy.get =>
      log.warning("DCOSProxy terminated, restarting!")
      configured((configuration) => {
        startDCOSProxy(configuration)
      })
    case Success(DCOSProxy.HeartbeatOK(updated: DateTime)) =>
      log.info(s"DCOSProxy reports heartbeat passing at $updated")
    case Failure(DCOSProxy.ClusterUnavailable(message, cause, healthReport)) =>
      log.error(
        s"DCOSProxy reports heartbeat failing: $message, $cause, $healthReport")
      heartbeatSchedule match {
        case Some(s: Cancellable) =>
          s.cancel()
          log.debug(s"Cancelled heartbeat schedule: $s")
          heartbeatSchedule = None
        case None =>
          log.warning("No heartbeat schedule found to cancel?")
      }
      stopDCOSProxy()
    case CreateServiceInstance(organizationGuid,
                               plan,
                               serviceId,
                               spaceGuid,
                               serviceInstanceId,
                               parameters) =>
      val originalSender = sender()
      log.debug(s"Received CreateServiceInstance from $originalSender")

      withActorConfiguration((aC: ActorConfiguration) => {

        createServiceInstance(organizationGuid,
          plan,
          serviceId,
          spaceGuid,
          s"/dcosb/$organizationGuid/$spaceGuid/${aC.serviceId}/$serviceInstanceId".toLowerCase,
          parameters) onComplete {

          case Success(packageOptions) =>
            log.debug(
              s"Received packageOptions $packageOptions from ServiceModule, forwarding to Cosmos")
            configured((actorConfiguration => {
              withServiceModuleConfiguration(
                actorConfiguration.serviceId,
                ((smc: ServiceModuleConfiguration[_, _, _, _, _]) => {
                  withDCOSProxy(
                    ((p: ActorRef) => {
                      (p ? DCOSProxy.Forward(
                        DCOSProxy.Target.COSMOS,
                        CosmosApiClient.InstallPackage[DCOSCommon.PackageOptions](
                          smc.dcosService.pkg.pkgName,
                          smc.dcosService.pkg.pkgVersion,
                          packageOptions,
                          smc.dcosService.pkgOptionsWriter
                            .asInstanceOf[(DCOSCommon.PackageOptions => JsValue)]
                        )
                      )) onComplete {

                        case Success(
                        Success(
                        packageInstallStarted: CosmosApiClient.PackageInstallStarted)) =>
                          log.debug("Cosmos reports package install started")
                          originalSender ! Success(packageOptions)
                        case Success(Failure(e: Throwable)) =>
                          originalSender ! Failure(e)
                        case Failure(e: Throwable) =>
                          originalSender ! Failure(e)
                      }

                    }),
                    originalSender
                  )
                }),
                originalSender
              )
            }))

          case Failure(e: Throwable) =>
            originalSender ! Failure(e)

        }


      }, originalSender)


    case UpdateServiceInstance(serviceId,
                               plan,
                               previousValues,
                               serviceInstanceId,
                               parameters) =>
      val originalSender = sender()
      log.debug(s"Received UpdateServiceInstance from $originalSender")

      withActualServiceInstanceId(serviceInstanceId, (actualServiceInstanceId: String) => {

        discoverEndpoints(
          actualServiceInstanceId,
          (endpoints: List[Endpoint]) => {
            getScheduler(actualServiceInstanceId, (scheduler: Option[DCOSCommon.Scheduler]) => {

              updateServiceInstance(serviceId,
                actualServiceInstanceId,
                plan,
                previousValues,
                parameters,
                endpoints,
                scheduler) onComplete {

                case Success(packageOptions) =>
                  log.debug(
                    s"Received packageOptions $packageOptions from ServiceModule, forwarding to Cosmos")

                  configured((actorConfiguration => {
                    withServiceModuleConfiguration(
                      actorConfiguration.serviceId,
                      ((smc: ServiceModuleConfiguration[_, _, _, _, _]) => {
                        withDCOSProxy(
                          ((p: ActorRef) => {
                            (p ? DCOSProxy.Forward(
                              DCOSProxy.Target.COSMOS,
                              CosmosApiClient
                                .UpdatePackageOptions[DCOSCommon.PackageOptions](
                                actualServiceInstanceId,
                                packageOptions,
                                smc.dcosService.pkgOptionsWriter
                                  .asInstanceOf[(
                                  DCOSCommon.PackageOptions => JsValue)]
                              )
                            )) onComplete {

                              case Success(Success(
                              packageUpdated: CosmosApiClient.PackageUpdated)) =>
                                log.debug("Cosmos reports package update started")
                                originalSender ! Success(packageOptions)
                              case Success(Failure(e: Throwable)) =>
                                originalSender ! Failure(e)
                              case Failure(e: Throwable) =>
                                originalSender ! Failure(e)
                            }

                          }),
                          originalSender
                        )
                      }),
                      originalSender
                    )
                  }))

                case Failure(e: Throwable) =>
                  originalSender ! Failure(e)

              }

            }, originalSender)


          },
          originalSender
        )
      }, originalSender)


    case BindApplicationToServiceInstance(serviceId,
                                          plan,
                                          bindResource,
                                          bindingId,
                                          serviceInstanceId,
                                          parameters) =>
      val originalSender = sender()

      withActualServiceInstanceId(serviceInstanceId, (actualServiceInstanceId: String) => {

        discoverEndpoints(
          actualServiceInstanceId,
          (endpoints: List[Endpoint]) => {
            getScheduler(actualServiceInstanceId, (scheduler: Option[DCOSCommon.Scheduler]) => {

              broadcastFuture(bindApplicationToServiceInstance(serviceId,
                plan,
                bindResource,
                bindingId,
                actualServiceInstanceId,
                parameters,
                endpoints,
                scheduler),
                originalSender)

            }, originalSender)


          },
          originalSender
        )
      }, originalSender)


    case UnbindApplicationFromServiceInstance(serviceId,
                                              plan,
                                              bindingId,
                                              serviceInstanceId) =>
      val originalSender = sender()

      withActualServiceInstanceId(serviceInstanceId, (actualServiceInstanceId: String) => {

        discoverEndpoints(
          actualServiceInstanceId,
          (endpoints: List[Endpoint]) => {

            getScheduler(actualServiceInstanceId, (scheduler: Option[DCOSCommon.Scheduler]) => {

              broadcastFuture(
                unbindApplicationFromServiceInstance(serviceId,
                  plan,
                  bindingId,
                  actualServiceInstanceId,
                  endpoints,
                  scheduler),
                originalSender)

            }, originalSender)

          },
          originalSender
        )
      }, originalSender)


    case LastOperation(serviceId, plan, serviceInstanceId, operation) =>
      val originalSender = sender()

      withActualServiceInstanceId(serviceInstanceId, (actualServiceInstanceId: String) => {

        discoverEndpoints(
          actualServiceInstanceId,
          (endpoints: List[Endpoint]) => {
            retrievePlan(
              actualServiceInstanceId,
              "deploy",
              (updatePlan: Try[PlanApiClient.ApiModel.Plan]) => {
                getScheduler(actualServiceInstanceId, (scheduler: Option[DCOSCommon.Scheduler]) => {

                  lastOperation(serviceId,
                    plan,
                    actualServiceInstanceId,
                    operation,
                    endpoints,
                    scheduler,
                    updatePlan) onComplete {
                    case Success(lastOperationStatus: LastOperationStatus) =>
                      originalSender ! Success(lastOperationStatus)
                    case Failure(e: Throwable) =>
                      originalSender ! Failure(e)
                  }

                }, originalSender)

              }
            )
          },
          originalSender
        )
      }, originalSender)


    case DestroyServiceInstance(serviceId, plan, serviceInstanceId) =>
      val originalSender = sender()

      withActualServiceInstanceId(serviceInstanceId, (actualServiceInstanceId: String) => {

        def invokeDestroyService(dcosProxy: ActorRef,
                                 smc: ServiceModuleConfiguration[_, _, _, _, _],
                                 endpoints: List[Endpoint], scheduler: Option[DCOSCommon.Scheduler]): Unit = {

          destroyServiceInstance(serviceId, plan, actualServiceInstanceId, endpoints, scheduler) onComplete {
            case Success(serviceInstanceDestroyed: ServiceInstanceDestroyed) =>
              (dcosProxy ? DCOSProxy.Forward(
                DCOSProxy.Target.COSMOS,
                CosmosApiClient.UninstallPackage(smc.dcosService.pkg.pkgName,
                  actualServiceInstanceId))) onComplete {

                case Success(
                Success(
                packageUninstalled: CosmosApiClient.PackageUninstalled)) =>
                  log.debug("Cosmos reports package uninstall started")
                  originalSender ! Success(
                    ServiceModule.ServiceInstanceDestroyed(actualServiceInstanceId))
                case Success(Failure(e: Throwable)) =>
                  originalSender ! Failure(e)
                case Failure(e: Throwable) =>
                  originalSender ! Failure(e)

              }

            case Failure(e: Throwable) =>
              originalSender ! Failure(e)

          }
        }

        configured((actorConfiguration => {
          withServiceModuleConfiguration(
            actorConfiguration.serviceId,
            ((smc: ServiceModuleConfiguration[_, _, _, _, _]) => {
              withDCOSProxy(
                ((p: ActorRef) => {
                  discoverEndpoints(actualServiceInstanceId,
                    (endpoints: List[Endpoint]) => {
                      getScheduler(actualServiceInstanceId, (scheduler: Option[DCOSCommon.Scheduler]) => {
                        invokeDestroyService(p, smc, endpoints, scheduler)
                      }, originalSender)
                    },
                    originalSender)

                }),
                originalSender
              )
            }),
            originalSender
          )
        }))
      }, originalSender)


    case GetServiceModuleConfiguration(serviceId: String) =>
      sender() ! getConfiguration(serviceId)

  }

  /**
    * Takes the service instance id as sent by the platform and
    * resolves the complete id ( of the scheduler - with application groups ) as present in Marathon
    * @param platformServiceInstanceId
    * @param f
    * @param replyTo
    * @return
    */
  private def withActualServiceInstanceId(platformServiceInstanceId: String, f:(String) => _, replyTo: ActorRef) = {
    withDCOSProxy((p: ActorRef) => {
      log.debug(s"Getting AppIds matching $platformServiceInstanceId via MarathonApiClient")
      (p ? DCOSProxy.Forward(
        DCOSProxy.Target.MARATHON,
        MarathonApiClient.GetAppIds(platformServiceInstanceId)
      )) onComplete {
        case Success(Success(ids: Seq[String])) if ids.size == 0 =>
          // scheduler not running :S
          replyTo ! Failure(ServiceModule.ServiceInstanceNotFound(platformServiceInstanceId))
        case Success(Success(ids: Seq[String])) if ids.size > 1 =>
          // ambigous cfServiceInstanceId ( resolved to multiple schedulers )
          replyTo ! Failure(ServiceModule.MalformedRequest(s"Ambigous service instance identifier $platformServiceInstanceId. Contact support."))
        case Success(Success(ids: Seq[String])) =>
          f(ids.head)
        case Success(Failure(e: Throwable)) =>
          log.error(
            s"MarathonApiClient failed to retrieve app ids for id substring $platformServiceInstanceId, failure was: $e")
          replyTo ! Failure(e)
        case Failure(e: Throwable) =>
          log.error(
            s"Exception while trying to retrieve app ids for id substring $platformServiceInstanceId: $e")
          replyTo ! Failure(e)
      }

    }, replyTo)
  }

  private def endpointsFromFrameworks(frameworks: Seq[Framework],
                                      fwName: String): List[Endpoint] = {
    // filter frameworks by name
    (frameworks.filter(_.name == fwName) flatMap { framework =>
      framework.tasks flatMap { task =>
        task.discovery flatMap { discovery =>
          discovery.ports flatMap { ps =>
            ps.ports flatMap { ports =>
                  Some(Endpoint(
                    name = discovery.name,
                    ports = for (port <- ports)
                      yield
                        // TODO make TLD configurable..
                      Port(port.name,
                        new InetSocketAddress(
                          s"${discovery.name}.${fwName.replaceAll("/","")}.mesos",
                          port.number))
                  ))
            }
          }
        }

      }
    }).toList
  }

  private def discoverEndpoints(serviceInstanceId: String,
                                f: (List[ServiceModule.Endpoint] => _),
                                replyTo: ActorRef) = {
    withDCOSProxy(
      (p: ActorRef) => {
        // TODO this is a bit heavy
        log.debug("Getting Endpoints via MesosApiClient")
        log.debug(s"Getting StateSummary to resolve framework name ${serviceInstanceId} to a framework id")
        (p ? DCOSProxy.Forward(
          DCOSProxy.Target.MESOS,
          MesosApiClient.Master.GetStateSummary())) onComplete {
          case Success(Success(summary: MesosApiClient.Master.StateSummary)) =>
            log.debug("Retrieved StateSummary..")
            summary.frameworks.find( _.name == serviceInstanceId) match {
              case Some(frameworkSummary) =>
                log.debug(s"Resolved framework name $serviceInstanceId to framework id ${frameworkSummary.id}")
                log.debug(s"Getting framework with id ${frameworkSummary.id}")
                (p ? DCOSProxy.Forward(
                  DCOSProxy.Target.MESOS,
                  MesosApiClient.Master.GetFramework(frameworkSummary.id))) onComplete {
                  case Success(Success(
                  framework: MesosApiClient.MesosApiModel.Master.Frameworks.Framework)) =>
                    log.debug("Retrieved Framework")
                    f(endpointsFromFrameworks(List(framework), serviceInstanceId))
                  case Success(Failure(e: Throwable)) =>
                    log.error(
                      s"MesosApiClient failed to retrieve framework, failure was: $e")
                    replyTo ! Failure(e)
                  case Failure(e: Throwable) =>
                    log.error(
                      s"MesosApiClient failed to retrieve framework, failure was: $e")
                    replyTo ! Failure(e)

                }
              case None =>
                log.debug(s"No framework with name ${serviceInstanceId} was found in summary")
                f(List.empty)
            }
          case Success(Failure(e: Throwable)) =>
            log.error(
              s"MesosApiClient failed to retrieve state summary, failure was: $e")
            replyTo ! Failure(e)
          case Failure(e: Throwable) =>
            log.error(
              s"MesosApiClient failed to retrieve state summary, failure was: $e")
            replyTo ! Failure(e)

        }
      },
      replyTo
    )
  }

  private def getScheduler(serviceInstanceId: String,
                                f: (Option[DCOSCommon.Scheduler] => _),
                                replyTo: ActorRef) = {

    withDCOSProxy(
      (p: ActorRef) => {
        log.debug("Getting scheduler app state via MarathonApiClient")
        (p ? DCOSProxy.Forward(
          DCOSProxy.Target.MARATHON,
          MarathonApiClient.GetApp(serviceInstanceId))) onComplete {
          case Success(Success(app: MarathonApiClient.APIModel.App)) =>
            f(Some(DCOSCommon.Scheduler(app.env, app.labels)))
          case Success(Failure(_: MarathonApiClient.AppNotFound)) =>
            f(None)

          case Success(Failure(e: Throwable)) =>
            log.error(
              s"MarathonApiClient failed to retrieve app, failure was: $e")
            replyTo ! Failure(e)

          case Failure(e: Throwable) =>
            log.error(
              s"MarathonApiClient failed to retrieve app, failure was: $e")
            replyTo ! Failure(e)

        }

      },
      replyTo
    )

  }

  private def withDCOSProxy(f: (ActorRef => _), replyTo: ActorRef) = {
    dcosProxy match {
      case Some(p: ActorRef) => f(p)
      case None =>
        replyTo ! Failure(
          DCOSProxy.ClusterUnavailable(
            "No DC/OS Proxy was available to serve this request",
            None,
            None))
    }
  }

  private def withActorConfiguration(f: (ServiceModule.ActorConfiguration) => _, replyTo: ActorRef) = {
    try {
      configured((actorConfiguration: ServiceModule.ActorConfiguration) => { f(actorConfiguration) })
    } catch {
      case e: Throwable => replyTo ! Failure(e)
    }
  }

  private def withServiceModuleConfiguration(
      serviceId: String,
      f: (ServiceModuleConfiguration[_, _, _, _, _] => _),
      replyTo: ActorRef) = {
    getConfiguration(serviceId) match {
      case Success(configuration) =>
        f(configuration)
      case Failure(e: Throwable) => replyTo ! Failure(e)
    }

  }

}
