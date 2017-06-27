package viper.silver.parser

import java.nio.file.{Files, Path}
import scala.language.implicitConversions
import scala.language.reflectiveCalls
import scala.util.parsing.input.NoPosition
import fastparse.core.Parsed
import viper.silver.ast.SourcePosition
import viper.silver.FastPositions
import viper.silver.ast.utility.Rewriter.{PartialContextC, StrategyBuilder}
import viper.silver.verifier.ParseError

case class ParseException(msg: String, pos: scala.util.parsing.input.Position) extends Exception

object FastParser extends PosParser {

  var _lines: Array[Int] = null

  /** Set of already imported files.
    *
    * Only absolute paths should be recorded in order to prevent that different relative paths
    * (referencing the same file) result in importing files more than once.
    * Hence, the two methods [[isAlreadyImported]] and [[addToImported]] below.
    */
  private val _imported = collection.mutable.Set.empty[Path]

  private def isAlreadyImported(path: Path): Boolean =
    _imported.contains(path.toAbsolutePath)

  private def addToImported(path: Path): Boolean =
    _imported.add(path.toAbsolutePath)

  def parse(s: String, f: Path) = {
    _file = f
    val lines = s.linesWithSeparators
    _lines = lines.map(_.length).toArray

    // Strategy to handle imports
    // Idea: Import every import reference and merge imported methods, functions, imports, .. into current program
    //       iterate until no new imports are present.
    val importer = StrategyBuilder.Slim[PProgram]({
      case p: PProgram =>
        val firstImport = p.imports.headOption

        if (firstImport.isEmpty) {
          p
        } else {
          val toImport = firstImport.get
          if (addToImported(pathFromImport(toImport))) {
            val newProg = importProgram(toImport)

            PProgram(
              p.imports.drop(1) ++ newProg.imports,
              p.macros ++ newProg.macros,
              p.domains ++ newProg.domains,
              p.fields ++ newProg.fields,
              p.functions ++ newProg.functions,
              p.predicates ++ newProg.predicates,
              p.methods ++ newProg.methods,
              p.errors ++ newProg.errors)
          } else {
            PProgram(p.imports.drop(1), p.macros, p.domains, p.fields, p.functions, p.predicates, p.methods, p.errors)
          }
        }
        // Stop recursion at the program node already. Nodes other than PProgram are not
        // interesting for our transformation
    }).recurseFunc({ case p: PProgram => Seq() }).repeat

    try {
      val rp = RecParser(f).parses(s)
      rp match {
        case Parsed.Success(program@PProgram(_, _, _, _, _, _, _, errors), e) =>
          _imported.clear() // Don't keep state in between parsing programs (same parse instance might be reused)
          addToImported(f) // Add the current program to already imported
          val importedProgram = importer.execute[PProgram](program) // Import programs
          val expandedProgram = expandDefines(importedProgram) // Expand macros
          Parsed.Success(expandedProgram, e)
        case _ => rp
      }
    }
    catch {
      case e@ParseException(msg, pos) =>
        var line = 0
        var column = 0
        if (pos != null) {
          line = pos.line
          column = pos.column
        }
        ParseError(msg, SourcePosition(_file, line, column))
    }
  }

  case class RecParser(file: Path) {

    def parses(s: String) = {
      fastparser.parse(s)
    }
  }


  val White = PWrapper {
    import fastparse.all._

    NoTrace((("/*" ~ (AnyChar ~ !StringIn("*/")).rep ~ AnyChar ~ "*/") | ("//" ~ CharsWhile(_ != '\n').? ~ ("\n" | End)) | " " | "\t" | "\n" | "\r").rep)
  }

  import fastparse.noApi._

  import White._


  // Actual Parser starts from here
  def identContinues = CharIn('0' to '9', 'A' to 'Z', 'a' to 'z', "$_")

  def keyword(check: String) = check ~~ !identContinues

  def parens[A](p: fastparse.noApi.Parser[A]) = "(" ~ p ~ ")"

  def quoted[A](p: fastparse.noApi.Parser[A]) = "\"" ~ p ~ "\""

  def foldPExp[E <: PExp](e: PExp, es: Seq[PExp => E]): E =
    es.foldLeft(e) { (t, a) =>
      val result = a(t)
      result.setPos(t)
      result
    }.asInstanceOf[E]

  def isFieldAccess(obj: Any) = {
    obj.isInstanceOf[PFieldAccess]
  }

  /**
    * Function that parses a file and converts it into a program
    *
    * @param importStmt Import statement.
    * @return `PProgram` node corresponding to the imported program.
    */
  def importProgram(importStmt: PImport): PProgram = {
    val path = pathFromImport(importStmt)

    if (java.nio.file.Files.notExists(path))
      throw ParseException(s"""file "$path" does not exist""", FastPositions.getStart(importStmt))

    val source = scala.io.Source.fromInputStream(Files.newInputStream(path))
    val buffer = try {
      source.getLines.toArray
    } catch {
      case e@(_: RuntimeException | _: java.io.IOException) =>
        throw ParseException(s"""could not import file ($e)""", FastPositions.getStart(importStmt))
    } finally {
      source.close()
    }
    val imported_source = buffer.mkString("\n") + "\n"
    val p = RecParser(path).parses(imported_source)
    p match {
      case fastparse.core.Parsed.Success(prog, _) => prog
      case fastparse.core.Parsed.Failure(msg, next, extra) => throw ParseException(s"Failure: $msg", FilePosition(path, extra.line, extra.col))
    }
  }

