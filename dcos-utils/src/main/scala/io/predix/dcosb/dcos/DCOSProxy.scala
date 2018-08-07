package io.predix.dcosb.dcos

import java.net.InetSocketAddress
import java.security.PrivateKey

import akka.actor.{Actor, ActorRef, ActorRefFactory}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.headers.Accept
import akka.pattern.ask
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import io.predix.dcosb.dcos.DCOSProxy.ApiModel.HealthReport
import io.predix.dcosb.dcos.cosmos.CosmosApiClient
import io.predix.dcosb.dcos.marathon.MarathonApiClient
import io.predix.dcosb.dcos.security.TokenKeeper.DCOSAuthorizationTokenHeader
import io.predix.dcosb.dcos.security.TokenKeeper
import io.predix.dcosb.dcos.service.PlanApiClient
import io.predix.dcosb.mesos.MesosApiClient
import io.predix.dcosb.util.JsonFormats
import io.predix.dcosb.util.actor.{ConfiguredActor, HttpClientActor}
import pureconfig.syntax._
import org.joda.time.DateTime
import spray.json.DefaultJsonProtocol

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

object DCOSProxy {

  type HttpClient = (HttpRequest, String) => Future[(HttpResponse, String)]

  type ConnectionParametersVerifier =
    (DCOSCommon.Connection => Future[DCOSCommon.Connection])

  val optimisticConnectionParametersVerifier: ConnectionParametersVerifier = {
    Future.successful(_)
  }

  /**
    * An idempotent factory function that creates http client functions that transform HttpRequests in to HttpResponses
    * Note that this should take in to account whether the cluster is secure or not
    */
  type HttpClientFactory = (
      DCOSCommon.Connection => ((HttpRequest,
                                 String) => Future[(HttpResponse, String)]))

  case class Configuration(
      childMaker: (ActorRefFactory, Class[_ <: Actor], String) => ActorRef,
      aksm: ActorRef,
      httpClientFactory: HttpClientFactory,
      connection: DCOSCommon.Connection,
      connectionVerifier: ConnectionParametersVerifier,
      pkgInfo: DCOSCommon.PkgInfo)

  object Target extends Enumeration {
    val COSMOS = Value(CosmosApiClient.name)
    val PLAN = Value(PlanApiClient.name)
    val MESOS = Value(MesosApiClient.name)
    val MARATHON = Value(MarathonApiClient.name)
  }

  // handled messages
  case class Forward[M](actor: Target.Value, message: M)

  /**
    * Get Cosmos and other critical system components' (units) health
    * via /system/health/v1/report
    */
  case class Heartbeat()

  // responses
  case class HeartbeatOK(updated: DateTime)

  case class ClusterUnavailable(message: String,
                                cause: Option[Throwable],
                                healthReport: Option[ApiModel.HealthReport] =
                                  None)
      extends Throwable {

    override def equals(obj: scala.Any): Boolean = {
      obj match {
        case c: ClusterUnavailable =>
          (c.message.equals(message) && c.cause.equals(cause) && healthReport
            .equals(healthReport))
        case h => super.equals(h)
      }
    }

  }
  case class InvalidConnectionParameters(message: String,
                                         cause: Option[Throwable] = None)
      extends Throwable

  object ApiModel {

    case class DCOSUnitNode(Leader: Boolean,
                            Role: String,
                            IP: String,
                            Host: String,
                            Health: Int,
                            MesosID: String,
                            Output: Map[String, String])
    case class DCOSUnit(UnitName: String,
                        Health: Int,
                        PrettyName: String,
                        Timestamp: DateTime,
                        Nodes: List[DCOSUnitNode])
    case class HealthReport(Units: Map[String, DCOSUnit], UpdatedTime: DateTime)

    trait JsonSupport extends DefaultJsonProtocol {

      implicit val dateTimeFormat = new JsonFormats.DateTimeFormat()
      implicit val dcosUnitNode = jsonFormat7(DCOSUnitNode)
      implicit val dcosUnitFormat = jsonFormat5(DCOSUnit)
      implicit val healthReportFormat = jsonFormat2(HealthReport)

    }

  }

