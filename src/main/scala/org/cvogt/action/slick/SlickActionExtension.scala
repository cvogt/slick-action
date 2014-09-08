package org.cvogt.action
package slick
import org.cvogt.constraints._

import scala.slick.driver.JdbcDriver
/** Slick SlickAction extensions public interface */
class SlickActionExtension[P <: JdbcDriver](val driver: P)
  extends SlickActionOperations[P]{
  import driver._
  import driver.simple._

  val action = new Extension

  /** Used to store a session in the context
      (used for transaction or forced multi query sessions */
  sealed trait SlickConfig{
    private[slick] type Self <: SlickConfig
    private[slick] var session: Option[(Session,Long)] = None
  }

  /** extracts the Session from the ActionContext[T] */
  private def session(c: SlickConfig): Option[Session]
    = {
      c.session.map{ case (session,threadId) =>
        // actually this probably can't even happen, but I am not 100% certain yet
        assert(
          threadId == Thread.currentThread.getId,
          """Thread id changed in the middle of a transaction. """+
          """You probably used a Future or some other means of """+
          """concurrency inside of a transaction, which is forbidden """+
          """because JDBC sessions are not generally thread-safe."""
        )
        session
      }
    }

  /** trick to reduce implicit priority */
  sealed trait ExtensionLowPriority{
    /** Stores the database config for reads */
    sealed trait Read extends SlickConfig{
      private[slick] def read: Database
    }
    /** Stores the database config for writes */
    sealed trait Write extends SlickConfig{
      private[slick] def write: Database
    }

    /** Type class for implicitly selecting read or write database */
    class ReadWriteSelect[T](val db: T => Database)

    /** Implicitly select read database for Actions that need Read configuration */
    implicit def selectRead[T <: Read]
      (implicit ev: T !<: Write) // <- trick to make it harder to use this for writes
      = new ReadWriteSelect[T](_.read)
  }

  final class Extension extends ExtensionLowPriority with Operations{
    object SlickAction extends ActionCompanion{
      type Self[T,+R] = SlickAction[T,R]
      override protected def create[T,R](run: T => R): Self[T,R] = new SlickAction(run)  
      private[slick] def apply[T <: SlickConfig,R](run: T => R) = new SlickAction(run)
    }
    class SlickAction[T,+R] private[slick](run: T => R) extends Action[T,R]{
      type Self[T,+R] = SlickAction[T,R]
      protected def create[T,R](run: T => R): Self[T,R] = new SlickAction(run)  
      def apply(t: T) = run(t)
    }

    private final class MasterSlaveConfig(master: Database, slave: Database) extends Read with Write{
      private[slick] type Self = MasterSlaveConfig
      def read = slave
      def write = master
    }
    /** Creates a context that has separate master and slave databases. */
    def MasterSlaveConfig(master: Database, slave: Database): Read with Write = new MasterSlaveConfig(master,slave)

    /** Creates a context that uses the same database for reads and writes. */
    def DatabaseConfig(db: Database): Read with Write = new MasterSlaveConfig(db,db)

    /**
     Implicitly select write database for Actions that need Write configuration.
     Takes precedence over selectReadDatabase
     */
    implicit def selectWrite[T <: Write]
      = new ReadWriteSelect[T](_.write)

    /** Runs the action and all enclosed actions in a transaction.
      * Currently transcations are only rolled back if an Exception
      * is encountered and no explicit rollback is supported, except using
      * sameConnectionWithConnectionOptions. If necessary we need to add proper
      * support for this.
      *
      * Prevent usage with Async actions, because JDBC connections aren't thread-safe.
      */
    def transaction[T <: SlickConfig,R]
      (action: SlickAction[T,R])
      (implicit db: ReadWriteSelect[T])
      = sameConnection(SlickAction[T,R](
        context => 
          session(context)
            .get
            .withTransaction{
              action(context)
            }
      ))

    /** Runs the action and all enclosed actions in the same JDBC connection. */
    def sameConnection[T <: SlickConfig,R]
      (action: SlickAction[T,R])
      (implicit db: ReadWriteSelect[T])
      = SlickAction[T,R](
          context =>
            session(context)
              .map(
                _ => action(context)
              ).getOrElse(
                db.db(context).withSession{ session =>
                  context.session = Some((session, Thread.currentThread.getId))
                  try{
                    action(context)
                  }finally{
                    context.session = None
                  }
                }
              )
        )

    /** Helper for unsafe actions to allow partial type-inference */
    def unsafe[T <: SlickConfig](implicit db: ReadWriteSelect[T]) = new unsafe[T]
    /** Helper for unsafe actions to allow partial type-inference */
    class unsafe[T <: SlickConfig](implicit db: ReadWriteSelect[T]){
      /** Creates an SlickAction from a function taking a JDBC Connection.
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

      /** Creates an SlickAction from a function taking a Slick Session.
          A Session will be kept open for the whole duration.
          BE WARNED:
          You need to specify type T correctly as Read, Write and/or Async.
          Don't pass the Session across Threads.
          Don't hold onto the Session, e.g. in some captured scope.
      */
      def slick[R]
        (_run: Session => R)
        = sameConnection[T,R](SlickAction[T,R](
            context => _run(session(context).get)
          ))
    }
  }
}
