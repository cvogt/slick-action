package org.cvogt.action
import scala.concurrent.{Future, future, ExecutionContext, Await}
import scala.concurrent.duration._
import org.cvogt.constraints._
import scala.language.higherKinds

/**
Container for passing scoped config through action executions.
Certain Actions may depend on certain values existing in the ActionContext.
Be very careful if creating a ActionContext by hand. The phantom type
T does not provide any type safety at creation time.
@tparam T phantom type, makes ActionContext applicable to Action[_ >: T,...]
*/
class ActionContext[+T] private[action](private[action] val options: Seq[Any]){
  /** Create a new Config phantom with union of the types */
  def ++[Q](config: ActionContext[Q]) = new ActionContext[T with Q](options ++ config.options)
  private[action] def +(options: Any*) = new ActionContext[T](options ++ this.options)
}

/**
Abstract type for Actions. Allows separation of execution logic and
resource usage management logic from composition logic.
@tparam T ActionContext type
@tparam R Result type
@tparam A tracks if an Action is run synchronously or asynchronously
*/
final case class Action[T,+R](private[action] _run: ActionContext[T] => R){
  // we could add variance as -T, but that would allow people to request more
  // resources than they actually need (e.g. Write, when they only need Read)
  // so let's not do it for now
  outer =>
  def run(config: ActionContext[T]) = _run(new ActionContext(config.options))
  def map[Q](f: R => Q)
    = Action[T,Q](context => f(outer._run(context)))
  def flatMap[S,Q](f: R => Action[S,Q])
    = Action[T with S,Q](context => f(outer._run(context))._run(context))
  import scala.util.Try
  /** what about transaction rollbacks? */
  def asTry
    = Action[T,Try[R]](context => Try(_run(context)))
}

object Action{
  /** Lifts a Seq of Actions into an Action that produces a Seq */
  implicit class LiftSeq[T,R](actions: Seq[Action[T,R]]){
    def sequence = Action[T,Seq[R]](
      context => actions.map(_._run(context))
    )
  }
  /**
  Wraps a constant value into an Action to allow composition with other Actions.
  Not made implicit for now to force people to think about what they are doing.
  */
  def const[R](v: => R) = Action[Any,R](_ => v)
}

trait SourceID[+T]

final class SourceSelector[I[_] <: SourceID[_]](val options: Seq[Any])

final class MultiConfig[T]
  (val inner: ActionContext[T]){
  trait IO[+T] extends SourceID[T]
  val selector = new SourceSelector[IO](inner.options)
  /** keep this concealed, if you don't want people to execute queries */
  val config = new ActionContext[IO[T]](Seq())
}
