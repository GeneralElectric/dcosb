package io.predix.dcosb.servicebroker

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorRefFactory, Props}
import akka.http.scaladsl.model.{
  FormData,
  HttpRequest,
  HttpResponse,
  StatusCodes
}
import akka.http.scaladsl.model.headers.{
  Authorization,
  BasicHttpCredentials,
  HttpChallenges
}
import akka.http.scaladsl.server.AuthenticationFailedRejection
import akka.testkit.{CallingThreadDispatcher, TestActorRef}
import akka.util.Timeout
import akka.pattern.ask
import io.predix.dcosb.dcos.{DCOSCommon, DCOSProxy}
import io.predix.dcosb.servicebroker.OpenServiceBrokerApi.APIModel.JsonSupport
import io.predix.dcosb.servicebroker.OpenServiceBrokerApi.APIModel
import io.predix.dcosb.servicemodule.api.ServiceModule.{
  InsufficientApplicationPermissions,
  MalformedRequest,
  ServiceInstanceDestroyed
}
import io.predix.dcosb.servicemodule.api.ServiceModuleMockingSuite.TestServiceModule
import io.predix.dcosb.servicemodule.api.util.BasicServiceModule.{
  DCOSModelSupport,
  OpenServiceBrokerModelSupport
}
import io.predix.dcosb.servicemodule.api.util.{
  BasicServiceModule,
  ServiceLoader,
  StubServiceModule
}
import io.predix.dcosb.servicemodule.api.util.BasicServiceModule.OpenServiceBrokerModelSupport.{
  CommonProvisionInstanceParameters,
  CommonUpdateInstanceParameters
}
import io.predix.dcosb.servicemodule.api.{
  ServiceModule,
  ServiceModuleConfiguration,
  ServiceModuleMockingSuite
}
import io.predix.dcosb.util.RouteSuite

import scala.collection.immutable.HashMap
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.FiniteDuration
import scala.io.Source
import scala.util.{Success, Try}

