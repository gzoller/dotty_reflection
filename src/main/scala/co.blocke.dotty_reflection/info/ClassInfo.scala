package co.blocke.dotty_reflection
package info

import impl._
import java.nio.ByteBuffer

trait ClassInfo extends RType: 
  lazy val fields:                Array[FieldInfo]
  lazy val typeMembers:           Array[TypeMemberInfo]
  lazy val annotations:           Map[String, Map[String,String]]
  lazy val mixins:                Array[String]

  def hasMixin(mixin: String): Boolean = mixins.contains(mixin)


trait ScalaClassInfoBase extends ClassInfo with AppliedRType:
  val name:                   String
  val paramSymbols:           Array[TypeSymbol]
  val _typeMembers:           Array[TypeMemberInfo]
  val _fields:                Array[FieldInfo]
  val _annotations:           Map[String, Map[String,String]]
  val _mixins:                Array[String]
  val isValueClass:           Boolean

  override def equals(obj: Any) =
    obj match {
      case s: ScalaClassInfoBase if s.fullName == this.fullName => s._fields.toList == this.fields.toList
      case _ => false
    }

  // All this laziness is because Java classes use a proxy that isn't resolved until runtime.
  lazy val typeMembers = _typeMembers
  lazy val annotations = _annotations
  lazy val mixins = _mixins
  lazy val infoClass: Class[_] = Class.forName(name)
  lazy val typeParams = infoClass.getTypeParameters.toList.map(_.getName.asInstanceOf[TypeSymbol])

  override def toType(reflect: scala.tasty.Reflection): reflect.Type = 
    import reflect.{_, given _}
    val actualParameterTypes = fields.collect{
      case f if f.originalSymbol.isDefined => f.originalSymbol.get -> f.fieldType.toType(reflect).asInstanceOf[Type]
    }.toMap
    if typeParams.nonEmpty then
      val args = typeParams.map(sym => actualParameterTypes.getOrElse(sym,PrimitiveType.Scala_Any.toType(reflect).asInstanceOf[Type])).toList
      AppliedType(Type(infoClass), args)
    else
      reflect.Type(infoClass)
 
  // Fields may be self-referencing, so we need to unwind this...
  lazy val fields = _fields.map( f => f.fieldType match {
    case s: SelfRefRType => f.asInstanceOf[ScalaFieldInfo].copy(fieldType = s.resolve)
    case s => f
  })

  lazy val constructor = infoClass.getConstructor(fields.map(_.asInstanceOf[ScalaFieldInfo].constructorClass):_*)

  override def findPaths(findSyms: Map[TypeSymbol,Path], referenceTrait: Option[TraitInfo] = None): (Map[TypeSymbol, Path], Map[TypeSymbol, Path]) = 
    val interestingFields = referenceTrait match {
      case Some(t:TraitInfo) => 
        val refTraitFieldNames = t.fields.map(_.name)
        fields.filter(f => refTraitFieldNames.contains(f.name))
      case _ => fields
    }
    interestingFields.foldLeft((Map.empty[TypeSymbol,Path], findSyms)) { (acc, f) =>
      val (found, notFound) = acc
      val nameForPath = referenceTrait.map(_.name).getOrElse(name)
      if notFound.nonEmpty then
        val pathElement = 
          if referenceTrait.isDefined then
            TraitPathElement(nameForPath, f.name)
          else
            ClassPathElement(nameForPath, f.index)
        f.fieldType match {
          case ts: TypeSymbolInfo if notFound.contains(ts.name.asInstanceOf[TypeSymbol]) =>
            // This field's type is one of the sought-after TypeSymbols...
            val sym = ts.name.asInstanceOf[TypeSymbol]
            (found + (sym -> notFound(sym).push(pathElement)), notFound - sym)
          case _ =>
            // Or it's not...
            val (themThatsFound, themThatsStillLost) = f.fieldType.findPaths(notFound.map( (k,v) => k -> v.push(pathElement) ))
            (found ++ themThatsFound, themThatsStillLost.map( (k,v) => k -> findSyms(k) ))
        }
      else
        (found, notFound)
      }

  // Used for ScalaJack writing of type members ("external type hints").  If some type members are not class/trait, it messes up any
  // type hint modifiers, so for the purposes of serialization we want to filter out "uninteresting" type members (e.g. primitives)
  def filterTraitTypeParams: ScalaClassInfoBase 

  def show(tab:Int = 0, seenBefore: List[String] = Nil, supressIndent: Boolean = false, modified: Boolean = false): String = 
    val newTab = {if supressIndent then tab else tab+1}
    if seenBefore.contains(name) then
      {if(!supressIndent) tabs(tab) else ""} + this.getClass.getSimpleName + s"($name) (self-ref recursion)\n"
    else
      {if(!supressIndent) tabs(tab) else ""} + this.getClass.getSimpleName 
      + {if isValueClass then "--Value Class--" else ""}
      + s"($name):\n"
      + tabs(newTab) + "fields:\n" + {if modified then fields.map(f => tabs(newTab+1) + f.name+s"<${f.fieldType.infoClass.getName}>\n").mkString else fields.map(_.show(newTab+1, name::seenBefore)).mkString}
      + {if annotations.filterNot((k,_)=>k == "co.blocke.dotty_reflection.S3Reflection").nonEmpty then tabs(newTab) + "annotations: "+annotations.filterNot((k,_)=>k == "co.blocke.dotty_reflection.S3Reflection").toString + "\n" else ""}
      + {if( typeMembers.nonEmpty ) tabs(newTab) + "type members:\n" + typeMembers.map(_.show(newTab+1,name :: seenBefore)).mkString else ""}