  def pathFromImport(importStmt: PImport): Path = {
    val fileName = importStmt.file
    val path = file.getParent.resolve(fileName)
    path
  }

  /**
    * Expands the macros of a PProgram
    *
    * @param p PProgram with macros to be expanded
    * @return PProgram with expanded macros
    */
  def expandDefines(p: PProgram): PProgram = {
    val globalMacros = p.macros

    // Collect all global names to avoid conflicts
    val globalNames: Set[String] = (
         p.domains.map(_.idndef.name).toSet
      ++ p.functions.map(_.idndef.name).toSet
      ++ p.predicates.map(_.idndef.name).toSet
      ++ p.macros.map(_.idndef.name).toSet
      ++ p.methods.map(_.idndef.name).toSet
    )

    // Expand defines
    val domains =
      p.domains.map(domain => {
        val namesInScope = globalNames ++ domain.deepCollect { case d: PIdnDef => d.name }
        doExpandDefines[PDomain](globalMacros, domain, namesInScope)
      })

    val functions =
      p.functions.map(function => {
        val namesInScope = globalNames ++ function.deepCollect { case d: PIdnDef => d.name }
        doExpandDefines(globalMacros, function, namesInScope)
      })

    val predicates =
      p.predicates.map(predicate => {
        val namesInScope = globalNames ++ predicate.deepCollect { case d: PIdnDef => d.name }
        doExpandDefines(globalMacros, predicate, namesInScope)
      })

    val methods = p.methods.map(method => {
      val namesInScope = globalNames ++ method.deepCollect { case d: PIdnDef => d.name }

      // Collect all method local macros and expand them in the method
      // Remove the method local macros from the method for convenience
      val localMacros = method.deepCollect { case n: PDefine => n }

      val withoutDefines =
        if (localMacros.isEmpty)
          method
        else
          method.transform { case mac: PDefine => PSkip().setPos(mac) }()

      doExpandDefines(localMacros ++ globalMacros, withoutDefines, namesInScope)
    })

    PProgram(p.imports, p.macros, domains, p.fields, functions, predicates, methods, p.errors)
  }


