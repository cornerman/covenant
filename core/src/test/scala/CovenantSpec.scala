package test.covenant

import org.scalatest._

class CovenantSpec extends AsyncFreeSpec with MustMatchers {
  "true" in {
    true mustEqual true
  }
}
