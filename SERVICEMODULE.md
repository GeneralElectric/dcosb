# Service Module developer guide

## Document overview

This document is for developers who wish to create implementation/s of ( io.predix.dcosb:service-module-api ) `ServiceModule` to
provide an Open Service Broker API compatible, restful HTTP service that manages the lifecycle of one or more DC/OS Package/s.

Seeing how the Open Service Broker API spec is a mature standard, and the DC/OS Lifecycle management APIs are becoming the same,
the package-specific gaps between these can be bridged with a lightweight, plugin-like structure and some configuration.


### Pre-requisites

- Basic understanding of Scala
- Access to a DC/OS cluster ( a principal with privileges to deploy packages )
- Private key of the above principal in PKCS8 format

This and many referenced projects below use `sbt` to build jars and docker images.
When necessary, http requests are sent from the command line with [httpie](http://httpie.org)

## DC/OS Open Service Broker

![Architecture](/docs/architecture.png)

## Service Module overview

A `ServiceModule` is an abstraction of a DC/OS Package's lifecycle management, towards the built-in Open Service Broker web service ( io.predix.dcosb:service-broker ) `OpenServiceBrokerAPI`

The `dcosb` toolkit as a whole, when used to create a `ServiceModule` implementation, provides:
1) End-to-end processing of Open Service Broker API requests and ( when appropriate ) translating them to an invocation of the `ServiceModule` implementation's
relevant lifecycle management method
2) Inspecting return values from `ServiceModule` implementations' lifecycle management methods and ( when appropriate ) 
taking relevant DC/OS Package lifecycle management actions
3) Providing information on the current state ( if any ) of the package instance in DC/OS being created, updated, deleted or un/bound from/to, by querying various
DC/OS Lifecycle management and other DC/OS and Mesos API endpoints automatically, and making this information available to the `ServiceModule` implementations' lifecycle
management methods

read more under _Value added features_ below

### A Service Module is a plug-in..

..that is dynamically loaded at start-up. **There is no limit on how many `ServiceModule` implementations you can package together or how many of the same `ServiceModule` implementation
you can register under different "service" keys** ( typically you would do this to configure the same implementation in different ways ). Discovery of `ServiceModule` implementations happens via `ServiceLoader`, which in turn relies on the Typesafe Configuration library key
`dcosb.services` to contain the below map of minimal implementation configuration ( currently only the fully qualified class name is required )

An example of such configuration would be :

```hocon
dcosb {
  services: {
    cassandra {
      implementation: "io.predix.dcosb.servicemodule.CassandraPXServiceModule"
    }
  }
}
```

This configuration file, in order to be accessible to Typesafe Configuration's load() method should be named `application.conf`
and made available/packaged on the implementation jar's class path

### Service Module Configuration

In order to ease the burden of carrying Open Service Broker service, catalog, and DC/OS Package information in the
`ServiceModule` implementation itself, we defined another type that encapsulates typical configuration that is
 expected ( but not required ) to be read off some persistent store at start-up. The `ServiceModule` implementation
 is required to provide an instance of this type in `getConfiguration(..)`, and the `BasicServiceModule` mix-in is what
 provides Typesafe Configuration integration to object-map values in global configuration to an instance of `CommonServiceConfiguration`
 ( which is a non-abstract class that implements `ServiceModuleConfiguration` fully)
 