class OpenServiceBrokerApiRouteTest
    extends RouteSuite
    with ServiceModuleMockingSuite {
  implicit val timeout = Timeout(FiniteDuration(1, TimeUnit.SECONDS))
  implicit val eC =
    getActorSystem().dispatchers.lookup(CallingThreadDispatcher.Id)

  "An OpenServiceBrokerApi instance" - {

    trait ServiceModuleMock {

      def serviceId: String

      val childMaker
        : (ActorRefFactory, Class[_ <: Actor], String) => ActorRef = {
        (factory, actorClass, actorName) =>
          TestActorRef(
            Props(actorClass).withDispatcher(CallingThreadDispatcher.Id))
      }

      val serviceBroker =
        TestActorRef(
          Props(classOf[OpenServiceBrokerApi])
            .withDispatcher(CallingThreadDispatcher.Id))

      Await.result(
        serviceBroker ? OpenServiceBrokerApi.Configuration(childMaker),
        timeout.duration)

      val aksmStub = TestActorRef(Props(new Actor {
        override def receive = {
          case _ =>
        }
      }))

      val testServiceModuleMock = new TestServiceModuleMock()

      val serviceModule = testServiceModuleMock.serviceModule
      Await.result(
        serviceModule ? ServiceModule
          .ActorConfiguration(
            childMaker,
            stubFunction[DCOSCommon.Connection,
                         ((HttpRequest,
                           String) => Future[(HttpResponse, String)])],
            aksmStub,
            serviceId),
        timeout.duration
      )

      val serviceConfiguration = Await
        .result(serviceModule ? ServiceModule.GetServiceModuleConfiguration(
                  serviceId),
                timeout.duration)
        .asInstanceOf[Success[BasicServiceModule.CommonServiceConfiguration[
          OpenServiceBrokerModelSupport.CommonProvisionInstanceParameters,
          OpenServiceBrokerModelSupport.CommonUpdateInstanceParameters,
          OpenServiceBrokerModelSupport.CommonBindParameters,
          OpenServiceBrokerModelSupport.CommonBindResponse,
          DCOSModelSupport.CommonPackageOptions]]]

      // TODO ..an NPE here will be a missing basic plan :p
      val basicPlan = (serviceConfiguration.get.openService.plans find {
        _.id == "basic"
      }).get

      val serviceModules: ServiceLoader.ServiceList =
        List((serviceId, (serviceConfiguration.get, serviceModule)))

    }

    trait RouteMock extends ServiceModuleMock {

      val route = serviceBroker.underlyingActor
        .asInstanceOf[OpenServiceBrokerApi]
        .routeForServiceModules(serviceModules)

    }

    "when it's routeForServiceModules method is invoked with a Set of ServiceModule implementations" - {

      "it produces a route that" - {

        "rejects any request without Basic authentication" in new RouteMock {
          override def serviceId = "test-service"

          Get("/") ~> route ~> check {
            rejection shouldEqual AuthenticationFailedRejection(
              AuthenticationFailedRejection.CredentialsMissing,
              HttpChallenges.basic("dcosb"))
          }

        }

        "to a request with a Basic authentication header" - {

          "that has invalid credentials, rejects the request" in new RouteMock {
            override def serviceId = "test-service"

            val invalidCredentials =
              Authorization(BasicHttpCredentials("foo", "bar"))

            Get("/").addHeader(invalidCredentials) ~> route ~> check {
              rejection shouldEqual AuthenticationFailedRejection(
                AuthenticationFailedRejection.CredentialsRejected,
                HttpChallenges.basic("dcosb"))
            }

          }

          "that has valid credentials" - {

            val credentials =
              Authorization(BasicHttpCredentials("apiuser", "YYz3aN-kmw"))

            val servicePrefix = "/dcosb/test-service"

            "implements a superset of the OpenServiceBroker API 2.10 specification, in that" - {

              "it delivers catalog information" in new RouteMock {
                override def serviceId = "test-service"

                import spray.json._

                val catalogJson = Source
                  .fromURL(getClass.getResource("/catalog.json"))
                  .getLines
                  .mkString
                  .parseJson

                Get(s"$servicePrefix/broker/v2/catalog")
                  .addHeader(credentials) ~> route ~> check {
                  responseAs[String].parseJson shouldEqual catalogJson
                }

              }

              "given a valid a create instance request" - new JsonSupport {
                import spray.json._

                val createRequest = Put(
                  s"$servicePrefix/broker/v2/service_instances/foo-service-1",
                  OpenServiceBrokerApi.APIModel.CreateInstance(
                    "foo-org",
                    "basic",
                    "foo-service-guid",
                    "foo-space",
                    Some("""{"nodes":3}""".parseJson))
                ).addHeader(credentials)

                "and a service module that delivers a package object to a create instance request" - {

                  "it returns HTTP Created with a json response indicating the correct pending operation" in new RouteMock {
                    override def serviceId = "test-service"

                    import BasicServiceModule.DCOSModelSupport._

                    // set up createInstance mock to provide expected response to the route
                    val createInstance =
                      testServiceModuleMock.createServiceInstance()._1
                    createInstance expects ("foo-org", basicPlan, "foo-service-guid", "foo-space", "foo-service-1", Some(
                      CommonProvisionInstanceParameters(3))) onCall { (p) =>
                      Future.successful(
                        CommonPackageOptions(CommonService("foo")))
                    } once ()

                    createRequest ~> route ~> check {
                      response.status shouldEqual StatusCodes.Accepted
                      responseAs[String] shouldEqual """{"operation":"create"}"""
                    }

                  }
                }

                "and a service module that delivers in a failure," - {

                  "any Throwable" - {

                    "it returns HTTP 500" in new RouteMock {
                      override def serviceId = "test-service"
                      import BasicServiceModule.DCOSModelSupport._

                      // set up createInstance mock to provide expected response to the route
                      val createInstance =
                        testServiceModuleMock.createServiceInstance()._1
                      createInstance expects ("foo-org", basicPlan, "foo-service-guid", "foo-space", "foo-service-1", Some(
                        CommonProvisionInstanceParameters(3))) onCall { (p) =>
                        Future.failed(new Throwable {})
                      } once ()

                      createRequest ~> route ~> check {
                        response.status shouldEqual StatusCodes.ServiceUnavailable
                      }

                    }

                  }

                  "an InsufficientApplicationPermissions Throwable" - {

                    "it returns HTTP 401 and the message from the Throwable" in new RouteMock {

                      override def serviceId = "test-service"

                      // set up createInstance mock to provide expected response to the route
                      val createInstance =
                        testServiceModuleMock.createServiceInstance()._1
                      createInstance expects ("foo-org", basicPlan, "foo-service-guid", "foo-space", "foo-service-1", Some(
                        CommonProvisionInstanceParameters(3))) onCall { (p) =>
                        Future.failed(
                          new InsufficientApplicationPermissions("foo"))
                      } once ()

                      createRequest ~> route ~> check {
                        response.status shouldEqual StatusCodes.Unauthorized
                        responseAs[String] shouldEqual """{"description":"foo"}"""
                      }

                    }

                  }

                  "a MalformedRequest Throwable" - {

                    "it returns HTTP 400 and the message from the Throwable" in new RouteMock {

                      override def serviceId = "test-service"

                      // set up createInstance mock to provide expected response to the route
                      val createInstance =
                        testServiceModuleMock.createServiceInstance()._1
                      createInstance expects ("foo-org", basicPlan, "foo-service-guid", "foo-space", "foo-service-1", Some(
                        CommonProvisionInstanceParameters(3))) onCall { (p) =>
                        Future.failed(new MalformedRequest("foo"))
                      } once ()

                      createRequest ~> route ~> check {
                        response.status shouldEqual StatusCodes.BadRequest
                        responseAs[String] shouldEqual """{"description":"foo"}"""
                      }

                    }

                  }

                }

              }

              "given a create instance request that is invalid in " - {
                import spray.json._

                "supplying a parameters object the service module can't unserialize" - new APIModel.JsonSupport {

                  val createRequest = Put(
                    s"$servicePrefix/broker/v2/service_instances/foo-instance",
                    OpenServiceBrokerApi.APIModel.CreateInstance(
                      "foo-org",
                      "basic",
                      "foo-service-1",
                      "foo-space",
                      Some("""{"nodez":3}""".parseJson))
                  ).addHeader(credentials)

                  "it returns HTTP Bad Request" in new RouteMock {
                    override def serviceId = "test-service"

                    createRequest ~> route ~> check {
                      response.status shouldEqual StatusCodes.BadRequest
                    }

                  }

                }

                "not providing all required parameters in" - {}

              }

              "given a valid deprovision request" - {

                val deprovisionRequest = Delete(
                  s"$servicePrefix/broker/v2/service_instances/foo-instance/?service_id=service-guid&plan_id=basic")
                  .addHeader(credentials)

                "and a service module that delivers a ServiceInstanceDestoryed message" - {

                  "it returns HTTP Accepted with a json response indicating the correct pending operation" in new RouteMock {
                    override def serviceId = "test-service"

                    val destroyInstance =
                      testServiceModuleMock.destroyServiceInstance()._1
                    destroyInstance expects ("service-guid", basicPlan, "foo-instance", None, *) returning (Future
                      .successful(ServiceInstanceDestroyed("foo-instance"))) once ()

                    deprovisionRequest ~> route ~> check {
                      response.status shouldEqual StatusCodes.Accepted
                      responseAs[String] shouldEqual """{"operation":"destroy"}"""
                    }

                  }

                }

                "and a service module that delivers in a failure," - {

                  "any Throwable" - {

                    "it returns HTTP 500" in new RouteMock {
                      override def serviceId = "test-service"

                      val destroyInstance =
                        testServiceModuleMock.destroyServiceInstance()._1
                      destroyInstance expects ("service-guid", basicPlan, "foo-instance", None, *) returning (Future
                        .failed(new Throwable {})) once ()

                      deprovisionRequest ~> route ~> check {
                        response.status shouldEqual StatusCodes.ServiceUnavailable
                      }

                    }

                  }

                  "an InsufficientApplicationPermissions Throwable" - {

                    "it returns HTTP 401 and the message from the Throwable" in new RouteMock {
                      override def serviceId = "test-service"

                      val destroyInstance =
                        testServiceModuleMock.destroyServiceInstance()._1
                      destroyInstance expects ("service-guid", basicPlan, "foo-instance", None, *) returning (Future
                        .failed(new InsufficientApplicationPermissions("foo"))) once ()

                      deprovisionRequest ~> route ~> check {
                        response.status shouldEqual StatusCodes.Unauthorized
                        responseAs[String] shouldEqual """{"description":"foo"}"""
                      }

                    }

                  }

                  "a MalformedRequest Throwable" - {

                    "it returns HTTP 400 and the message from the Throwable" in new RouteMock {
                      override def serviceId = "test-service"

                      val destroyInstance =
                        testServiceModuleMock.destroyServiceInstance()._1
                      destroyInstance expects ("service-guid", basicPlan, "foo-instance", None, *) returning (Future
                        .failed(new MalformedRequest("foo"))) once ()

                      deprovisionRequest ~> route ~> check {
                        response.status shouldEqual StatusCodes.BadRequest
                        responseAs[String] shouldEqual """{"description":"foo"}"""
                      }

                    }

                  }

                }

              }

              "given a valid last operation request" - {

                val lastOperationRequest = Get(
                  s"$servicePrefix/broker/v2/service_instances/foo-instance/last_operation/?operation=create")
                  .addHeader(credentials)

                "and a service module that delivers a LastOperationStatus message" - {

                  "it returns a json representation of operation status" in new RouteMock {
                    override def serviceId = "test-service"

                    val lastOperation = testServiceModuleMock.lastOperation()._1
                    lastOperation expects (None, None, "foo-instance", ServiceModule.OSB.Operation.CREATE, None, *, *) returning (Future
                      .successful(ServiceModule.LastOperationStatus(
                        ServiceModule.OperationState.IN_PROGRESS,
                        Some("Are we there yet?"))))

                    lastOperationRequest ~> route ~> check {
                      response.status shouldEqual StatusCodes.OK
                      responseAs[String] shouldEqual """{"state":"in progress","description":"Are we there yet?"}"""
                    }

                  }

                }

                "and a service module that delivers in a failure," - {

                  "any Throwable" - {

                    "it returns HTTP 500" in new RouteMock {
                      override def serviceId = "test-service"

                      val lastOperation =
                        testServiceModuleMock.lastOperation()._1
                      lastOperation expects (None, None, "foo-instance", ServiceModule.OSB.Operation.CREATE, None, *, *) returning (Future
                        .failed(new Throwable {}))

                      lastOperationRequest ~> route ~> check {
                        response.status shouldEqual StatusCodes.ServiceUnavailable
                      }

                    }

                  }

                  "an InsufficientApplicationPermissions Throwable" - {

                    "it returns HTTP 401 and the message from the Throwable" in new RouteMock {
                      override def serviceId = "test-service"

                      val lastOperation =
                        testServiceModuleMock.lastOperation()._1
                      lastOperation expects (None, None, "foo-instance", ServiceModule.OSB.Operation.CREATE, None, *, *) returning (Future
                        .failed(new InsufficientApplicationPermissions("foo")))

                      lastOperationRequest ~> route ~> check {
                        response.status shouldEqual StatusCodes.Unauthorized
                        responseAs[String] shouldEqual """{"description":"foo"}"""
                      }

                    }

                  }

                  "a MalformedRequest Throwable" - {

                    "it returns HTTP 400 and the message from the Throwable" in new RouteMock {
                      override def serviceId = "test-service"

                      val lastOperation =
                        testServiceModuleMock.lastOperation()._1
                      lastOperation expects (None, None, "foo-instance", ServiceModule.OSB.Operation.CREATE, None, *, *) returning (Future
                        .failed(new MalformedRequest("foo")))

                      lastOperationRequest ~> route ~> check {
                        response.status shouldEqual StatusCodes.BadRequest
                        responseAs[String] shouldEqual """{"description":"foo"}"""
                      }

                    }

                  }

                }

              }

              "given an invalid last operation request" - {

                "in that it does not provide an operation parameter" - {}

                "in that it provides an invalid value in the operation parameter" - {}

              }

              "given a valid bind application request" - new APIModel.JsonSupport {
                import spray.json._

                val bindApplicationToServiceInstanceRequest = Put(
                  s"$servicePrefix/broker/v2/service_instances/foo-instance/service_bindings/binding-id",
                  OpenServiceBrokerApi.APIModel
                    .BindApplicationToServiceInstance(
                      "service-guid",
                      "basic",
                      Some("""{"user":"foo"}""".parseJson),
                      Some(
                        OpenServiceBrokerApi.APIModel.BindResource("app-id")))
                ).withHeaders(credentials)

                "and a service module that delivers a BindResponse" - {

                  "it returns a json representation of the ServiceModule's BindResponse implementation" in new RouteMock {
                    override def serviceId = "test-service"

                    import BasicServiceModule.OpenServiceBrokerModelSupport._

                    val bindApplicationToServiceInstance =
                      testServiceModuleMock
                        .bindApplicationToServiceInstance()
                        ._1

                    bindApplicationToServiceInstance expects ("service-guid", basicPlan, Some(
                      ServiceModule.OSB
                        .BindResource("app-id")), "binding-id", "foo-instance", Some(
                      CommonBindParameters(Some("foo"))), *, *) returning (Future
                      .successful(
                        CommonBindResponse(
                          credentials = Some(Credentials(
                            "alice",
                            "foo",
                            nodes = List(Node("localhost", 80))))))) once ()

                    bindApplicationToServiceInstanceRequest ~> route ~> check {
                      response.status shouldEqual StatusCodes.OK
                      responseAs[String] shouldEqual """{"credentials":{"username":"alice","password":"foo","nodes":[{"host":"localhost","port":80}]}}"""
                    }

                  }

                }

                "and a service module that delivers in a failure," - {

                  "any Throwable" - {

                    "it returns HTTP 500" in new RouteMock {
                      import BasicServiceModule.OpenServiceBrokerModelSupport._

                      override def serviceId = "test-service"

                      val bindApplicationToServiceInstance =
                        testServiceModuleMock
                          .bindApplicationToServiceInstance()
                          ._1

                      bindApplicationToServiceInstance expects ("service-guid", basicPlan, Some(
                        ServiceModule.OSB
                          .BindResource("app-id")), "binding-id", "foo-instance", Some(
                        CommonBindParameters(Some("foo"))), *, *) returning (Future
                        .failed(new Throwable {})) once ()

                      bindApplicationToServiceInstanceRequest ~> route ~> check {
                        response.status shouldEqual StatusCodes.ServiceUnavailable
                      }

                    }

                  }

                  "an InsufficientApplicationPermissions Throwable" - {

                    "it returns HTTP 401 and the message from the Throwable" in new RouteMock {
                      import BasicServiceModule.OpenServiceBrokerModelSupport._

                      override def serviceId = "test-service"

                      val bindApplicationToServiceInstance =
                        testServiceModuleMock
                          .bindApplicationToServiceInstance()
                          ._1

                      bindApplicationToServiceInstance expects ("service-guid", basicPlan, Some(
                        ServiceModule.OSB
                          .BindResource("app-id")), "binding-id", "foo-instance", Some(
                        CommonBindParameters(Some("foo"))), *, *) returning (Future
                        .failed(new InsufficientApplicationPermissions("foo"))) once ()

                      bindApplicationToServiceInstanceRequest ~> route ~> check {
                        response.status shouldEqual StatusCodes.Unauthorized
                        responseAs[String] shouldEqual """{"description":"foo"}"""
                      }

                    }

                  }

                  "a MalformedRequest Throwable" - {

                    "it returns HTTP 400 and the message from the Throwable" in new RouteMock {
                      import BasicServiceModule.OpenServiceBrokerModelSupport._

                      override def serviceId = "test-service"

                      val bindApplicationToServiceInstance =
                        testServiceModuleMock
                          .bindApplicationToServiceInstance()
                          ._1

                      bindApplicationToServiceInstance expects ("service-guid", basicPlan, Some(
                        ServiceModule.OSB
                          .BindResource("app-id")), "binding-id", "foo-instance", Some(
                        CommonBindParameters(Some("foo"))), *, *) returning (Future
                        .failed(new MalformedRequest("foo"))) once ()

                      bindApplicationToServiceInstanceRequest ~> route ~> check {
                        response.status shouldEqual StatusCodes.BadRequest
                        responseAs[String] shouldEqual """{"description":"foo"}"""
                      }

                    }

                  }

                  "a ServiceInstanceNotFound Throwable" - {

                    "it returns HTTP 404 and the message from the Throwable" in new RouteMock {
                      import BasicServiceModule.OpenServiceBrokerModelSupport._
                      import ServiceModule.ServiceInstanceNotFound

                      override def serviceId = "test-service"

                      val bindApplicationToServiceInstance =
                        testServiceModuleMock
                          .bindApplicationToServiceInstance()
                          ._1

                      bindApplicationToServiceInstance expects ("service-guid", basicPlan, Some(
                        ServiceModule.OSB
                          .BindResource("app-id")), "binding-id", "foo-instance", Some(
                        CommonBindParameters(Some("foo"))), *, *) returning (Future
                        .failed(new ServiceInstanceNotFound("foo"))) once ()

                      bindApplicationToServiceInstanceRequest ~> route ~> check {
                        response.status shouldEqual StatusCodes.NotFound
                        responseAs[String] shouldEqual """{"description":"foo"}"""
                      }

                    }

                  }

                }

              }

              "given an invalid bind application request" - {}

              "given a valid unbind application request" - new APIModel.JsonSupport {

                val unbindApplicationFromServiceInstanceRequest = Delete(
                  s"$servicePrefix/broker/v2/service_instances/foo-instance/service_bindings/binding-id/?service_id=service-guid&plan_id=basic")
                  .withHeaders(credentials)

                "for an existing binding" - {

                  "and a service module that delivers an ApplicationUnboundFromServiceInstance response" - {

                    "it returns a json representation confirming the application was unbound from the service instance" in new RouteMock {
                      override def serviceId = "test-service"

                      val unbindApplicationFromServiceInstance =
                        testServiceModuleMock
                          .unbindApplicationFromServiceInstance()
                          ._1

                      unbindApplicationFromServiceInstance expects ("service-guid", basicPlan, "binding-id", "foo-instance", *, *) returning (Future
                        .successful(
                          ServiceModule.ApplicationUnboundFromServiceInstance(
                            "foo-instance",
                            "binding-id"))) once ()

                      unbindApplicationFromServiceInstanceRequest ~> route ~> check {
                        response.status shouldEqual StatusCodes.OK
                        responseAs[String] shouldEqual """{}"""
                      }

                    }
                  }

                  "and a service module that delivers in a failure," - {

                    "any Throwable" - {

                      "it returns HTTP 500" in new RouteMock {
                        override def serviceId = "test-service"

                        val unbindApplicationFromServiceInstance =
                          testServiceModuleMock
                            .unbindApplicationFromServiceInstance()
                            ._1

                        unbindApplicationFromServiceInstance expects ("service-guid", basicPlan, "binding-id", "foo-instance", *, *) returning (Future
                          .failed(new Throwable {})) once ()

                        unbindApplicationFromServiceInstanceRequest ~> route ~> check {
                          response.status shouldEqual StatusCodes.ServiceUnavailable
                        }

                      }

                    }

                    "an InsufficientApplicationPermissions Throwable" - {

                      "it returns HTTP 401 and the message from the Throwable" in new RouteMock {
                        override def serviceId = "test-service"

                        val unbindApplicationFromServiceInstance =
                          testServiceModuleMock
                            .unbindApplicationFromServiceInstance()
                            ._1

                        unbindApplicationFromServiceInstance expects ("service-guid", basicPlan, "binding-id", "foo-instance", *, *) returning (Future
                          .failed(new InsufficientApplicationPermissions("foo"))) once ()

                        unbindApplicationFromServiceInstanceRequest ~> route ~> check {
                          response.status shouldEqual StatusCodes.Unauthorized
                          responseAs[String] shouldEqual """{"description":"foo"}"""
                        }

                      }

                    }

                    "a MalformedRequest Throwable" - {

                      "it returns HTTP 400 and the message from the Throwable" in new RouteMock {
                        override def serviceId = "test-service"

                        val unbindApplicationFromServiceInstance =
                          testServiceModuleMock
                            .unbindApplicationFromServiceInstance()
                            ._1

                        unbindApplicationFromServiceInstance expects ("service-guid", basicPlan, "binding-id", "foo-instance", *, *) returning (Future
                          .failed(new MalformedRequest("foo"))) once ()

                        unbindApplicationFromServiceInstanceRequest ~> route ~> check {
                          response.status shouldEqual StatusCodes.BadRequest
                          responseAs[String] shouldEqual """{"description":"foo"}"""
                        }

                      }

                    }

                  }

                }

                "for a non-existant binding" - {}

              }

              "given a valid update service instance request" - new APIModel.JsonSupport {
                import spray.json._

                def updateServiceInstanceRequest(serviceId: String) =
                  Patch(
                    s"/dcosb/$serviceId/broker/v2/service_instances/foo-instance/?accepts_incomplete=true",
                    OpenServiceBrokerApi.APIModel.UpdateInstance(
                      "service-guid",
                      Some("basic"),
                      Some("""{"nodes":9}""".parseJson),
                      Some(OpenServiceBrokerApi.APIModel.PreviousValues(
                        "old-plan")))
                  ).withHeaders(credentials)

                "for an existing service instance" - {

                  "that allows a plan update" - {

                    "and allows this particular plan update" - {

                      "and a service module that delivers a package options object in response to the UpdateServiceInstance request" - {

                        "it returns HTTP Created with a json response indicating the correct pending operation" in new RouteMock {

                          import BasicServiceModule.DCOSModelSupport._
                          override def serviceId = "test-service"

                          val updateServiceInstance =
                            testServiceModuleMock.updateServiceInstance()._1

                          updateServiceInstance expects ("service-guid", "foo-instance", Some(
                            basicPlan), Some(ServiceModule.OSB.PreviousValues(
                            "old-plan")), Some(CommonUpdateInstanceParameters(
                            9)), *, *) returning (Future.successful(
                            CommonPackageOptions(CommonService("foo"))))

                          updateServiceInstanceRequest(serviceId) ~> route ~> check {
                            response.status shouldEqual StatusCodes.Accepted
                            responseAs[String] shouldEqual """{"operation":"update"}"""
                          }

                        }
                      }

                      "and a service module that delivers in a failure," - {

                        "any Throwable" - {

                          "it returns HTTP 500" in new RouteMock {

                            import BasicServiceModule.DCOSModelSupport._
                            override def serviceId = "test-service"

                            val updateServiceInstance =
                              testServiceModuleMock.updateServiceInstance()._1

                            updateServiceInstance expects ("service-guid", "foo-instance", Some(
                              basicPlan), Some(ServiceModule.OSB.PreviousValues(
                              "old-plan")), Some(CommonUpdateInstanceParameters(
                              9)), *, *) returning (Future.failed(new Throwable {}))

                            updateServiceInstanceRequest(serviceId) ~> route ~> check {
                              response.status shouldEqual StatusCodes.ServiceUnavailable
                            }

                          }

                        }

                        "an InsufficientApplicationPermissions Throwable" - {

                          "it returns HTTP 401 and the message from the Throwable" in new RouteMock {

                            import BasicServiceModule.DCOSModelSupport._
                            override def serviceId = "test-service"

                            val updateServiceInstance =
                              testServiceModuleMock.updateServiceInstance()._1

                            updateServiceInstance expects ("service-guid", "foo-instance", Some(
                              basicPlan), Some(ServiceModule.OSB.PreviousValues(
                              "old-plan")), Some(CommonUpdateInstanceParameters(
                              9)), *, *) returning (Future.failed(new InsufficientApplicationPermissions("foo")))

                            updateServiceInstanceRequest(serviceId) ~> route ~> check {
                              response.status shouldEqual StatusCodes.Unauthorized
                              responseAs[String] shouldEqual """{"description":"foo"}"""
                            }

                          }

                        }

                        "a MalformedRequest Throwable" - {

                          "it returns HTTP 400 and the message from the Throwable" in new RouteMock {

                            import BasicServiceModule.DCOSModelSupport._
                            override def serviceId = "test-service"

                            val updateServiceInstance =
                              testServiceModuleMock.updateServiceInstance()._1

                            updateServiceInstance expects ("service-guid", "foo-instance", Some(
                              basicPlan), Some(ServiceModule.OSB.PreviousValues(
                              "old-plan")), Some(CommonUpdateInstanceParameters(
                              9)), *, *) returning (Future.failed(new MalformedRequest("foo")))

                            updateServiceInstanceRequest(serviceId) ~> route ~> check {
                              response.status shouldEqual StatusCodes.BadRequest
                              responseAs[String] shouldEqual """{"description":"foo"}"""
                            }

                          }

                        }

                      }

                    }

                    "but does not allow this particular plan update" - {

                      "it returns HTTP Bad Request explaining the limitation" in {}

                    }

                  }

                  "that does not allow plan update" - new RouteMock {
                    override def serviceId = "test-service-no-plan-updateable"

                    "it returns HTTP Bad Request explaining the limitation" in {

                      updateServiceInstanceRequest(serviceId) ~> route ~> check {
                        response.status shouldEqual StatusCodes.BadRequest
                        responseAs[String] shouldEqual """{"description":"Unfortunately, test-service-no-plan-updateable does not support updating of service instance plans"}"""
                      }

                    }

                  }

                }

                "for a non-existant service instance" - {}

              }

              "given an invalid update service instance request" - {

                "in that" - {

                  "it does not specify a new plan nor a parameters object" - new APIModel.JsonSupport {

                    def updateServiceInstanceRequest(serviceId: String) =
                      Patch(
                        s"/dcosb/$serviceId/broker/v2/service_instances/foo-instance/?accepts_incomplete=true",
                        OpenServiceBrokerApi.APIModel.UpdateInstance(
                          "service-guid",
                          None,
                          None,
                          Some(OpenServiceBrokerApi.APIModel.PreviousValues(
                            "old-plan")))
                      ).withHeaders(credentials)

                    "it returns HTTP Bad Request explaining the error" in new RouteMock {
                      override def serviceId = "test-service"

                      updateServiceInstanceRequest(serviceId) ~> route ~> check {
                        response.status shouldEqual StatusCodes.BadRequest
                        responseAs[String] shouldEqual """{"description":"No updated plan or parameters were provided - not sure what you need changed"}"""
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

}