  /**
    * Expand a macro inside a PNode of type T
    *
    * @param macros      All macros that could be invoked inside the code
    * @param toExpand    The AST node where we want to expand the macros in
    * @param namesInScope Names that are considered to be in scope for all of `toExpand`
    * @tparam T Type of the PNode
    * @return PNode with expanded macros of type T
    */
  def doExpandDefines[T <: PNode]
                     (macros: Seq[PDefine], toExpand: T, namesInScope: Set[String])
                     : T = {

    /* Variables currently in scope; locally bound variables must not clash with them */
    var namesCurrentlyInScope = namesInScope

    /* Store the replacements from normal variable to freshly generated variable */
    var freshNames = Map.empty[String, String]

    // It follows a list of useful helper classes and functions

    case class ReplaceContext(formalArgumentSubstitutions: Map[String, PExp] = Map.empty)

    // Context class used for expanding the macros themselves.
    // Seq macros contains the name of every macro we already imported to detect collisions
    case class ExpandContext(macros: Seq[String] = Seq())

    // Handy method to get a macro from its name string
    def getMacroByName(name: String): PDefine = macros.find(_.idndef.name == name) match {
      case Some(mac) => mac
      case None => throw ParseException(s"Macro " + name + " used but not present in scope", FastPositions.getStart(name))
    }

    // Check if a string is a valid macro name
    def isMacro(name: String): Boolean = macros.exists(_.idndef.name == name)

    // The position of every node inside the macro is the position where the macro is "called"
    def adaptPositions(body: PNode, f: FastPositioned): Unit = {
      val adapter = StrategyBuilder.SlimVisitor[PNode] {
        n => {
          FastPositions.setStart(n, f.start, force = true)
          FastPositions.setFinish(n, f.finish, force = true)
        }
      }
      adapter.execute[PNode](body)
    }

    def getFreshVar(context: ReplaceContext, name: String): String =
      getFreshVarWithSuffix(context, name, 0)

    // Acquire a fresh variable name for a macro definition
    // Rule: newName = name + $ + x where: x >= 0 and newName does not collide with global name
    def getFreshVarWithSuffix(context: ReplaceContext, name: String, counter: Int): String = {
      val newName = s"$name$$$counter"

      if (namesCurrentlyInScope.contains(newName)) {
        /* newName would clash with a name already in scope */

        /* TODO: Seems that the implementation could be optimised rather easily to avoid
         *       the linear search for the next "available" identifier
         */
        getFreshVarWithSuffix(context, name, counter + 1)
      } else {
        newName
      }
    }

    // Check if the macro name was already expanded => recursion found
    def recursionCheck(name: String, ctxt: ExpandContext) = {
      if (ctxt.macros.contains(name))
        throw ParseException("Recursive macro declaration found: " + name, NoPosition)
    }

    // Create a map that maps the formal parameters to the actual parameters of a macro call
    def mapParamsToArgs(params: Seq[PIdnDef], args: Seq[PExp]): Map[String, PExp] = {
      params.map(_.name).zip(args).toMap
    }

    // Strategy that replaces every formal parameter occurrence in the macro body with the corresponding actual parameter
    // Also makes the macro call hygienic by creating a unique variable name for every newly declared variable
    val replacer = StrategyBuilder.Context[PNode, ReplaceContext]({
      case (varDecl: PIdnDef, ctxt) =>
        /* We found a locally-bound variable (e.g. by a quantifier) */

        if (namesCurrentlyInScope.contains(varDecl.name)) {
          /* Rename locally bound variable to avoid name clashes */
          val freshName = getFreshVar(ctxt.c, varDecl.name)
          val freshDecl = PIdnDef(freshName)

          freshNames += varDecl.name -> freshName
          namesCurrentlyInScope += freshName

          adaptPositions(freshDecl, varDecl)

          freshDecl
        } else {
          /* Record locally-bound variable as in scope */
          namesCurrentlyInScope += varDecl.name

          varDecl
        }

      case (ident: PIdnUse, ctxt) if ctxt.c.formalArgumentSubstitutions.contains(ident.name) =>
        /* Replace formal with actual argument */
        val replaceParam = ctxt.c.formalArgumentSubstitutions(ident.name)
        replaceParam

      case (ident: PIdnUse, ctxt) if freshNames.contains(ident.name) =>
        /* Rename occurrence of a variable whose declaration has been renamed (case for PIdnDef
         * above) to avoid name clashes
         */
        PIdnUse(freshNames(ident.name))
    }, ReplaceContext()).duplicateEverything // Duplicate everything to avoid type checker bug with sharing (#191)

    val replacerContextUpdater: PartialFunction[(PNode, ReplaceContext), ReplaceContext] = {
      case (ident: PIdnUse, c) if c.formalArgumentSubstitutions.contains(ident.name) =>
        /* Matches case "replace formal with actual argument" above: having replaced a formal
         * with an actual argument, no further substitutions should be carried out for the
         * plugged-in actual argument.
         */
        c.copy(formalArgumentSubstitutions = c.formalArgumentSubstitutions.empty)
    }

    // Replace variables in macro body, adapt positions correctly (same line number as macro call)
    def replacerOnBody(body: PNode, p2a: Map[String, PExp], pos: FastPositioned): PNode = {
      /* TODO: It would be best if the context updater function were passed as another argument
       *       to the replacer above. That is already possible, but when the replacer is executed
       *       and an initial context is passed, that initial context's updater function (which
       *       defaults to "never update", if left unspecified) replaces the updater function that
       *       was initially passed to replacer.
       */
      val context =
        new PartialContextC[PNode, ReplaceContext](ReplaceContext(p2a), replacerContextUpdater)
      val res = replacer.execute[PNode](body, context)
      adaptPositions(res, pos)
      res
    }

    // Strategy that expands the macros and checks for infinite recursion in the expansion process
    val expander = StrategyBuilder.Context[PNode, ExpandContext]({
      case (pMacro: PMacroRef, ctxt) =>
        val name = pMacro.idnuse.name
        recursionCheck(name, ctxt.c)

        val body = getMacroByName(name).body

        if (!body.isInstanceOf[PStmt])
          throw ParseException("Expression macro used as statement", FastPositions.getStart(pMacro.idnuse))

        replacerOnBody(body, Map(), pMacro)

      case (pMacro: PMethodCall, ctxt) if isMacro(pMacro.method.name) =>
        val name = pMacro.method.name
        recursionCheck(name, ctxt.c)

        val realMacro = getMacroByName(name)
        val body = realMacro.body

        if (pMacro.args.length != realMacro.args.getOrElse(Seq()).length) // Would not be a PMethodCall in case of no arguments
          throw ParseException("Number of arguments does not match", FastPositions.getStart(pMacro.method))

        if (!body.isInstanceOf[PStmt])
          throw ParseException("Statement macro used as expression", FastPositions.getStart(pMacro.method))

        replacerOnBody(body, mapParamsToArgs(realMacro.args.get, pMacro.args), pMacro)

      case (pMacro: PCall, ctxt) if isMacro(pMacro.func.name) =>
        val name = pMacro.func.name
        recursionCheck(name, ctxt.c)

        val realMacro = getMacroByName(name)
        val body = realMacro.body

        if (pMacro.args.length != realMacro.args.getOrElse(Seq()).length) // Would not be a PMethodCall in case of no arguments
          throw ParseException("Number of arguments does not match", FastPositions.getStart(pMacro))

        if (!body.isInstanceOf[PExp])
          throw ParseException("Expression macro used as statement", FastPositions.getStart(pMacro))

        replacerOnBody(body, mapParamsToArgs(realMacro.args.get, pMacro.args), pMacro)

      case (pMacro: PIdnUse, ctxt) if isMacro(pMacro.name) =>
        val name = pMacro.name
        recursionCheck(name, ctxt.c)

        val body = getMacroByName(name).body

        if (!body.isInstanceOf[PExp])
          throw ParseException("Statement macro used as expression", FastPositions.getStart(pMacro))

        replacerOnBody(body, Map(), pMacro)

    }, ExpandContext(), {
      case (pMacro: PMacroRef, c) =>
        val realMacro = getMacroByName(pMacro.idnuse.name)
        ExpandContext(c.macros ++ Seq(realMacro.idndef.name))

      case (pMacro: PMethodCall, c) if isMacro(pMacro.method.name) =>
        val realMacro = getMacroByName(pMacro.method.name)
        ExpandContext(c.macros ++ Seq(realMacro.idndef.name))

      case (pMacro: PCall, c) if isMacro(pMacro.func.name) =>
        val realMacro = getMacroByName(pMacro.func.name)
        ExpandContext(c.macros ++ Seq(realMacro.idndef.name))

      case (pMacro: PIdnUse, c) if isMacro(pMacro.name) =>
        val realMacro = getMacroByName(pMacro.name)
        ExpandContext(c.macros ++ Seq(realMacro.idndef.name))
    }).recurseFunc {
      /* Don't recurse into the PIdnUse of nodes that themselves could represent macro
       * applications. Otherwise, the expansion of nested macros will fail due to attempting
       * to construct invalid AST nodes.
       * Recursing into such PIdnUse nodes caused Silver issue #205.
       */
      case PMacroRef(_) => Seq.empty
      case PMethodCall(targets, _, args) => Seq(targets, args)
      case PCall(_, args, typeAnnotated) => Seq(args, typeAnnotated)
    }.repeat

    val res = expander.execute[T](toExpand)
    res
  }

