package test.covenant

import org.scalatest._
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers

class CovenantSpec extends AsyncFreeSpec with Matchers {
  "true" in {
    true mustEqual true
  }
}
