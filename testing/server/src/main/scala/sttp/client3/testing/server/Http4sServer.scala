package sttp.client3.testing.server

import cats.effect._
import cats.implicits._
import org.http4s.CacheDirective._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.headers._
import org.http4s.implicits._
import org.http4s.server.AuthMiddleware
import org.http4s.server.blaze._
import org.http4s.server.middleware.authentication.BasicAuth
import org.http4s.util.CaseInsensitiveString

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

object Http4sServer extends IOApp {

  private def formToString(m: UrlForm): String =
    m.values.view.mapValues(_.foldLeft("")(_ + _)).toList.sortBy(_._1).map(p => s"${p._1}=${p._2}").mkString(" ")

  private def paramsToString(m: Map[String, String]): String =
    m.toList.sortBy(_._1).map(p => s"${p._1}=${p._2}").mkString(" ")

  val echo: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ _ -> Root / "echo" / "as_string" =>
      request.decode[UrlForm] { params =>
        Ok(formToString(params))
      }

    case request @ _ -> Root / "echo" / "as_params" =>
      request.decode[UrlForm] { params =>
        //todo encode as FormData
        Ok(formToString(params))
      }

    case request @ _ -> Root / "echo" / "headers" =>
      val encoded = request.headers.iterator.map(h => h.name + "->" + h.value).mkString(",")
      Ok(encoded)

    case request @ GET -> Root / "echo" =>
      val response = List("GET", "/echo", paramsToString(request.params))
        .filter(_.nonEmpty)
        .mkString(" ")
      Ok(response)

    case request @ POST -> Root / "echo" =>
      val response = List("POST", "/echo", paramsToString(request.params))
        .filter(_.nonEmpty)
        .mkString(" ")
      Ok(response)

    case request @ POST -> Root / "echo" / "custom_status" / IntVar(status) =>
      request.decode[String] { body =>
        val response = List("POST", s"/echo/custom_status/$status", body)
          .filter(_.nonEmpty)
          .mkString(" ")

        IO(Response(Status(status)).withEntity(response))
      }

    case request @ POST -> Root / "streaming" / "echo" =>
      request.decode[String] { body =>
        Ok(body)
      }
  }

  val headers: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "set_headers" =>
      val response = Response[IO](Status.Ok)
        .withEntity("ok")
        .withHeaders(`Cache-Control`(`max-age`(1000.seconds)), `Cache-Control`(`no-cache`()))
      IO(response)

    case request @ _ -> Root / "set_content_type_header_with_encoding_in_quotes" =>
      request.decode[String] { body =>
        Ok(body).map(_.withHeaders(Header("Content-Type", "text/plain; charset=\"UTF-8\"")))
      }
  }

  val cookies: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case _ -> Root / "cookies" / "set_with_expires" =>
      Ok("ok").map(
        _.addCookie(
          ResponseCookie(
            name = "c",
            content = "v",
            expires = Some(HttpDate.MinValue)
          )
        )
      )
    case request @ _ -> Root / "cookies" / "get_cookie2" =>
      request.cookies.find(_.name == "cookie2") match {
        case Some(c) => Ok(s"${c.name}=${c.content}")
        case None    => Ok("no cookie")
      }

    case _ -> Root / "cookies" / "set" =>
      Ok("ok").map(
        _.addCookie(
          ResponseCookie(
            name = "cookie1",
            content = "value1",
            secure = true,
            httpOnly = true,
            maxAge = Some(123L)
          )
        ).addCookie(
          ResponseCookie(
            name = "cookie3",
            content = "",
            domain = Some("xyz"),
            path = Some("a/b/c")
          )
        )
      )
  }

  val authMiddleware: AuthMiddleware[IO, String] = BasicAuth(
    "test realm",
    {
      case BasicCredentials("adam", "1234") => IO(Some("adam"))
      case _                                => IO(None)
    }
  )

  val authedService: AuthedRoutes[String, IO] =
    AuthedRoutes.of[String, IO] { case GET -> Root / "secure_basic" as user =>
      Ok(s"Welcome, $user")
    }

  val authed: HttpRoutes[IO] = authMiddleware(authedService)

  val secureDigest: HttpRoutes[IO] = HttpRoutes.of[IO] { case request @ GET -> Root / "secure_digest" =>
    request.headers.get(CaseInsensitiveString("Authorization")) match {
      case Some(authHeader) =>
        val correctHeader = authHeader.value.contains(
          """Digest algorithm=MD5,
              |cnonce=e5d93287aa8532c1f5df9e052fda4c38,
              |nc=00000001,
              |nonce="a2FzcGVya2FzcGVyCg==",
              |qop=auth,
              |realm=my-custom-realm,
              |response=f1f784de97f8badb4acec7c5f85eb877,
              |uri="/secure_digest",
              |username=adam""".stripMargin.replaceAll("\n", "")
        )

        if (correctHeader) {
          Ok()
        } else {
          IO(Response(Status.Unauthorized))
        }
      case None =>
        IO(
          Response(Status.Unauthorized).withHeaders(
            `WWW-Authenticate`(
              Challenge("Digest", "my-custom-realm", Map("qop" -> "auth", "nonce" -> "a2FzcGVya2FzcGVyCg=="))
            )
          )
        )
    }
  }

  val compression: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case _ -> Root / "compress-unsupported" =>
      Ok("I'm compressed!").map(_.withHeaders(`Content-Encoding`(ContentCoding.unsafeFromString("secret-encoding"))))
    case _ -> Root / "compress-custom" =>
      Ok("I'm compressed, but who cares! Must be overwritten by client encoder").map(
        _.withHeaders(`Content-Encoding`(ContentCoding.unsafeFromString("custom")))
      )
    case _ -> Root / "compress" =>
      Ok("I'm compressed!").map(
        _.withHeaders(`Content-Encoding`(ContentCoding.gzip))
      )
  }

  def run(args: List[String]): IO[ExitCode] =
    BlazeServerBuilder[IO](global)
      //todo set correct port
      .bindHttp(8080, "localhost")
      .withHttpApp((echo <+> headers <+> cookies <+> authed <+> secureDigest <+> compression).orNotFound)
      .serve
      .compile
      .drain
      .as(ExitCode.Success)

}