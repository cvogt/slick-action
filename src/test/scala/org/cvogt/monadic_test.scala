package test.org.cvogt.action
import org.cvogt.monadic.functions._

import org.scalatest._
import org.scalautils.TypeCheckedTripleEquals
import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import org.cvogt.di.reflect.TMap
import scala.language.higherKinds

class MonadicTest extends FunSuite with TypeCheckedTripleEquals{
  class B
  test("monadic functions"){
    val a: Int with String => String = for{
      a <- (_:Int) => new B
      b <- (_:String) => new B
    } yield "test"

    val b: TMap[Int with String] => Unit = for{
      _ <- (i:TMap[Int]) => "test"
      _ <- (i:TMap[String]) => "test"
    } yield ()

    val l: Int => (List[Int],List[Int]) = for{
      i <- (i:Int) => List(1,3)
      j <- (i:Int) => List(5,9)
    } yield (i,j)
    assert((List(1,3),List(5,9)) === l(5))

    ;{
      import org.cvogt.monadic.higherKindedFunctions._
      import scala.concurrent.ExecutionContext.Implicits.global
      val l1: List[(Int,Int)] = for{
        i <- List(1,3)
        j <- List(5,9)
      } yield (i,j)

      val l: Int => List[(Int,Int)] = for{
        i <- (i:Int) => List(1,3)
        j <- (i:Int) => List(5,9)
      } yield (i,j)
      assert(l1 === l(5))

      val f: Int with String => Future[String] = for{
        i <- (i:Int) => Future("test")
        j <- (i:String) => Future("test")
      } yield i + j

      val tf: TMap[Int with String] => Future[String] = for{
        i <- (i:TMap[Int]) => Future("test")
        j <- (i:TMap[String]) => Future("test")
      } yield i + j

      val tf2: TMap[Int with String] => Future[String] = for{
        i <- ((i:TMap[Int]) => "test").async
        j <- (i:TMap[String]) => Future("test")
      } yield i + j
      val f2: Int with String => Future[String] = for{
        i <- ((i:Int) => "test").map(Future(_))
        j <- (i:String) => Future("test")
      } yield i + j
      val f3: Int with String => Future[String] = for{
        i <- ((i:Int) => Future(5)).direct.map((f:Future[Int]) => f.map(_+6))
        j <- (i:String) => Future("test")
      } yield i + j
      assert("11test" === Await.result(f3(null.asInstanceOf[Int with String]),10.seconds))
    }
    val t2: TMap[TMap[Int with String]] => String = for{
      i <- ((i:Int) => "test").tMap.tMap
      j <- ((i:TMap[String]) => "test").tMap
    } yield i + j

    val t3: Int with String => String = for{
      i <- ((i:Int) => "test")
      j <- ((i:TMap[String]) => "test").unTMap
    } yield i + j
  }
  /*
  test("clean type signatures"){
    import scala.reflect.runtime.universe._
    val y = for{
      _ <- (_:String) => new B
      _ <- (_:String) => new B
      _ <- (_:String) => new B
      _ <- (_:String) => new B
    } yield "test"
    def tt[T](t: T)(implicit tt: TypeTag[T]) = tt
    assert(
      "String => java.lang.String" === tt(y).tpe.toString
    )
  }
  */
  test("test first failed future wins"){
    intercept[Exception](
      Await.result((Future failed new Exception("test")).map(_ => 5), 10.seconds)
    )
  }
}
