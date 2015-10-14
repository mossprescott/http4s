package com.example.http4s.blaze

import org.http4s._
import org.http4s.server._
import org.http4s.server.blaze._
import org.http4s.dsl._

import scalaz._, Scalaz._
import scalaz.concurrent._
import scalaz.stream._

object ZipTest extends App {
  class Count { var n = 0 }
  val acquire = Task.delay {
    println("acquire")
    new Count
  }
  def release(r: Count): Task[Unit] = Task.delay {
    println(s"release: ${r.n}")
  }
  def step(r: Count): Task[String] = Task.delay {
    val n = r.n
    if (n < 100000) {
      r.n += 1
      s"This is line number $n\r\n"
    }
    else throw Cause.End.asThrowable
  }
  val p = io.resource(acquire)(release)(step)
  
  val svc = HttpService {
    case req =>
      Ok(p)
  }
  val zipped = middleware.GZip(svc)
  val srv = BlazeBuilder
    .bindHttp(8080, "0.0.0.0")
    .mountService(zipped)
    .start
    
  srv.run.awaitShutdown()
}