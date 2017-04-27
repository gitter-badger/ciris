import sbt._

object SourceGenerators extends AutoPlugin {
  object autoImport {
    def generateSources(root: File, rootPackage: String): Seq[File] =
      generateConfigValueClasses(root, rootPackage) ++
        generateLoadConfigs(root, rootPackage)
  }

  val autoGeneratedNotice: String =
    """
      |/**
      |  * Generated using sbt source generators.
      |  * You should not modify this file directly.
      |  */
    """.stripMargin.trim

  val maximumNumberOfParams: Int = 22

  /**
    * Generates: {{{ A1${sep}A2$sep...A$n$sep }}}
    */
  def typeParams(n: Int, sep: String = ", "): String =
    (1 to n).map(typeParam).mkString(sep)

  /**
    * Generates: {{{ A$n }}}
    */
  def typeParam(n: Int): String = s"A$n"

  /**
    * Generates: {{{ a1${sep}a2$sep...a$n$sep }}}
    */
  def valueParams(n: Int, sep: String = ", "): String =
    (1 to n).map(valueParam).mkString(sep)

  /**
    * Generates: {{{ a$n }}}
    */
  def valueParam(n: Int): String = s"a$n"

  /**
    * Generates: {{{ a1: ${typeName(1)}, a2: ${typeName(2)},..., a$n: ${typeName(n)} }}}
    */
  def args(n: Int, typeName: Int ⇒ String): String =
    (1 to n).map(i ⇒ s"${valueParam(i)}: ${typeName(i)}").mkString(", ")

  def generateLoadConfigs(root: File, rootPackage: String): Seq[File] = {
    val defs =
      (2 until maximumNumberOfParams)
        .map { current ⇒
          val params = typeParams(current)
          val firstArgs = args(current, arg ⇒ s"ConfigValue[${typeParam(arg)}]")
          val secondArgs = s"f: (${typeParams(current)}) => Z"

          s"""
           |def loadConfig[$params, Z]($firstArgs)($secondArgs): Either[ConfigErrors, Z] =
           |    (${valueParams(current, sep = " append ")}).value.right.map(f.tupled)
         """.stripMargin.trim
        }
        .map("  " + _)
        .mkString("\n\n")

    val content =
      s"""
        |// format: off
        |
        |$autoGeneratedNotice
        |
        |package $rootPackage
        |
        |private [$rootPackage] trait LoadConfigs {
        |$defs
        |}
      """.stripMargin.trim + "\n"

    val output = root / rootPackage / "LoadConfigs.scala"
    IO.write(output, content)
    Seq(output)
  }

  def generateConfigValueClasses(root: File, rootPackage: String): Seq[File] = {
    val classes = (2 to maximumNumberOfParams)
      .map { current ⇒
        val next = current + 1
        val nextTypeParam = typeParam(next)
        val currentTypeParams = typeParams(current)

        val defs =
          if (current == maximumNumberOfParams) ""
          else {
            // format: off
            s"""
               |{
               |  def append[$nextTypeParam](next: ConfigValue[$nextTypeParam]): ConfigValue$next[${typeParams(next)}] = {
               |    (value, next.value) match {
               |      case (Right((${valueParams(current)})), Right(${valueParam(next)})) => new ConfigValue$next(Right((${valueParams(next)})))
               |      case (Left(errors), Right(_)) => new ConfigValue$next(Left(errors))
               |      case (Right(_), Left(error)) => new ConfigValue$next(Left(ConfigErrors(error)))
               |      case (Left(errors), Left(error)) => new ConfigValue$next(Left(errors append error))
               |    }
               |  }
               |}
               """.stripMargin.trim
            // format: on
          }

        val signature =
          s"private[$rootPackage] final class ConfigValue$current[$currentTypeParams](val value: Either[ConfigErrors, ($currentTypeParams)])"

        s"$signature$defs"
      }
      .mkString("\n\n")

    val content =
      s"""
         |// format: off
         |
         |$autoGeneratedNotice
         |
         |package $rootPackage
         |
         |$classes
       """.stripMargin.trim + "\n"

    val output = root / rootPackage / "ConfigValueClasses.scala"
    IO.write(output, content)
    Seq(output)
  }
}
