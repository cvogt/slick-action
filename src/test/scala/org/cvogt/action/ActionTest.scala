package test.org.cvogt.action
import org.scalatest._
import org.scalautils.TypeCheckedTripleEquals
import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import org.cvogt.di.reflect._
import org.cvogt.monadic.functions._

class ActionTest extends FunSuite with TypeCheckedTripleEquals{
  import org.cvogt.action._
  import org.cvogt.action.slick._
  import scala.slick.driver.H2Driver
  lazy val slickAction = SlickActionExtension(H2Driver)
  import slickAction.action._

  val noDb = DatabaseConfig(null)

  class Translations(tag: Tag) extends Table[(Option[Int], String)](tag, "translations") {
    def id = column[Int]("id")

    val textkey: Column[String] = column[String]("textkey")

    def * = (id.?, textkey)
  }
  val translations = TableQuery[Translations]

  test("test usage with db and slick actions"){
    val db = Database.forURL(
      "jdbc:h2:mem:action_monad", driver = "org.h2.Driver"
    )
    val config = DatabaseConfig(db)

    val a = for{
      _ <- translations.ddl.create
      _ <- translations.insert((Option(5),"test"))
      _ <- translations.insert((Option(3),"test3"))
      _ <- translations.filter(_.id===3).delete
      _ <- translations.update((Option(6),"test2"))
      r <- translations.list
    } yield r
    val expected = Seq((Option(6),"test2"))
    assert(
      expected === sameConnection(a).apply(config)
    )
    assert(
      expected === transaction(a).apply(config)
    )

    assert{
      expected === Await.result(transaction(a).async.apply(config), 10.seconds)
    }
    intercept[org.h2.jdbc.JdbcSQLException]{
      // runs queries in individual sessions, where h2 does not keep the db
      expected === a(config)
    }
  }

  test("non-slick-action-composition"){
    val res = SlickAction.const(5).direct.flatMap( i =>
      SlickAction.const(7).map( j => 
        i+j
      )
    )
    
    assert( 12 === res(()) )
  }

  test("test lift seq action"){
    val actions = Seq[SlickAction[Any,Int]](
      SlickAction.const(5),
      SlickAction.const(7),
      SlickAction.const(99)
    )
    assert(Seq(5,7,99) === SlickAction.sequence(actions)(noDb))
  }

  test("asTry"){
    assert(
      None === SlickAction.const(throw new Exception()).asTry(noDb).toOption
    )
  }

  test("mixed actions"){
    val db = DatabaseConfig(Database.forURL(
      "jdbc:h2:mem:action_monad", driver = "org.h2.Driver"
    ))
    val a = for{
      _ <- new unsafe[Read].JDBC(_.getAutoCommit).tMap.async
      _ <- ((_:Any) => 5).tMap.async
      _ <- (_:TMap[Int]) => Future successful 5
    } yield ()

    a(TMap(db) ++ TMap(123))

    val d = new Extend_Any_Any((_:Any) => 5).flatMap(_ => (_:Int) => 5)
    d(6)

    trait A
    trait B
    // this seems to be a Scalac limitation
    assertTypeError("""
      val c = for{
        a <- (_:Any) => 5
        s <- (_:B) => a
      } yield (s)
    """)
    //c(new A with B{})

    val b = for{
      _ <- new unsafe[Read].JDBC(_.getAutoCommit).async
      _ <- ((_:Read) => 5).async
      _ <- (_:Read) => Future successful 5
    } yield ()

    b(db)
  }

