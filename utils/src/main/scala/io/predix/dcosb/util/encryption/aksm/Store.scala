package io.predix.dcosb.util.encryption.aksm

import java.io.{IOException, InputStream}
import java.net.URI
import java.security.cert.Certificate
import java.security._

import io.predix.dcosb.util.FileUtils
import io.predix.dcosb.util.encryption.AkkaKeyStoreManager
import io.predix.dcosb.util.encryption.AkkaKeyStoreManager.Configuration
import io.predix.dcosb.util.encryption.AkkaKeyStoreManager.Configuration.StoreConfiguration

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

object Store {

  // exceptions
  final class KeystoreFailedToInit(cause: Throwable)
      extends Exception(cause)
  final case class KeystoreFailedToOpen(uri: URI) extends Throwable
  final object KeystoreNotInitialized extends Throwable

  final case class AliasExistsButIsUnexpectedType(alias: String, actualType: Class[_]) extends Throwable
  final case class AliasDoesNotExistOrIsUnexpectedType(alias: String) extends Throwable

  final class UnrecoverableKey(cause: Throwable) extends Exception(cause)

  def unapply(store: Store[_ <: AkkaKeyStoreManager.Configuration.StoreConfiguration]): Option[String] = store.id
  
  object Local {

    final case class UnknownStoreFormat(format: String, cause: Throwable)
        extends Exception(cause)
    final case class KeystoreUnexpectedFormat(cause: Throwable)
        extends Exception(cause)

  }

  class Local extends Store[AkkaKeyStoreManager.Configuration.LocalStoreConfiguration] {
    import Local._

    private var ks: Option[KeyStore] = None

    override def init(configuration: Configuration.LocalStoreConfiguration, storeIndex: Int): Future[Unit] = {

      try {
        ks = Some(KeyStore.getInstance(configuration.uri.getScheme.toUpperCase))

        FileUtils.fileInputStream(configuration.uri.getSchemeSpecificPart) match {

          case Some(is: InputStream) =>
            ks.get.load(is, configuration.password match {
              case None             => null
              case Some(pw: String) => pw.toCharArray
            })
            super.init(configuration, storeIndex)

          case None => Future.failed(KeystoreFailedToOpen(configuration.uri))

        }


      } catch {
        case e: KeyStoreException if e.getMessage.endsWith("not found") =>
          Future.failed(new UnknownStoreFormat(configuration.uri.getScheme, e))
        case e: IOException if e.getMessage == "Invalid keystore format" =>
          Future.failed(KeystoreUnexpectedFormat(e))
        case e: IOException =>
          Future.failed(new KeystoreFailedToInit(e))
        case e: KeyStoreException =>
          Future.failed(new KeystoreFailedToInit(e))
        case e: Throwable =>
          Future.failed(new KeystoreFailedToInit(e))
      }

    }

    override def getPrivateKey(alias: String, password: Option[String]): Future[PrivateKey] = {

      ks match {

        case Some(keyStore) =>
          try {
            keyStore.getKey(alias, password match { case Some(pw) => pw.toCharArray case _ => null }) match {
              case pK: PrivateKey => Future.successful(pK)
              case null =>
                Future.failed(new AliasDoesNotExistOrIsUnexpectedType(alias))
              case e => Future.failed(new AliasExistsButIsUnexpectedType(alias, e.getClass))
            }

          } catch {
            case _: KeyStoreException => Future.failed(KeystoreNotInitialized)
            case e: UnrecoverableKeyException => Future.failed(new UnrecoverableKey(e))
          }

        case None => Future.failed(KeystoreNotInitialized)

      }

    }

    override def getPublicKey(alias: String, storeId: Option[String]): Future[PublicKey] = ???

    override def getCertificate(alias: String, storeId: Option[String]): Future[Certificate] = {

      ks match {

        case Some(keyStore) =>
          try {
            keyStore.getCertificate(alias) match {
              case cert: Certificate => Future.successful(cert)
              case null =>
                Future.failed(new AliasDoesNotExistOrIsUnexpectedType(alias))
              case e => Future.failed(new AliasExistsButIsUnexpectedType(alias, e.getClass))
            }

          } catch {
            case _: KeyStoreException => Future.failed(KeystoreNotInitialized)
            case e: UnrecoverableKeyException => Future.failed(new UnrecoverableKey(e))
          }

        case None => Future.failed(KeystoreNotInitialized)

      }

    }
  }

}

trait Store[C <: StoreConfiguration] {

  private var configuration: Option[C] = None

  def getPrivateKey(alias: String, password: Option[String] = None): Future[PrivateKey]
  def getPublicKey(alias: String, storeId: Option[String] = None): Future[PublicKey]
  def getCertificate(alias: String, storeId: Option[String] = None): Future[Certificate]

  def init(configuration: C, storeIndex: Int): Future[Unit] = {
    this.configuration = Some(configuration)
    Future.successful((): Unit)
  }

  def id:Option[String] = {
    configuration match {
      case Some(c) => Some(c.id)
      case None => None
    }
  }

  def default:Boolean = {
    configuration match {
      case Some(c) => c.default
      case None => false
    }
  }

}
