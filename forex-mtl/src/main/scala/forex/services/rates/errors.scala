package forex.services.rates

object errors {

  sealed trait Error
  object Error {
    final case class PairNotFound(pair: String) extends Error
    case object StaleRates extends Error
  }

}