  def tokenAuthenticatedHttpClientFactory(wrappedClient: HttpClient,
                                          tokenKeeper: ActorRef)(
      implicit timeout: Timeout,
      executionContext: ExecutionContext)
    : (DCOSCommon.Connection => HttpClient) = {
    (connection: DCOSCommon.Connection) =>
      { (r: HttpRequest, c: String) =>
        {
          val promise = Promise[(HttpResponse, String)]
          // add auth headers if we have a token
          (tokenKeeper ? TokenKeeper.GetOrRefreshToken()) onComplete {
            case Success(Success(t: TokenKeeper.Token)) =>
              promise.completeWith(
                wrappedClient(r.withHeaders((r.headers ++ Seq(
                                new DCOSAuthorizationTokenHeader(t.token)))),
                              c))
            case Success(Failure(e: Throwable)) => promise.failure(e)
            case Failure(e: Throwable)          => promise.failure(e)

          }
          promise.future
        }

      }
  }

  val name = "dcos-proxy"

}

/**
  * Verifies a [[DCOSCommon.Connection]], keeps tabs on DC/OS cluster health,
  * brings up and configures DC/OS and related (Mesos) Actors,
  * routes requests to them, taking in to account the connection state
  */
class DCOSProxy
    extends ConfiguredActor[DCOSProxy.Configuration]
    with HttpClientActor
    with DCOSProxy.ApiModel.JsonSupport
    with SprayJsonSupport {
  import DCOSProxy._

  val tConf = ConfigFactory.load()
  implicit val configurationTimeout = Timeout(
    tConfig
      .getValue("dcosb.dcos.configuration-timeout")
      .toOrThrow[FiniteDuration])
  val criticalUnits = tConf.getStringList("dcosb.dcos.proxy.critical-units")

  implicit val ec = context.dispatcher
  implicit val mat = ActorMaterializer(ActorMaterializerSettings(context.system))

  private var targets: mutable.HashMap[Target.Value, ActorRef] =
    mutable.HashMap()

  override def configure(configuration: DCOSProxy.Configuration)
    : Future[ConfiguredActor.Configured] = {

    val promise = Promise[ConfiguredActor.Configured]
    // verify connection parameters
    configuration.connectionVerifier(configuration.connection) onComplete {
      case Success(c: DCOSCommon.Connection) =>
        log.debug(s"Successfully verified connection parameters $c")
        // create http client with connection params
        val httpClient = configuration.httpClientFactory(c)

        log.debug(
          s"Created httpClient(${c.principal}@${c.apiHost}:${c.apiPort})")
        // if needed, create token keeper with the original factory
        createTokenKeeperIfNeeded(
          configuration,
          c,
          httpClient,
          ({ tokenKeeper =>
            val httpClientFactory = tokenKeeper match {
              case Left(Some(tokenKeeper: ActorRef)) =>
                // create token authenticating http client factory wrapping the original client..
                Success(
                  tokenAuthenticatedHttpClientFactory(httpClient, tokenKeeper))
              case Left(None) =>
                // no tokenkeeper, use original client factory
                Success(configuration.httpClientFactory)
              case Right(e: Throwable) => Failure(e)
            }

            httpClientFactory match {
              case Success(h: DCOSProxy.HttpClientFactory) =>
                this.httpClient = Some(h(c))

                // TODO parallelize actor creation instead of this 100 line callback hell
                // create cosmos api client actor
                targets put (Target.COSMOS, configuration.childMaker(
                  context,
                  classOf[CosmosApiClient],
                  CosmosApiClient.name))

                log.debug(s"Created CosmosApiClient(${targets(Target.COSMOS)}")
                (targets(Target.COSMOS) ? CosmosApiClient
                  .Configuration(h(c))) onComplete {

                  case Success(Success(ConfiguredActor.Configured())) =>
                    log.debug(
                      s"Configured CosmosApiClient(${targets(Target.COSMOS)})")

                    targets put (Target.PLAN, configuration.childMaker(
                      context,
                      classOf[PlanApiClient],
                      PlanApiClient.name))

                    log.debug(s"Created PlanApiClient(${targets(Target.PLAN)})")
                    (targets(Target.PLAN) ? PlanApiClient
                      .Configuration(h(c), configuration.pkgInfo.planApiCompatible)) onComplete {
                      case Success(Success(ConfiguredActor.Configured())) =>
                        log.debug(
                          s"Configured PlanApiClient(${targets(Target.PLAN)})")

                        targets put (Target.MESOS, configuration.childMaker(
                          context,
                          classOf[MesosApiClient],
                          MesosApiClient.name
                        ))

                        log.debug(
                          s"Created MesosApiClient(${targets(Target.MESOS)}")
                        (targets(Target.MESOS) ? MesosApiClient.Configuration(
                          h(c))) onComplete {
                          case Success(Success(ConfiguredActor.Configured())) =>
                            log.debug(
                              s"Configured MesosApiClient(${targets(Target.MESOS)})")

                            targets put (Target.MARATHON, configuration
                              .childMaker(context,
                                          classOf[MarathonApiClient],
                                          MarathonApiClient.name))

                            log.debug(
                              s"Created MarathonApiClient(${targets(Target.MARATHON)}")
                            (targets(Target.MARATHON) ? MarathonApiClient
                              .Configuration(h(c))) onComplete {
                              case Success(
                                  Success(ConfiguredActor.Configured())) =>
                                log.debug(
                                  s"Configured MarathonApiClient(${targets(Target.MARATHON)}")

                                // all done!
                                promise.completeWith(
                                  super.configure(configuration))

                              case Success(Failure(e: Throwable)) => promise.failure(e)
                              case Failure(e: Throwable) => promise.failure(e)

                            }

                          case Success(Failure(e: Throwable)) =>
                            promise.failure(e)
                          case Failure(e: Throwable) => promise.failure(e)
                        }

                      case Success(Failure(e: Throwable)) => promise.failure(e)
                      case Failure(e: Throwable)          => promise.failure(e)
                    }

                  case Success(Failure(e: Throwable)) => promise.failure(e)
                  case Failure(e: Throwable)          => promise.failure(e)

                }

              case Failure(e: Throwable) => promise.failure(e)
            }
          })
        )

      case Failure(e: Throwable) => promise.failure(e)
    }

    promise.future

  }

  override def configuredBehavior: Receive = {

    case f: Forward[_] => targets(f.actor).tell(f.message, sender())
    case _: Heartbeat  => broadcastFuture(handleHeartbeat(), sender())

  }

  def createTokenKeeperIfNeeded(
      configuration: DCOSProxy.Configuration,
      connection: DCOSCommon.Connection,
      httpClient: DCOSProxy.HttpClient,
      f: (Either[Option[ActorRef], Throwable] => Unit)): Unit = {
    configuration.connection match {
      case DCOSCommon.Connection(Some(principal: String),
                                 _,
                                 Some(privateKeyAlias: String),
                                 _,
                                 _,
                                 _) =>
        val tokenKeeper = configuration.childMaker(
          context,
          classOf[TokenKeeper.JWTSigningTokenKeeper],
          TokenKeeper.name)
        log.debug(s"Created TokenKeeper($tokenKeeper)")
        (tokenKeeper ? TokenKeeper.Configuration(connection, httpClient, configuration.aksm)) onComplete {
          case Success(Success(ConfiguredActor.Configured())) =>
            log.debug(s"Configured TokenKeeper($tokenKeeper)")
            f(Left(Some(tokenKeeper)))
          case Success(Failure(e: Throwable)) => Right(e)
          case Failure(e: Throwable)          => Right(e)
        }

      case _ => f(Left(None))
    }
  }

  def handleHeartbeat(): Future[HeartbeatOK] = {
    log.debug("Processing Heartbeat")

    val promise = Promise[HeartbeatOK]()

    val heartbeatRequest = HttpRequest(method = HttpMethods.GET, uri = "/system/health/v1/report")

    `sendRequest and handle response`(heartbeatRequest, {
      case Success(HttpResponse(StatusCodes.OK, _, re, _)) =>
        Unmarshal(re).to[DCOSProxy.ApiModel.HealthReport] onComplete {
          case Success(r: DCOSProxy.ApiModel.HealthReport) =>
            collectFailingUnits(r) match {
              case failingUnits: List[String] if failingUnits.filter {
                criticalUnits.contains(_)
              }.size > 0 =>
                log.warning(
                  s"Critical units failing health check found: ${failingUnits.mkString(",")}")
                promise.failure(ClusterUnavailable(
                  s"The following units failed health check: ${failingUnits.mkString(",")}",
                  None,
                  Some(r)))
              case _ =>
                log.debug(s"No failing critical units")
                promise.success(HeartbeatOK(r.UpdatedTime))
            }

          case Failure(e: Throwable) =>
            log.error(s"Failed to send http request to cluster: $e")
            re.discardBytes()
            promise.failure(
              ClusterUnavailable("Failed to send http request to cluster", Some(e)))
        }

      case Success(r: HttpResponse) =>
        r.entity.discardBytes()
        promise.failure(new ClusterUnavailable(s"Unexpected response to heartbeat: $r", None))
      case Failure(e: Throwable) =>
        promise.failure(new ClusterUnavailable(s"Failed to send http request to cluster", Some(e)))
    })

    promise.future

  }

  def collectFailingUnits(healthReport: HealthReport): List[String] = {

    (for ((unitName, unit) <- healthReport.Units; if unit.Health == 1)
      yield unitName).toList

  }

}
