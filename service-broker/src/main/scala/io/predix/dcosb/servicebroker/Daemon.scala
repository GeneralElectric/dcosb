package io.predix.dcosb.servicebroker

import java.util.concurrent.TimeUnit
import java.io.InputStream
import java.security.cert.{Certificate, X509Certificate}
import java.security.{KeyStore, PrivateKey, SecureRandom}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}

import com.typesafe.sslconfig.akka.AkkaSSLConfig
import akka.actor.{Actor, ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import io.predix.dcosb.servicemodule.api.util.ServiceLoader
import akka.pattern.ask
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.stream.scaladsl.{Flow, Sink, Source}
import io.predix.dcosb.dcos.DCOSProxy
import io.predix.dcosb.util.FileUtils
import io.predix.dcosb.util.actor.ConfiguredActor
import io.predix.dcosb.util.actor.ConfiguredActor._
import io.predix.dcosb.util.encryption.AkkaKeyStoreManager
import io.predix.dcosb.util.encryption.AkkaKeyStoreManager.Configuration

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

/**
  * An executable object that:
  * - brings up an Actor System,
  * - starts the ServiceLoader, and instructs it to attempt to load
  * configured ( per dcosb.services ) ServiceModule implementations.
  * - starts the OpenServiceBrokerApi, asks for a Route to be generated
  * from the loaded service objects in ServiceLoader
  * - binds to a configured ( per dcosb.service-broker ) listening socket
  * and registers the Route from the OpenServiceBrokerApi as a handler
  *
  */
object Daemon extends App {

  val tConfig = ConfigFactory.load()

  // create ActorSystem
  implicit val system = ActorSystem("service-broker-daemon")
  implicit val executionContext = system.dispatcher
  implicit val timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))
  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system))

  val childMaker = (f: ActorRefFactory, a: Class[_ <: Actor], name: String) => {
    f.actorOf(Props(a), name)
  }

  val httpClientFactory: DCOSProxy.HttpClientFactory = { connection => {

      val clientFlow = Http(system).cachedHostConnectionPoolHttps[String](connection.apiHost, connection.apiPort)
      (request: HttpRequest, context:String) => {
        Source.single(request -> context)
          .via(clientFlow)
          .runWith(Sink.head).flatMap {
          case (Success(r: HttpResponse), c: String) => Future.successful((r, c))
          case (Failure(f), _) => Future.failed(f)
        }

      }

    }
  }


  system.log.info("Parsing AkkaKeyStoreManager configuration & initializing key stores")
  // start & configure AkkaKeyStoreManager
  AkkaKeyStoreManager.initStores(AkkaKeyStoreManager.parseConfiguration(tConfig.getConfig("dcosb.service-broker.aksm"))) onComplete {
    case Success(stores) =>
      import AkkaKeyStoreManager.Configuration.StoreConfiguration
      import io.predix.dcosb.util.encryption.aksm.Store

      val initializedStores:Seq[Option[Store[_ <: Configuration.StoreConfiguration]]] = (stores map {
        case Success(store) => Some(store)
        case Failure(e) =>
          system.log.error(s"Ignoring key store with initialization failure: $e")
          None
      })

      system.log.info("Starting AkkaKeyStoreManager")
      val aksm = childMaker(system, classOf[AkkaKeyStoreManager], AkkaKeyStoreManager.name)
      (aksm ? AkkaKeyStoreManager.Configuration.ActorConfiguration(initializedStores.flatten)) onComplete {
        case Success(_) =>
          system.log.info(s"$aksm started and configured")

          // start & configure ServiceLoader
          val serviceLoader =
            childMaker(system, classOf[ServiceLoader], ServiceLoader.name)
          (serviceLoader ? ServiceLoader.Configuration(childMaker, httpClientFactory, aksm)) onComplete {
            case Success(Success(_)) =>
              // iterate through dcosb.services & send RegisterService messages
              val serviceActors = (tConfig
                .getObject("dcosb.services")
                .asScala map {
                case (serviceId, _) =>
                  val implementingClass = tConfig.getString(
                    s"dcosb.services.${serviceId}.implementation")

                  system.log.debug(
                    s"Trying to register ServiceModule($serviceId,$implementingClass)")

                  (serviceLoader ? ServiceLoader.RegisterService(
                    serviceId,
                    implementingClass)) map {
                    case Success(actor: ActorRef) =>
                      system.log.warning(
                        s"Registered ServiceModule($serviceId,$implementingClass) as $actor")

                      Some(actor)
                    case e =>
                      system.log.error(
                        s"Failed to register ServiceModule($serviceId,$implementingClass), skipping! $e")
                      None
                  }

              }).toList

              Future.sequence(serviceActors) onComplete {
                case Success(actors) =>
                  // get total registered service modules from ServiceLoader
                  (serviceLoader ? ServiceLoader.GetServices()) onComplete {
                    case Success(
                        ServiceLoader.Services(
                          registeredServices: ServiceLoader.ServiceList)) =>
                      system.log.debug(
                        s"Completed registering ServiceModule implementations: $registeredServices")

                      // get route from OpenServiceBroker
                      val broker = childMaker(system,
                                              classOf[OpenServiceBrokerApi],
                                              OpenServiceBrokerApi.name)
                      (broker ? OpenServiceBrokerApi.Configuration(childMaker)) onComplete {
                        case Success(Success(_)) =>
                          (broker ? OpenServiceBrokerApi.RouteForServiceModules(
                            registeredServices)) onComplete {
                            case Success(route: Route) =>
                              system.log.debug(s"Created Route: $route")

                              implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system))

                              // get private key for TLS from AKSM
                              val pkAlias = tConfig.getString("dcosb.service-broker.listen.private-key.alias")
                              val pkPassword = if (tConfig.hasPath("dcosb.service-broker.listen.private-key.password")) Some(tConfig.getString("dcosb.service-broker.listen.private-key.password")) else None
                              val pkStoreId = if (tConfig.hasPath("dcosb.service-broker.listen.private-key.store-id")) Some(tConfig.getString("dcosb.service-broker.listen.private-key.store-id")) else None

                              (aksm ? AkkaKeyStoreManager.GetPrivateKey(pkAlias, pkPassword, pkStoreId)) onComplete {
                                case Success(Success(privateKey: PrivateKey)) =>

                                  // get certificate for TLS from AKSM
                                  val certAlias = tConfig.getString("dcosb.service-broker.listen.certificate.alias")
                                  val certStoreId = if (tConfig.hasPath("dcosb.service-broker.listen.certificate.store-id")) Some(tConfig.getString("dcosb.service-broker.listen.certificate.store-id")) else None
                                  // get certificate for TLS from AKSM
                                  (aksm ? AkkaKeyStoreManager.GetCertificate(certAlias, certStoreId)) onComplete {
                                    case Success(Success(certificate: Certificate)) =>

                                      // build a faux keystore with only this key
                                      val ks: KeyStore = KeyStore.getInstance("PKCS12")
                                      ks.load(null, pkPassword match { case Some(pw) => pw.toCharArray case None => null })
                                      ks.setKeyEntry(pkAlias, privateKey, pkPassword match { case Some(pw) => pw.toCharArray case None => null }, Array(certificate))

                                      val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
                                      keyManagerFactory.init(ks, pkPassword match { case Some(pw) => pw.toCharArray case None => null })

                                      val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
                                      tmf.init(ks)

                                      val sslContext: SSLContext = SSLContext.getInstance("TLS")
                                      sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, new SecureRandom)
                                      val https: HttpsConnectionContext = ConnectionContext.https(sslContext)

                                      // create listening socket, bind route
                                      Http(system).setDefaultServerHttpContext(https)
                                      Http(system)
                                        .bindAndHandle(
                                          route.asInstanceOf[Route],
                                          tConfig.getString(
                                            "dcosb.service-broker.listen.address"),
                                          tConfig.getInt(
                                            "dcosb.service-broker.listen.port")) onComplete {

                                        case Success(Http.ServerBinding(a)) =>
                                          system.log.warning(s"Open Service Broker listening at $a")
                                        case Failure(e: Throwable) =>
                                          system.log.error(s"Failed to bind Open Service Broker: $e")
                                      }

                                    case Success(Failure(e)) =>

                                    case Failure(e) =>

                                  }



                                case Success(Failure(e)) =>
                                  system.log.error(s"Failed to retrieve private key $pkAlias to start service broker socket over TLS: $e")
                                  bail()

                                case Failure(e) =>
                                  system.log.error(s"Exception while trying to retrieve private key $pkAlias to start service broker socket over TLS: $e")
                                  bail()

                              }


                          }
                      }

                    case r =>
                      system.log.error(
                        s"Failed to retrieve registered services from ServiceLoader: $r")
                      bail()

                  }

                case Failure(e: Throwable) =>
                  system.log.error(
                    s"Failed to register ServiceModule implementations: $e")
                  bail()

              }

            case r =>
              system.log.error(s"Failed to configure ServiceLoader, shutting down: $r")
              bail()

          }



        case Failure(e) =>
      }


    case Failure(e) =>
      system.log.error(s"Failed to initalize key stores: $e")
      bail()
  }






  def bail() = {
    system.terminate() onComplete { _ =>
      System.exit(1)
    }
  }

}
