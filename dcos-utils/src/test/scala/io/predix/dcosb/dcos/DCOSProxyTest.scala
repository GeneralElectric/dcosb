package io.predix.dcosb.dcos

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorRefFactory, Props}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.testkit.{CallingThreadDispatcher, TestActorRef}
import akka.pattern.ask
import akka.util.Timeout
import com.github.nscala_time.time.Imports.DateTime
import io.predix.dcosb.dcos.DCOSProxy.{ClusterUnavailable, HeartbeatOK}
import io.predix.dcosb.dcos.cosmos.CosmosApiClient
import io.predix.dcosb.dcos.marathon.MarathonApiClient
import io.predix.dcosb.dcos.security.TokenKeeper
import io.predix.dcosb.dcos.service.PlanApiClient
import io.predix.dcosb.mesos.MesosApiClient
import io.predix.dcosb.util.ActorSuite
import io.predix.dcosb.util.actor.ConfiguredActor
import org.joda.time.DateTimeZone
import org.joda.time.chrono.ISOChronology

import scala.collection.immutable.HashMap
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Future}
import scala.io.Source
import scala.util.{Failure, Success}

class DCOSProxyJsonSupportTest extends ActorSuite {
  import spray.json._

  "Given a json representation of a health report" - {

    val json = Source
      .fromURL(getClass.getResource("/health-report.json"))
      .getLines
      .mkString

    "formats in implicit scope in JsonSupport should create a HealthReport object" in {

      import DCOSProxy.ApiModel._

      val parser = new JsonSupport {

        def fromString(json: String): HealthReport =
          healthReportFormat.read(json.parseJson)

      }

      val slaveOutput = HashMap(
        "dcos-adminrouter-agent-reload.service" -> "",
        "dcos-adminrouter-agent-reload.timer" -> "",
        "dcos-adminrouter-agent.service" -> "",
        "dcos-diagnostics.service" -> "",
        "dcos-diagnostics.socket" -> "",
        "dcos-docker-gc.service" -> "",
        "dcos-docker-gc.timer" -> "",
        "dcos-epmd.service" -> "",
        "dcos-gen-resolvconf.service" -> "",
        "dcos-gen-resolvconf.timer" -> "",
        "dcos-log-agent.service" -> "",
        "dcos-log-agent.socket" -> "",
        "dcos-logrotate-agent.service" -> "",
        "dcos-logrotate-agent.timer" -> "",
        "dcos-mesos-slave.service" -> "",
        "dcos-metrics-agent.service" -> "",
        "dcos-metrics-agent.socket" -> "",
        "dcos-navstar.service" -> "",
        "dcos-pkgpanda-api.service" -> "",
        "dcos-rexray.service" -> "",
        "dcos-signal.timer" -> "",
        "dcos-spartan-watchdog.service" -> "",
        "dcos-spartan-watchdog.timer" -> "",
        "dcos-spartan.service" -> ""
      )
      parser.fromString(json) shouldEqual HealthReport(
        HashMap(
          "dcos-cosmos.service" -> DCOSUnit(
            "dcos-cosmos.service",
            0,
            "DC/OS Package Manager (Cosmos)",
            DateTime
              .parse("2017-07-31T19:09:08.079629596Z")
              .withChronology(
                ISOChronology.getInstance(DateTimeZone.getDefault())),
            List(DCOSUnitNode(
              true,
              "master",
              "10.42.60.59",
              "ip-10-42-60-59",
              0,
              "a6826bb5-2945-43a1-9290-29c62b818222",
              HashMap(
                "dcos-adminrouter-reload.service" -> "",
                "dcos-adminrouter-reload.timer" -> "",
                "dcos-adminrouter.service" -> "",
                "dcos-backup-master.service" -> "",
                "dcos-backup-master.socket" -> "",
                "dcos-bouncer-legacy.service" -> "",
                "dcos-bouncer.service" -> "",
                "dcos-ca.service" -> "",
                "dcos-cockroach.service" -> "",
                "dcos-cosmos.service" -> "",
                "dcos-diagnostics.service" -> "",
                "dcos-diagnostics.socket" -> "",
                "dcos-epmd.service" -> "",
                "dcos-exhibitor.service" -> "",
                "dcos-gen-resolvconf.service" -> "",
                "dcos-gen-resolvconf.timer" -> "",
                "dcos-history.service" -> "",
                "dcos-log-master.service" -> "",
                "dcos-log-master.socket" -> "",
                "dcos-logrotate-master.service" -> "",
                "dcos-logrotate-master.timer" -> "",
                "dcos-marathon.service" -> "",
                "dcos-mesos-dns.service" -> "",
                "dcos-mesos-master.service" -> "",
                "dcos-metrics-master.service" -> "",
                "dcos-metrics-master.socket" -> "",
                "dcos-metronome.service" -> "",
                "dcos-navstar.service" -> "",
                "dcos-networking_api.service" -> "",
                "dcos-pkgpanda-api.service" -> "",
                "dcos-secrets.service" -> "",
                "dcos-secrets.socket" -> "",
                "dcos-signal.service" -> "",
                "dcos-signal.timer" -> "",
                "dcos-spartan-watchdog.service" -> "",
                "dcos-spartan-watchdog.timer" -> "",
                "dcos-spartan.service" -> "",
                "dcos-vault.service" -> ""
              )
            ))
          ),
          "dcos-adminrouter-agent.service" -> DCOSUnit(
            "dcos-adminrouter-agent.service",
            0,
            "Admin Router Agent",
            DateTime
              .parse("2017-07-31T19:09:08.181817995Z")
              .withChronology(
                ISOChronology.getInstance(DateTimeZone.getDefault())),
            List(
              DCOSUnitNode(
                false,
                "agent",
                "10.42.60.239",
                "ip-10-42-60-239",
                0,
                "a6826bb5-2945-43a1-9290-29c62b818222-S3",
                slaveOutput
              ),
              DCOSUnitNode(
                false,
                "agent",
                "10.42.60.157",
                "ip-10-42-60-157",
                0,
                "a6826bb5-2945-43a1-9290-29c62b818222-S0",
                slaveOutput
              ),
              DCOSUnitNode(
                false,
                "agent",
                "10.42.60.136",
                "ip-10-42-60-136",
                0,
                "a6826bb5-2945-43a1-9290-29c62b818222-S2",
                slaveOutput
              ),
              DCOSUnitNode(
                false,
                "agent",
                "10.42.60.89",
                "ip-10-42-60-89",
                0,
                "a6826bb5-2945-43a1-9290-29c62b818222-S1",
                slaveOutput
              ),
              DCOSUnitNode(
                false,
                "agent_public",
                "10.42.60.160",
                "ip-10-42-60-160",
                0,
                "a6826bb5-2945-43a1-9290-29c62b818222-S4",
                HashMap(
                  "dcos-adminrouter-agent-reload.service" -> "",
                  "dcos-adminrouter-agent-reload.timer" -> "",
                  "dcos-adminrouter-agent.service" -> "",
                  "dcos-diagnostics.service" -> "",
                  "dcos-diagnostics.socket" -> "",
                  "dcos-docker-gc.service" -> "",
                  "dcos-docker-gc.timer" -> "",
                  "dcos-epmd.service" -> "",
                  "dcos-gen-resolvconf.service" -> "",
                  "dcos-gen-resolvconf.timer" -> "",
                  "dcos-log-agent.service" -> "",
                  "dcos-log-agent.socket" -> "",
                  "dcos-logrotate-agent.service" -> "",
                  "dcos-logrotate-agent.timer" -> "",
                  "dcos-mesos-slave-public.service" -> "",
                  "dcos-metrics-agent.service" -> "",
                  "dcos-metrics-agent.socket" -> "",
                  "dcos-navstar.service" -> "",
                  "dcos-pkgpanda-api.service" -> "",
                  "dcos-rexray.service" -> "",
                  "dcos-signal.timer" -> "",
                  "dcos-spartan-watchdog.service" -> "",
                  "dcos-spartan-watchdog.timer" -> "",
                  "dcos-spartan.service" -> ""
                )
              )
            )
          )
        ),
        DateTime
          .parse("2017-07-31T19:09:08.222594981Z")
          .withChronology(ISOChronology.getInstance(DateTimeZone.getDefault()))
      )

    }

  }

}

