package io.predix.dcosb.util.encryption

import java.net.URI
import java.security.PrivateKey
import java.util.concurrent.TimeUnit

import akka.actor.{ActorInitializationException, Props}
import akka.testkit.{CallingThreadDispatcher, TestActorRef}
import akka.util.Timeout
import akka.pattern.ask
import com.typesafe.config.ConfigFactory
import io.predix.dcosb.util.ActorSuite
import io.predix.dcosb.util.actor.ConfiguredActor
import io.predix.dcosb.util.encryption.AkkaKeyStoreManager.Configuration.LocalStoreConfiguration
import io.predix.dcosb.util.encryption.aksm.Store
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

class AkkaKeyStoreManagerTest extends ActorSuite {
  implicit val executionContext =
    system.dispatchers.lookup(CallingThreadDispatcher.Id)
  implicit val timeout = Timeout(FiniteDuration(5, TimeUnit.SECONDS))

  trait TestStoreConfiguration {
    import com.typesafe.config.ConfigFactory.parseString

    val config =
      ConfigFactory.load(parseString("""test {
                                       |
                                       |  akka-keystore-manager: {
                                       |
                                       |    stores: [
                                       |      {
                                       |        type: "local"
                                       |        id: "test"
                                       |        uri: "pkcs12:keystore.pkcs12"
                                       |        password: "test-test"
                                       |      }
                                       |    ]
                                       |
                                       |  }
                                       |
                                       |}""".stripMargin))

  }

  "The AkkaKeyStoreManager.parseConfiguration() method" - {

    "given a typesafe Config object that parses correctly to AKMConfiguration" - {

      "produces an AKMConfiguration instance with the key stores from the typesafe Config object" in new TestStoreConfiguration {
        import AkkaKeyStoreManager.Configuration._

        val akmConfig =
          AkkaKeyStoreManager.parseConfiguration(
            config.getConfig("test.akka-keystore-manager"))

        akmConfig shouldEqual AKMConfiguration(
          stores = Seq(
            LocalStoreConfiguration(id = "test",
                                    uri = new URI("pkcs12:keystore.pkcs12"),
                                    password = Some("test-test"))))

      }

    }

  }

  "AkkaKeyStoreManager" - {

    trait UnconfiguredAkkaKeyStoreManager {

      val akm =
        TestActorRef[AkkaKeyStoreManager](Props(classOf[AkkaKeyStoreManager]))

    }

    trait StoreMock {

      val mockStore = mock[Store[LocalStoreConfiguration]]
      (mockStore.id _) expects () returning Some("test") twice ()
      (mockStore.default _) expects () returning true

    }

    trait ConfiguredAkkaKeyStoreManager
        extends UnconfiguredAkkaKeyStoreManager {
      import AkkaKeyStoreManager.Configuration._

      def configuredAkm(configuration: ActorConfiguration)
        : TestActorRef[AkkaKeyStoreManager] = {

        Await.result(akm ? configuration, timeout.duration) match {
          case Success(ConfiguredActor.Configured()) => akm
          case Failure(e: Throwable)                 => throw e
        }

      }

    }

    "on receiving an ActorConfiguration message" - {
      import AkkaKeyStoreManager.Configuration._
      import io.predix.dcosb.util.encryption.aksm.Store

      trait StoreStub {

        val stubStore = stub[Store[LocalStoreConfiguration]]
        (stubStore.id _) when () returns Some("stub-store")
        (stubStore.default _) when () returns true

      }

      "with a non-empty Seq of Stores" - {

        "it responds with Success(Configured)" in new UnconfiguredAkkaKeyStoreManager
        with StoreStub {

          Await.result(akm ? ActorConfiguration(List(stubStore)),
                       timeout.duration) shouldEqual Success(
            ConfiguredActor.Configured())

        }

      }

      "with an empty Seq of Stores" - {

        "it responds with Failure(IllegalArgumentException), describing the problem" in new UnconfiguredAkkaKeyStoreManager {

          Await.result(akm ? ActorConfiguration(initializedStores = Seq.empty),
                       timeout.duration) shouldEqual Failure(
            ConfigurationError("No stores received in configuration"))

        }

      }

    }

    "on receiving a GetPrivateKey message" - {

      "for an existing storeId" - {

        "it invokes the getPrivateKey() on the Store object under the storeId" in new ConfiguredAkkaKeyStoreManager
        with StoreMock {

          (mockStore.getPrivateKey _) expects ("test", *) returning (Future
            .successful(mock[PrivateKey]))

          Await.result(
            configuredAkm(AkkaKeyStoreManager.Configuration.ActorConfiguration(
              List(mockStore))) ? AkkaKeyStoreManager.GetPrivateKey("test",
                                                                    None),
            timeout.duration)

        }

        "it responds with the return value of it's underlying store implementations' getPrivateKey()" in new ConfiguredAkkaKeyStoreManager
        with StoreMock {

          (mockStore.getPrivateKey _) expects ("test", *) returning (Future
            .successful(mock[PrivateKey]))

          ScalaFutures.whenReady(
            configuredAkm(AkkaKeyStoreManager.Configuration.ActorConfiguration(
              List(mockStore))) ? AkkaKeyStoreManager.GetPrivateKey("test",
                                                                    None)) {
            k =>
              k shouldBe a[Success[_]]
              k.asInstanceOf[Success[_]].get shouldBe a[PrivateKey]
          }

        }

      }

      "for a non-existent storeid" - {}

    }

  }

}
