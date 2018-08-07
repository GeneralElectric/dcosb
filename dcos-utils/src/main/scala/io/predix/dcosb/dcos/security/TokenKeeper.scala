package io.predix.dcosb.dcos.security

import java.io.ByteArrayInputStream
import java.security.{KeyFactory, KeyStore, PrivateKey}
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef, Stash}
import akka.event.LoggingReceive
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.headers.{ModeledCustomHeader, ModeledCustomHeaderCompanion}
import akka.http.scaladsl.model.{DateTime => _, _}
import akka.http.scaladsl.unmarshalling.Unmarshal
import io.predix.dcosb.config.model.DCOSClusterConnectionParameters
import io.predix.dcosb.util.actor.ConfiguredActor
import com.github.nscala_time.time.Imports._
import akka.pattern._
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import io.predix.dcosb.dcos.{DCOSCommon, DCOSProxy}
import io.predix.dcosb.util.actor.HttpClientActor
import pdi.jwt.{Jwt, JwtAlgorithm}
import spray.json.DefaultJsonProtocol

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

object TokenKeeper {

  case class Configuration(connection: DCOSCommon.Connection,
                           httpClient: DCOSProxy.HttpClient,
                           aksm: ActorRef)

  case class Token(expiresAt: DateTime, token: String)

  // messages handled
  case class GetOrRefreshToken()
  case class SetToken(token: Token)

  // responses
  case class InvalidCredentials(principal: Option[String],
                                privateKeyProvided: Boolean)
      extends Throwable {
    override def toString: String = {
      s"InvalidCredentials($principal, private key alias present: $privateKeyProvided)"
    }
  }
  case class UnexpectedResponse(resp: HttpResponse) extends Throwable {
    override def toString: String = {
      super.toString + s", $resp"
    }
  }

  // misc
  final class DCOSAuthorizationTokenHeader(token: String)
      extends ModeledCustomHeader[DCOSAuthorizationTokenHeader] {
    override def renderInRequests = true
    override def renderInResponses = false
    override val companion = DCOSAuthorizationTokenHeader

    override def equals(obj: scala.Any): Boolean = {
      obj match {
        case a: DCOSAuthorizationTokenHeader => a.value.equals(value)
        case a                               => super.equals(a)
      }
    }

    override def value: String = s"token=$token"
  }

  object DCOSAuthorizationTokenHeader
      extends ModeledCustomHeaderCompanion[DCOSAuthorizationTokenHeader] {
    override val name = "Authorization"
    override def parse(value: String) =
      Try(new DCOSAuthorizationTokenHeader(value))
  }

  /**
    * Simply always returns a configured Token. Useful mostly for the CLI.
    * @param token
    */
  class SingleTokenEmittingTokenKeeper(token: Token) extends TokenKeeper {

    override def getOrRefreshToken(): Future[Token] = Future.successful(token)
    override def setToken(token: Token): Future[Token] =
      Future.successful(token)
  }

