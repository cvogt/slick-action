package org.cvogt.action.slick
import scala.slick.driver.JdbcDriver
import scala.slick.driver._
import scala.slick.profile._
import scala.slick.lifted.{Query => _,_}
import scala.slick.ast._
import scala.slick.jdbc._
import scala.language.implicitConversions
import scala.language.higherKinds
/**
Slick Action operations.
This is hacked onto Slick at the moment. It should really become a part of Slick.
*/
trait SlickActionOperations[P <: JdbcDriver]{
  self: SlickActionExtension[P] =>
  import driver._
  import driver.simple._
  trait OperationsLowPriority{
    self: Extension =>
    // JdbcProfile
    //implicit def queryToAppliedQueryInvoker[U, C[_]](q: Query[_,U, C]): QueryInvoker[U] = createQueryInvoker[U](queryCompiler.run(q.toNode).tree, ())
    implicit def queryToAppliedQueryInvoker[U, C[_]](q: Query[_,U, C])
      = new QueryInvokerAction[U](simple.queryToAppliedQueryInvoker(q))
    //implicit def queryToUpdateInvoker[U, C[_]](q: Query[_, U, C]): UpdateInvoker[U] = createUpdateInvoker(updateCompiler.run(q.toNode).tree, ())
    implicit def queryToUpdateInvoker[U, C[_]](q: Query[_, U, C])
      = new UpdateInvokerAction[U](simple.queryToUpdateInvoker(q))

    implicit class DDLInvokerAction(i: DDLInvoker){
      def create[O] = UnsafeAction[Write,Unit](i.create(_))
      def drop[O] = UnsafeAction[Write,Unit](i.drop(_))
    }
    implicit class DeleteInvokerAction(i: DeleteInvoker){
      def deleteInvoker = i
      def deleteStatement = i.deleteStatement
      def delete[O] = UnsafeAction[Write, Int](i.delete(_))
    }
    implicit class InsertInvokerAction[T](i: InsertInvoker[T]){
      def insertInvoker = i
      def insertStatement = i.insertStatement
      def forceInsertStatement = i.forceInsertStatement
      def insert[O](v: T) = UnsafeAction[Write, Int](i.insert(v)(_))
      def forceInsert[O](v: T) = UnsafeAction[Write, Int](i.forceInsert(v)(_))
      def insertAll[O](v: T*) = UnsafeAction[Write, Option[Int]](i.insertAll(v: _*)(_))
      def forceInsertAll[O](v: T*) = UnsafeAction[Write, Option[Int]](i.forceInsertAll(v: _*)(_))
    }
    implicit class UpdateInvokerAction[T](i: UpdateInvoker[T]){
      def updateInvoker = i
      def updateStatement = i.updateStatement
      def update[O](v: T) = UnsafeAction[Write, Int](i.update(v)(_))
    }
    implicit class QueryInvokerAction[V](i: QueryInvoker[V]){
      def invoker = i
      def execute[O] = UnsafeAction[Write, Unit](i.execute(_))
      def executeRead[O] = UnsafeAction[Read, Unit](i.execute(_))
      def list[O] = UnsafeAction[Read, List[V]](i.list(_))
      def first[O] = UnsafeAction[Read, V](i.first(_))
      def firstOption[O] = UnsafeAction[Read, Option[V]](i.firstOption(_))
      def foreach[O](f: V => Unit) = UnsafeAction[Read, Unit](i.foreach(f)(_))
      //import scala.collection.generic.CanBuildFrom
      //def build[To](implicit canBuildFrom: CanBuildFrom[Nothing, V, To], db: DatabaseSelect[AbstractRead]) = UnsafeAction[AbstractRead, To](i.build(_,canBuildFrom))
    }
    implicit class QueryExecutorAction[V](e: QueryExecutor[V]){
      def run[O](v: V) = UnsafeAction[Read, V](e.run(_))
      def executor = e
    }
  }

