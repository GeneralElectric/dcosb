package io.predix.dcosb.util.encryption

import java.net.URI
import java.security.PrivateKey
import java.security.cert.Certificate

import akka.actor.{Actor, ActorLogging}
import com.typesafe.config.Config
import io.predix.dcosb.util.AsyncUtils
import io.predix.dcosb.util.actor.ConfiguredActor
import io.predix.dcosb.util.encryption.AkkaKeyStoreManager.Configuration.{AKMConfiguration, StoreConfiguration}
import io.predix.dcosb.util.encryption.aksm.Store
import pureconfig._

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.reflect.ClassTag
import scala.util.Try

object AkkaKeyStoreManager {

  // handled messages
  case class GetPrivateKey(alias: String,
                           password: Option[String],
                           storeId: Option[String] = None)
  case class GetPublicKey(alias: String,
                          password: Option[String],
                          storeId: Option[String] = None)
  case class GetCertificate(alias: String,
                            storeId: Option[String] = None)

  case class StoreIdNotFound(storeId: String) extends Throwable

  object Configuration {

    final case class ConfigurationError(message: String) extends ConfiguredActor.ActorConfigurationException

    case class ActorConfiguration(initializedStores: Seq[Store[_ <: StoreConfiguration]])

    /**
      * This is the type that's mapped via pureconfig
      * @param stores
      */
    case class AKMConfiguration(stores: Seq[StoreConfiguration])

    sealed trait StoreConfiguration {
      def id: String
      def uri: URI
      def default: Boolean

      def init[S <: Store[_ <: StoreConfiguration]](storeIndex: Int)(implicit stag: ClassTag[S]): Future[S]

    }

    final case class LocalStoreConfiguration(id: String,
                                             uri: URI,
                                             default: Boolean = false,
                                             password: Option[String])
        extends StoreConfiguration {

      override def init[S <: Store[_ <: StoreConfiguration]](storeIndex: Int)(implicit stag: ClassTag[S]): Future[S] = {

        val store = new Store.Local()
        store.init(this, storeIndex)

        Future.successful(store.asInstanceOf[S])

      }

    }

  }

  /**
    * Take a Typesafe Configuration object and parse it in to [[AKMConfiguration]] with
    * pureconfig
    * @param fromConfig Configuration source (the Typesafe Configuration object)
    * @return the object representation of configuration, [[AKMConfiguration]]
    */
  def parseConfiguration(fromConfig: Config): Configuration.AKMConfiguration = {
    import Configuration._

    implicit val storeConfHint =
      new FieldCoproductHint[StoreConfiguration]("type") {
        override def fieldValue(name: String) =
          name.dropRight("StoreConfiguration".length).toLowerCase
      }

    val config: AKMConfiguration =
      loadConfigOrThrow[AKMConfiguration](fromConfig)

    // validate
    if ((config.stores filter (_.default)).size > 1)
      throw ConfigurationError("Multiple default stores defined")

    config

  }

  def initStores(configuration: AKMConfiguration)(implicit executionContext: ExecutionContext): Future[Seq[Try[Store[_ <: StoreConfiguration]]]] = {
    import Configuration._

    if (configuration.stores.size == 0) {
      Future.failed(ConfigurationError("No stores received in configuration"))
    } else {

      AsyncUtils.waitAll[Store[_ <: StoreConfiguration]](configuration.stores.zipWithIndex map {

        case (localConfig: LocalStoreConfiguration, i) =>
          localConfig.init(i)

      })

    }

  }

  val name = "aksm"

}

class AkkaKeyStoreManager
    extends ConfiguredActor[AkkaKeyStoreManager.Configuration.ActorConfiguration]
    with Actor
    with ActorLogging {
  import AkkaKeyStoreManager._

  var storesById
    : Option[Map[String, Store[_ <: Configuration.StoreConfiguration]]] = None

  var defaultStore: Option[Store[_ <: Configuration.StoreConfiguration]] = None

  override def configure(configuration: Configuration.ActorConfiguration)
    : Future[ConfiguredActor.Configured] = {
    import AkkaKeyStoreManager.Configuration._

    if (configuration.initializedStores.size > 0) {

      storesById = Some({
        (configuration.initializedStores filter { _.id.nonEmpty } map {
          case store => store.id.get -> store

        }).toMap
      })

      defaultStore = Some({
        configuration.initializedStores find { _.default } match {
          case Some(store) => store
          case None =>
            log.warning(
              s"No default store was found, considering first store ${configuration.initializedStores(0)} as default. This store may not have been correctly initialized")
            configuration.initializedStores(0)
        }
      })

      super.configure(configuration)

    } else {
      Future.failed(ConfigurationError("No stores received in configuration"))

    }


  }

  override def configuredBehavior = {

    case GetPrivateKey(alias, password, storeId) =>
      broadcastFuture(getPrivateKey(alias, password, storeId), sender())
    case GetCertificate(alias, storeId) => broadcastFuture(getCertificate(alias, storeId), sender())

    case _ =>
  }

  def getPrivateKey(alias: String,
                    password: Option[String],
                    storeId: Option[String]): Future[PrivateKey] = {

    val promise = Promise[PrivateKey]

    withStoresOrFailPromise(
      (storesById: Map[String, Store[_ <: Configuration.StoreConfiguration]],
       defaultStore: Store[_ <: Configuration.StoreConfiguration]) => {

        storeId match {
          case Some(id) =>
            storesById.get(id) match {
              case Some(store) =>
                promise.completeWith(store.getPrivateKey(alias, password))
              case None => promise.failure(new StoreIdNotFound(id))
            }

          case None =>
            promise.completeWith(defaultStore.getPrivateKey(alias, password))
        }

      },
      promise
    )

    promise.future

  }

  def getCertificate(alias: String, storeId: Option[String]): Future[Certificate] = {

    val promise = Promise[Certificate]

    withStoresOrFailPromise(
      (storesById: Map[String, Store[_ <: Configuration.StoreConfiguration]],
       defaultStore: Store[_ <: Configuration.StoreConfiguration]) => {

        storeId match {
          case Some(id) =>
            storesById.get(id) match {
              case Some(store) =>
                promise.completeWith(store.getCertificate(alias))
              case None => promise.failure(new StoreIdNotFound(id))
            }

          case None =>
            promise.completeWith(defaultStore.getCertificate(alias))
        }

      }, promise)


    promise.future

  }

  private def withStoresOrFailPromise(
      f: (Map[String, Store[_ <: Configuration.StoreConfiguration]],
          Store[_ <: Configuration.StoreConfiguration]) => _,
      promise: Promise[_]): Unit = {
    (storesById, defaultStore) match {
      case (s, d) if s.isEmpty || d.isEmpty =>
        promise.failure(
          new IllegalStateException(
            "No stores are available to serve requests.."))
      case (Some(s), Some(d)) => f(s, d)
    }
  }

}
