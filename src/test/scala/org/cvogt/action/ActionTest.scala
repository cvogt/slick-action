package test.org.cvogt.action
import org.scalatest._
import org.scalautils.TypeCheckedTripleEquals
import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global

class ActionTest extends FunSuite with TypeCheckedTripleEquals{
  import org.cvogt.action._
  import org.cvogt.action.slick._
  import scala.slick.driver.H2Driver
  lazy val slickAction = SlickActionExtension(H2Driver)
  import slickAction.action._

  val noDb = new DatabaseConfig(null)

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
    val config = new DatabaseConfig(db)

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
      expected === sameConnection(a).run(config)
    )
    assert(
      expected === transaction(a).run(config)
    )
    assert{
      expected === Await.result(transaction(a).async.run(config), 10.seconds)
    }
    intercept[org.h2.jdbc.JdbcSQLException]{
      // runs queries in individual sessions, where h2 does not keep the db
      expected === a.run(config)
    }
  }

  test("test lift seq action"){
    val action = Seq[Action[Any,Int]](
      Action.const(5),
      Action.const(7),
      Action.const(99)
    )
    assert(Seq(5,7,99) === action.sequence.run(noDb))
    assert(Seq(5,7,99) === action.sequenceAsync.run(noDb))
  }

  test("asTry"){
    assert(
      None === Action.const(throw new Exception()).asTry.run(noDb).toOption
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
      val config = new DatabaseConfig(master)
      (for{
        _ <- translations.ddl.create
        _ <- translations.insert((Option(1),"master"))
      } yield ()) run config
    }
    ;{
      val config = new DatabaseConfig(slave)
      (for{
        _ <- translations.ddl.create
        _ <- translations.insert((Option(1),"slave"))
      } yield ()) run config
    }

    val config = new MasterSlaveConfig(master,slave)
    assert(
      // lonely reads go against slave
      "slave" === ((for{
        s <- translations.filter(_.id === 1).map(_.textkey).list
      } yield s) run config).head
    )
    assert(
      // mixed reads and writes, writes go to master, reads go to slave
      "slave" === ((for{
        _ <- translations.filter(_.id === 1).delete
        _ <- translations.insert((Option(1),"master"))
        s <- translations.filter(_.id === 1).map(_.textkey).list
      } yield s) run config).head
    )
    assert(
      // multiple reads using same connection, all go to slave
      ("slave","slave") === (sameConnection(for{
        t <- translations.filter(_.id === 1).map(_.textkey).first
        s <- translations.filter(_.id === 1).map(_.textkey).first
      } yield (s,t)) run config)
    )
    // TODO: the following test should validate where the inserts go
    assert(
      // mixed reads and writes using same connection, all go to master
      "master" === (sameConnection(for{
        _ <- translations.insert((Option(3),"foo"))
        s <- translations.filter(_.id === 1).map(_.textkey).list
      } yield s) run config).head
    )
    assert(
      // mixed reads and writes using transaction, all go to master
      "master" === (transaction(for{
        _ <- translations.insert((Option(4),"foo"))
        s <- translations.filter(_.id === 1).map(_.textkey).list
      } yield s) run config).head
    )
  }

  test("multi db select"){
    val db1 = new DatabaseConfig(Database.forURL(
      "jdbc:h2:mem:db1;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver"
    )).demux
    val db2 = new DatabaseConfig(Database.forURL(
      "jdbc:h2:mem:db2;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver"
    )).demux
    ;{
      (for{
        _ <- translations.ddl.create
        _ <- translations.insert((Option(1),"db1"))
      } yield ()) run db1.inner
    }
    ;{
      (for{
        _ <- translations.ddl.create
        _ <- translations.insert((Option(1),"db2"))
      } yield ()) run db2.inner
    }

    val multi = db1.config ++ db2.config
    import scala.reflect.ClassTag
    val a = {
      val a: Action[db1.IO[Read],List[(Option[Int],String)]] = using(db1.selector)(translations.list)
      assert(
        List((Some(1),"db1"))
        === a.run(multi)
      )
      a
    }
    val b = {
      val b: Action[db2.IO[Read],List[(Option[Int],String)]] = using(db2.selector)(translations.list)
      assert(
        List((Some(1),"db2"))
        === b.run(multi)
      )
      b
    }
    val ab = for{
      a0 <- a
      a1 <- using(db1.selector)(translations.ddl.drop)
      b0 <- b
    } yield a0 ++ b0
    assert(
      List((Some(1),"db1"),(Some(1),"db2"))
      === ab.run(multi)
    )
  }
}
