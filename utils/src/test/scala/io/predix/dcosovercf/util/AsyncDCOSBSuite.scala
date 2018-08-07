package io.predix.dcosb.util

import org.scalamock.scalatest.MockFactory
import org.scalatest.{AsyncFreeSpecLike, Matchers, OneInstancePerTest}

trait AsyncDCOSBSuite extends AsyncFreeSpecLike
  with OneInstancePerTest
  with MockFactory
  with Matchers {

}