  /** The file we are currently parsing (for creating positions later). */
  def file: Path = _file


  val keywords = Set("result",
    // types
    "Int", "Perm", "Bool", "Ref", "Rational",
    // boolean constants
    "true", "false",
    // null
    "null",
    // preamble importing
    "import",
    // declaration keywords
    "method", "function", "predicate", "program", "domain", "axiom", "var", "returns", "field", "define", "wand",
    // specifications
    "requires", "ensures", "decreases", "invariant",
    // statements
    "fold", "unfold", "inhale", "exhale", "new", "assert", "assume", "package", "apply",
    // control flow
    "while", "if", "elseif", "else", "goto", "label",
    // special fresh block
    "fresh", "constraining",
    // sequences
    "Seq",
    // sets and multisets
    "Set", "Multiset", "union", "intersection", "setminus", "subset",
    // prover hint expressions
    "unfolding", "in", "folding", "applying", "packaging",
    // old expression
    "old", "lhs",
    // other expressions
    "let",
    // quantification
    "forall", "exists", "forperm",
    // permission syntax
    "acc", "wildcard", "write", "none", "epsilon", "perm",
    // modifiers
    "unique")


  lazy val atom: P[PExp] = P(integer | booltrue | boolfalse | nul | old | applyOld
    | result | unExp
    | "(" ~ exp ~ ")" | accessPred | inhaleExhale | perm | let | quant | forperm | unfolding | folding | applying
    | packaging | setTypedEmpty | explicitSetNonEmpty | multiSetTypedEmpty | explicitMultisetNonEmpty | seqTypedEmpty
    | seqLength | explicitSeqNonEmpty | seqRange | fapp | typedFapp | idnuse)


  lazy val result: P[PResultLit] = P(keyword("result").map { _ => PResultLit() })

  lazy val unExp: P[PUnExp] = P((CharIn("-!+").! ~ suffixExpr).map { case (a, b) => PUnExp(a, b) })

  lazy val integer: P[PIntLit] = P(CharIn('0' to '9').rep(1)).!.map { s => PIntLit(BigInt(s)) }

  lazy val booltrue: P[PBoolLit] = P(keyword("true")).map(_ => PBoolLit(b = true))

  lazy val boolfalse: P[PBoolLit] = P(keyword("false")).map(_ => PBoolLit(b = false))

  lazy val nul: P[PNullLit] = P(keyword("null")).map(_ => PNullLit())

  lazy val identifier: P[Unit] = P(CharIn('A' to 'Z', 'a' to 'z', "$_") ~~ CharIn('0' to '9', 'A' to 'Z', 'a' to 'z', "$_").repX)

  lazy val ident: P[String] = P(identifier.!).filter { case a => !keywords.contains(a) }.opaque("invalid identifier (could be a keyword)")

  lazy val idnuse: P[PIdnUse] = P(ident).map(PIdnUse)

  lazy val old: P[PExp] = P(StringIn("old") ~ (parens(exp).map(POld) | ("[" ~ idnuse ~ "]" ~ parens(exp)).map { case (a, b) => PLabelledOld(a, b) }))

  lazy val applyOld: P[PExp] = P((StringIn("lhs") ~ parens(exp)).map(PApplyOld))

  lazy val magicWandExp: P[PExp] = P(orExp ~ ("--*".! ~ exp).?).map { case (a, b) => b match {
    case Some(c) => PBinExp(a, c._1, c._2)
    case None => a
  }
  }

  lazy val realMagicWandExp: P[PExp] = P((orExp ~ "--*".! ~ magicWandExp).map { case (a, b, c) => PBinExp(a, b, c) })

  lazy val implExp: P[PExp] = P(magicWandExp ~ (StringIn("==>").! ~ implExp).?).map { case (a, b) => b match {
    case Some(c) => PBinExp(a, c._1, c._2)
    case None => a
  }
  }
  lazy val iffExp: P[PExp] = P(implExp ~ ("<==>".! ~ iffExp).?).map { case (a, b) => b match {
    case Some(c) => PBinExp(a, c._1, c._2)
    case None => a
  }
  }
  lazy val iteExpr: P[PExp] = P(iffExp ~ ("?" ~ iteExpr ~ ":" ~ iteExpr).?).map { case (a, b) => b match {
    case Some(c) => PCondExp(a, c._1, c._2)
    case None => a
  }
  }
  lazy val exp: P[PExp] = P(iteExpr)