class DCOSProxyTest extends ActorSuite {

  implicit val executionContext =
    system.dispatchers.lookup(CallingThreadDispatcher.Id)
  implicit val timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))

  trait DCOSProxyMocks {

    val aksm = TestActorRef(Props(new Actor {
      override def receive: Receive = {
        case _ =>
      }
    }))

    val tokenKeeper = TestActorRef(Props(new Actor {
      override def receive: Receive = {
        case TokenKeeper.Configuration(_, _, _) =>
          sender() ! Success(ConfiguredActor.Configured())
      }
    }).withDispatcher(CallingThreadDispatcher.Id))

    val cosmosApiClient = TestActorRef(Props(new Actor {
      override def receive: Receive = {
        case CosmosApiClient.Configuration(_) =>
          sender() ! Success(ConfiguredActor.Configured())
      }
    }).withDispatcher(CallingThreadDispatcher.Id))

    val planApiClient = TestActorRef(Props(new Actor {
      override def receive: Receive = {
        case PlanApiClient.Configuration(_, _) =>
          sender() ! Success(ConfiguredActor.Configured())
      }
    }).withDispatcher(CallingThreadDispatcher.Id))

    val mesosApiClient = TestActorRef(Props(new Actor {
      override def receive: Receive = {
        case MesosApiClient.Configuration(_) =>
          sender() ! Success(ConfiguredActor.Configured())
      }
    }))

    val marathonApiClient = TestActorRef(Props(new Actor {
      override def receive: Receive = {
        case MarathonApiClient.Configuration(_) =>
          sender() ! Success(ConfiguredActor.Configured())
      }
    }))

    val childMaker: (ActorRefFactory, Class[_ <: Actor], String) => ActorRef = {
      (factory, actorClass, name) =>
        actorClass match {
          case c: Class[_] if c == classOf[TokenKeeper.JWTSigningTokenKeeper] =>
            tokenKeeper
          case c: Class[_] if c == classOf[CosmosApiClient] => cosmosApiClient
          case c: Class[_] if c == classOf[PlanApiClient]   => planApiClient
          case c: Class[_] if c == classOf[MesosApiClient]  => mesosApiClient
          case c: Class[_] if c == classOf[MarathonApiClient] =>
            marathonApiClient
          case _ =>
            TestActorRef(Props(new Actor {
              override def receive: Receive = {

                case _ => // do nothing
              }
            }).withDispatcher(CallingThreadDispatcher.Id))
        }
    }

    val httpClient =
      mockFunction[HttpRequest, String, Future[(HttpResponse, String)]]
    val httpClientFactory
      : (DCOSCommon.Connection => (HttpRequest, String) => Future[(HttpResponse,
                                                                   String)]) = {
      address =>
        httpClient

    }

    val connectionParameters =
      DCOSCommon.Connection(None, None, None, None, "master.mesos", 80)
    val connectionParametersVerifier =
      mockFunction[DCOSCommon.Connection, Future[DCOSCommon.Connection]]
    val pkgInfo = DCOSCommon.PkgInfo("foo-pkg", "1.0.0", true)

    val dcosProxy = TestActorRef(
      Props(classOf[DCOSProxy]).withDispatcher(CallingThreadDispatcher.Id))

  }

  "A DCOSProxy" - {

    "in response to a Configuration message" - {

      "uses the connection parameters verifier in the configuration message, to verify incoming connection parameters" - new DCOSProxyMocks {

        "failing the configuration, if the connection parameters verifier fails the connection parameters" in {

          connectionParametersVerifier expects (connectionParameters) returns (Future
            .failed(DCOSProxy.InvalidConnectionParameters("nope"))) once ()

          Await.result(
            dcosProxy ? DCOSProxy.Configuration(childMaker,
                                                aksm,
                                                httpClientFactory,
                                                connectionParameters,
                                                connectionParametersVerifier,
                                                pkgInfo),
            timeout.duration) shouldEqual Failure(
            DCOSProxy.InvalidConnectionParameters("nope"))

        }

        "successfully completing the configuration, if the connection parameters verifier verifies the connection parameters" in {

          connectionParametersVerifier expects (connectionParameters) returns (Future
            .successful(connectionParameters)) once ()

          Await.result(
            dcosProxy ? DCOSProxy.Configuration(childMaker,
                                                aksm,
                                                httpClientFactory,
                                                connectionParameters,
                                                connectionParametersVerifier,
                                                pkgInfo),
            timeout.duration) shouldEqual Success(ConfiguredActor.Configured())

        }

      }

    }

    "after having been sent valid configuration" - {

      trait ConfiguredDCOSProxyMocks extends DCOSProxyMocks {

        connectionParametersVerifier expects (*) onCall ({
          c: DCOSCommon.Connection =>
            Future.successful(c)
        }) once ()

        Await.result(
          dcosProxy ? DCOSProxy.Configuration(childMaker,
                                              aksm,
                                              httpClientFactory,
                                              connectionParameters,
                                              connectionParametersVerifier,
                                              pkgInfo),
          timeout.duration)

      }

      "In response to a Heartbeat message" - {

        "when unable to send the health report http request" - {

          trait FailingHttpRequest extends ConfiguredDCOSProxyMocks {

            case class SomeException() extends Throwable
            httpClient expects (*, *) returning (Future
              .failed(SomeException())) once ()

          }

          "responds with a ClusterUnavailable message with error details" in new FailingHttpRequest {

            Await.result(dcosProxy ? DCOSProxy.Heartbeat(), timeout.duration) shouldEqual Failure(
              ClusterUnavailable("Failed to send http request to cluster",
                                 Some(SomeException())))

          }

        }

        "when able to send the health report http request and the response indicates" - {

          "cosmos is down" - {

            trait HealthReportWithCosmosDown
                extends ConfiguredDCOSProxyMocks
                with DCOSProxy.ApiModel.JsonSupport
                with SprayJsonSupport {
              import DCOSProxy.ApiModel._

              val now = DateTime.now()
              val healthReportRequest =
                HttpRequest(method = HttpMethods.GET,
                            uri = "/system/health/v1/report")
              val healthReport = HealthReport(
                Units = HashMap(
                  "dcos-cosmos.service" -> DCOSUnit(
                    UnitName = "dcos-cosmos.service",
                    Nodes = List(),
                    Health = 1,
                    PrettyName = "DC/OS Package Manager (Cosmos)",
                    Timestamp = now)),
                UpdatedTime = now
              )

            }

            "responds with a ClusterUnavailable message with error details" in new HealthReportWithCosmosDown {

              Marshal(healthReport).to[ResponseEntity] onComplete {

                case Success(re: ResponseEntity) =>
                  httpClient expects (healthReportRequest, *) returning (Future
                    .successful((HttpResponse(entity = re), ""))) once ()

                  Await.result(dcosProxy ? DCOSProxy.Heartbeat(),
                               timeout.duration) shouldEqual Failure(
                    ClusterUnavailable(
                      "The following units failed health check: dcos-cosmos.service",
                      None,
                      Some(healthReport)))

                case Failure(e: Throwable) =>
                  fail("Could not encode HealthReport, is DCOSProxyJsonSupportTest passing?")
              }

            }

          }

          "all critical services are healthy" - {

            trait HealthReportWithCosmosUp
                extends ConfiguredDCOSProxyMocks
                with DCOSProxy.ApiModel.JsonSupport
                with SprayJsonSupport {
              import DCOSProxy.ApiModel._

              val now = DateTime.now()
              val healthReportRequest =
                HttpRequest(method = HttpMethods.GET,
                            uri = "/system/health/v1/report")
              val healthReport = HealthReport(
                Units = HashMap(
                  "dcos-cosmos.service" -> DCOSUnit(
                    UnitName = "dcos-cosmos.service",
                    Nodes = List(),
                    Health = 0,
                    PrettyName = "DC/OS Package Manager (Cosmos)",
                    Timestamp = now)),
                UpdatedTime = now
              )

            }

            "sends a HeartbeatOK response" in new HealthReportWithCosmosUp {

              Marshal(healthReport).to[ResponseEntity] onComplete {

                case Success(re: ResponseEntity) =>
                  httpClient expects (healthReportRequest, *) returning (Future
                    .successful((HttpResponse(entity = re), ""))) once ()

                  Await.result(dcosProxy ? DCOSProxy.Heartbeat(),
                               timeout.duration) shouldEqual Success(
                    HeartbeatOK(now))

                case Failure(e: Throwable) =>
                  fail("Could not encode HealthReport, is DCOSProxyJsonSupportTest passing?")
              }

            }

            "non-critical services are unhealthy" - {

              trait HealthReportWithNoncriticalServiceDown
                  extends ConfiguredDCOSProxyMocks
                  with DCOSProxy.ApiModel.JsonSupport
                  with SprayJsonSupport {
                import DCOSProxy.ApiModel._

                val now = DateTime.now()
                val healthReportRequest =
                  HttpRequest(method = HttpMethods.GET,
                              uri = "/system/health/v1/report")
                val healthReport = HealthReport(
                  Units = HashMap(
                    "dcos-cosmos.service" -> DCOSUnit(
                      UnitName = "dcos-cosmos.service",
                      Nodes = List(),
                      Health = 0,
                      PrettyName = "DC/OS Package Manager (Cosmos)",
                      Timestamp = now),
                    "some-other.service" -> DCOSUnit(UnitName =
                                                       "some-other.service",
                                                     Nodes = List(),
                                                     Health = 1,
                                                     PrettyName =
                                                       "Some failing service",
                                                     Timestamp = now)
                  ),
                  UpdatedTime = now
                )

              }

              "sends a HeartbeatOK response" in new HealthReportWithNoncriticalServiceDown {

                Marshal(healthReport).to[ResponseEntity] onComplete {

                  case Success(re: ResponseEntity) =>
                    httpClient expects (healthReportRequest, *) returning (Future
                      .successful((HttpResponse(entity = re), ""))) once ()

                    Await.result(dcosProxy ? DCOSProxy.Heartbeat(),
                                 timeout.duration) shouldEqual Success(
                      HeartbeatOK(now))

                  case Failure(e: Throwable) =>
                    fail("Could not encode HealthReport, is DCOSProxyJsonSupportTest passing?")
                }

              }

            }

          }

        }

      }

    }

  }

}
