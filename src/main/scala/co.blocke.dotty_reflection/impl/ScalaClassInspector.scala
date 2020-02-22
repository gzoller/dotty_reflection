package co.blocke.dotty_reflection
package impl

import model._
import scala.quoted._
import scala.reflect._
import scala.tasty.Reflection
import scala.tasty.inspector.TastyInspector

class ScalaClassInspector[T](clazz: Class[_], cache: Reflector.CacheType) extends TastyInspector:

  protected def processCompilationUnit(reflect: Reflection)(root: reflect.Tree): Unit = 
    import reflect.{given _}
    reflect.rootContext match {
      case ctx if ctx.isJavaCompilationUnit() => cache.put( clazz.getName, JavaClassInspector.inspectClass(clazz, cache))
      case ctx if ctx.isScala2CompilationUnit() => // do nothing... can't handle Scala2 non-Tasty classes at present
      case _ => inspectClass(clazz.getName, reflect)(root)
    }
    

  def inspectClass(className: String, reflect: Reflection)(tree: reflect.Tree): Option[ConcreteType] =
    import reflect.{given _}

    object Descended {
      def unapply(t: reflect.Tree): Option[ConcreteType] = descendInto(reflect)(t)
    }
  
    // We expect a certain structure:  PackageClause, which contains ClassDef's for target class + companion object
    val foundClass = tree match {
      case t: reflect.PackageClause => 
        t.stats collectFirst {
          case Descended(m) => m
        }
        //t.stats.map( m => descendInto(reflect)(m) ).find(_.isDefined).get
      case t => 
        None  // not what we expected!
    }
    foundClass //.iterator.to(List).headOption


  private def descendInto(reflect: Reflection)(tree: reflect.Tree): Option[ConcreteType] =
    import reflect.{_, given _}
    tree match {
      case t: reflect.ClassDef if !t.name.endsWith("$") =>
        val className = t.symbol.show
        cache.get(className).orElse{
          val constructor = t.constructor
          val typeParams = constructor.typeParams.map(x => x.show.stripPrefix("type ")).map(_.toString.asInstanceOf[TypeSymbol])
          val inspected:ReflectedThing = if(t.symbol.flags.is(reflect.Flags.Trait))
            // === Trait ===
            StaticTraitInfo(className, typeParams)
          else
            // === Scala Class (case or non-case) ===
            val clazz = Class.forName(className)
            val isCaseClass = t.symbol.flags.is(reflect.Flags.Case)
            val paramz = constructor.paramss
            val members = t.body.collect {
              case vd: reflect.ValDef => vd
            }
            val fields = paramz.head.zipWithIndex.map{ (valDef, i) =>
              val fieldName = valDef.name
              if(!isCaseClass)
                scala.util.Try(clazz.getDeclaredMethod(fieldName)).toOption.orElse(
                  throw new Exception(s"Class [$className]: Non-case class constructor arguments must all be 'val'")
                )
              // Field annotations (stored internal 'val' definitions in class)
              val annoSymbol = members.find(_.name == fieldName).get.symbol.annots.filter( a => !a.symbol.signature.resultSig.startsWith("scala.annotation.internal."))
              val fieldAnnos = annoSymbol.map{ a => 
                val reflect.Apply(_, params) = a
                val annoName = a.symbol.signature.resultSig
                (annoName,(params collect {
                  case NamedArg(argName, Literal(Constant(argValue))) => (argName.toString, argValue.toString)
                }).toMap)
              }.toMap

              inspectField(reflect)(valDef, i, fieldAnnos, className) 
            }

            // Class annotations
            val annoSymbol = t.symbol.annots.filter( a => !a.symbol.signature.resultSig.startsWith("scala.annotation.internal."))
            val annos = annoSymbol.map{ a => 
              val reflect.Apply(_, params) = a
              val annoName = a.symbol.signature.resultSig
              (annoName,(params collect {
                case NamedArg(argName, Literal(Constant(argValue))) => (argName.toString, argValue.toString)
              }).toMap)
            }.toMap
 
            val isValueClass = t.parents.collectFirst {
              case Apply(Select(New(x),_),_) => x 
            }.map(_.symbol.name == "AnyVal").getOrElse(false)

            StaticClassInfo(className, fields, typeParams, annos, isValueClass)
          cache.put(className, inspected)
          Some(inspected)
        }
      case _ => None
    }


  private def inspectField(reflect: Reflection)(
    valDef: reflect.ValDef, 
    index: Int, 
    annos: Map[String,Map[String,String]], 
    className: String
    ): FieldInfo =
    import reflect.{_, given _}

    val fieldTypeInfo: ALL_TYPE = 
      valDef.tpt.tpe match {
        case ot: OrType => inspectUnionType(reflect)(ot)
        case t: TypeRef => inspectType(reflect)(t)
        case matchType => inspectType(reflect)(matchType.simplified.asInstanceOf[TypeRef])
      }
  
    // See if there's default values specified -- look for gonzo method on companion class.  If exists, default value is available.
    val defaultAccessor = fieldTypeInfo match
      case _: TypeSymbol => None
      case _ =>
        scala.util.Try{
          val companionClazz = Class.forName(className+"$") // This will fail for non-case classes, including Java classes
          val defaultMethod = companionClazz.getMethod("$lessinit$greater$default$"+(index+1)) // This will fail if there's no default value for this field
          val const = companionClazz.getDeclaredConstructor()
          const.setAccessible(true)
          ()=>defaultMethod.invoke(const.newInstance())
        }.toOption

    val clazz = Class.forName(className)
    val valueAccessor = scala.util.Try(clazz.getDeclaredMethod(valDef.name))
      .getOrElse(throw new Exception(s"Problem with class $className, field ${valDef.name}: All non-case class constructor fields must be vals"))
    ScalaFieldInfo(index, valDef.name, fieldTypeInfo, annos, valueAccessor, defaultAccessor)


  private def inspectUnionType( reflect: Reflection )(union: reflect.OrType): StaticUnionInfo =
    import reflect.{_, given _}
    val OrType(left,right) = union
    val resolvedLeft: ALL_TYPE = left match {
      case ot: OrType => inspectUnionType(reflect)(ot)
      case _ => inspectType(reflect)(left.asInstanceOf[TypeRef])
    }
    val resolvedRight: ALL_TYPE = inspectType(reflect)(right.asInstanceOf[TypeRef])
    resolvedLeft match { 
      case u: StaticUnionInfo => StaticUnionInfo("__union_type__", List.empty[TypeSymbol], u.unionTypes :+ resolvedRight )
      case x => StaticUnionInfo("__union_type__", List.empty[TypeSymbol], List(x, resolvedRight))
    }


  private def inspectType(reflect: Reflection)(typeRef: reflect.TypeRef): ALL_TYPE = 
    import reflect.{_, given _}

    def evalNestedType(tr:TypeRef): ALL_TYPE = 
      tr match {
        case ot: OrType => inspectUnionType(reflect)(ot)
        case t: TypeRef => inspectType(reflect)(t)
      }

    typeRef match {
      case named: dotty.tools.dotc.core.Types.NamedType if typeRef.isOpaqueAlias =>  // Scala3 opaque type alias
        typeRef.translucentSuperType match {
          case ot: OrType => AliasInfo(typeRef.show, inspectUnionType(reflect)(ot))
          case tr: TypeRef => AliasInfo(typeRef.show, inspectType(reflect)(tr))
          case _ => throw new Exception("Boom!")
        }
      case named: dotty.tools.dotc.core.Types.NamedType =>  // Scala3 Tasty-enabled type
        val classSymbol = typeRef.classSymbol.get
        classSymbol.name match {
          case "Boolean" => PrimitiveType.Scala_Boolean
          case "Byte"    => PrimitiveType.Scala_Byte
          case "Char"    => PrimitiveType.Scala_Char
          case "Double"  => PrimitiveType.Scala_Double
          case "Float"   => PrimitiveType.Scala_Float
          case "Int"     => PrimitiveType.Scala_Int
          case "Long"    => PrimitiveType.Scala_Long
          case "Short"   => PrimitiveType.Scala_Short
          case "String"  => PrimitiveType.Scala_String
          case _ =>
            val isTypeParam = typeRef.typeSymbol.flags.is(Flags.Param)   // Is 'T' or a "real" type?  (true if T)
            if(!isTypeParam) 
              descendInto(reflect)(classSymbol.tree).get
            else
              typeRef.name.asInstanceOf[TypeSymbol]
        }

      // Extract & Handle Scala Collection types
      //----------------------------------------
      case AppliedType(t,tob) if(t.classSymbol.isDefined && t.classSymbol.get.fullName == "scala.Option") =>
        ScalaOptionInfo(t.classSymbol.get.fullName, evalNestedType(tob.head.asInstanceOf[TypeRef]))

      case AppliedType(t,tob) if(t.classSymbol.isDefined && t.classSymbol.get.fullName == "scala.util.Either") =>
        // tob is a list, either side of which could be a union...must check both!
        ScalaEitherInfo(
          t.classSymbol.get.fullName,
          evalNestedType(tob(0).asInstanceOf[TypeRef]),
          evalNestedType(tob(1).asInstanceOf[TypeRef])
        )

      // Extract & Handle Java Collection types (we do this here vs in JavaClassInspector because here we have
      // all the needed type parameter information)
      //---------------------------------------
      case AppliedType(t,tob) if(t.classSymbol.isDefined && t.classSymbol.get.fullName == "java.util.Optional") =>
        JavaOptionInfo("java.util.Optional", inspectType(reflect)(tob.head.asInstanceOf[TypeRef]))

      // Do our best with anything else that falls thru the cracks
      //----------------------------------------------------------
      case _ =>  // Something else (either Java or Scala2 most likely)--go diving
        val classSymbol = typeRef.classSymbol.get
        Reflector.reflectOnClass(Class.forName(classSymbol.fullName))
    }