  lazy val suffix: fastparse.noApi.Parser[PExp => PExp] =
    P(("." ~ idnuse).map { id => (e: PExp) => PFieldAccess(e, id) } |
      ("[.." ~/ exp ~ "]").map { n => (e: PExp) => PSeqTake(e, n) } |
      ("[" ~ exp ~ "..]").map { n => (e: PExp) => PSeqDrop(e, n) } |
      ("[" ~ exp ~ ".." ~ exp ~ "]").map { case (n, m) => (e: PExp) => PSeqDrop(PSeqTake(e, m), n) } |
      ("[" ~ exp ~ "]").map { e1 => (e0: PExp) => PSeqIndex(e0, e1) } |
      ("[" ~ exp ~ ":=" ~ exp ~ "]").map { case (i, v) => (e: PExp) => PSeqUpdate(e, i, v) })

  lazy val suffixExpr: P[PExp] = P((atom ~ suffix.rep).map { case (fac, ss) => foldPExp[PExp](fac, ss) })

  lazy val realSuffixExpr: P[PExp] = P((atom ~ suffix.rep).map { case (fac, ss) => foldPExp[PExp](fac, ss) })

  lazy val termOp: P[String] = P(StringIn("*", "/", "\\", "%").!)

  lazy val term: P[PExp] = P((suffixExpr ~ termd.rep).map { case (a, ss) => foldPExp[PExp](a, ss) })

  lazy val termd: P[PExp => PBinExp] = P(termOp ~ suffixExpr).map { case (op, id) => (e: PExp) => PBinExp(e, op, id) }

  lazy val sumOp: P[String] = P(StringIn("++", "+", "-").! | keyword("union").! | keyword("intersection").! | keyword("setminus").! | keyword("subset").!)

  lazy val sum: P[PExp] = P((term ~ sumd.rep).map { case (a, ss) => foldPExp[PBinExp](a, ss) })

  lazy val sumd: P[PExp => PBinExp] = P(sumOp ~ term).map { case (op, id) => (e: PExp) => PBinExp(e, op, id) }

  lazy val cmpOp = P(StringIn("<=", ">=", "<", ">").! | keyword("in").!)

  lazy val cmpExp: P[PExp] = P(sum ~ (cmpOp ~ cmpExp).?).map { case (a, b) => b match {
    case Some(c) => PBinExp(a, c._1, c._2)
    case None => a
  }
  }

  lazy val eqOp = P(StringIn("==", "!=").!)

  lazy val eqExp: P[PExp] = P(cmpExp ~ (eqOp ~ eqExp).?).map { case (a, b) => b match {
    case Some(c) => PBinExp(a, c._1, c._2)
    case None => a
  }
  }
  lazy val andExp: P[PExp] = P(eqExp ~ ("&&".! ~ andExp).?).map { case (a, b) => b match {
    case Some(c) => PBinExp(a, c._1, c._2)
    case None => a
  }
  }
  lazy val orExp: P[PExp] = P(andExp ~ ("||".! ~ orExp).?).map { case (a, b) => b match {
    case Some(c) => PBinExp(a, c._1, c._2)
    case None => a
  }
  }

  lazy val accessPredImpl: P[PAccPred] = P((keyword("acc") ~/ "(" ~ locAcc ~ ("," ~ exp).? ~ ")").map {
    case (loc, perms) => PAccPred(loc, perms.getOrElse(PFullPerm()))
  })

  lazy val accessPred: P[PAccPred] = P(accessPredImpl.map {
    case acc => {
      val perm = acc.perm
      if (FastPositions.getStart(perm) == NoPosition) {
        FastPositions.setStart(perm, acc.start)
        FastPositions.setFinish(perm, acc.finish)
      }
      acc
    }
  })

  lazy val locAcc: P[PLocationAccess] = P(fieldAcc | predAcc)

  lazy val fieldAcc: P[PFieldAccess] =
    P(realSuffixExpr.filter(isFieldAccess).map {
      case fa: PFieldAccess => fa
      case other => sys.error(s"Unexpectedly found $other")
    })

  lazy val predAcc: P[PLocationAccess] = P(fapp)

  lazy val actualArgList: P[Seq[PExp]] = P(exp.rep(sep = ","))

  lazy val inhaleExhale: P[PExp] = P("[" ~ exp ~ "," ~ exp ~ "]").map { case (a, b) => PInhaleExhaleExp(a, b) }

  lazy val perm: P[PExp] = P(keyword("none").map(_ => PNoPerm()) | keyword("wildcard").map(_ => PWildcard()) | keyword("write").map(_ => PFullPerm())
    | keyword("epsilon").map(_ => PEpsilon()) | ("perm" ~ parens(locAcc)).map(PCurPerm))

  lazy val let: P[PExp] = P(
    ("let" ~/ idndef ~ "==" ~ "(" ~ exp ~ ")" ~ "in" ~ exp).map { case (id, exp1, exp2) =>
      /* Type unresolvedType is expected to be replaced with the type of exp1
       * after the latter has been resolved
       * */
      val unresolvedType = PUnknown().setPos(id)
      val formalArgDecl = PFormalArgDecl(id, unresolvedType).setPos(id)
      val nestedScope = PLetNestedScope(formalArgDecl, exp2).setPos(exp2)

      PLet(exp1, nestedScope)
    })

