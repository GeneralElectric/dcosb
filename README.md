*DC/OS Open Service Broker* is a toolkit that enables the quick and painless integration of (dcos-commons powered) DC/OS
Services in to CloudFoundry, or any other OSB implementing platform, by providing a high level `ServiceModule` API, that allows for the service specific bits -mostly around transforming OSB configuration types to DC/OS package options and service intialization/teardown- to reside in a well isolated, plugin-like implementation class.


No knowledge of the Open Service Broker spec or the DC/OS Service lifecycle management
APIs is necessary - simply fill in the service specific gaps by extending the `ServiceModule` abstract class, drop credentials and
 other configuration, fire up the main class from `io.predix.dcosb:service-broker`,
and you are good to go!

## Implementing and building a ServiceModule

`ServiceModule` implementations represent DC/OS Services towards the toolkit. For
an example, see the `service-module-cassandra` module.

The tl;dr is

1. extend the `ServiceModule` abstract class from `service-module-api`
2. add `io.predix.dcosb:service-broker` as a run & build-time dependency
3. optionally, add `io.predix.dcosb:cli` as a run & build-time dependency
4. review configuration options in `service-broker/src/main/resources/reference.conf` - drop any overrides at the root of your classpath in to `application.conf`, or any other location specified
by `-Dconfig.file` You may also override individual configuration parameters this way, see [the typsafe config documentation for details](https://github.com/typesafehub/config#standard-behavior)
5. for a HOCON representation of the minimally required ServiceModuleConfiguration, see `service-broker-api/src/test/resources/reference.conf` A helper to parse this structure in to a ServiceModuleConfiguration
object is available by mixing in `BasicServiceModule` See `service-module-cassandra` for an example.
6. start `sbt "runMain io.predix.dcosb.servicebroker.Daemon"` for the Open Service Broker. By default, the Service Broker will listen for requests on `0.0.0.0:8080`
7. run `sbt "runMain io.predix.dcosb.cli.DCOSBCli --help"` to get the CLI help, if you added `cli` as a dependency

### Open Service Broker API v2.12 compatibility

The `DC/OS Open Service Broker` intends to eventually fully implement an Open Service Broker API version 2.12
compatible RESTful web service. However, we anticipate this to be achieved over several releases. Please find a
matrix describing API compatibility in this release

<table>
<tr>
    <th>OSB API Feature</th>
    <th>Supported?</th>
    <th>ServiceModule API</th>
    <th>Notes</th>
</tr>
<tr>
	<td><a href="https://github.com/openservicebrokerapi/servicebroker/blob/v2.12/spec.md#catalog-management">Catalog management</a></td>
	<td colspan="3">The catalog response is completely served up from the ServiceModuleConfiguration object and involves no interaction with the ServiceModule implememntation</td>
</tr>
<tr>
    <td><a href="https://github.com/openservicebrokerapi/servicebroker/blob/v2.12/spec.md#provisioning">Provisioning</a></td>
    <td colspan="3">In summary, the Provisioning action is mostly supported. The context object parameter is not yet supported and synchronous processing may not be supported in the near future</td>
</tr>
<tr>
	<td><small>uri fragment</small><pre>/v2/service_instances/<strong>instance_id</strong></pre></td>
	<td>YES</td>
<td><pre>def createServiceInstance(...
    <strong>serviceInstanceId: String</strong>,
    ...): Future[_ <: PackageOptions]</pre></td>
                            <td></td>
</tr>
<tr>
	<td>Asynchronous processing<small><br />( query parameter "accepts_incomplete" = true )</small></td>
	<td>YES</td>
	<td></td>
	<td>the value of the returned operation field is "create"</td>
</tr>
<tr>
	<td>Synchronous processing<small><br />( query parameter "accepts_incomplete" = false, or absent )</small></td>
	<td>NO</td>
	<td></td>
	<td>The Service Broker will return 422 Unprocessable Entity per the spec</td>
</tr>
<tr>
	<td><small>request object field<br /></small>"service_id"</td>
	<td>YES</td>
<td><pre>def createServiceInstance(...
    <strong>serviceId: String</strong>,
    ...): Future[_ <: PackageOptions]</pre></td>
                            <td></td>
</tr>
<tr>
	<td><small>request object field<br /></small>"plan_id"</td>
	<td>YES</td>
<td><pre>def createServiceInstance(...
    <strong>planId: String</strong>,
    ...): Future[_ <: PackageOptions]</pre></td>
                            <td></td>	
</tr>
<tr>
	<td><small>request object field<br /></small>"context"</td>
	<td>NO</td>
	<td></td>
	<td></td>
</tr>
<tr>
	<td><small>request object field<br /></small>"organization_guid"</td>
	<td>YES</td>
<td><pre>def createServiceInstance(...
    <strong>organizationGuid: String</strong>,
    ...): Future[_ <: PackageOptions]</pre></td>
                            <td></td>
</tr>
<tr>
	<td><small>request object field<br /></small>"space_guid"</td>
	<td>YES</td>
<td><pre>def createServiceInstance(...
    <strong>spaceGuid: String</strong>,
    ...): Future[_ <: PackageOptions]</pre></td>
                            <td></td>
</tr>
<tr>
	<td><small>request object field<br /></small>"parameters"</td>
	<td>YES</td>
<td><pre>def createServiceInstance(...
    <strong>parameters: Option[T] forSome {
      type T <: InstanceParameters
    } = None</strong>,
    ...): Future[_ <: PackageOptions]</pre></td>	<td>To implement support for receiving arbitrary parameters at provisioning time ("instance parameters") you need to:
                            <ol>
                            <li>Create type T to represent these parameters as a single object, that extends InstanceParameters</li>
                            <li>Create a function to parse the JSON representation of the parameters object into an instance of T, and set this on the ServiceModuleConfiguration object<pre>(Option[JsValue] => Try[T])</pre></li>
                            <li>Implement the createServiceInstance method on your ServiceModule implementation ( see left )</li>
                            </ol></td>
</tr>
<tr>
	<td><a href="https://github.com/openservicebrokerapi/servicebroker/blob/v2.12/spec.md#updating-a-service-instance">Updating a Service Instance</a></td>
	<td colspan="3">In summary, updating a service instance is supported on the most basic level. We do not currently allow for an arbitrary parameters object to be sent with the request ( this will require yet another type + reader combo ) nor do we support the similar context object. Additionally, only asynchronous processing is supported</td>
</tr>
<tr>
	<td><small>uri fragment</small><pre>/v2/service_instances/<strong>instance_id</strong></pre></td>
	<td>YES</td>
<td><pre>def updateServiceInstance(...
    <strong>serviceInstanceId: String</strong>,
    ...): Future[_ <: PackageOptions]</pre></td>
                            <td></td>
</tr>
<tr>
	<td>Asynchronous processing<small><br />( query parameter "accepts_incomplete" = true )</small></td>
	<td>YES</td>
	<td></td>
	<td>the value of the returned operation field is "update"</td>
</tr>
<tr>
	<td>Synchronous processing<small><br />( query parameter "accepts_incomplete" = false, or absent )</small></td>
	<td>NO</td>
	<td></td>
	<td>The Service Broker will return 422 Unprocessable Entity per the spec</td>
</tr>
<tr>
	<td><small>request object field<br /></small>"context"</td>
	<td>NO</td>
	<td></td>
	<td></td>
</tr>
<tr>
	<td><small>request object field<br /></small>"service_id"</td>
	<td>YES</td>
<td><pre>def updateServiceInstance(...
    <strong>serviceId: String</strong>,
    ...): Future[_ <: PackageOptions]</pre></td>
                            <td></td>
</tr>
<tr>
	<td><small>request object field<br /></small>"plan_id"</td>
	<td>YES</td>
<td><pre>def updateServiceInstance(...
    <strong>planId: Option[String]</strong>,
    ...): Future[_ <: PackageOptions]</pre></td>
                            <td></td>
</tr>
<tr>
	<td><small>request object field<br /></small>"parameters"</td>
	<td>NO</td>
	<td></td>
   <td></td>
</tr>
<tr>
	<td><small>request object field<br /></small>"previous_values"</td>
	<td>PARTIAL</td>
<td><pre>def updateServiceInstance(...
    <strong>previousValues: Option[OSB.PreviousValues]</strong>,
    ...): Future[_ <: PackageOptions]</pre></td>
   <td>On the previous_values object we only support the single non-deprecated field: "plan_id"<pre>case class PreviousValues(planId: String)</pre></td>
</tr>
<tr>
	<td><a href="https://github.com/openservicebrokerapi/servicebroker/blob/v2.12/spec.md#binding">Binding</a></td>
	<td colspan="3">In summary, the binding operation is mostly supported. There is no support for the parameters object yet and currently only the app_guid field on the bind_response object will be parsed</td>
</tr>
<tr>
	<td><small>uri fragment</small><pre>/v2/service_instances/<strong>instance_id</strong>/service_bindings/binding_id</pre></td>
	<td>YES</td>
<td><pre>def bindApplicationToServiceInstance(...
    <strong>serviceInstanceId: String</strong>,
    ...): Future[_ <: BindResponse]</pre></td>
                            <td></td>
</tr>
<tr>
	<td><small>uri fragment</small><pre>/v2/service_instances/instance_id/service_bindings/<strong>binding_id<strong></pre></td>
	<td>YES</td>
<td><pre>def bindApplicationToServiceInstance(...
    <strong>bindingId: String</strong>,
    ...): Future[_ <: BindResponse]</pre></td>
                            <td></td>
</tr>
<tr>
	<td><small>request object field<br /></small>service_id</td>
	<td>YES</td>
<td><pre>def bindApplicationToServiceInstance(...
    <strong>serviceId: String</strong>,
    ...): Future[_ <: BindResponse]</pre></td>
<td></td>
</tr>
<tr>
	<td><small>request object field<br /></small>plan_id</td>
	<td>YES</td>
<td><pre>def bindApplicationToServiceInstance(...
    <strong>planId: String</strong>,
    ...): Future[_ <: BindResponse]</pre></td>
<td></td>
</tr>
<tr>
	<td><small><strong>deprecated</strong> request object field<br /></small>app_guid</td>
	<td>NO</td>
<td></td>
<td>See bind_resource below</td>
</tr>
<tr>
	<td><small>request object field<br /></small>bind_resource</td>
	<td>PARTIAL</td>
<td><pre>def bindApplicationToServiceInstance(...
    <strong>bindResource: Option[OSB.BindResource]</strong>,
    ...): Future[_ <: BindResponse]</pre></td>
<td>The BindResource object supports only the app_guid field currently: <pre>case class BindResource(appGuid: String)</pre></td>
</tr>
<tr>
	<td><small>request object field<br /></small>parameters</td>
	<td>NO</td>
<td></td>
<td></td>
</tr>
<tr>
	<td>returned binding value (type)</td>
	<td>YES*</td>
<td><pre>trait BindResponse {
	def credentials: Option[Any]
	}</pre></td>
<td>*Currently, only an optional credentials field is enforced on the returned value. This may change if we decide to implement support for proxy or log consuming applications. In the mean time, to return an abitrary binding response object, you need to:
	<ol>
		<li>Create an implementation of BindResponse</li>
		<li>Create a function to write the BindResponse in to it's JSON representation, and set this on the ServiceModuleConfiguration object<pre>(B => JsValue)</pre> where B is your BindResponse implementation</li>
		<li>Implement the bindApplicationToServiceInstance method on your ServiceModule implementation ( see left )</li>
	</ol>
</td>

</tr>

<tr>
	<td><a href="https://github.com/openservicebrokerapi/servicebroker/blob/v2.12/spec.md#unbinding">Unbinding</a></td>
	<td colspan="3"></td>
</tr>
<tr>
	<td><small>uri fragment</small><pre>/v2/service_instances/<strong>instance_id</strong>/service_bindings/binding_id</pre></td>
	<td>YES</td>
<td><pre>def unbindApplicationFromServiceInstance(...
    <strong>serviceInstanceId: String</strong>,
    ...): Future[_ <: ApplicationUnboundFromServiceInstance]</pre></td>
                            <td></td>
</tr>
<tr>
	<td><small>uri fragment</small><pre>/v2/service_instances/instance_id/service_bindings/<strong>binding_id<strong></pre></td>
	<td>YES</td>
<td><pre>def unbindApplicationFromServiceInstance(...
    <strong>bindingId: String</strong>,
    ...): Future[_ <: ApplicationUnboundFromServiceInstance]</pre></td>
                            <td></td>
</tr>
<tr>
	<td><small>request object field<br /></small>service_id</td>
	<td>YES</td>
<td><pre>def unbindApplicationFromServiceInstance(...
    <strong>serviceId: String</strong>,
    ...): Future[_ <: ApplicationUnboundFromServiceInstance]</pre></td>
<td></td>
</tr>
<tr>
	<td><small>request object field<br /></small>plan_id</td>
	<td>YES</td>
<td><pre>def unbindApplicationFromServiceInstance(...
    <strong>planId: String</strong>,
    ...): Future[_ <: ApplicationUnboundFromServiceInstance]</pre></td>
<td></td>
</tr>


<tr>
	<td><a href="https://github.com/openservicebrokerapi/servicebroker/blob/v2.12/spec.md#deprovisioning">Deprovisioning</a></td>
	<td colspan="3"></td>
</tr>
<tr>
	<td>Asynchronous processing<small><br />( query parameter "accepts_incomplete" = true )</small></td>
	<td>YES</td>
	<td></td>
	<td>the value of the returned operation field is "destroy"</td>
</tr>
<tr>
	<td>Synchronous processing<small><br />( query parameter "accepts_incomplete" = false, or absent )</small></td>
	<td>NO</td>
	<td></td>
	<td>The Service Broker will return 422 Unprocessable Entity per the spec</td>
</tr>
<tr>
	<td><small>uri fragment</small><pre>/v2/service_instances/<strong>instance_id</strong></pre></td>
	<td>YES</td>
<td><pre>def destroyServiceInstance(...
    <strong>serviceInstanceId: String</strong>,
    ...): Future[ServiceInstanceDestroyed]</pre></td>
                            <td></td>
</tr>
<tr>
	<td><small>request object field<br /></small>service_id</td>
	<td>YES</td>
<td><pre>def destroyServiceInstance(...
    <strong>serviceId: String</strong>,
    ...): Future[ServiceInstanceDestroyed]</pre></td>
<td></td>
</tr>
<tr>
	<td><small>request object field<br /></small>plan_id</td>
	<td>YES</td>
<td><pre>def destroyServiceInstance(...
    <strong>planId: String</strong>,
    ...): Future[ServiceInstanceDestroyed]</pre></td>
<td></td>
</tr>

<tr>
	<td><a href="https://github.com/openservicebrokerapi/servicebroker/blob/v2.12/spec.md#polling-last-operation">Last Operation</a></td>
	<td colspan="3"></td>
</tr>
<tr>
	<td><small>uri fragment</small><pre>/v2/service_instances/<strong>instance_id</strong></pre></td>
	<td>YES</td>
<td><pre>def lastOperation(...
    <strong>serviceInstanceId: String</strong>,
    ...): Future[LastOperationStatus]</pre></td>
                            <td></td>
</tr>
<tr>
	<td><small>request object field<br /></small>service_id</td>
	<td>YES</td>
<td><pre>def lastOperation(...
    <strong>serviceId: Optional[String]</strong>,
    ...): Future[LastOperationStatus]</pre></td>
<td></td>
</tr>
<tr>
	<td><small>request object field<br /></small>plan_id</td>
	<td>YES</td>
<td><pre>def lastOperation(...
    <strong>planId: Optional[String]</strong>,
    ...): Future[LastOperationStatus]</pre></td>
<td></td>
</tr>
<tr>
	<td><small>request object field<br /></small>operation</td>
	<td>YES</td>
<td><pre>def lastOperation(...
    <strong>operation: OSB.Operation.Value</strong>,
    ...): Future[LastOperationStatus]</pre></td>
<td></td>
</tr>
</table>

### Utilities provided to ServiceModule implementations

The features of the toolkit don't stop at simply translating HTTP API calls to the Open Service Broker in to invocations of your ServiceModule API methods.

#### Contextual information

On many of the abstract Service Module methods that you are expected to implement for a fully functional Service Broker, you'll find parameters that are not
parsed out of the incoming request, but help you communicate with the DC/OS Service Instance for which the request is being made. 

##### Endpoint

An Endpoint is discoverability information per service port that is registered by a single task of the DC/OS Service, on a single slave.

    /**
    * If there are service discovery mechanisms available in the
    * DC/OS cluster, the application endpoints / service endpoints of
    * the service instance will be presented to the API methods whenever
    * possible via this type
    */
    case class Endpoint(name: String,
                        ports: List[Port] = List.empty)
                        
    case class Port(name: Option[String] = None,
                    address: InetSocketAddress)
      
Receiving this with every invocation of the API ( except for provisioning of course ) will help you carry out your service specific lifecycle actions
like creating a user account at binding time, cleaning up at unbinding, etc.

##### PlanApiClient

There is an Actor, accessible via sending a `Forward` message to `dcosProxy` that can
pull named plans from a `dcos-commons` compatible service API. For this you'll require
a package name ( available on your `ServiceModuleConfiguration` ) and a Service Instance ID ( available on every method )

Plans give you insight in to any change operation ( create/update/delete ) you perform on a DC/OS service.

You could use this in your `lastOperation` implementation to discover the progress of an asynchronous operation ( `createServiceInstance`, `updateServiceInstance` and `destroyServiceInstance` ), return a status update to the platform and take any additional action necesarry ( update billing, etc )

##### HttpClient

There is a helper method available via `ServiceModule` to send HttpRequests *to the DC/OS admin proxy*. Typically you will use this to communicate with your Service API to perform service specific tasks. The signature of the helper method is:

    def `sendRequest and handle response`[T](request: HttpRequest,
                                           handling: Try[HttpResponse] => T)
                                           
For examples how this method is used to send requests and process responses, take a look at `PlanApiConsumer`
                                           
The `HttpClientActor` trait that contributes method is undergoing some changes, and more helpers should be available soon.

##### Retrieving Actor and ServiceModule Configuration for your ServiceModule

Should you ever need them, actor and service module configuration instances are available via the two below helper methods on `ServiceModule`

     def withServiceModuleConfigurationOrFailPromise[T](
      serviceId: String,
      f: (ServiceModuleConfiguration[_, _, _] => _),
      promise: Promise[T])
 
     def withActorConfigurationOrFailPromise[T](
      f: (ServiceModule.ActorConfiguration => _),
      promise: Promise[T])

`f` is your code needing a configuration object



## Under the hood

### Actor Graph

![Actor Graph](/docs/actor-graph.png)

### ServiceModule loading

![ServiceModule loading](/docs/flow-service-module-loading.png)

### CreateInstance request flow

![CreateInstance request flow](/docs/flow-create-instance.png)

### DestroyInstance request flow

### Bind request flow

### Unbind request flow

## Appendices

### module overview

#### service-module-api
Provides the abstract classes and traits to implement a ServiceModule. ServiceModules represent DC/OS Services
we wish to expose to CloudFoundry (or any other OSB implementing platform), via the toolkit

#### service-module-cassandra
A very basic ServiceModule implementation to launch and manage Cassandra clusters

Check dcos connection information under `src/main/resources/application.conf`, then launch the Cassandra ServiceModule in a Service Broker by running
     
    sbt -Dakka.loglevel=DEBUG "project smCassandra" run
    
Once launched, you can (currently) send the following requests:
    
##### Provision a cluster

    http -a apiuser:YYz3aN-kmw PUT http://localhost:8080/dcosb/cassandra-example/broker/v2/service_instances/dcosb-cassandra-1 parameters:='{"nodes":3,"cluster_name":"dcosb-cassandra-1"}' organization_guid=SomeORG plan_id=developer service_id=SomeServiceGUID space_guid=SomeSpaceGUID
    HTTP/1.1 201 Created
    Content-Length: 22
    Content-Type: application/json
    Date: Thu, 24 Aug 2017 03:55:25 GMT
    Server: akka-http/10.0.5
    	
    {
        "operation": "create"
    }
    
##### Monitor the provision operation progress

    http -a apiuser:YYz3aN-kmw GET http://localhost:8080/dcosb/cassandra-example/broker/v2/service_instances/dcosb-cassandra-1/last_operation/?operation=create
    HTTP/1.1 200 OK
    Content-Length: 123
    Content-Type: application/json
    Date: Thu, 24 Aug 2017 03:56:32 GMT
    Server: akka-http/10.0.5
    
    {
        "description": "node-0:[server] -> STARTING, node-1:[server] -> PENDING, node-2:[server] -> PENDING",
        "state": "in progress"
    }

##### Create an application binding with the cluster instance

    http -a apiuser:YYz3aN-kmw PUT http://localhost:8080/dcosb/cassandra-example/broker/v2/service_instances/dcosb-cassandra-1/service_bindings/binding-1 service_id=SomeServiceGUID plan_id=developer bind_resource:='{"app_guid":"SomeAppGUID"}'
    HTTP/1.1 200 OK
    Content-Length: 259
    Content-Type: application/json
    Date: Thu, 24 Aug 2017 23:48:09 GMT
    Server: akka-http/10.0.5

    {
        "credentials": {
            "password": "cassandra",
            "username": "cassandra"
        },
        "nodes": [
            {
                "host": "node-2-server.dcosb-cassandra-1.mesos",
                "port": 24339
            },
            {
                "host": "node-1-server.dcosb-cassandra-1.mesos",
                "port": 24339
            },
            {
                "host": "node-0-server.dcosb-cassandra-1.mesos",
                "port": 24339
            }
        ]
    }

##### Update cluster instance

_coming soon_

##### Destroy an application binding with the cluster instance

    http -a apiuser:YYz3aN-kmw DELETE http://localhost:8080/dcosb/cassandra-example/broker/v2/service_instances/dcosb-cassandra-1/service_bindings/binding-1/?service_id=SomeServiceGUID\&plan_id=developer
    HTTP/1.1 200 OK
    Content-Length: 2
    Content-Type: application/json
    Date: Fri, 25 Aug 2017 20:46:21 GMT
    Server: akka-http/10.0.5

    {}

##### De-provision the cluster

    http -a apiuser:YYz3aN-kmw DELETE http://localhost:8080/dcosb/cassandra-example/broker/v2/service_instances/dcosb-cassandra-1/?service_id=service_guid\&plan_id=developer
    HTTP/1.1 202 Accepted
    Content-Length: 23
    Content-Type: application/json
    Date: Thu, 24 Aug 2017 03:59:47 GMT
    Server: akka-http/10.0.5
    
    {
        "operation": "destroy"
    }
    
##### Monitor the de-provision operation progress

    http -a apiuser:YYz3aN-kmw GET http://localhost:8080/dcosb/cassandra-example/broker/v2/service_instances/dcosb-cassandra-1/last_operation/?operation=destroy
    HTTP/1.1 200 OK
    Content-Length: 151
    Content-Type: application/json
    Date: Thu, 24 Aug 2017 03:59:53 GMT
    Server: akka-http/10.0.5
    
    {
        "description": "Scheduler for cluster with id dcosb-cassandra-1 is being re/started. Please re-try later for operation details.",
        "state": "in progress"
    }

#### dcos-utils
Actors that consume DC/OS authentication/authorization, service lifecycle and various other management APIs

#### mesos-utils
Actors that consume Mesos REST APIs for DC/OS Service monitoring and management

#### utils
Utility objects, classes, traits, shared all over

#### service-broker
Actors that provide a Open Service Broker API compatible HTTP RESTful service around ServiceModule implementations.
Also includes a main class to start the Service Broker in an embedded ( akka-http ) web server

#### metrics-collector
Reads the DC/OS Metrics API and forwards data points to the Predix Timeseries service

#### metrics-proxy
Provides a Grafana `simple-json-datasource` compatible HTTP RESTful service wrapping around the Predix Timeseries service. By default, deployed by `service-broker` via `dcos-utils`, as a Marathon application per ServiceModule instance 

#### cli
Convenience maintenance, debugging and monitoring actions for ServiceModule implementation instances
