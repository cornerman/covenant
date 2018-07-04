package covenant

trait RequestType {
  type SingleF[F[+_], +ErrorType, +T] = F[Either[ErrorType, T]]
  type StreamF[F[+_], O[+_], +ErrorType, +T] = F[Either[ErrorType, O[T]]]
}