  lazy val idndef: P[PIdnDef] = P(ident).map(PIdnDef)

  lazy val quant: P[PExp] = P((keyword("forall") ~/ nonEmptyFormalArgList ~ "::" ~/ trigger.rep ~ exp).map { case (a, b, c) => PForall(a, b, c) } |
    (keyword("exists") ~/ nonEmptyFormalArgList ~ "::" ~ exp).map { case (a, b) => PExists(a, b) })

  lazy val nonEmptyFormalArgList: P[Seq[PFormalArgDecl]] = P(formalArg.rep(min = 1, sep = ","))

  lazy val formalArg: P[PFormalArgDecl] = P(idndef ~ ":" ~ typ).map { case (a, b) => PFormalArgDecl(a, b) }

  lazy val typ: P[PType] = P(primitiveTyp | domainTyp | seqType | setType | multisetType)

  lazy val domainTyp: P[PDomainType] = P((idnuse ~ "[" ~ typ.rep(sep = ",") ~ "]").map { case (a, b) => PDomainType(a, b) } |
    idnuse.map {
      // domain type without type arguments (might also be a type variable)
      case name => PDomainType(name, Nil)
    })

  lazy val seqType: P[PType] = P(keyword("Seq") ~/ "[" ~ typ ~ "]").map(PSeqType)

  lazy val setType: P[PType] = P(keyword("Set") ~/ "[" ~ typ ~ "]").map(PSetType)

  lazy val multisetType: P[PType] = P(keyword("Multiset") ~/ "[" ~ typ ~ "]").map(PMultisetType)

  lazy val primitiveTyp: P[PType] = P(keyword("Rational").map { case _ => PPrimitiv("Perm") }
    | (StringIn("Int", "Bool", "Perm", "Ref") ~~ !identContinues).!.map(PPrimitiv))

  lazy val trigger: P[PTrigger] = P("{" ~/ exp.rep(sep = ",", min = 1) ~ "}").map(s => PTrigger(s))

  lazy val forperm: P[PExp] = P(keyword("forperm") ~ "[" ~ idnuse.rep(sep = ",") ~ "]" ~ idndef ~ "::" ~/ exp).map {
    case (ids, id, body) => PForPerm(PFormalArgDecl(id, PPrimitiv("Ref")), ids, body)
  }

  lazy val unfolding: P[PExp] = P(keyword("unfolding") ~/ predicateAccessPred ~ "in" ~ exp).map { case (a, b) => PUnfolding(a, b) }

  lazy val predicateAccessPred: P[PAccPred] = P(accessPred | predAcc.map {
    case loc => {
      val perm = PFullPerm()
      FastPositions.setStart(perm, loc.start)
      FastPositions.setFinish(perm, loc.finish)
      PAccPred(loc, perm)
    }
  })

  lazy val folding: P[PExp] = P(keyword("folding") ~/ predicateAccessPred ~ "in" ~ exp).map { case (a, b) => PFoldingGhostOp(a, b) }

  lazy val applying: P[PExp] =

  /**
    * We must be careful here to not create ambiguities in our grammar.
    * when 'magicWandExp' is used instead of the more specific
    * 'realMagicWandExp | idnuse', then the following problem can occur:
    * Consider an expression such as "applying w in A". The parser
    * will interpret "w in A" as a set-contains expression, which is
    * fine according to our rules.
    * The outer applying-rule will fail.
    * Possible solution is that we should backtrack enough
    * to reparse "w in A", but this time as desired, not as a
    * set-contains expression.
    */
    P("applying" ~ ("(" ~ realMagicWandExp ~ ")" | idnuse) ~ ("in" ~ exp)).map { case (a, b) => PApplyingGhostOp(a, b) }

  lazy val packaging: P[PExp] = /* See comment on applying */
    P("packaging" ~ ("(" ~ realMagicWandExp ~ ")" | idnuse) ~ "in" ~ exp).map { case (a, b) => PPackagingGhostOp(a, b) }

  lazy val setTypedEmpty: P[PExp] = collectionTypedEmpty("Set", PEmptySet)

  lazy val explicitSetNonEmpty: P[PExp] = P("Set" ~ "(" ~/ exp.rep(sep = ",", min = 1) ~ ")").map(PExplicitSet)

  lazy val explicitMultisetNonEmpty: P[PExp] = P("Multiset" ~ "(" ~/ exp.rep(min = 1, sep = ",") ~ ")").map(PExplicitMultiset)

  lazy val multiSetTypedEmpty: P[PExp] = collectionTypedEmpty("Multiset", PEmptyMultiset)

  lazy val seqTypedEmpty: P[PExp] = collectionTypedEmpty("Seq", PEmptySeq)

  lazy val seqLength: P[PExp] = P("|" ~ exp ~ "|").map(PSize)

  lazy val explicitSeqNonEmpty: P[PExp] = P("Seq" ~ "(" ~/ exp.rep(min = 1, sep = ",") ~ ")").map(PExplicitSeq)

  private def collectionTypedEmpty(name: String, typeConstructor: PType => PExp): P[PExp] =
    P(`name` ~ ("[" ~/ typ ~ "]").? ~ "(" ~ ")").map(typ => typeConstructor(typ.getOrElse(PTypeVar("#E"))))


