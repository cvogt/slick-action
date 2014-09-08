package org.cvogt.monadic
import scala.language.higherKinds
import scala.concurrent._
import scala.concurrent.duration._
import org.cvogt.constraints._
import scala.annotation.implicitNotFound
import scala.concurrent.ExecutionContext

abstract class CompositionOps[H[_],T](v:H[T]){
  def map[W](f: T => W): H[W]
  def flatMap[W](f: T => H[W]): H[W]
}
trait Composable[H[_]]{
  type M[X] = H[X]
  def apply[T](v:M[T]): CompositionOps[M,T]
}
/** Helper to keep type signatures clean */
@implicitNotFound("""
Cannot infer Union type for
${T}
with
${S}
to
${Union}
""")
class UnionType[+T,S,Union]
trait UnionTypeImplicits{
  implicit def unionTypeDiffer[T,S](implicit ev: T !=:= S) = new UnionType[T,S,T with S]  
  implicit def unionTypeIdentical[T,S](implicit ev: T =:= S) = new UnionType[T,S,T]
}
//package object unionType extends UnionTypeImplicits
package object functions extends UnionTypeImplicits{
  implicit class Extend_Any_Any[-T,+R](f: T => R){
    def map[Q](g: R => Q) = (t: T) => g(f(t))
    def flatMap[S,Q/*,Union <: T with S*/](g: R => (S => Q))/*(implicit unionType: UnionType[T,S,Union])*/ = (ts: T with S) => g(f(ts))(ts)
    def async(implicit ec: ExecutionContext) = f.map(Future(_))
    def direct = this
  }
  implicit class Extend_Higher_Any[-T,+R,TH[+_]](f: TH[T] => R){
    def map[Q](g: R => Q) = (t: TH[T]) => g(f(t))
    def flatMap[S,Q/*,Union <: T with S*/](g: R => (TH[S] => Q))/*(implicit unionType: UnionType[T,S,Union])*/ = (ts: TH[T with S]) => g(f(ts))(ts)
  }
  implicit class Extend_Any_Future[-T,+R](f: T => Future[R]){
    def mapFailure(m: PartialFunction[Throwable, Throwable])(implicit ec: ExecutionContext)
      : T => Future[R]
      = f(_).recoverWith(m andThen (Future failed _))
  }

  // TODO: move this
  import org.cvogt.di.reflect.TMap
  import scala.reflect.runtime.universe.TypeTag
  implicit class Extend_Any_Any_Reflect[-T:TypeTag,+R](f: T => R){
    def tMap = (tmap: TMap[T]) => f(tmap[T])
  }
  implicit class Extend_TMap_Any_Reflect[-T:TypeTag,+R](f: TMap[T] => R){
    def unTMap = (t: T) => f(TMap(t))
  }
}
package object higherKindedFunctions{
  implicit def composableFuture(implicit ec: ExecutionContext) = new Composable[Future]{
    def apply[T](v:M[T]) = new CompositionOps[M,T](v){
      def map[W](f: T => W) = v.map(f)
      def flatMap[W](f: T => M[W]) = v.flatMap(f)
    }
  }
  implicit def composableOption(implicit ec: ExecutionContext) = new Composable[Option]{
    def apply[T](v:M[T]) = new CompositionOps[M,T](v){
      def map[W](f: T => W) = v.map(f)
      def flatMap[W](f: T => M[W]) = v.flatMap(f)
    }
  }
  implicit def composableList(implicit ec: ExecutionContext) = new Composable[List]{
    def apply[T](v:M[T]) = new CompositionOps[M,T](v){
      def map[W](f: T => W) = v.map(f)
      def flatMap[W](f: T => M[W]) = v.flatMap(f)
    }
  }
  // Breaks comprehending: _ => List[_]
  implicit class Extend_Any_Higher[-T,+R,RH[+_]](f: T => RH[R])(implicit val c: Composable[RH]){
    def map[Q](g: R => Q) = (t: T) => c(f(t)).map(g)
    def flatMap[S,Q/*,Union <: T with S*/](g: R => (S => RH[Q]))/*(implicit unionType: UnionType[T,S,Union])*/ = (ts: T with S) => c(f(ts)).flatMap(r => g(r)(ts))
  }
  implicit class Extend_Higher_Higher[-T,+R,TH[+_],RH[+_]](f: TH[T] => RH[R])(implicit val c: Composable[RH]){
    def map[Q](g: R => Q) = (t: TH[T]) => c(f(t)).map(g)
    def flatMap[S,Q/*,Union <: T with S*/](g: R => (TH[S] => RH[Q]))/*(implicit unionType: UnionType[T,S,Union])*/ = (ts: TH[T with S]) => c(f(ts)).flatMap(r => g(r)(ts))
  }
}