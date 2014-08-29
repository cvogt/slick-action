package org.cvogt
package object action{
  import scala.language.higherKinds
  def using[I[_] <: SourceID[_],T,R]
    (selector: SourceSelector[I])
    (action: Action[T,R])
    = Action[I[T],R](context =>
      action._run(new ActionContext[T](selector.options ++ context.options))
    )
  implicit class ActionContextExtensions[T](c: ActionContext[T]){
    def demux = new MultiConfig[T](c)
  }
}