  lazy val seqRange: P[PExp] = P("[" ~ exp ~ ".." ~ exp ~ ")").map { case (a, b) => PRangeSeq(a, b) }


  lazy val fapp: P[PCall] = P(idnuse ~ parens(actualArgList)).map {
    case (func, args) => PCall(func, args, None)
  }

  lazy val typedFapp: P[PExp] = P(parens(idnuse ~ parens(actualArgList) ~ ":" ~ typ)).map {
    case (func, args, typeGiven) => PCall(func, args, Some(typeGiven))
  }


  lazy val stmt: P[PStmt] = P(fieldassign | localassign | fold | unfold | exhale | assertP |
    inhale | assume | ifthnels | whle | varDecl | defineDecl | letwandDecl | newstmt | fresh | constrainingBlock |
    methodCall | goto | lbl | packageWand | applyWand | macroref)

  lazy val nodefinestmt: P[PStmt] = P(fieldassign | localassign | fold | unfold | exhale | assertP |
    inhale | assume | ifthnels | whle | varDecl | letwandDecl | newstmt | fresh | constrainingBlock |
    methodCall | goto | lbl | packageWand | applyWand | macroref)

  lazy val macroref: P[PMacroRef] = P(idnuse).map { case (a) => PMacroRef(a) }

  lazy val fieldassign: P[PFieldAssign] = P(fieldAcc ~ ":=" ~ exp).map { case (a, b) => PFieldAssign(a, b) }

  lazy val localassign: P[PVarAssign] = P(idnuse ~ ":=" ~ exp).map { case (a, b) => PVarAssign(a, b) }

  lazy val fold: P[PFold] = P("fold" ~ predicateAccessPred).map(PFold)

  lazy val unfold: P[PUnfold] = P("unfold" ~ predicateAccessPred).map(PUnfold)

  lazy val exhale: P[PExhale] = P(keyword("exhale") ~/ exp).map(PExhale)

  lazy val assertP: P[PAssert] = P(keyword("assert") ~/ exp).map(PAssert)

  lazy val inhale: P[PInhale] = P(keyword("inhale") ~/ exp).map(PInhale)

  lazy val assume: P[PAssume] = P(keyword("assume") ~/ exp).map(PAssume)

  lazy val ifthnels: P[PIf] = P("if" ~ "(" ~ exp ~ ")" ~ block ~ elsifEls).map {
    case (cond, thn, ele) => PIf(cond, PSeqn(thn), ele)
  }

  lazy val block: P[Seq[PStmt]] = P("{" ~ stmts ~ "}")

  lazy val stmts: P[Seq[PStmt]] = P(stmt ~/ ";".?).rep

  lazy val elsifEls: P[PStmt] = P(elsif | els)

  lazy val elsif: P[PStmt] = P("elseif" ~/ "(" ~ exp ~ ")" ~ block ~ elsifEls).map {
    case (cond, thn, ele) => PIf(cond, PSeqn(thn), ele)
  }

  lazy val els: P[PStmt] = (keyword("else") ~/ block).?.map { block => PSeqn(block.getOrElse(Nil)) }

  lazy val whle: P[PWhile] = P(keyword("while") ~/ "(" ~ exp ~ ")" ~ inv.rep ~ block).map {
    case (cond, invs, body) => PWhile(cond, invs, PSeqn(body))
  }

  lazy val inv: P[PExp] = P(keyword("invariant") ~ exp ~ ";".?)

  lazy val varDecl: P[PLocalVarDecl] = P(keyword("var") ~/ idndef ~ ":" ~ typ ~ (":=" ~ exp).?).map { case (a, b, c) => PLocalVarDecl(a, b, c) }

  lazy val defineDecl: P[PDefine] = P(keyword("define") ~/ idndef ~ ("(" ~ idndef.rep(sep = ",") ~ ")").? ~ (exp | "{" ~ (nodefinestmt ~ ";".?).rep ~ "}")).map {
    case (a, b, c) => c match {
      case e: PExp => PDefine(a, b, e)
      case ss: Seq[PStmt]@unchecked => PDefine(a, b, PSeqn(ss))
    }
  }

  lazy val letwandDecl: P[PLetWand] = P(keyword("wand") ~/ idndef ~ ":=" ~ exp).map { case (a, b) => PLetWand(a, b) }

  lazy val newstmt: P[PNewStmt] = P(idnuse ~ ":=" ~ "new" ~ "(" ~ starOrFields ~ ")").map { case (a, b) => PNewStmt(a, b) }

  lazy val starOrFields: P[Option[Seq[PIdnUse]]] = P(("*").!.map { _ => None } | (idnuse.rep(sep = ",").map { fields => Some(fields) }))

  lazy val fresh: P[PFresh] = P(keyword("fresh") ~ idnuse.rep(sep = ",")).map { case vars => PFresh(vars) }

  lazy val constrainingBlock: P[PConstraining] = P("constraining" ~ "(" ~ idnuse.rep(sep = ",") ~ ")" ~ block).map { case (vars, s) => PConstraining(vars, PSeqn(s)) }

  lazy val methodCall: P[PMethodCall] = P((idnuse.rep(sep = ",") ~ ":=").? ~ idnuse ~ parens(exp.rep(sep = ","))).map {
    case (None, method, args) => PMethodCall(Nil, method, args)
    case (Some(targets), method, args) => PMethodCall(targets, method, args)
  }