  test("jdbc connection side-effects"){
    val db = DatabaseConfig(Database.forURL(
      "jdbc:h2:mem:action_monad", driver = "org.h2.Driver"
    ))
    val a = (for{
      autoCommit <- unsafe[Read].JDBC(_.getAutoCommit)
      _ <- unsafe[Read].JDBC(_.setAutoCommit(!autoCommit))
      newAutoCommit <- unsafe[Read].JDBC(_.getAutoCommit)
    } yield (autoCommit,newAutoCommit))

    // side-effect does not survive between connections
    assert(
      a(db) match { case (old,_new) => old === _new }
    )

    // side-effect survives within a single connection
    assert(
      sameConnection(a).apply(db) match { case (old,_new) => old !== _new }
    )

    // side-effect survives within a transaction
    assert(
      transaction(a).apply(db) match { case (old,_new) => old !== _new }
    )
  }

  test("test master/slave selection"){
    val master = Database.forURL(
      "jdbc:h2:mem:action_monad_master;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver"
    )
    val slave = Database.forURL(
      "jdbc:h2:mem:action_monad_slave;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver"
    )

    ;{
      val config = DatabaseConfig(master)
      (for{
        _ <- translations.ddl.create
        _ <- translations.insert((Option(1),"master"))
      } yield ()) apply config
    }
    ;{
      val config = DatabaseConfig(slave)
      (for{
        _ <- translations.ddl.create
        _ <- translations.insert((Option(1),"slave"))
      } yield ()) apply config
    }

    val config = MasterSlaveConfig(master,slave)
    assert(
      // lonely reads go against slave
      "slave" === ((for{
        s <- translations.filter(_.id === 1).map(_.textkey).list
      } yield s) apply config).head
    )
    assert(
      // mixed reads and writes, writes go to master, reads go to slave
      "slave" === ((for{
        _ <- translations.filter(_.id === 1).delete
        _ <- translations.insert((Option(1),"master"))
        s <- translations.filter(_.id === 1).map(_.textkey).list
      } yield s) apply config).head
    )
    assert(
      // multiple reads using same connection, all go to slave
      ("slave","slave") === (sameConnection(for{
        t <- translations.filter(_.id === 1).map(_.textkey).first
        s <- translations.filter(_.id === 1).map(_.textkey).first
      } yield (s,t)) apply config)
    )
    // TODO: the following test should validate where the inserts go
    assert(
      // mixed reads and writes using same connection, all go to master
      "master" === (sameConnection(for{
        _ <- translations.insert((Option(3),"foo"))
        s <- translations.filter(_.id === 1).map(_.textkey).list
      } yield s) apply config).head
    )
    assert(
      // mixed reads and writes using transaction, all go to master
      "master" === (transaction(for{
        _ <- translations.insert((Option(4),"foo"))
        s <- translations.filter(_.id === 1).map(_.textkey).list
      } yield s) apply config).head
    )
  }

  case class DB1(db: Read with Write) extends TMapDemux(db)
  case class DB2(db: Read with Write) extends TMapDemux(db)
  test("multi db select"){
    val db1 = DatabaseConfig(Database.forURL(
      "jdbc:h2:mem:db1;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver"
    ))
    val db2 = DatabaseConfig(Database.forURL(
      "jdbc:h2:mem:db2;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver"
    ))
    ;{
      (for{
        _ <- translations.ddl.create
        _ <- translations.insert((Option(1),"db1"))
      } yield ()) apply db1
    }
    ;{
      (for{
        _ <- translations.ddl.create
        _ <- translations.insert((Option(1),"db2"))
      } yield ()) apply db2
    }

    val multi = TMap(DB1(db1)) ++ TMap(DB2(db2))
    val a = { 
      val a = (tmap:TMap[DB1]) => translations.list(tmap[DB1].db)
      assert(
        List((Some(1),"db1"))
        === a(multi)
      )
      a
    }
    val b = {
      val b = using[DB2].apply(translations.list)
      assert(
        List((Some(1),"db2"))
        === b(multi)
      )
      b
    }

    val ab = for{
      a0 <- a
      a1 <- using[DB1].apply(translations.ddl.drop)
      b0 <- b
    } yield a0 ++ b0
    assert(
      List((Some(1),"db1"),(Some(1),"db2"))
      === ab(multi)
    )
  }
}
