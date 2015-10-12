package org.http4s.blaze.util

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.Headers
import org.http4s.blaze.TestHead
import org.http4s.blaze.pipeline.{LeafBuilder, TailStage}
import org.http4s.util.StringWriter
import org.specs2.mutable.Specification
import scodec.bits.ByteVector

import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global
import scalaz.\/-

import scalaz.concurrent.Task
import scalaz.stream.{Cause, Process}



class ProcessWriterSpec extends Specification {

  def writeProcess(p: Process[Task, ByteVector])(builder: TailStage[ByteBuffer] => ProcessWriter): String = {
    val tail = new TailStage[ByteBuffer] {
      override def name: String = "TestTail"
    }

    val head = new TestHead("TestHead") {
      override def readRequest(size: Int): Future[ByteBuffer] =
        Future.failed(new Exception("Head doesn't read."))
    }

    LeafBuilder(tail).base(head)
    val w = builder(tail)

    w.writeProcess(p).run
    head.stageShutdown()
    Await.ready(head.result, Duration.Inf)
    new String(head.getBytes(), StandardCharsets.ISO_8859_1)
  }

  val message = "Hello world!"
  val messageBuffer = ByteVector(message.getBytes(StandardCharsets.ISO_8859_1))

  def runNonChunkedTests(builder: TailStage[ByteBuffer] => ProcessWriter) = {
    import scalaz.stream.Process
    import scalaz.stream.Process._
    import scalaz.stream.Cause.End

    "Write a single emit" in {
      writeProcess(emit(messageBuffer))(builder) must_== "Content-Length: 12\r\n\r\n" + message
    }

    "Write two emits" in {
      val p = emit(messageBuffer) ++ emit(messageBuffer)
      writeProcess(p)(builder) must_== "Content-Length: 24\r\n\r\n" + message + message
    }

    "Write an await" in {
      val p = Process.eval(Task(messageBuffer))
      writeProcess(p)(builder) must_== "Content-Length: 12\r\n\r\n" + message
    }

    "Write two awaits" in {
      val p = Process.eval(Task(messageBuffer))
      writeProcess(p ++ p)(builder) must_== "Content-Length: 24\r\n\r\n" + message + message
    }

    "Write a Process that fails and falls back" in {
      val p = Process.await(Task.fail(new Exception("Failed")))(identity).onFailure { _ =>
        emit(messageBuffer)
      }
      writeProcess(p)(builder) must_== "Content-Length: 12\r\n\r\n" + message
    }

    "execute cleanup processes" in {
      var clean = false
      val p = emit(messageBuffer).onComplete(eval_(Task {
          clean = true
        }))

      writeProcess(p)(builder) must_== "Content-Length: 12\r\n\r\n" + message
      clean must_== true
    }

    "Write tasks that repeat eval" in {
      val t = {
        var counter = 2
        Task {
          counter -= 1
          if (counter >= 0) ByteVector("foo".getBytes(StandardCharsets.ISO_8859_1))
          else throw Cause.Terminated(Cause.End)
        }
      }
      val p = Process.repeatEval(t) ++ emit(ByteVector("bar".getBytes(StandardCharsets.ISO_8859_1)))
      writeProcess(p)(builder) must_== "Content-Length: 9\r\n\r\n" + "foofoobar"
    }
  }


  "CachingChunkWriter" should {
    runNonChunkedTests(tail => new CachingChunkWriter(new StringWriter(), tail, Task.now(Headers())))
  }

  "CachingStaticWriter" should {
    runNonChunkedTests(tail => new CachingChunkWriter(new StringWriter(), tail, Task.now(Headers())))
  }

