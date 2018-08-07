package io.predix.dcosb.dcos.security

import io.predix.dcosb.dcos.security.TokenKeeper.DCOSAuthorizationTokenHeader
import io.predix.dcosb.util.DCOSBSuite
import org.scalatest.OneInstancePerTest

class TokenKeeperTest extends DCOSBSuite with OneInstancePerTest {

  "Two DCOSAuthorizationTokenHeader instances with matching string tokens" - {

    val one = DCOSAuthorizationTokenHeader("foo")
    val two = DCOSAuthorizationTokenHeader("foo")

    "should be equal" in {

      one shouldEqual two

    }

  }

}
