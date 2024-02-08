import zio._
import zio.test._
import zio.test.Assertion._

import java.io.IOException

object HelloWorld {
  def sayHello: ZIO[Any, IOException, Unit] =
    Console.printLine("Hello, World!")
}

import HelloWorld._

object HelloWorldSpec extends ZIOSpecDefault {
  def spec = suite("HelloWorldSpec") (
    test("sayHello correctly displays output") {
      for {
        _ <- sayHello
        output <- TestConsole.output
      } yield assertTrue(output == Vector("Hello, World!\n"))
    }
  )
}

object AssertionsSpec extends ZIOSpecDefault {
  def spec = suite("AssertionsSpec") (
    suite ("Suite 1 with 2 classic-assertion tests") (
      test("Nested and logically composed assertions") {
        val x = Right(Some(7))
        val y = 1 + 1
        assert(x)(isRight(isSome(equalTo(7)))) && assert(y)(equalTo(2))
      },
      test("Fourth value of a vector is equal to 3") {
        val xs = Vector(1, 3, 5, 7)
        assert(xs)(hasAt(3)(equalTo(7)))
      }
    ),
    suite ("Suite 2 with 3 smart-assertion tests") (
      test("Assertion on ordinary value: 1 + 1 == 2") {
        val x = 1 + 1
        assertTrue(x == 2)
      },
      test("Assertion on ordinary value: fourth value of a vector is equal to 3") {
        val xs = Vector(1, 3, 5, 7)
        assertTrue(xs(3) == 7)
      },
      test ("Assertion on effectual value: update successfully increments a Ref[Int]") {
        for {
          r <- Ref.make(0)
          _ <- r.update(_ + 1)
          v <- r.get
        } yield assertTrue(v == 1)
      }
    )
  ) @@ TestAspect.sequential
}

object AspectsSpec extends ZIOSpecDefault {
  def spec = suite("AspectsSpec") (
    test("Aspects example") {
      assertTrue(1 + 1 == 2)
    } @@ TestAspect.jvmOnly @@ TestAspect.repeat(Schedule.recurs(6))
  )
}

case class Counter(value: Ref[Int]) {
  def inc: UIO[Unit] = value.update(_ + 1)
  def get: UIO[Int] = value.get
}

object Counter {
  val layer =
    ZLayer.scoped(
      ZIO.acquireRelease
      (Ref.make(0).map(Counter(_)) <* ZIO.debug("Counter message - initialized!"))
      (c => c.get.debug("Counter message - number of tests executed"))
    )
  def inc = ZIO.service[Counter].flatMap(_.inc)
}

object CounterSpec extends ZIOSpecDefault {
  def spec = {
    suite("Spec1")(
      test("test1") {
        assertTrue(true)
      } @@ TestAspect.after(Counter.inc),
      test("test2") {
        assertTrue(true)
      } @@ TestAspect.after(Counter.inc)
    ) +
      suite("Spec2") {
        test("test1") {
          assertTrue(true)
        } @@ TestAspect.after(Counter.inc)
      }
  }.provideShared(Counter.layer)
}
