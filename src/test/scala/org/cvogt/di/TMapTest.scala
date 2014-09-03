package test.org.cvogt.di
import org.cvogt.constraints._
import org.scalatest._
import org.scalautils.TypeCheckedTripleEquals

class ContextTest extends FunSuite with TypeCheckedTripleEquals{
  import org.cvogt.di._
  import org.cvogt.di.reflect._

  test("test "){
    def squared(c: TMap[Int with String]) = {
      c[String]+": "+(c[Int] * c[Int])
    }
    sealed class Foo extends Key[Int]
    val Foo = new Foo
    val c = KMap(Foo -> 5)
    sealed class Bar extends Key[String]
    val Bar = new Bar
    sealed class Bar2 extends Key[String]
    val Bar2 = new Bar2
    val c2 = (c add Bar -> "test"
                proj Bar2 -> Bar
                add key[Option[Int]] -> Option(5)
              )
              .tMap
              .add[Option[String]](Option("test"))
              .kMap


    assert(5 === c2(Foo))
    assert("test" === c2(Bar))
    assert("test" === c2(Bar2))
    assert(Some(5) === c2(key[Option[Int]]))
    assert(Some("test") === c2.typed[Option[String]])

    sealed class db2[T] extends Key[KMap[T]]
    val db2 = new db2[Foo]

    c2 add db2 -> KMap(Foo -> 6)

    val tc: TMap[Int with String] = TMap[Int](3).add[String]("result")
    assert("result: 9" === squared(tc))
  }

  implicit class anyToTMap(a:Any) {
    def monadic = (_:TMap[Any]) => a
  }

  class Database{
    def query(sql: String) = "query result"
  }
  class Cache{
    def filled = false
    def get = "cached result"
  }
  class Logger{
    def log(msg: String) = assert(msg === "Fetching person")
  }

  val cache = new Cache
  val database = new Database
  val logger = new Logger
  test("global dependencies"){
    def getPeople = {
      logger.log("Fetching person")
      database.query("SELECT * FROM PERSON")
    }

    def getPeopleWithCache = {
      if(cache.filled) cache.get else getPeople
    }

    assert(
      "query result"
      === getPeopleWithCache
    )    
  }

  test("functions"){
    def getPeople(l: Logger, db: Database) = {
      l.log("Fetching person")
      db.query("SELECT * FROM PERSON")
    }

    def getPeopleWithCache(c: Cache, l: Logger, db: Database) = {
      if(c.filled) c.get else getPeople(l,db)
    }

    assert(
      "query result"
      === getPeopleWithCache(cache, logger, database)
    )    
  }

  test("functions with implicits"){
    def getPeople(implicit l: Logger, db: Database) = {
      l.log("Fetching person")
      db.query("SELECT * FROM PERSON")
    }

    def getPeopleWithCache(implicit c: Cache, l: Logger, db: Database) = {
      if(c.filled) c.get else getPeople
    }

    assert{
      implicit val icache = cache
      implicit val idatabase = database
      implicit val ilogger = logger      
      "query result" === getPeopleWithCache
    }
  }

  /*
   Alternatives:
   - tuples
     disadvantages: explicit wiring like arg lists, position, copy everything
   - GOD context object with members
     disadvantage: always needs everything, multiple resources
   - mixin context traits (e.g. plans for Play 2.4)
     disadvantage: boiler plate, multiple resources
  */

  // multiple values of the same type via wrapper types
  case class Person(name: String)
  test("TMap"){
    val tmap: TMap[Int with Option[Long] with Person with String]
      = TMap(5) ++ TMap(Option(10l)) ++ TMap(Person("Chris")) ++ TMap("test")

    assert( tmap[Int] === 5 )
    assert( tmap[Option[Long]].get === 10l )
    assert( tmap[Person].name === "Chris" )
    assert( tmap[String] === "test" )
    assert( tmap[Any] === 5 )
  }

  test("functions with implicit TMap"){
    def getPeople(implicit ctx: TMap[Logger with Database]) = {
      ctx[Logger]  .log("Fetching person")
      ctx[Database].query("SELECT * FROM PERSON")
    }

    def getPeopleWithCache(implicit ctx: TMap[Cache with Logger with Database]) = {
      val c = ctx[Cache]
      if(c.filled) c.get else getPeople
    }

    assert{
      implicit val ctx = TMap(database) ++ TMap(cache) ++ TMap(logger)
      "query result" === getPeopleWithCache
    }
  }

  test("functions with TMap"){
    def getPeople = (ctx: TMap[Logger with Database]) => {
      ctx[Logger]  .log("Fetching person")
      ctx[Database].query("SELECT * FROM PERSON")
    }

    def getPeopleWithCache = (ctx: TMap[Cache with Logger with Database]) => {
      val c = ctx[Cache]
      if(c.filled) c.get else getPeople(ctx)
    }

    assert{
      val ctx = TMap(database) ++ TMap(cache) ++ TMap(logger)
      "query result" === getPeopleWithCache(ctx)
    }
  }