  trait Operations
    //extends driver.Implicits
    // RelationalProfile
    extends ImplicitColumnTypes
    // BasicProfile
    with Aliases
    with ExtensionMethodConversions
    with OperationsLowPriority{
    self: Extension =>

    // JdbcProfile
    type FastPath[T] = JdbcFastPath[T]

    //implicit def ddlToDDLInvoker(d: DDL): DDLInvoker = createDDLInvoker(d)
    implicit def ddlToDDLInvoker(d: DDL)
      = new DDLInvokerAction(simple.ddlToDDLInvoker(d))
    //implicit def queryToDeleteInvoker[C[_]](q: Query[_ <: Table[_], _, C]): DeleteInvoker = createDeleteInvoker(deleteCompiler.run(q.toNode).tree, ())
    implicit def queryToDeleteInvoker[C[_]](q: Query[_ <: Table[_], _, C])
      = new DeleteInvokerAction(simple.queryToDeleteInvoker(q))
    //implicit def runnableCompiledToAppliedQueryInvoker[RU, C[_]](c: RunnableCompiled[_ <: Query[_, _, C], C[RU]]): QueryInvoker[RU] = createQueryInvoker[RU](c.compiledQuery, c.param)
    implicit def runnableCompiledToAppliedQueryInvoker[RU, C[_]](c: RunnableCompiled[_ <: Query[_, _, C], C[RU]])
      = new QueryInvokerAction[RU](simple.runnableCompiledToAppliedQueryInvoker(c))
    //implicit def runnableCompiledToUpdateInvoker[RU, C[_]](c: RunnableCompiled[_ <: Query[_, _, C], C[RU]]): UpdateInvoker[RU] =
    //  createUpdateInvoker(c.compiledUpdate, c.param)
    implicit def runnableCompiledToUpdateInvoker[RU, C[_]](c: RunnableCompiled[_ <: Query[_, _, C], C[RU]])
      = new UpdateInvokerAction[RU](simple.runnableCompiledToUpdateInvoker(c))
    //implicit def runnableCompiledToDeleteInvoker[RU, C[_]](c: RunnableCompiled[_ <: Query[_, _, C], C[RU]]): DeleteInvoker =
    //  createDeleteInvoker(c.compiledDelete, c.param)
    implicit def runnableCompiledToDeleteInvoker[RU, C[_]](c: RunnableCompiled[_ <: Query[_, _, C], C[RU]]): DeleteInvokerAction
      = new DeleteInvokerAction(simple.runnableCompiledToDeleteInvoker(c))

    implicit def jdbcFastPathExtensionMethods[T, P](mp: MappedProjection[T, P]) = new JdbcFastPathExtensionMethods[T, P](mp)

    //// This conversion only works for fully packed types
    //implicit def productQueryToUpdateInvoker[T, C[_]](q: Query[_ <: Rep[T], T, C]): UpdateInvoker[T] =
    //  createUpdateInvoker(updateCompiler.run(q.toNode).tree, ())
    implicit def productQueryToUpdateInvoker[T, C[_]](q: Query[_ <: Rep[T], T, C])
      = new UpdateInvokerAction[T](simple.productQueryToUpdateInvoker(q))

    // RelationalProfile
    type Table[T] = driver.Table[T]
    type Sequence[T] = driver.Sequence[T]
    val Sequence = driver.Sequence
    type ColumnType[T] = driver.ColumnType[T]
    type BaseColumnType[T] = driver.BaseColumnType[T]
    val MappedColumnType = driver.MappedColumnType

    implicit def columnToOptionColumn[T : BaseTypedType](c: Column[T]): Column[Option[T]] = c.?
    implicit def valueToConstColumn[T : TypedType](v: T) = new LiteralColumn[T](v)
    implicit def columnToOrdered[T](c: Column[T]): ColumnOrdered[T] = c.asc
    implicit def tableQueryToTableQueryExtensionMethods[T <: Table[_], U](q: Query[T, U, Seq] with TableQuery[T]) =
      new TableQueryExtensionMethods[T, U](q)
    
    // BasicProfile
    type Database = Backend#Database
    val Database = backend.Database
    type Session = Backend#Session
    type SlickException = scala.slick.SlickException

    implicit val slickDriver: driver.type = driver
    //implicit def ddlToDDLInvoker(d: SchemaDescription): DDLInvoker

    //implicit def repToQueryExecutor[U](rep: Rep[U]): QueryExecutor[U] = createQueryExecutor[U](queryCompiler.run(rep.toNode).tree, ())
    implicit def repToQueryExecutor[U](rep: Rep[U])
      = new QueryExecutorAction[U](simple.repToQueryExecutor(rep))
    //implicit def runnableCompiledToQueryExecutor[RU](c: RunnableCompiled[_, RU]): QueryExecutor[RU] = createQueryExecutor[RU](c.compiledQuery, c.param)
    implicit def runnableCompiledToQueryExecutor[RU](c: RunnableCompiled[_, RU])
      = new QueryExecutorAction[RU](simple.runnableCompiledToQueryExecutor(c))
    //implicit def streamableCompiledToInsertInvoker[EU](c: StreamableCompiled[_, _, EU]): InsertInvoker[EU] = createInsertInvoker[EU](c.compiledInsert.asInstanceOf[CompiledInsert])
    implicit def streamableCompiledToInsertInvoker[EU](c: StreamableCompiled[_, _, EU])
      = new InsertInvokerAction[EU](simple.streamableCompiledToInsertInvoker(c))
    //// This only works on Scala 2.11 due to SI-3346:
    //implicit def recordToQueryExecutor[M, R](q: M)(implicit shape: Shape[_ <: FlatShapeLevel, M, R, _]): QueryExecutor[R] = createQueryExecutor[R](queryCompiler.run(shape.toNode(q)).tree, ())
    implicit def recordToQueryExecutor[M, R](q: M)(implicit shape: Shape[_ <: FlatShapeLevel, M, R, _])
      = new QueryExecutorAction[R](simple.recordToQueryExecutor(q))
    //implicit def queryToInsertInvoker[U, C[_]](q: Query[_, U, C]) = createInsertInvoker[U](compileInsert(q.toNode))
    implicit def queryToInsertInvoker[U, C[_]](q: Query[_, U, C])
     = new InsertInvokerAction[U](simple.queryToInsertInvoker(q))

    // Work-around for SI-3346
    @inline implicit final def anyToToShapedValue[T](value: T) = new ToShapedValue[T](value)
  }
}