If you have no need for highly specialized `ServiceModule` configuration, it is recommended you make use of `CommonServiceConfiguration`
and the `BasicServiceModule` mix-in's `loadFromTypeSafeConfig`. If you do, just drop your `CommonServiceConfiguration`'s HSON representation
([for an example, see dcosb-cassandra's application.conf](https://github.build.ge.com/dcosb/dcosb-cassandra/blob/master/src/main/resources/application.conf))
on the classpath of your build artifact, or any other filesystem location configured with `-Dconfig.file=path/to/config-file`

Below is a `getConfiguration` on a `ServiceModule` implementation that uses `loadFromTypeSafeConfig` to read a `ServiceModuleConfiguration`
compatible object off disk. Note that type parameters to `ServiceModuleConfiguration` ( `DCOSModel._` and `OSBModel._` ) 
and json parsers ( `..ParametersReader`, `..ParametersWriter`, ..etc ) still need to be provided.

```scala
override def getConfiguration(serviceId: String) = {

import io.predix.dcosb.servicemodule.CassandraPXServiceModuleConfiguration._
    // ^ for OSBModel and DCOSModel
    
    
    loadFromTypeSafeConfig[OSBModel.ProvisionInstanceParameters,
                           OSBModel.UpdateInstanceParameters,
                           OSBModel.BindParameters,
                           OSBModel.BindResponse,
                           DCOSModel.PackageOptions](
      s"dcosb.services.$serviceId",
      provisionInstanceParametersReader,
      updateInstanceParametersReader,
      bindParametersReader,
      bindResponseWriter,
      packageOptionsWriter,
      packageOptionsReader
    )
}
```

### Service Module API guide

#### Before you begin

##### DC/OS principal and required permissions

you will need to provision a DC/OS principal and configure it's private key in `ServiceModuleConfiguration.dcosService.connection.privateKey` 
When using the `BasicServiceModule` mix-in, this is read from the `dcosb.services.$serviceId.dcos.connection.private-key` Typesafe Configuration key

#### Indicating errors via `Throwable`s

As Open Service Broker Request processing in the toolkit is 100% asynchronous, all API methods are expected to wrap
their responses in a `Future`. A failed `Future` is also the delivery vehicle for communicating failures to the Request.

Any `Throwable`s in a failed `Future`, other than those listed below will be considered/logged a `ServiceModule` implementation failure.

```scala
trait OperationDenied extends Throwable

case class InsufficientApplicationPermissions(message: String) extends OperationDenied
// ^ may map to 401 or similar response code in OpenServiceBrokerAPI
case class MalformedRequest(message: String) extends OperationDenied
// ^ may map to 302 or similar response code in OpenServiceBrokerAPI
case class ServiceInstanceNotFound(serviceInstanceId: String) extends OperationDenied
// ^ may map to 404 or similar response code in OpenServiceBrokerAPI
```

#### createServiceInstance() - Provision service instance

##### Signature

```scala
def createServiceInstance(organizationGuid: String,
    plan: ServiceModuleConfiguration.OpenServiceBrokerApi.ServicePlan,
    serviceId: String,
    spaceGuid: String,
    serviceInstanceId: String,
    parameters: Option[T] forSome {
      type T <: ProvisionInstanceParameters
    } = None): Future[_ <: PackageOptions]

```

##### OSB Http Request example

```bash
http --verify=no -a apiuser:YYz3aN-kmw \
    PUT https://localhost:8080/dcosb/cassandra/broker/v2/service_instances/dcosb-cassandra-1 \
    parameters:='{"nodes":1,"cluster_name":"dcosb-cassandra-1"}' \
    organization_guid=SomeORG \
    plan_id=basic \
    service_id=SomeServiceGUID \
    space_guid=SomeSpaceGUID
```

##### _Parameter_ ( _example value in above request_ ) _guide_

_plan_ ( `ServicePlan(id = basic)` ) the `ServicePlan` object retrieved from your `ServiceModuleConfiguration` - if the incoming plan_id on the request matched any

_serviceId_ ( `SomeServiceGUID` ) the Open Service Broker endpoint's service GUID

_spaceGuid_ ( `SomeSpaceGUID` ) the GUID of the space that is making the request

_serviceInstanceId_ ( `dcosb-cassandra-1` ) the UUID of the service instance being provisioned

_parameters_ ( `MyProvisionInstanceParams(nodes = 1, cluster_name = "dcosb-cassandra-1")` ) value of the parameters object passed along with the provision request

##### Response type

The DC/OS Package options object as defined by your `ServiceModule` implementation

##### ..Before it hits your method

The HTTP request is validated, plan_id resolved to `ServicePlan`, parameters object parsed with your `ServiceModuleConfiguration`'s 
`openService.provisionInstanceParametersReader`

##### ..After your Future completes

A `PackageOptions` object when received in a successful `Future` will be serialized with your `ServiceModuleConfiguration`'s `dcosService.pkgOptionsWriter`

This `PackageOptions` object will then be sent to the `CosmosAPIClient` to perform the deployment. Depending on the response
from this actor, an HTTP response to the OSB Request may be formulated to indicate that a package exists, the cosmos request failed for some other reason
or the deployment is now in-progress ( 201 Created )

A Throwable in a failed Future will be transformed to a non-200 response as described in "Indicating errors via `Throwable`s" above.

#### updateServiceInstance() - Update plan or parameters on provisioned service instance

##### Signature

```scala
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
```

##### OSB Http Request example

```bash
http --verify=no -a apiuser:YYz3aN-kmw \
    PATCH https://localhost:8080/dcosb/cassandra/broker/v2/service_instances/dcosb-cassandra-1 \
    service_id=SomeServiceGUID \
    parameters:='{"nodes":3}'
```

##### _Parameter_ ( _example value in above request_ ) _guide_

_serviceId_ ( `SomeServiceGUID` ) the Open Service Broker endpoint's service GUID

_serviceInstanceId_ ( `dcosb-cassandra-1` ) the UUID of the service instance being updated

_plan_ ( `None` ) the `Option[ServicePlan]` object retrieved from your `ServiceModuleConfiguration` - if the incoming plan_id on the request matched any

_previousValues_ ( `None` ) a container object optionally sent by the platform, with the previous plan id ( if it's being updated )

_parameters_ ( `MyUpdateInstanceParams(nodes = 3)` ) value of the parameters object passed along with the update request

_endpoints_ See "Accessing DC/OS Package Instance" below

_scheduler_ See "Accessing DC/OS Package Instance" below

##### Response type

The DC/OS Package options object as defined by your `ServiceModule` implementation

##### ..Before it hits your method

The HTTP request is validated, plan_id ( if present in the update and plan-update allowed in configuration ) resolved to `ServicePlan`, parameters object parsed with your `ServiceModuleConfiguration`'s 
`openService.updateInstanceParametersReader`

Some DC/OS and Mesos APIs are consulted to discover existing tasks and scheduler configuration in the cluster.

##### ..After your Future completes

A `PackageOptions` object when received in a successful `Future` will be serialized with your `ServiceModuleConfiguration`'s `dcosService.pkgOptionsWriter`

This `PackageOptions` object will then be sent to the `CosmosAPIClient` to perform the update. Depending on the response
from this actor, an HTTP response to the OSB Request may be formulated to indicate either that the cosmos request failed for some reason
or the deployment is now in-progress ( 201 Created )

A Throwable in a failed Future will be transformed to a non-200 response as described in "Indicating errors via `Throwable`s" above.

#### bindApplicationToServiceInstance() - Create a binding between an OSB application id and a provisioned service instance

#### unbindApplicationFromServiceInstance() - Remove a binding between an OSB application id and a provisioned service instance

#### destroyServiceInstance() - Destroy a provisioned service instance

#### lastOperation() - Provide an update on an asynchronous operation

### Value added features

As discussed above, `dcosb` brings more to a `ServiceModule` implementation, than just transforming requests between the Open Service Broker
and DC/OS Package management APIs.

#### Open Service Broker request validation

The toolkit's aim is to prevent invalid requests from ever hitting your `ServiceModule` implementation

##### Plan id

When a plan id may be present in an OSB request, dcosb will verify it against plans in your `ServiceModuleConfiguration`

##### Plan update

If you mark a plan as non-updateable in your `ServiceModuleConfiguration`, requests to update the plan on a
service instance provisioned under the non-updateable plan, will be turned away with the appropriate error message

##### Parsing payload

In cases where there may be JSON-encoded information present in the body of an OSB request, dcosb will automatically invoke
the appropiate reader object set on your `ServiceModuleConfiguration`

##### Encoding response payload

In cases where there may be JSON-encoded information present in the body of an OSB response, dcosb will automatically invoke
the appropiate writer object set on your `ServiceModuleConfiguration`

#### Accessing DC/OS Package Instance ( "DC/OS Service" below ) state

For update, un/bind, destroy and last operation type requests, you need to have access to basic DC/OS Service
information to provide a meaningful response.

##### Scheduler state

Every DC/OS Service will have a framework scheduler running in Marathon to orchestrate resource management. This
scheduler will have Service configuration and other important meta-data available on it's _environment_ and _label_ maps
(String -> String) With every `updateServiceInstance()`, `bindApplicationToServiceInstance()`,
`unbindApplicationFromServiceInstance()`, `destroyServiceInstance()` and `lastOperation()` invocation, you are passed
a `scheduler:Option[DCOSCommon.Scheduler]` parameter

```scala
// Straightforward enough..
case class Scheduler(
    envvars: Map[String, String],
    labels: Map[String, String])
```

The parameter is an _Optional_ because there may be no scheduler running with your `serviceInstanceId` - this in itself
may be relevant information to your response

##### Discovery information ( _Endpoint_ )

Mesos frameworks running in DC/OS Services publish discovery information on tasks they are running. Usually these are
a combination of "task name + task type + port number" per task. With every `updateServiceInstance()`, `bindApplicationToServiceInstance()`,
 `unbindApplicationFromServiceInstance()`, `destroyServiceInstance()` and `lastOperation()` invocation, you are passed
 a `endpoints:List[Endpoint]` parameter. An `Endpoint` is a named list of `Port`s, a `Port` is an (optionally) named `InetSocketAddress`
 
```scala
case class Endpoint(
    name: String,
    ports: List[Port] = List.empty)

case class Port(
    name: Option[String] = None,
    address: InetSocketAddress)

```

##### Deploy plan

For `dcos-commons` ( also referred to as `DC/OS SDK` ) based packages, most Service API actions that alter the deployment in some way, or transitions in the package lifecycle
should be encapsulated in `Plan` objects. `Plan` objects in turn have `Phases` and `Phases` have `Step`s.

For a `lastOperation()` invocation you are passed a `deployPlan: Try[PlanApiClient.ApiModel.Plan]` parameter

```scala
case class Plan(
  status: String,
  errors: Seq[String],
  phases: Seq[Phase])
  
case class Phase(
  id: String,
  name: String,
  status: String,
  steps: Seq[Step])
  
case class Step(
  id: String,
  name: String,
  status: String,
  message: String)
```

It is mostly service-specific how information is encoded in the above structure, but the use of a Plan named "deploy", for 
initial deployment, configuration update and service teardown, has been found to be common across `dcos-commons` packages.

With these service-specific differences in mind, we provide a helper method on `ServiceModule` to easily turn a "deploy"
`Plan`, a `DCOSCommon.Scheduler` object and an `OSB.Operation` enum value in to a `LastOperationStatus`, which is what
`lastOperation()` is expected to return a `Future` of

```scala
def operationStatusFrom(
    serviceInstanceId: String,
    operation: OSB.Operation.Value,
    scheduler: Option[DCOSCommon.Scheduler],
    deployPlan: Try[ApiModel.Plan],
    inProgressStates: List[String] = List("WAITING", "PENDING", "STARTING", "IN_PROGRESS"),
    msgDestroyPending:(Seq[ApiModel.Phase] => String),
    msgCreateUpdatePending:(Seq[ApiModel.Phase] => String),
    msgCreateUpdateComplete:(Seq[ApiModel.Phase] => String)): Option[LastOperationStatus]
```

Since in this helper, we are only interested in the status of the deploy `Plan`, recovering any further
progress information is delegated to the `Seq[Phase] => String` lambdas set in the `msg..` parameters

Find a watered-down example of how the cassandra service module uses this helper to shorten it's `lastOperation`
implementation to a few lines:

```scala
override def lastOperation(
    serviceId: Option[String],
    plan: Option[OpenServiceBrokerApi.ServicePlan],
    serviceInstanceId: String,
    operation: OSB.Operation.Value,
    endpoints: List[ServiceModule.Endpoint],
    scheduler: Option[DCOSCommon.Scheduler],
    deployPlan: Try[ApiModel.Plan]): Future[LastOperationStatus] = {
    
    operationStatusFrom(
        serviceInstanceId,
        operation,
        scheduler,
        deployPlan,
        msgDestroyPending = { phases: Seq[ApiModel.Phase] => s"Resources pending clean up: ${PlanProcessors.countIncompleteSteps(phases, "resource-phase")} of ${PlanProcessors.countSteps(phases, "resource-phase")}, de-registration: ${PlanProcessors.stepMessages(phases, "deregister-phase")
        .mkString(", ")}" },
        msgCreateUpdatePending = { phases: Seq[ApiModel.Phase] => PlanProcessors.stepMessages(phases, "node-deploy").mkString(", ") },
        msgCreateUpdateComplete = { phases: Seq[ApiModel.Phase] => PlanProcessors.stepMessages(phases, "node-deploy").mkString(", ") }) match {
    
        case Some(status) => processOperationStatus(
            serviceInstanceId,
            plan,
            status)
            
        case None =>
            // unable to determine operation state
    
    }

}

/**
* Do status processing, i.e. billing, logging, etc..
* MUST be idempotent
* @param status
* @return
*/
def processOperationStatus(serviceInstanceId: String, plan: Option[OpenServiceBrokerApi.ServicePlan], status: LastOperationStatus): Future[LastOperationStatus] = {

  Future.successful(status)

}
```

#### Misc

##### Accessing `ServiceModuleConfiguration`, `ActorConfiguration`




### Open Service Broker

The main consumer of `ServiceModule` implementations ( by way of `ServiceLoader` ) is the `OpenServiceBrokerAPI` actor that provides
functionality to create an `akka-http` `Route` for any known implementation. This `Route` will then interact with the implementation
via the message handling and other convenience methods available on the `ServiceModule` abstract class.

![For example, this is a provision request flow](/docs/flow-create-instance.png)

In summary, the akka-http `Route` created by `OpenServiceBrokerAPI` for any single `ServiceModule` implementation will:
- Authenticate and validate OSB API Http requests
- When the spec allows a payload to be present in an OSB request or response, it invokes the `ServiceModuleConfiguration`'s appropriate
parser/s for processing of said payload
- Invokes the `ServiceModule` implementation's lifecycle management methods via the message handling functionality provided by the `ServiceModule` abstract class
- Depending on the response from the `ServiceModule` implementation, returns a result to the OSB request
- All the above in a non-blocking manner based on Akka messaging patterns

`OpenServiceBrokerAPI` does not currently provide a 100% OSB 2.12 compatible API. It is our intention to get to 100% with
certain notable exceptions ( for example synchronous operations are unlikely to be ever supported )
