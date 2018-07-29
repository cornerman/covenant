package covenant

object RequestType {
  type SingleF[F[+_], +ErrorType, +T] = F[Either[ErrorType, T]]
  type StreamF[F[+_], O[+_], +ErrorType, +T] = SingleF[F, ErrorType, O[T]]
}
