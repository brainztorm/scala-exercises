package services.parser

import models.{Section, Category, Exercise}

import scala.annotation.tailrec
import scalaz._, Scalaz._

/**
 * Parses source code extracting section, categories and exercise code.
 * This should provably be rewritten at some point with Parser Combinators or a more robust solution than
 * top to botton regex matching
 */
object ExerciseCodeExtractor {

  private[this] sealed trait ParserState

  private[this] case object DefiningClass extends ParserState

  private[this] case object AccumulatingExercises extends ParserState

  private[this] case object ExtractingCode extends ParserState

  private[this] val descLineSep = "\n"

  private[this] case class CategoryBuilder(
      title: Option[String] = None,
      description: Option[String] = None,
      currentExercise: Option[Exercise] = None,
      exercises: List[Exercise] = Nil,
      state: ParserState = DefiningClass) {

    def categoryTitle(title: String) =
      copy(title = title.some)

    def addToCategoryDescription(line: String) =
      copy(description = description.map(_.concat(descLineSep)) |+| line.some)

    def setCurrentExercise(exercise: Exercise) =
      copy(currentExercise = exercise.some)

    def addCurrentExercise = currentExercise match {
      case Some(ex) => copy(exercises = exercises :+ ex, currentExercise = None)
      case None => this
    }

    def setExerciseMethod(method: String) =
      copy(currentExercise = currentExercise map (e => e.copy(method = method.some)))

    def setExerciseTitle(title: String) =
      copy(currentExercise = currentExercise map (e => e.copy(title = title.some)))

    def addToExerciseDesc(line: String) =
      copy(currentExercise = currentExercise map (e => e.copy(description = e.description.map(_.concat(descLineSep)) |+| line.some)))

    def addToExerciseCode(line: String) =
      copy(currentExercise = currentExercise map (e => e.copy(code = e.code.map(_.concat(descLineSep)) |+| line.some)))

    def setState(state: ParserState) = copy(state = state)

    def build: Option[Category] = (title, description, exercises) match {
      case (Some(t), Some(d), head :: tail) => Some(Category(t, Some(d), head :: tail))
      case _ => None
    }

  }

  private[this] val commentStart = "\\s*\\/\\*\\*(.*)".r
  private[this] val pkgStarts = "\\s*package\\s*object\\s+(\\w+).*".r
  private[this] val classStarts = "\\s*class\\s+(\\w+)\\s+extends.*Exercises.*".r
  private[this] val inComment = "\\s*\\*([^\\/\\n]+).*".r
  private[this] val commentEnd = "\\s*\\*\\/[ \\t]*".r
  private[this] val methodStart = "\\s*def\\s+(\\w+)(\\([^\\)]+\\))?(:\\s+ExerciseResult\\[.*\\])?\\s*=\\s*ExerciseRunner\\(\"(.+)\"\\)\\s*\\{".r
  private[this] val methodEnd = "\\s*\\}\\(∞\\).*".r

  import utils.StringUtils._

  /**
   * Recursively walks the source lines building a Category and it's all contained exercises
   */
  def buildCategory(sources: List[String]): Option[Category] = {
    @tailrec
    def loop(remaining: List[String], builder: CategoryBuilder): CategoryBuilder =
      remaining match {
        case Nil => builder
        case h :: t =>
          val modBuilder = (h, builder.state) match {
            case (commentStart(startLine), DefiningClass) =>
              builder.addToCategoryDescription(startLine)
            case (inComment(comment), DefiningClass) =>
              builder.addToCategoryDescription(comment)
            case (classStarts(className), DefiningClass) =>
              builder.categoryTitle(className.humanizeCamelCase).setState(AccumulatingExercises)
            case (commentStart(comment), AccumulatingExercises) =>
              builder.setCurrentExercise(Exercise()).addToExerciseDesc(comment)
            case (inComment(comment), AccumulatingExercises) =>
              builder.addToExerciseDesc(comment)
            case (methodStart(method, _, _, friendlyDes), AccumulatingExercises) =>
              builder.setExerciseTitle(friendlyDes).setExerciseMethod(method).setState(ExtractingCode)
            case (methodEnd(), ExtractingCode) =>
              builder.addCurrentExercise.setState(AccumulatingExercises)
            case (code, ExtractingCode) =>
              builder.addToExerciseCode(code)
            case _ => builder
          }
          loop(t, modBuilder)
      }
    loop(sources, CategoryBuilder()).build
  }

  /**
   * Recursive walks a package object sources parsing section information
   */
  def buildSection(sources: List[String]): Option[Section] = {
    @tailrec
    def loop(remaining: List[String], maybeSection: Option[Section] = None): Option[Section] =
      remaining match {
        case Nil => maybeSection
        case h :: t =>
          val modSection = (h, maybeSection) match {
            case (commentStart(startLine), None) =>
              Section(title = "", description = startLine).some
            case (inComment(comment), Some(section)) =>
              section.copy(description = section.description.concat(comment)).some
            case (pkgStarts(pkgName), Some(section)) =>
              section.copy(title = pkgName).some
            case (_, Some(section)) =>
              section.some
            case _ => None
          }
          loop(t, modSection)
      }
    loop(sources)
  }


}