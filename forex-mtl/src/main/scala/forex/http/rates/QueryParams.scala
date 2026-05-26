package forex.http.rates

import forex.domain.Currency
import org.http4s.{ ParseFailure, QueryParamDecoder }
import org.http4s.dsl.impl.ValidatingQueryParamDecoderMatcher

object QueryParams {

  private[http] implicit val currencyQueryParam: QueryParamDecoder[Currency] =
    QueryParamDecoder[String].emap(s => Currency.fromString(s).left.map(ParseFailure(_, s)))

  object FromQueryParam extends ValidatingQueryParamDecoderMatcher[Currency]("from")
  object ToQueryParam extends ValidatingQueryParamDecoderMatcher[Currency]("to")

}
