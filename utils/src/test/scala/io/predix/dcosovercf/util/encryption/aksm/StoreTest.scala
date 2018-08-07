package io.predix.dcosovercf.util.encryption.aksm

import java.net.URI
import java.security.PrivateKey
import java.util.concurrent.TimeUnit

import akka.testkit.CallingThreadDispatcher
import akka.util.Timeout
import io.predix.dcosb.util.{ActorSuite, DCOSBSuite}
import io.predix.dcosb.util.encryption.aksm.Store.{
  AliasDoesNotExistOrIsUnexpectedType,
  AliasExistsButIsUnexpectedType,
  UnrecoverableKey
}
import io.predix.dcosb.util.encryption.aksm
import io.predix.dcosb.util.encryption.aksm.Store
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

/**
  * keys taking part in this test suite were generated per below:
  *
  * - keystore.pkcs12 :
  *
  * keytool -genkeypair -v \
  *   -alias test \
  *   -dname "CN=$BROKER_HOST, OU=Predix Datafabric, O=GE Digital, L=San Ramon, ST=California, C=US" \
  *   -keystore keystore.pkcs12 \
  *   -storetype PKCS12 \
  *   -storepass test-test \
  *   -keyalg RSA \
  *   -keysize 2048
  *
  * keytool -genseckey -v \
  *   -alias test-secret \
  *   -keystore keystore.pkcs12 \
  *   -storetype PKCS12 \
  *   -storepass test-test \
  *   -keyalg aes \
  *   -keysize 256
  */
class StoreTest extends ActorSuite {
  implicit val executionContext =
    system.dispatchers.lookup(CallingThreadDispatcher.Id)
  implicit val timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))

  "A LocalStore" - {

    trait LocalStore {
      val localStore = new Store.Local
    }

    "when it's init() method is invoked with a LocalStore configuration object" - {
      import io.predix.dcosb.util.encryption.AkkaKeyStoreManager.Configuration.LocalStoreConfiguration

      "that points to a" - {

        "keystore on the classpath or filesystem" - {

          "specified to be in a known format" - {

            "with a correct store password" - {

              "it successfully initializes" in new LocalStore {

                ScalaFutures.whenReady(
                  localStore.init(LocalStoreConfiguration(
                                    id = "pkcs12",
                                    uri = new URI("pkcs12:keystore.pkcs12"),
                                    password = Some("test-test")),
                                  1)) { r =>
                  r shouldEqual (())

                }

              }

            }

            "with an incorrect store password" - {

              "it throws KeystoreFailedToOpen" in new LocalStore {

                ScalaFutures.whenReady(
                  localStore
                    .init(
                      LocalStoreConfiguration(id = "pkcs12",
                                              new URI("pkcs12:keystore.pkcs12"),
                                              password = Some("incorrect!")),
                      1)
                    .failed) { r =>
                  r shouldBe a[Store.KeystoreFailedToInit]

                }

              }

            }

          }

          "specified to be in an unknown format" - {

            "it throws UnknownStoreFormat" in new LocalStore {

              ScalaFutures.whenReady(
                localStore
                  .init(LocalStoreConfiguration(id = "pkcs12",
                                                new URI("jkjk:keystore.dud"),
                                                password = Some("test")),
                        1)
                  .failed) { e =>
                e shouldBe a[Store.Local.UnknownStoreFormat]

              }

            }

          }

          "specified to be in a known format but the provider fails in opening it" - {

            "it throws KeystoreUnexpectedFormat" in new LocalStore {

              ScalaFutures.whenReady(
                localStore
                  .init(LocalStoreConfiguration(id = "jks",
                                                new URI("jks:keystore.dud"),
                                                password = Some("test")),
                        1)
                  .failed) { e =>
                e shouldBe a[Store.Local.KeystoreUnexpectedFormat]

              }

            }

          }

        }

        "a keystore not found on the classpath or filesystem" - {

          "it throws KeystoreFailedToOpen" in new LocalStore {

            ScalaFutures.whenReady(
              localStore
                .init(LocalStoreConfiguration(id = "jks",
                                              new URI("jks:keystore.notfound"),
                                              password = Some("test")),
                      1)
                .failed) { e =>
              e shouldBe a[Store.KeystoreFailedToOpen]

            }

          }

        }

      }

    }

    "after it's been successfully initialized" - {

      trait InitializedLocalStore {
        import io.predix.dcosb.util.encryption.AkkaKeyStoreManager.Configuration.LocalStoreConfiguration

        val localStore = new aksm.Store.Local
        localStore.init(
          LocalStoreConfiguration(id = "test",
                                  uri = new URI("pkcs12:keystore.pkcs12"),
                                  password = Some("test-test")),
          1)

      }

      "when it's getPrivateKey() method is invoked with a string alias and password" - {

        "with an alias that exists in the wrapped KeyStore" - {

          "the password is correct for the PrivateKey stored under the alias" - {

            "it returns a PrivateKey object" in new InitializedLocalStore {

              ScalaFutures.whenReady(
                localStore.getPrivateKey(alias = "test",
                                         password = Some("test-test"))) { k =>
                k shouldBe a[PrivateKey]
              }

            }

          }

          "the password is incorrect for the PrivateKey stored under the alias" - {

            "it returns Failure(UnrecoverableKey) explaining the problem" in new InitializedLocalStore {

              ScalaFutures.whenReady(
                localStore
                  .getPrivateKey(alias = "test",
                                 password = Some("not the password"))
                  .failed) { e =>
                e shouldBe a[UnrecoverableKey]

              }

            }

          }

        }

        "with an alias that does not exist in the wrapped KeyStore" - {

          "it returns Failure(AliasDoesNotExistOrIsUnexpectedType) explaining the problem" in new InitializedLocalStore {

            ScalaFutures.whenReady(
              localStore
                .getPrivateKey(alias = "fictional-key",
                               password = Some("irrelevant"))
                .failed) { e =>
              e shouldBe a[AliasDoesNotExistOrIsUnexpectedType]
            }

          }

        }

        "with an alias that exists in the wrapped KeyStore, but is not a PrivateKey" - {

          "it returns Failure(AliasExistsButIsUnexpectedType) explaining the problem" in new InitializedLocalStore {

            ScalaFutures.whenReady(localStore.getPrivateKey(
              alias = "test-secret",
              password = Some("test-test")).failed) { e =>
              e shouldBe a[AliasExistsButIsUnexpectedType]
              e.asInstanceOf[AliasExistsButIsUnexpectedType].alias shouldEqual "test-secret"
            }

          }

        }

      }

    }

  }

}