//------------------------------------------------------------

object ScalaCaseClassInfo:
  def fromBytes( bbuf: ByteBuffer ): ScalaCaseClassInfo = 
    ScalaCaseClassInfo(
      StringByteEngine.read(bbuf),
      StringByteEngine.read(bbuf),
      ArrayStringByteEngine.read(bbuf).asInstanceOf[Array[TypeSymbol]],
      ArrayRTypeByteEngine.read(bbuf).map(_.asInstanceOf[TypeMemberInfo]),
      ArrayFieldInfoByteEngine.read(bbuf),
      MapStringByteEngine.read(bbuf),
      ArrayStringByteEngine.read(bbuf),
      BooleanByteEngine.read(bbuf),
      BooleanByteEngine.read(bbuf)
      )

case class ScalaCaseClassInfo protected[dotty_reflection] (
    name:                   String,
    fullName:               String,
    paramSymbols:           Array[TypeSymbol],
    _typeMembers:           Array[TypeMemberInfo],
    _fields:                Array[FieldInfo],
    _annotations:           Map[String, Map[String,String]],
    _mixins:                Array[String],
    override val isAppliedType: Boolean,
    isValueClass:           Boolean
  ) extends ScalaClassInfoBase: 

  // Used for ScalaJack writing of type members ("external type hints").  If some type members are not class/trait, it messes up any
  // type hint modifiers, so for the purposes of serialization we want to filter out "uninteresting" type members (e.g. primitives)
  def filterTraitTypeParams: ScalaClassInfoBase = this.copy( _typeMembers = typeMembers.filter(tm => tm.memberType.isInstanceOf[TraitInfo] || tm.memberType.isInstanceOf[ScalaCaseClassInfo]) )

  override def resolveTypeParams( paramMap: Map[TypeSymbol, RType] ): RType =
    this.copy( 
      _fields = _fields.map( _.asInstanceOf[ScalaFieldInfo].resolveTypeParams(paramMap) )
      )

  def toBytes( bbuf: ByteBuffer ): Unit = 
    bbuf.put( SCALA_CASE_CLASS_INFO )
    StringByteEngine.write(bbuf, name)
    StringByteEngine.write(bbuf, fullName)
    ArrayStringByteEngine.write(bbuf, paramSymbols.asInstanceOf[Array[String]])
    ArrayRTypeByteEngine.write(bbuf, _typeMembers.asInstanceOf[Array[RType]])
    ArrayFieldInfoByteEngine.write(bbuf, _fields)
    MapStringByteEngine.write(bbuf, _annotations)
    ArrayStringByteEngine.write(bbuf, _mixins)
    BooleanByteEngine.write(bbuf, isAppliedType)
    BooleanByteEngine.write(bbuf, isValueClass)

//------------------------------------------------------------

object ScalaClassInfo:
  def fromBytes( bbuf: ByteBuffer ): ScalaClassInfo = 
    ScalaClassInfo(
      StringByteEngine.read(bbuf),
      StringByteEngine.read(bbuf),
      ArrayStringByteEngine.read(bbuf).asInstanceOf[Array[TypeSymbol]],
      ArrayRTypeByteEngine.read(bbuf).map(_.asInstanceOf[TypeMemberInfo]),
      ArrayFieldInfoByteEngine.read(bbuf),
      ArrayFieldInfoByteEngine.read(bbuf).map(_.asInstanceOf[ScalaFieldInfo]),
      MapStringByteEngine.read(bbuf),
      ArrayStringByteEngine.read(bbuf),
      BooleanByteEngine.read(bbuf),
      BooleanByteEngine.read(bbuf)
      )

