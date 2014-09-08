package org.cvogt.action
import scala.language.higherKinds

/**
Abstract type for Actions. Allows separation of execution logic and
resource usage management logic from composition logic.
@tparam T Context type
@tparam R Result type
*/
abstract class Action[T,+R] extends Function1[T,R]{
  type Self[T,+R] <: Action[T,R]
  protected def create[T,R](run: T => R): Self[T,R]
  def apply(t: T): R
  def map[Q](f: R => Q)
    = create[T,Q](t => f(apply(t)))
  def flatMap[S,Q](f: R => Self[S,Q])
    = create[T with S,Q](t => f(apply(t))(t))
  import scala.util.Try
  /** what about transaction rollbacks? */
  def asTry
    = create[T,Try[R]](t => Try(apply(t)))
}

trait ActionCompanion{
  type Self[T,+R] <: Function1[T,R]
  protected def create[T,R](f: T => R): Self[T,R]
  /** Lifts a Seq of Actions into an Action that produces a Seq */
  def sequence[T,R](actions: Seq[Self[T,R]]) = create[T,Seq[R]](
    context => actions.map(_(context))
  )
  /**
  Wraps a constant value into an Action to allow composition with other Actions.
  Not made implicit for now to force people to think about what they are doing.
  */
  def const[R](v: => R) = create[Any,R](_ => v)
}

import scala.concurrent.{Future, ExecutionContext}
abstract class AsyncAction[T,+R] extends Function1[T,Future[R]]{
  type Self[T2 <: T,+R] <: AsyncAction[T2,R]
  protected def create[T2 <: T,R](run: T2 => Future[R]): Self[T2,R]
  def apply(t: T): Future[R]
  def ec(t: T): ExecutionContext
  def map[Q](f: R => Q)
    = create[T,Q]{c =>
        apply(c).map(f)(ec(c))
      }

  def mapFailure(f: PartialFunction[Throwable, Throwable])
    = create[T,R]{c =>
        apply(c).recoverWith(f andThen (Future failed _))(ec(c))
      }
/*
  def mergeFuture[Q](ev: R <:< Future[Q])
    = new MongoAction[Q]({c =>
        import c.executionContext
        _run(c).flatMap(f => f.asInstanceOf[Future[Q]])
      })
*/
  def flatMap[S,Q](f: R => AsyncAction[S,Q])
    = create[T with S,Q]{c =>
        apply(c).flatMap(f(_).apply(c))(ec(c))
      }
}