  /**
    * Reads out a principal and private key from DCOSClusterConnectionParameters,
    * and sends a JSON Web Token signed authentication request to DC/OS to acquire
    * a Token. Keeps track of Token expiry.
    */
  class JWTSigningTokenKeeper
      extends TokenKeeper
      with HttpClientActor
      with SprayJsonSupport
      with DefaultJsonProtocol {

    implicit val ec = context.dispatcher
    implicit val mat = ActorMaterializer(ActorMaterializerSettings(context.system))

    // DC/OS API model
    case class TokenResponse(token: String)

    implicit val tokenResponseFormat = jsonFormat1(TokenResponse)

    // config
    private val tokenExpiry = 4.days + 23.hours // TODO make configurable

    // misc state
    private var currentToken: Option[Token] = None
    private var privateKey: Option[PrivateKey] = None

    override def configure(
        configuration: Configuration): Future[ConfiguredActor.Configured] = {
      log.debug(s"Configuring ${this} with $configuration")
      import io.predix.dcosb.util.encryption.AkkaKeyStoreManager._
      // must have principal & privateKey set
      (configuration.connection.principal, configuration.connection.privateKeyAlias, configuration.connection.privateKeyStoreId, configuration.connection.privateKeyPassword) match {
        case (None, None, _, _) => Future.failed(InvalidCredentials(None, false))
        case (Some(principal: String), None, _, _) =>
          Future.failed(InvalidCredentials(Some(principal), false))
        case (None, Some(_: String), _, _) =>
          Future.failed(InvalidCredentials(None, true))
        case (Some(principal: String), Some(privateKeyAlias: String), privateKeyStoreId, privateKeyPassword) =>
          httpClient = Some(configuration.httpClient)
          val promise = Promise[ConfiguredActor.Configured]
          // ask key store manager for our privatekey
          implicit val t = timeout
          (configuration.aksm ? GetPrivateKey(privateKeyAlias, privateKeyPassword, privateKeyStoreId)) onComplete {

            case Success(Success(pk: PrivateKey)) =>
              privateKey = Some(pk)
              promise.completeWith(super.configure(configuration))
            case Success(Failure(e: Throwable)) =>
              log.error(s"Failed to get dc/os principal's private key with alias $privateKeyAlias from aksm: $e")
              promise.failure(e)
            case Success(r) =>
              log.error(s"Unexpected response from aksm while trying to get dc/os principal's private key: $r")
              promise.failure(new ConfiguredActor.ActorConfigurationException {})
            case Failure(e) =>
              log.error(s"Exception while trying to get dc/os principal's private key from aksm: $e")
              promise.failure(e)
          }

          promise.future

      }

    }

    override def getOrRefreshToken(): Future[Token] = {
      log.debug("Handling getOrRefreshToken()")

      val promise = Promise[Token]()

      def retrieveToken(principal: String,
                        privateKey: PrivateKey,
                        promise: Promise[Token]): Unit = {

        def `sendRequest, unmarshall and fulfill promise`(
            tokenKeeper: ActorRef,
            request: HttpRequest,
            promise: Promise[Token]): Unit = {

          `sendRequest and handle response`(request, {
            case Success(HttpResponse(StatusCodes.OK, _, re, _)) =>
              Unmarshal(re).to[TokenResponse] onComplete {
                case Success(tokenResponse: TokenResponse) =>
                  log.debug(s"Retrieved token from API: ${tokenResponse}")
                  // send a SetToken to TokenKeeper, fulfull promise once TokenKeeper confirms it
                  implicit val timeout: Timeout = Timeout(
                    FiniteDuration(1000, TimeUnit.MILLISECONDS)) // this should be a message to ourselves..
                val newToken =
                  Token(DateTime.now + tokenExpiry, tokenResponse.token)
                  (tokenKeeper ? SetToken(newToken)) onComplete {
                    case Success(Success(t: Token)) => promise.success(t)
                    case Success(Failure(e: Throwable)) =>
                      log.debug(s"Failed to set token: $e")
                      promise.failure(e)
                    case Failure(e: Throwable) =>
                      log.debug(s"Failed to set token: $e")
                      promise.failure(e)

                  }
                case Failure(e: Throwable) =>
                  log.debug(s"Failed to retrieve token from API: $e")
                  promise.failure(e)
              }
            case Success(r: HttpResponse) =>
              r.entity.discardBytes()
              promise.failure(new UnexpectedResponse(r))
            case Failure(e: Throwable) =>
              promise.failure(e)
          })

        }



          try {
            val loginToken = Jwt.encode(
              s"""{"uid":"$principal"}""",
              privateKey,
              JwtAlgorithm.RS256)

            log.debug(s"Created loginToken: $loginToken")

            val tokenRequest = HttpRequest(
              method = HttpMethods.POST,
              uri = "/acs/api/v1/auth/login",
              entity =
                HttpEntity(ContentType(MediaTypes.`application/json`),
                  s"""{"uid":"$principal","token":"${loginToken}"}""")
            )

            `sendRequest, unmarshall and fulfill promise`(context.self,
              tokenRequest,
              promise)

          } catch {
            case e: Throwable => promise.failure(e)
          }



      }

      // retrieve or create token
      configured((configuration) => {
        currentToken match {
          case Some(t: Token) if (t.expiresAt > (DateTime.now)) =>
            log.debug("Found non-expired token, returning it")
            promise.success(t)
          case _ =>
            log.debug("No valid token was found, getting a new one")
            privateKey match {
              case Some(pk: PrivateKey) =>
                // we know dcosClusterConnectionParameters are correctly set because of the check in configure() above
                retrieveToken(configuration.connection.principal.get,
                  pk,
                  promise)
              case None =>
                log.error("No private key was available to sign token request..")
                promise.failure(new ConfiguredActor.ActorConfigurationException {})

            }

        }

      })

      promise.future
    }

    override def setToken(token: Token): Future[Token] = {

      currentToken = Some(token)
      Future.successful(token)

    }
  }

  val name = "token-keeper"

}

abstract class TokenKeeper
    extends ConfiguredActor[TokenKeeper.Configuration]
    with ActorLogging
    with Stash {
  import TokenKeeper._

  final def configuredBehavior: Actor.Receive = LoggingReceive {
    case configuration: Configuration => configure(configuration)
    case GetOrRefreshToken()          => broadcastFuture(getOrRefreshToken(), sender())
    case SetToken(token)              => broadcastFuture(setToken(token), sender())
  }

  def getOrRefreshToken(): Future[Token]
  def setToken(token: Token): Future[Token]

}
