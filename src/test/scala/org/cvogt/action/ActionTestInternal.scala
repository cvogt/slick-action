package org.cvogt.action.slick.test
import org.scalatest._
import org.scalautils.TypeCheckedTripleEquals
import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import org.cvogt.di.reflect.TMap
import org.cvogt.monadic.functions._

class ActionTestInternal extends FunSuite with TypeCheckedTripleEquals{
  import org.cvogt.action._
  import org.cvogt.action.slick._
  import scala.slick.driver.H2Driver
  lazy val slickAction = SlickActionExtension(H2Driver)
  import slickAction.action._

  test("test rw/transaction tracking"){
    val config = DatabaseConfig(null)

    val writeAction = SlickAction[Write,Int](_ => 5)
    val readAction = SlickAction[Read,Int](_ => 99)

    val readActionAsync =
    readAction.async: Read => Future[Int]
    
    val testTransactionsWithAsync =
    transaction(readAction): SlickAction[Read, Int]
    assertTypeError("transaction(readActionAsync)")
    
    val readSingleConnection =
    sameConnection(readAction): SlickAction[Read, Int]
    assertTypeError("sameConnection(readActionAsync)")

    val _____ =
    readAction: SlickAction[Read,Int]
    val readTransaction = transaction(readAction): SlickAction[Read,Int]
    val writeTransaction = transaction(writeAction): SlickAction[Write,Int]

    val a: SlickAction[Write with Read, Int] = 
      for{
        i <- writeAction
        j <- readAction
      } yield i + j

    assert(104 === a(null:Write with Read))

    transaction(a): SlickAction[Read with Write,Int]
    
    // preventing read transaction on Write Action
    intercept[NullPointerException]{
      transaction(a).apply(null:Write with Read)
    }
    assertTypeError("a: Action[Read, Int]")
  }
}
