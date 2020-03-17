package co.blocke.dotty_reflection
package extractors

import impl._
import impl.Clazzes._
import model._ 
import scala.tasty.Reflection

case class SeqExtractor() extends TypeInfoExtractor[Collection_A1_Info]:

  def matches(clazz: Class[_]): Boolean = clazz <:< SeqClazz || clazz <:< SetClazz

  def emptyInfo(clazz: Class[_]): Collection_A1_Info = ???

  def extractInfo(reflect: Reflection)(
    t: reflect.Type, 
    tob: List[reflect.TypeOrBounds], 
    className: String, 
    clazz: Class[_], 
    typeInspector: ScalaClassInspector): ConcreteType =

    Collection_A1_Info(
            className, 
            clazz,
            clazz.getTypeParameters.toList.map(_.getName.asInstanceOf[TypeSymbol]), 
            typeInspector.inspectType(reflect)(tob.head.asInstanceOf[reflect.TypeRef]))