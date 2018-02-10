package covenant.core

trait Common {
  type PathName = sloth.core.PathName

  type Router[PickleType, Result[_]] = sloth.server.Router[PickleType, Result]
  val Router = sloth.server.Router

  type Request[PickleType] = sloth.core.Request[PickleType]
  val Request = sloth.core.Request
}