  "ChunkProcessWriter" should {
    import scalaz.stream.Process._
    import scalaz.stream.Cause.End

    def builder(tail: TailStage[ByteBuffer]) =
      new ChunkProcessWriter(new StringWriter(), tail, Task.now(Headers()))

    "Not be fooled by zero length chunks" in {
      val p1 = Process(ByteVector.empty, messageBuffer)
      writeProcess(p1)(builder) must_== "Content-Length: 12\r\n\r\n" + message

      // here we have to use awaits or the writer will unwind all the components of the emitseq
      val p2 = Process.await(Task(emit(ByteVector.empty)))(identity) ++
         Process(messageBuffer) ++ Process.eval(Task(messageBuffer))

      writeProcess(p2)(builder) must_== "Transfer-Encoding: chunked\r\n\r\n" +
        "c\r\n" +
        message + "\r\n" +
        "c\r\n" +
        message + "\r\n" +
        "0\r\n" +
        "\r\n"
    }

    "Write a single emit with length header" in {
      writeProcess(emit(messageBuffer))(builder) must_== "Content-Length: 12\r\n\r\n" + message
    }

    "Write two emits" in {
      val p = emit(messageBuffer) ++ emit(messageBuffer)
      writeProcess(p)(builder) must_== "Transfer-Encoding: chunked\r\n\r\n" +
        "c\r\n" +
        message + "\r\n" +
        "c\r\n" +
        message + "\r\n" +
        "0\r\n" +
        "\r\n"
    }

    "Write an await" in {
      val p = Process.eval(Task(messageBuffer))
      writeProcess(p)(builder) must_== "Content-Length: 12\r\n\r\n" + message
    }

    "Write two awaits" in {
      val p = Process.eval(Task(messageBuffer))
      writeProcess(p ++ p)(builder) must_== "Transfer-Encoding: chunked\r\n\r\n" +
        "c\r\n" +
        message + "\r\n" +
        "c\r\n" +
        message + "\r\n" +
        "0\r\n" +
        "\r\n"
    }

    // The Process adds a Halt to the end, so the encoding is chunked
    "Write a Process that fails and falls back" in {
      val p = Process.await(Task.fail(new Exception("Failed")))(identity).onFailure { _ =>
        emit(messageBuffer)
      }
      writeProcess(p)(builder) must_== "Transfer-Encoding: chunked\r\n\r\n" +
        "c\r\n" +
        message + "\r\n" +
        "0\r\n" +
        "\r\n"
    }

    "execute cleanup processes" in {
      var clean = false
      val p = emit(messageBuffer).onComplete {
        clean = true
        Halt(End)
      }
      writeProcess(p)(builder) must_== "Transfer-Encoding: chunked\r\n\r\n" +
        "c\r\n" +
        message + "\r\n" +
        "0\r\n" +
        "\r\n"
      clean must_== true
    }

    // Some tests for the raw unwinding process without HTTP encoding.
    "write a deflated stream" in {
      val p = eval(Task(messageBuffer)) |> scalaz.stream.compress.deflate()
      DumpingWriter.dump(p) must_== p.runLog.run.foldLeft(ByteVector.empty)(_ ++ _)
    }

    val resource = scalaz.stream.io.resource(Task.delay("foo"))(_ => Task.now(())){ str =>
        val it = str.iterator
        Task.delay {
          if (it.hasNext) ByteVector(it.next)
          else throw Cause.Terminated(Cause.End)
        }
      }

    "write a resource" in {
      val p = resource
      DumpingWriter.dump(p) must_== p.runLog.run.foldLeft(ByteVector.empty)(_ ++ _)
    }

    "write a deflated resource" in {
      val p = resource |> scalaz.stream.compress.deflate()
      DumpingWriter.dump(p) must_== p.runLog.run.foldLeft(ByteVector.empty)(_ ++ _)
    }
    
    "write a large deflated resource" in {
      val Size = 200*1000
      
      class Count { var n = 0 }
      val acquire = Task.delay {
        val c = new Count
        println(s"acquire: $c")
        c
      }
      def release(c: Count) = Task.delay {
        println(s"release: ${c.n}")
        ()
      }
      def step(c: Count) = Task.delay {
        val n = c.n
        c.n += 1
        if ((n % (10*1000)) == 0) {
          println(s"step: $n")
        }
        if (n < Size) ByteVector.view(new Array[Byte](0))
        else throw Cause.End.asThrowable
      }
      
      val rsrc = scalaz.stream.io.resource(acquire)(release)(step)
      
      val p = rsrc |> scalaz.stream.compress.deflate()
      // DumpingWriter.dump(p) must_== p.runLog.run.foldLeft(ByteVector.empty)(_ ++ _)
      DumpingWriter.dump(p) must_== ByteVector.view(Array.fill[Byte](8)(0))
    }
    
    "ProcessWriter must be stack safe" in {
      val p = Process.repeatEval(Task.async[ByteVector]{ _(\/-(ByteVector.empty))}).take(300000)

      // the scalaz.stream built of Task.async's is not stack safe
      p.run.run must throwA[StackOverflowError]

      // The dumping writer is stack safe when using a trampolining EC
      DumpingWriter.dump(p) must_== ByteVector.empty
    }
  }
}
