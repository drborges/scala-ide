/*
 * Copyright 2005-2010 LAMP/EPFL
 */
// $Id$

package scala.tools.eclipse

import scala.collection.mutable
import scala.collection.mutable.{ ArrayBuffer, SynchronizedMap }

import org.eclipse.core.resources.IFile
import org.eclipse.jdt.core.compiler.IProblem
import org.eclipse.jdt.internal.compiler.problem.{ DefaultProblem, ProblemSeverities }
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global
import scala.tools.nsc.io.AbstractFile
import scala.tools.nsc.reporters.Reporter
import scala.tools.nsc.util.{ MutableSourceFile, Position, SourceFile }

import scala.tools.eclipse.javaelements.{
  ScalaCompilationUnit, ScalaIndexBuilder, ScalaJavaMapper, ScalaMatchLocator, ScalaStructureBuilder,
  ScalaOverrideIndicatorBuilder }
import scala.tools.eclipse.util.{ Cached, EclipseFile, EclipseResource }

class ScalaPresentationCompiler(project : ScalaProject, settings : Settings)
  extends Global(settings, new ScalaPresentationCompiler.PresentationReporter)
  with ScalaStructureBuilder with ScalaIndexBuilder with ScalaMatchLocator
  with ScalaOverrideIndicatorBuilder with ScalaJavaMapper with JVMUtils { self =>
  import ScalaPresentationCompiler._
  
  def presentationReporter = reporter.asInstanceOf[PresentationReporter]
  
  presentationReporter.compiler = this
  
  private val sourceFiles = new mutable.HashMap[ScalaCompilationUnit, MutableSourceFile] with SynchronizedMap[ScalaCompilationUnit, MutableSourceFile] {
    override def default(k : ScalaCompilationUnit) = { val v = k.createSourceFile; put(k, v); v } 
  }
  
  private val problems = new mutable.HashMap[IFile, ArrayBuffer[IProblem]] with SynchronizedMap[IFile, ArrayBuffer[IProblem]] {
    override def default(k : IFile) = { val v = new ArrayBuffer[IProblem] ; put(k, v); v }
  }
  
  private def fileOf(scu : ScalaCompilationUnit) =
    try { Some(scu.getCorrespondingResource.asInstanceOf[IFile]) } catch { case _ => None } 
  
  private def problemsOf(file : IFile) : List[IProblem] = {
    val ps = problems.remove(file)
    ps match {
      case Some(ab) => ab.toList
      case _ => Nil
    }
  }
  
  def problemsOf(scu : ScalaCompilationUnit) : List[IProblem] = fileOf(scu) match {
    case Some(file) => problemsOf(file)
    case None => Nil
  }
  
  private def clearProblemsOf(file : IFile) : Unit = problems.remove(file)
  
  private def clearProblemsOf(scu : ScalaCompilationUnit) : Unit = fileOf(scu) match {
    case Some(file) => clearProblemsOf(file)
    case None =>
  }
  
  def withSourceFile[T](scu : ScalaCompilationUnit)(op : (SourceFile, ScalaPresentationCompiler) => T) : T =
    op(sourceFiles(scu), this)
    
  def body(sourceFile : SourceFile) = {
	val tree = new Response[Tree]
	askType(sourceFile, false, tree)
	tree.get match {
		case Left(l) => l
		case Right(r) => throw r
	}
  }
    
  def askReload(scu : ScalaCompilationUnit, content : Array[Char]) {
    val f = sourceFiles(scu)
    f.content = content
	askReload(List(f), new Response[Unit])
    clearProblemsOf(scu)
  }
  
  def discardSourceFile(scu : ScalaCompilationUnit) {
    removeUnitOf(sourceFiles(scu))
	sourceFiles.remove(scu)
    clearProblemsOf(scu)
  }

  override def logError(msg : String, t : Throwable) =
    ScalaPlugin.plugin.logError(msg, t)
    
  def destroy() {
    sourceFiles.keysIterator.foreach(_.scheduleReconcile)
    askShutdown
  }
 
  class EclipseTyperRun extends TyperRun {
    override def compileSourceFor(context : Context, name : Name) = {
      def addImport(imp : analyzer.ImportInfo) = {
        val qual = imp.qual
        val sym = qual.symbol
        sym.isPackage && {
          var selectors = imp.tree.selectors
          if (selectors.head.name == name.toTermName)
            compileSourceFor(sym.fullName+"."+name)
          else if (selectors.head.name == nme.WILDCARD)
            compileSourceFor(sym.fullName+"."+name)
          else
            false
        }
      }
      
      context.imports.exists(addImport) || {
        val pkg = context.owner.enclosingPackage
        compileSourceFor(pkg.fullName+"."+name)
      }
    }

    override def compileSourceFor(qual : Tree, name : Name) = {
      val sym = qual.symbol
      sym != null && sym.isPackage && compileSourceFor(sym.fullName+"."+name)
    }
    
    def compileSourceFor(qualifiedName : String) : Boolean = {
      project.findSource(qualifiedName) match {
        case Some(iFile) if (!project.isStandardSource(iFile, qualifiedName)) =>
          val file = new EclipseFile(iFile)
          if (compiledFiles contains file.path)
            false
          else {
            println("Adding: "+file+" to resolve: "+qualifiedName)
            compileLate(file)
            true
          }
        case _ => false
      }
    }
  }
  
  override def newTyperRun = new EclipseTyperRun
}

object ScalaPresentationCompiler {
  class PresentationReporter extends Reporter {
    var compiler : ScalaPresentationCompiler = null
    
    override def info0(pos: Position, msg: String, severity: Severity, force: Boolean): Unit = {
      severity.count += 1
      
      try {
        if(pos.isDefined) {
          val source = pos.source
          source.file match {
            case EclipseResource(file : IFile) =>
              val length = source.identifier(pos, compiler).map(_.length).getOrElse(0)
              compiler.problems(file) +=
                new DefaultProblem(
                  file.getFullPath.toString.toCharArray,
                  formatMessage(msg),
                  0,
                  new Array[String](0),
                  nscSeverityToEclipse(severity),
                  pos.startOrPoint,
                  pos.endOrPoint,
                  pos.line,
                  pos.column
                )
            case _ =>  
          }
        }
      } catch {
        case ex : UnsupportedOperationException => 
      }
    }
    
    override def reset {
      super.reset
      compiler.problems.clear
    }
  
    def nscSeverityToEclipse(severity : Severity) = 
      severity.id match {
        case 2 => ProblemSeverities.Error
        case 1 => ProblemSeverities.Warning
        case 0 => ProblemSeverities.Ignore
      }
    
    def formatMessage(msg : String) =
      msg.map{
        case '\n' => ' '
        case '\r' => ' '
        case c => c
      }.mkString("","","")
  }
}
