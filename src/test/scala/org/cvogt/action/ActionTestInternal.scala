package org.cvogt.action.test
import org.scalatest._
import org.scalautils.TypeCheckedTripleEquals
import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global

class ActionTestInternal extends FunSuite with TypeCheckedTripleEquals{
  import org.cvogt.action._
  import org.cvogt.action.slick._
  import scala.slick.driver.H2Driver
  lazy val slickAction = SlickActionExtension(H2Driver)
  import slickAction.action._

  test("test rw/transaction tracking"){
    val config = new DatabaseConfig(null)

    val writeAction = Action[Write,Int](_ => 5)
    val readAction = Action[Read,Int](_ => 99)


    val readActionAsync =
    readAction.async: Action[Read with Async, Future[Int]]
    
    val testTransactionsWithAsync =
    transaction(readAction): Action[Read, Int]
    assertTypeError("transaction(readActionAsync)")
    
    val readSingleConnection =
    sameConnection(readAction): Action[Read, Int]
    assertTypeError("sameConnection(readActionAsync)")

    val _____ =
    readAction: Action[Read,Int]
    val readTransaction = transaction(readAction): Action[Read,Int]
    val writeTransaction = transaction(writeAction): Action[Write,Int]

    val a: Action[Write with Read, Int] = 
      for{
        i <- writeAction
        j <- readAction
      } yield i + j

    assert(104 === a.run(new ActionContext[Write with Read](Seq())))

    transaction(a): Action[Read with Write,Int]
    
    // preventing read transaction on Write Action
    intercept[NoSuchElementException]{
      transaction(a).run(new ActionContext[Write with Read](Seq()))
    }
    assertTypeError("a: Action[Read, Int]")
  }
}