  implicit class ExtendFunction[-T,+R](f: TMap[T] => R){
    def map[Q](g: R => Q) = (t: TMap[T]) => g(f(t))
    def flatMap[S,Q](g: R => (TMap[S] => Q)) = (ts: TMap[T with S]) => g(f(ts))(ts)
  }

  test("functions with TMap and monadic composition"){
    def getPeople: TMap[Logger with Database] => String
      = for {
          _   <- (ctx: TMap[Logger])   => ctx[Logger].log("Fetching person")
          res <- (ctx: TMap[Database]) => ctx[Database].query("SELECT * FROM PERSON")
        } yield res

    def getPeopleWithCache: TMap[Cache with Logger with Database] => String
      = for {
          c <- (ctx: TMap[Cache]) => ctx[Cache]
          people <- getPeople
        } yield if(c.filled) c.get else people

    assert{
      val ctx = TMap(database) ++ TMap(cache) ++ TMap(logger)
      "query result" === getPeopleWithCache(ctx)
    }
  }

  test("functions with TMap and monadic composition and inferred types"){
    def getPeople
      = for {
          _   <- {ctx: TMap[Logger]   => ctx[Logger].log("Fetching person")}
          res <- {ctx: TMap[Database] => ctx[Database].query("SELECT * FROM PERSON")}
        } yield res

    def getPeopleWithCache
      = for {
          c <- {ctx: TMap[Cache] => ctx[Cache]}
          people <- getPeople
        } yield if(c.filled) c.get else people

    assert{
      val ctx = TMap(database) ++ TMap(cache) ++ TMap(logger)
      "query result" === getPeopleWithCache(ctx)
    }
  }

  def Implicit[V:TTKey] = (c: TMap[V]) => c[V]
  // mimic scala implicits
  test("functions with TMap and monadic composition and inferred types and syntactic sugar"){
    def getPeople
      = for {
          logger <- Implicit[Logger]
          _   <- logger.log("Fetching person").monadic
          db <- Implicit[Database]
          res <- db.query("SELECT * FROM PERSON").monadic
        } yield res

    def getPeopleWithCache
      = for {
          c <- Implicit[Cache]
          people <- getPeople
        } yield if(c.filled) c.get else people

    assert{
      val ctx = TMap(database) ++ TMap(cache) ++ TMap(logger)
      "query result" === getPeopleWithCache(ctx)
    }
  }

  def get[V:TTKey] = (c: TMap[V]) => c[V]
  // Maybe get is a nicer name for dependency injection though
  test("functions with TMap and monadic composition and inferred types and syntactic sugar and inline style"){
    def getPeople
      = for {
          _   <- get[Logger]  .map(_.log("Fetching person"))
          res <- get[Database].map(_.query("SELECT * FROM PERSON"))
        } yield res

    def getPeopleWithCache
      = for {
          c <- get[Cache]
          people <- getPeople
        } yield if(c.filled) c.get else people

    assert{
      val ctx = TMap(database) ++ TMap(cache) ++ TMap(logger)
      "query result" === getPeopleWithCache(ctx)
    }
  }

  test("functions with TMap and monadic composition and inferred types and syntactic sugar and inline style and explicit flatMap"){
    def getPeople
      = get[Logger].map(_.log("Fetching person")).flatMap(
          _ => get[Database].map(_.query("SELECT * FROM PERSON"))
        )

    def getPeopleWithCache
      = for {
          c <- get[Cache]
          people <- getPeople
        } yield if(c.filled) c.get else people

    assert{
      val ctx = TMap(database) ++ TMap(cache) ++ TMap(logger)
      "query result" === getPeopleWithCache(ctx)
    }
  }

  /*
    The clue:
    - If query already returns a TMap[Database] => R,
      no need to write it at all.
    - Context can also contain dynamic info in addition, e.g. a Session.
    - A monad instead of function can conceal the context, e.g.
      - manage session life time
      - prevent people from passing session across threads, capture past life time
  */

  case class Name(value: String)
  case class Age(value: Int)

  case class Model(value: String)
  test("test struct"){
    type Person = TMap[Name with Age]
    val p: Person = TMap(Name("Chris")) ++ TMap(Age(99))
    assert("Chris" === p[Name].value)
    assert(99 === p[Age].value)

    // composable records:
    type Car = TMap[Model]
    val c: Car = TMap(Model("Porsche Carrera"))
    val pc: Car with Person = p ++ c
  }

  case class _1(value: String)
  case class _2(value: Int)
  test("test tuple"){
    type Tuple = TMap[_1 with _2]
    val p: Tuple = TMap(_1("Chris")) ++ TMap(_2(99))
    assert("Chris" === p[_1].value)
    assert(99 === p[_2].value)
  }

  case class left(value: Int)
  case class right(value: Int)
  test("test named arguments"){
    def plus(args: TMap[left with right])
      = args[left].value + args[right].value
    
    assert{
      val args = TMap(left(4)) ++ TMap(right(5))
      9 === plus(args)
    }
  }
}