case class ScalaClassInfo protected[dotty_reflection] (
    name:                   String,
    fullName:               String,
    paramSymbols:           Array[TypeSymbol],
    _typeMembers:           Array[TypeMemberInfo],
    _fields:                Array[FieldInfo],  // constructor fields
    nonConstructorFields:   Array[ScalaFieldInfo],
    _annotations:           Map[String, Map[String,String]],
    _mixins:                Array[String],
    override val isAppliedType: Boolean,
    isValueClass:           Boolean
  ) extends ScalaClassInfoBase:

  // Used for ScalaJack writing of type members ("external type hints").  If some type members are not class/trait, it messes up any
  // type hint modifiers, so for the purposes of serialization we want to filter out "uninteresting" type members (e.g. primitives)
  def filterTraitTypeParams: ScalaClassInfoBase = this.copy( _typeMembers = typeMembers.filter(tm => tm.memberType.isInstanceOf[TraitInfo] || tm.memberType.isInstanceOf[ScalaCaseClassInfo]) )

  override def resolveTypeParams( paramMap: Map[TypeSymbol, RType] ): RType =
    this.copy( 
      _fields = _fields.map( _.asInstanceOf[ScalaFieldInfo].resolveTypeParams(paramMap) )
      )


  override def show(tab:Int = 0, seenBefore: List[String] = Nil, supressIndent: Boolean = false, modified: Boolean = false): String = 
    val newTab = {if supressIndent then tab else tab+1}
    val showNCFields = {if !modified then nonConstructorFields else nonConstructorFields.sortBy(_.name) }

    if seenBefore.contains(name) then
      {if(!supressIndent) tabs(tab) else ""} + this.getClass.getSimpleName + s"($name) (self-ref recursion)\n"
    else
      {if(!supressIndent) tabs(tab) else ""} + this.getClass.getSimpleName 
      + {if isValueClass then "--Value Class--" else ""}
      + s"($name):\n"
      + tabs(newTab) + "fields:\n" + fields.map(_.show(newTab+1,name :: seenBefore)).mkString
      + tabs(newTab) + "non-constructor fields:\n" + showNCFields.map(_.show(newTab+1,name :: seenBefore, supressIndent, modified)).mkString
      + {if annotations.filterNot((k,_)=>k == "co.blocke.dotty_reflection.S3Reflection").nonEmpty then tabs(newTab) + "annotations: "+annotations.filterNot((k,_)=>k == "co.blocke.dotty_reflection.S3Reflection").toString + "\n" else ""}
      + {if( typeMembers.nonEmpty ) tabs(newTab) + "type members:\n" + typeMembers.map(_.show(newTab+1,name :: seenBefore)).mkString else ""}

  def toBytes( bbuf: ByteBuffer ): Unit = 
    bbuf.put( SCALA_CLASS_INFO )
    StringByteEngine.write(bbuf, name)
    StringByteEngine.write(bbuf, fullName)
    ArrayStringByteEngine.write(bbuf, paramSymbols.asInstanceOf[Array[String]])
    ArrayRTypeByteEngine.write(bbuf, _typeMembers.asInstanceOf[Array[RType]])
    ArrayFieldInfoByteEngine.write(bbuf, _fields)
    ArrayFieldInfoByteEngine.write(bbuf, nonConstructorFields.asInstanceOf[Array[FieldInfo]])
    MapStringByteEngine.write(bbuf, _annotations)
    ArrayStringByteEngine.write(bbuf, _mixins)
    BooleanByteEngine.write(bbuf, isAppliedType)
    BooleanByteEngine.write(bbuf, isValueClass)

//------------------------------------------------------------


/** Java class reflection has a special problem... we need the class file, which isn't available during compilation (i.e. inside a macro).
 *  The best we can do is capture the name of the class and materialize/reflect on the class outside of the macro, lazy-like.
 */
object JavaClassInfo:
  def fromBytes( bbuf: ByteBuffer ): JavaClassInfo = 
    JavaClassInfo(
      StringByteEngine.read(bbuf),
      StringByteEngine.read(bbuf),
      ArrayRTypeByteEngine.read(bbuf),
      OptionRTypeByteEngine.read(bbuf).map(_.asInstanceOf[JavaClassInfoProxy])
      )

