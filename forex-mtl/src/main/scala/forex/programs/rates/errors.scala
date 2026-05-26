package forex.programs.rates

import forex.services.rates.errors.{ Error => RatesServiceError }

object errors {

  sealed trait Error extends Exception
  object Error {
    final case class PairNotFound(pair: String) extends Error
    case object StaleRates extends Error
  }

  def toProgramError(error: RatesServiceError): Error = error match {
    case RatesServiceError.PairNotFound(pair) => Error.PairNotFound(pair)
    case RatesServiceError.StaleRates         => Error.StaleRates
  }
}