  lazy val goto: P[PGoto] = P("goto" ~/ idnuse).map(PGoto)

  lazy val lbl: P[PLabel] = P(keyword("label") ~/ idndef ~ (keyword("invariant") ~/ exp).rep).map { case (name, invs) => PLabel(name, invs) }

  lazy val packageWand: P[PPackageWand] = P("package" ~/ magicWandExp).map(PPackageWand)

  lazy val applyWand: P[PApplyWand] = P("apply" ~/ magicWandExp).map(PApplyWand)

  lazy val programDecl: P[PProgram] = P((preambleImport | defineDecl | domainDecl | fieldDecl | functionDecl | predicateDecl | methodDecl).rep).map {
    decls => {
      PProgram(
        decls.collect { case i: PImport => i }, // Imports
        decls.collect { case d: PDefine => d }, // Macros
        decls.collect { case d: PDomain => d }, // Domains
        decls.collect { case f: PField => f }, // Fields
        decls.collect { case f: PFunction => f }, // Functions
        decls.collect { case p: PPredicate => p }, // Predicates
        decls.collect { case m: PMethod => m }, // Methods
        Seq() // Parse Errors
      )
    }
  }

  lazy val preambleImport: P[PImport] = P(keyword("import") ~/ quoted(relativeFilePath.!)).map {
    case filename => PImport(filename)
  }

  lazy val relativeFilePath: P[String] = P((CharIn("~.").?).! ~~ (CharIn("/").? ~~ CharIn(".", 'A' to 'Z', 'a' to 'z', '0' to '9', "_- \n\t")).rep(1))

  lazy val domainDecl: P[PDomain] = P("domain" ~/ idndef ~ ("[" ~ domainTypeVarDecl.rep(sep = ",") ~ "]").? ~ "{" ~ (domainFunctionDecl | axiomDecl).rep ~
    "}").map {
    case (name, typparams, members) =>
      val funcs = members collect { case m: PDomainFunction1 => m }
      val axioms = members collect { case m: PAxiom1 => m }
      PDomain(
        name,
        typparams.getOrElse(Nil),
        funcs map (f => PDomainFunction(f.idndef, f.formalArgs, f.typ, f.unique)(PIdnUse(name.name)).setPos(f)),
        axioms map (a => PAxiom(a.idndef, a.exp)(PIdnUse(name.name)).setPos(a)))
  }

  lazy val domainTypeVarDecl: P[PTypeVarDecl] = P(idndef).map(PTypeVarDecl)

  lazy val domainFunctionDecl: P[PDomainFunction1] = P("unique".!.? ~ functionSignature ~ ";".?).map {
    case (unique, fdecl) => fdecl match {
      case (name, formalArgs, t) => PDomainFunction1(name, formalArgs, t, unique.isDefined)
    }
  }

  lazy val functionSignature = P("function" ~ idndef ~ "(" ~ formalArgList ~ ")" ~ ":" ~ typ)

  lazy val formalArgList: P[Seq[PFormalArgDecl]] = P(formalArg.rep(sep = ","))

  lazy val axiomDecl: P[PAxiom1] = P(keyword("axiom") ~ idndef ~ "{" ~ exp ~ "}" ~ ";".?).map { case (a, b) => PAxiom1(a, b) }

  lazy val fieldDecl: P[PField] = P("field" ~/ idndef ~ ":" ~ typ ~ ";".?).map { case (a, b) => PField(a, b) }

  lazy val functionDecl: P[PFunction] = P("function" ~/ idndef ~ "(" ~ formalArgList ~ ")" ~ ":" ~ typ ~ pre.rep ~
    post.rep ~ dec.rep ~ ("{" ~ exp ~ "}").?).map { case (a, b, c, d, e, f, g) => PFunction(a, b, c, d, e, f.flatten, g) }


  lazy val pre: P[PExp] = P("requires" ~/ exp ~ ";".?)

  lazy val post: P[PExp] = P("ensures" ~/ exp ~ ";".?)

  lazy val dec: P[Seq[PExp]] = P("decreases" ~/ decCl ~ ";".?) //pege

  //lazy val dec2: P[Seq[Char]] = P("decreases" ~/ "*" ~ ";".?) //pege

  lazy val decCl: P[Seq[PExp]] = P(exp.rep(sep = ","))

  lazy val predicateDecl: P[PPredicate] = P("predicate" ~/ idndef ~ "(" ~ formalArgList ~ ")" ~ ("{" ~ exp ~ "}").?).map { case (a, b, c) => PPredicate(a, b, c) }

  lazy val methodDecl: P[PMethod] = P(methodSignature ~/ pre.rep ~ post.rep ~ block.?).map {
    case (name, args, rets, pres, posts, Some(body)) =>
      PMethod(name, args, rets.getOrElse(Nil), pres, posts, PSeqn(body))
    case (name, args, rets, pres, posts, None) =>
      PMethod(name, args, rets.getOrElse(Nil), pres, posts, PSeqn(Seq(PInhale(PBoolLit(b = false)))))
  }

  lazy val methodSignature = P("method" ~/ idndef ~ "(" ~ formalArgList ~ ")" ~ ("returns" ~ "(" ~ formalArgList ~ ")").?)

  lazy val fastparser: P[PProgram] = P(Start ~ programDecl ~ End)


}
