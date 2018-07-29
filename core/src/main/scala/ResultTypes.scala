package covenant.core

object ResultTypes {
  type Apply[F[_], T] = F[T]

  type WithState[State] = { type Apply[F[_], T] = State => F[T] }
}
