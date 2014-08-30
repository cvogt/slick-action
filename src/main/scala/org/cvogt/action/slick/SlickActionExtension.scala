package org.cvogt.action
package slick
import scala.concurrent.{Future, future, ExecutionContext, Await}
import scala.concurrent.duration._
import org.cvogt.constraints._
import scala.annotation.implicitNotFound
import scala.reflect.ClassTag

import scala.slick.driver.JdbcDriver
/** phantom type representing a read query */
trait Read
/** phantom type representing a write query */
trait Write
/** phantom type representing a potentially asynchronous actions */
trait Async

/** Slick Action extensions public interface */
class SlickActionExtension[P <: JdbcDriver] private[slick](val driver: P)
  extends SlickActionOperations[P]{
  import driver._
  import driver.simple._

  val action = new Extension

  /** trick to reduce implicit priority */
  sealed trait ExtensionLowPriority{
    /** Type class for implicitly selecting read or write database */
    class ReadWriteSelect[T](val db: ActionContext[T] => Database)

    /** Stores the database config for reads in the ActionContext[T] */
    protected case class ReadDatabase(db: Database)

    /** Stores the database config for writes in the ActionContext[T] */
    protected case class ReadWriteDatabase(db: Database)

    /** Implicitly select read database for Actions that need Read configuration */
    implicit def selectRead[T <: Read]
      (implicit ev: T !<: Write) // <- trick to make it harder to use this for writes
      = new ReadWriteSelect[T](_.options.collect{ case ReadDatabase(db) => db }.head)
  }

  class Extension extends ExtensionLowPriority with Operations{
    /** Creates a context that has separate master and slave databases. */
    class MasterSlaveConfig(master: Database, slave: Database) extends ActionContext[Read with Write with Async](Seq(
      ReadWriteDatabase(master), ReadDatabase(slave)
    ))
    /** Creates a context that uses the same database for reads and writes. */
    class DatabaseConfig(db: Database) extends MasterSlaveConfig(db,db)

    /**
     Implicitly select write database for Actions that need Write configuration.
     Takes precedence over selectReadDatabase
     */
    implicit def selectWrite[T <: Write]
      = new ReadWriteSelect[T](_.options.collect{ case ReadWriteDatabase(db) => db }.head)

    /** Whitelist Slick Read Actions for transaction use*/
    implicit def allowRead: AllowsTransaction[Read] = null
    /** Whitelist Slick ReadWrite Actions for transaction use */
    implicit def allowReadWrite: AllowsTransaction[Read with Write] = null
    /** Whitelist Slick Write Actions for transaction use */
    implicit def allowWrite: AllowsTransaction[Write] = null

    /** Type class to limit transaction usage to Slick only non-async actions. */
    @implicitNotFound(msg =
      """
Transaction are only allowed for synchronous Actions only involving Slick.
Not for: ${T}"""
    )
    trait AllowsTransaction[T]

    /** Runs the action and all enclosed actions in a transaction.
      * Currently transcations are only rolled back if an Exception
      * is encountered and no explicit rollback is supported, except using
      * sameConnectionWithConnectionOptions. If necessary we need to add proper
      * support for this.
      *
      * Prevent usage with Async actions, because JDBC connections aren't thread-safe.
      */
    def transaction[T,R]
      (action: Action[T,R])
      (implicit ev: AllowsTransaction[T], db: ReadWriteSelect[T])
      = sameConnection(Action[T,R](
        context => 
          session(context)
            .get
            .withTransaction{
              action._run(context)
            }
      ))

    /** Runs the action and all enclosed actions in the same JDBC connection. */
    def sameConnection[T,R]
      (action: Action[T,R])
      (implicit ev: T !<: Async, db: ReadWriteSelect[T])
      = Action[T,R](
          context =>
            session(context)
              .map(
                _ => action._run(context)
              ).getOrElse(
                db.db(context).withSession{ session =>
                  action._run(context + PersistentSession(session, Thread.currentThread.getId))
                }
              )
        )

    /** Helper for unsafe actions to allow partial type-inference */
    def unsafe[T](implicit ev: T !<: Async, db: ReadWriteSelect[T]) = new unsafe[T]
    /** Helper for unsafe actions to allow partial type-inference */
    class unsafe[T](implicit ev: T !<: Async, db: ReadWriteSelect[T]){
      /** Creates an Action from a function taking a JDBC Connection.
          A Connection will be kept open for the whole duration.
          This can be used to perform side-effects on the JDBC connection.
          The side-effects will persist as lond a the connection is
          kept open, which you can affect using `sameConnection` or `transaction`.
          BE WARNED:
          You need to specify type T correctly as Read, Write and/or Async.
          Don't pass the Connection across Threads.
          Don't hold onto the Connection, e.g. in some captured scope.
        */
      def JDBC[R]
        (_run: java.sql.Connection => R)
        = unsafe[T].slick(session => _run(session.conn))

      /** Creates an Action from a function taking a Slick Session.
          A Session will be kept open for the whole duration.
          BE WARNED:
          You need to specify type T correctly as Read, Write and/or Async.
          Don't pass the Session across Threads.
          Don't hold onto the Session, e.g. in some captured scope.
      */
      def slick[R]
        (_run: Session => R)
        = sameConnection[T,R](Action[T,R](
            context => _run(session(context).get)
          ))
    }

    /** Add operations to synchronous actions only.
        Limited to Slick by requiring a ReadWriteSelect. */
    implicit class ActionExtensions[T,R](a: Action[T,R]){
      /** runs a session concurrently */
      def async(implicit ec: ExecutionContext)
        = Action[T with Async, Future[R]](context => future(a._run(context)))
    }

    /** Lifts a Seq of Actions into an Action of a Future that produces a Seq.
        Runs queries in parallel.
    */
    implicit class LiftSeq[T,R](actions: Seq[Action[T,R]]){
      def sequenceAsync(implicit ec: ExecutionContext)
       = Action[T with Async,Seq[R]](
           context =>
            Await.result(
              Future.sequence(actions.map(_.async._run(context))),
              365.days
            )
      )
    }

    /** Used to store a session in the context
        (used for transaction or forced multi query sessions */
    private case class PersistentSession(session: Session, threadId: Long)

    /** extracts the Session from the ActionContext[T] */
    private def session(context: ActionContext[_]): Option[Session]
      = context.options.collect{
        case PersistentSession(session, threadId) => 
          // actually this probably can't even happen, but I am not 100% certain yet
          assert(
            threadId == Thread.currentThread.getId,
            """Thread id changed in the middle of a transaction. """+
            """You probably used a Future or some other means of """+
            """concurrency inside of a transaction, which is forbidden """+
            """because JDBC sessions are not generally thread-safe."""
          )
          session
      }.headOption
  }
}