case class JavaClassInfo protected[dotty_reflection] ( 
    name:          String, 
    fullName:      String,
    paramTypes:    Array[RType], 
    _proxy:        Option[JavaClassInfoProxy] = None 
  ) extends ClassInfo:
  lazy val infoClass: Class[_] = Class.forName(name)
  private lazy val proxy = _proxy.getOrElse(impl.JavaClassInspector.inspectClass(infoClass, fullName, paramTypes).asInstanceOf[JavaClassInfoProxy])
  lazy val fields = proxy.fields
  lazy val typeMembers:           Array[TypeMemberInfo]           = proxy.typeMembers
  lazy val annotations:           Map[String, Map[String,String]] = proxy.annotations
  lazy val mixins:                Array[String]                   = proxy.mixins

  def field(name: String): Option[JavaFieldInfo] = fieldsByName.get(name)

  private lazy val fieldsByName = fields.map(f => (f.name, f.asInstanceOf[JavaFieldInfo])).toMap

  override def resolveTypeParams( paramMap: Map[TypeSymbol, RType] ): RType =
    val newProxy = this.proxy.copy(_fields = fields.map( f => f.resolveTypeParams(paramMap) ))
    val classParamSyms = infoClass.getTypeParameters.toList.map(_.getName.asInstanceOf[TypeSymbol])
    val newFullName =
      if proxy.paramMap.nonEmpty && classParamSyms.nonEmpty then
        val typeNames = classParamSyms.map(s => paramMap.get(s).map(_.fullName).orElse(Some(s.toString)).get).mkString("[",",","]")
        name + typeNames
      else
        name
    this.copy(_proxy = Some(newProxy), fullName = newFullName)

  def show(tab:Int = 0, seenBefore: List[String] = Nil, supressIndent: Boolean = false, modified: Boolean = false): String = 
    val newTab = {if supressIndent then tab else tab+1}

    if seenBefore.contains(name) then
      {if(!supressIndent) tabs(tab) else ""} + this.getClass.getSimpleName + s"($name) (self-ref recursion)\n"
    else
      {if(!supressIndent) tabs(tab) else ""} + this.getClass.getSimpleName 
      + s"($name):\n"
      + tabs(newTab) + "fields:\n" + fields.map(_.show(newTab+1,name :: seenBefore)).mkString
      + {if annotations.nonEmpty then tabs(newTab) + "annotations: "+annotations.toString + "\n" else ""}

  def toBytes( bbuf: ByteBuffer ): Unit = 
    bbuf.put( JAVA_CLASS_INFO )
    StringByteEngine.write(bbuf, name)
    StringByteEngine.write(bbuf, fullName)
    ArrayRTypeByteEngine.write(bbuf, paramTypes)
    OptionRTypeByteEngine.write(bbuf, _proxy)


object JavaClassInfoProxy:
  def fromBytes( bbuf: ByteBuffer ): JavaClassInfoProxy = 
    JavaClassInfoProxy(
      StringByteEngine.read(bbuf),
      ArrayFieldInfoByteEngine.read(bbuf),
      MapStringByteEngine.read(bbuf),
      MapStringRTypeByteEngine.read(bbuf).map( (k,v) => (k.asInstanceOf[TypeSymbol],v)).toMap
      )

case class JavaClassInfoProxy protected[dotty_reflection] (
    name:                   String,
    _fields:                Array[FieldInfo],
    _annotations:           Map[String, Map[String,String]],
    paramMap:               Map[TypeSymbol, RType]
  ) extends RType:

  val fullName = name
  lazy val annotations = _annotations
  lazy val infoClass: Class[_] = Class.forName(name)

  // Run up the interitance tree to the top (Object) to get all the superclasses and mixin interfaces of this one
  private def getSuperclasses(c: Class[_] = infoClass, stack:List[String] = List.empty[String]): List[String] = 
    val ammendedStack = (stack :+ c.getName) ::: c.getInterfaces.toList.map(_.getName)
    val sc = c.getSuperclass()
    if( sc == classOf[Object] || sc == null)
      ammendedStack
    else 
      getSuperclasses(sc, ammendedStack)

  lazy val mixins = getSuperclasses().toArray
 

  // Fields may be self-referencing, so we need to unwind this...
  lazy val fields = _fields.map{ f => 
    val fieldType = f.fieldType match {
      case s: SelfRefRType => f.asInstanceOf[JavaFieldInfo].copy(fieldType = s.resolve)
      case s => f
    }
    fieldType.resolveTypeParams(paramMap)
  }

  val typeMembers: Array[TypeMemberInfo] = Nil.toArray  // unused for Java classes but needed on ClassInfo

  def show(tab:Int = 0, seenBefore: List[String] = Nil, supressIndent: Boolean = false, modified: Boolean = false): String = 
    {if(!supressIndent) tabs(tab) else ""} + this.getClass.getSimpleName + s"($name)\n"

  def toBytes( bbuf: ByteBuffer ): Unit = 
    bbuf.put( JAVA_CLASS_INFO_PROXY )
    StringByteEngine.write(bbuf, name)
    ArrayFieldInfoByteEngine.write(bbuf, _fields)
    MapStringByteEngine.write(bbuf, _annotations)
    MapStringRTypeByteEngine.write(bbuf, paramMap.asInstanceOf[Map[String,RType]])