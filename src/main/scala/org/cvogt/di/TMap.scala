package org.cvogt.di
import org.cvogt.constraints._
import scala.language.higherKinds
import scala.language.existentials
import scala.annotation.implicitNotFound
import scala.language.implicitConversions

class UnsafeKey
class TKey extends UnsafeKey{
  type Value
}
class Key[T] extends TKey{
  type Value = T
}
private[di] case class KeyValuePair(key: Key[_], value: Any)
private[di] object kvp{
  def apply(kv: (Key[_],Any)) = KeyValuePair.tupled(kv)
}

class UnsafeMap private[di](private[di] val seq: Seq[KeyValuePair]){
  private[di] def create = new UnsafeMap(_)
  def unsafeAdd(kv: (Key[_],Any)) = create( kvp(kv) +: seq )
  def unsafeGet[K <: TKey](key: K) = seq.collect{
    case KeyValuePair(k, value) if k == key => value.asInstanceOf[key.Value]
  }.headOption
}

object KMap{
  def apply[V, K <: Key[V]](kv: (K, V))(implicit ev: K <:< Key[V])
    =  new KMap[K]( Seq(kvp(kv)) )
}

class HMap[+T](seq: Seq[KeyValuePair]) extends UnsafeMap(seq)

class KMap[+T] private[di](seq: Seq[KeyValuePair]) extends HMap[T](seq) {
  override private[di] def create = new KMap[T](_)
  def add[V, K <: Key[_]](kv: (K, V))(implicit ev: K <:< Key[V])
    = new KMap[T with K]( kvp(kv) +: seq )
  def ++[Q](other: HMap[Q]) = new KMap[T with Q]( seq ++ other.seq )
  def proj[K <: Key[_], K2 <: TKey]
    (kk: (K,K2))
    (implicit ev: T <:< K2, ev2: K <:< Key[kk._2.Value])
    = this add (kk._1 -> this(kk._2))
  def apply[K <: TKey](key: K)(implicit ev: T <:< K)
    : key.Value
    = unsafeGet(key).get
}

package object reflect{
  import scala.reflect.runtime.universe.TypeTag
  class TTKey[T](implicit val typeTag: TypeTag[T]) extends Key[T]{
    override def equals(a: Any)
      =  a.isInstanceOf[TTKey[_]] && typeTag.tpe <:< a.asInstanceOf[TTKey[_]].typeTag.tpe
  }
  implicit def key[T](implicit typeTag: TypeTag[T]) = new TTKey[T]
  implicit def h2t[T](c: HMap[T]) = c.tMap
  implicit def h2k[T](c: HMap[T]) = c.kMap
  implicit class KeyContextExtensions[T](kc: HMap[T]){
    //def lift[L <: Lifter[T]](l: L) = KMap((l,l.map))
    def tMap = new TMap[T](kc.seq)
    def kMap = new KMap[T](kc.seq)
    def typed = this
    def apply[V](implicit key: TTKey[V], ev: T <:< V)
      : key.Value
      = kc.seq.collect{
        case KeyValuePair(k, value) if k == key => value.asInstanceOf[key.Value]
      }.head
    def add[V](value: V)(implicit key: TTKey[V])
      = new KMap[T with V]( KeyValuePair.tupled((key, value)) +: kc.seq )
  }

  class opaqueKey[T](map: KMap[T] = null){
    class HMap[T] extends Key[KMap[T]]
    val key = new HMap[T]
  }

  /** Type-indexed map */
  class TMap[+T] private[di](seq: Seq[KeyValuePair]) extends HMap[T](seq){
    override private[di] def create = new KMap[T](_)
    def add[V](value: V)(implicit key: TTKey[V])
      = new TMap[T with V]( kvp((key,value)) +: seq )
    /** Adds two TMaps. Be aware: lhs values take precedence over rhs. */
    def ++[Q](other: HMap[Q]) = new TMap[T with Q]( seq ++ other.seq )
    /** Gets the LAST INSERTED value that matches the given type.
        Be aware of this when using subtyping. */
    def apply[V](implicit key: TTKey[V], ev: T <:< V)
      : V = unsafeGet(key).get
    def extract[C[_]] = new {
      def as[V](implicit ev: T <:< C[V], key: TTKey[V], key2: TTKey[C[V]]) = new {
        def using(f: C[V] => V)        = add[V](f(apply[C[V]]))
      }
    }
    def debug = println(seq)
  }
  object TMap{
    def apply[V](value: V)(implicit key: TTKey[V])
      = new TMap[V]( Seq(kvp((key,value))) )
  }
}
