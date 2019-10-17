package com.datasonnet

import java.io.{File, PrintWriter, StringWriter}

import com.datasonnet.portx.JacksonAdapter
import com.datasonnet.wrap.{DataSonnetPath, NoFileEvaluator}
import fastparse.{IndexedParserInput, Parsed}
import sjsonnet.Expr.Member.Visibility
import sjsonnet.Expr.Params
import sjsonnet.Val.Lazy
import sjsonnet._

import scala.collection.JavaConverters._
import scala.io.Source
import scala.util.{Failure, Success, Try}
import scala.util.chaining._


case class StringDocument(contents: String, mimeType: String) extends Document

case class ObjectDocument(contents: Object, mimeType: String) extends Document

object Mapper {
  def wrap(jsonnet: String, argumentNames: Iterable[String])=
    (Seq("payload") ++ argumentNames).mkString("function(", ",", ")\n") + jsonnet




  val location = raw"\.\(([a-zA-Z-_\.]*):(\d+):(\d+)\)|([a-zA-Z-]+):(\d+):(\d+)".r
  def expandErrorLineNumber(error: String, lineOffset: Int) = location.replaceAllIn(error, _ match {
    case location(filename, frow, fcolumn, token, trow, tcolumn) => {
      if (token != null) {
        s"$token at line ${trow.toInt - lineOffset} column $tcolumn"
      } else if (filename.length > 0) {
        s"$filename line $frow column $fcolumn"
      } else {
        s"line ${frow.toInt - lineOffset} column $fcolumn of the transformation"
      }
    }
  })


  def evaluate(evaluator: Evaluator, cache: collection.mutable.Map[String, fastparse.Parsed[(Expr, Map[String, Int])]], jsonnet: String, libraries: Map[String, Val], lineOffset: Int): Try[Val] = {

    for {
      fullParse <- cache.getOrElseUpdate(jsonnet, fastparse.parse(jsonnet, Parser.document(_))) match {
        case f @ Parsed.Failure(l, i, e) => Failure(new IllegalArgumentException("Problem parsing: " + expandErrorLineNumber(f.trace().msg, lineOffset)))
        case Parsed.Success(r, index) => Success(r)
      }

      (parsed, indices) = fullParse

      evaluated <-
        try Success(evaluator.visitExpr(parsed)(
          Mapper.scope(indices, libraries),
          new FileScope(DataSonnetPath("."), indices)
        ))
        catch { case e: Throwable =>
          val s = new StringWriter()
          val p = new PrintWriter(s)
          e.printStackTrace(p)
          p.close()
          Failure(new IllegalArgumentException("Please contact the developers with this error! Problem compiling: " + s.toString.replace("\t", "    ")))
        }
    } yield evaluated
  }

  def scope(indices: Map[String, Int], roots: Map[String, Val]) = Std.scope(indices.size + 1).extend(
    roots flatMap {
      case (key, value) =>
        if (indices.contains(key))
          Seq((indices(key), (_: Option[Val.Obj], _: Option[Val.Obj]) => Lazy(value)))
        else
          Seq()
    }
  )

  def objectify(objects: Map[String, Val]) = new Val.Obj(
    objects.map{
      case (key, value) =>
        (key, Val.Obj.Member(false, Visibility.Hidden, (self: Val.Obj, sup: Option[Val.Obj], _, _) => value))
    }
    .toMap,
    _ => (),
    None
  )

  def library(evaluator: Evaluator, name: String, cache: collection.mutable.Map[String, fastparse.Parsed[(Expr, Map[String, Int])]]): (String, Val) = {
    val jsonnetLibrarySource = Source.fromURL(getClass.getResource(s"/$name.libsonnet")).mkString
    val jsonnetLibrary = Mapper.evaluate(evaluator, cache, jsonnetLibrarySource, Map(), 0).get  // libraries are never wrapped
    (name -> jsonnetLibrary)
  }

  def input(data: Document): Expr = {
    val json = data.mimeType match {
      case "text/plain" | "application/csv" | "application/xml" => ujson.Str(data.contents.toString)
      case "application/json" => ujson.read(data.contents.toString)
      case "application/java" => JacksonAdapter.javaToJson(data.contents());
      case x => throw new IllegalArgumentException("The input mime type " + x + " is not supported")
    }

    Materializer.toExpr(json)
  }

  def output(output: ujson.Value, mimeType: String): Document = {
    mimeType match {
      case "application/json" => {
        val string = output.toString()
        new StringDocument(string, mimeType)
      }
      case "text/plain" | "application/csv" | "application/xml" => {
        val string = output.str
        new StringDocument(string, mimeType)
      }
      case "application/java" => {
        new ObjectDocument(JacksonAdapter.jsonToJava(output), mimeType)
      }
      case x => throw new IllegalArgumentException("The output mime type " + x + " is not supported")
    }

  }

}


class Mapper(var jsonnet: String, argumentNames: java.lang.Iterable[String], imports: java.util.Map[String, String], needsWrapper: Boolean) {

  if(needsWrapper) {
    jsonnet = Mapper.wrap(jsonnet, argumentNames.asScala)
  }

  def lineOffset = if (needsWrapper) 1 else 0

  def this(jsonnet: String, argumentNames: java.lang.Iterable[String], needsWrapper: Boolean) {
    this(jsonnet, argumentNames, new java.util.HashMap[String, String](), needsWrapper)
  }

  def importer(parent: Path, path: String): Option[(Path, String)] = for {
    resolved <- parent match {
      case DataSonnetPath("") => Some(path)
      case DataSonnetPath(p) => Some(p + "/" + path)
      case _ => None
    }
    contents <- imports.get(resolved) match {
      case null => None
      case v => Some(v)
    }
    combined = (DataSonnetPath(resolved) -> contents)
  } yield combined

  private val parseCache = collection.mutable.Map[String, fastparse.Parsed[(Expr, Map[String, Int])]]()


  val evaluator = new NoFileEvaluator(jsonnet, DataSonnetPath("."), parseCache, importer)

  private val libraries = Map(
    "DS" -> Mapper.objectify(
      PortX.libraries + Mapper.library(evaluator, "Util", parseCache)
    )
  )

  private val function = (for {
    evaluated <- Mapper.evaluate(evaluator, parseCache, jsonnet, libraries, lineOffset)
    verified <- evaluated match {
      case f: Val.Func => Success(f) // TODO check for at least one argument
      case _ => Failure(new IllegalArgumentException("Not a valid map. Maps must have a Top Level Function."))
    }
  } yield verified).get

  private val mapIndex = new IndexedParserInput(jsonnet);

  def transform(payload: String): String = {
    transform(new StringDocument(payload, "application/json"), new java.util.HashMap(), "application/json").contents.toString
  }

  def transform(payload: Document, arguments: java.util.Map[String, Document]): Document = {
    transform(payload, arguments,"application/json")
  }

  def transform(payload: Document, arguments: java.util.Map[String, Document], outputMimeType: String): Document = {

    val data = Mapper.input(payload)

    val parsedArguments = arguments.asScala.view.mapValues { Mapper.input(_) }

    val first +: rest = function.params.args

    val firstMaterialized = first.copy(_2 = Some(data))

    val values = rest.map { case (name, default, i) =>
      val argument: Option[Expr] = parsedArguments.get(name).orElse(default)
      (name, argument, i)
    }.toVector


    val materialized = try Materializer.apply(function.copy(params = Params(firstMaterialized +: values)))(evaluator)
    catch {
      // if there's a parse error it must be in an import, so the offset is 0
      case Error(msg, stack, underlying) if msg.contains("had Parse error")=> throw new IllegalArgumentException("Problem executing map: " + Mapper.expandErrorLineNumber(msg, 0))
      case e: Throwable =>
        val s = new StringWriter()
        val p = new PrintWriter(s)
        e.printStackTrace(p)
        p.close()
        throw new IllegalArgumentException("Problem executing map: " + Mapper.expandErrorLineNumber(s.toString, lineOffset).replace("\t", "    "))
    }

    Mapper.output(materialized, outputMimeType)
  }
}
