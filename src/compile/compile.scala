/*
   ╔═══════════════════════════════════════════════════════════════════════════════════════════════════════════╗
   ║ Fury, version 0.8.0. Copyright 2018-20 Jon Pretty, Propensive OÜ.                                         ║
   ║                                                                                                           ║
   ║ The primary distribution site is: https://propensive.com/                                                 ║
   ║                                                                                                           ║
   ║ Licensed under  the Apache License,  Version 2.0 (the  "License"); you  may not use  this file  except in ║
   ║ compliance with the License. You may obtain a copy of the License at                                      ║
   ║                                                                                                           ║
   ║     http://www.apache.org/licenses/LICENSE-2.0                                                            ║
   ║                                                                                                           ║
   ║ Unless required  by applicable law  or agreed to in  writing, software  distributed under the  License is ║
   ║ distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. ║
   ║ See the License for the specific language governing permissions and limitations under the License.        ║
   ╚═══════════════════════════════════════════════════════════════════════════════════════════════════════════╝
*/
package fury.core

import java.io._
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.channels._
import java.util.concurrent.{CompletableFuture, ExecutionException}

import bloop.launcher.LauncherMain
import bloop.launcher.LauncherStatus._
import ch.epfl.scala.bsp4j.{CompileResult => _, _}
import com.google.gson.{Gson, JsonElement}
import fury.core.Graph.CompilerDiagnostic
import fury.io._
import fury.model._
import fury.strings._
import fury.utils._
import gastronomy._
import kaleidoscope._
import mercator._
import org.eclipse.lsp4j.jsonrpc.Launcher

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.HashMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.Duration
import scala.language.higherKinds
import scala.reflect.{ClassTag, classTag}
import scala.util._

trait FuryBspServer extends BuildServer with ScalaBuildServer

object BloopServer {
 
  private var lock: Promise[Unit] = Promise.successful(())
  private var connections: List[Connection] = Nil
  private val bloopVersion = "1.4.0-RC1"

  def singleTasking[T](work: Promise[Unit] => T): Future[T] = {
    val newLock: Promise[Unit] = Promise()
    BloopServer.synchronized {
      val future = lock.future.map { case _ => work(newLock) }
      lock = newLock
      future
    }
  }

  case class Connection(server: FuryBspServer, client: FuryBuildClient, thread: Thread)
  
  private def connect(dir: Path, multiplexer: Multiplexer[ModuleRef, CompileEvent],
      compilation: Compilation, targetId: TargetId, layout: Layout)(implicit log: Log): Future[Connection] =
    singleTasking { promise =>
      val serverIoPipe = Pipe.open()
      val serverIn = Channels.newInputStream(serverIoPipe.source())
      val clientOut = Channels.newOutputStream(serverIoPipe.sink())
      val clientIoPipe = Pipe.open()
      val clientIn = Channels.newInputStream(clientIoPipe.source())
      val serverOut = Channels.newOutputStream(clientIoPipe.sink())

      val logging: PrintStream = log.stream { str =>
        (if(str.indexOf("[D]") == 9) str.drop(17) else str) match {
          case r"Starting the bsp launcher.*" =>
            log.info(msg"Starting the BSP launcher...")
          case r"Opening a bsp server connection with.*" =>
            log.info(msg"Opening a BSP server connection")
          case r"Waiting for a connection at" =>
            log.info(msg"Waiting for a socket connection")
          case r"Loading project from .*" => None
          case r"Loading previous analysis for .*" => None
          case r".*detected in file:.*" => None
          case r"  => .*" => None
          case r"Creating a scala instance from Bloop" =>
            log.info("Instantiating a new instance of the Scala compiler")
          case r"The server is listening for incoming connections.*" =>
            log.info(msg"BSP server is listening for incoming connections")
            multiplexer.start()
          case r"Waiting.*" => None
          case r"Starting thread.*" => None
          case r"Deduplicating compilation of .*" => None
          case r"No server running at .*" =>
            log.info("Could not detect a BSP server running locally")
          case r"Command: .*" => None
          case r"A bloop installation has been detected.*" =>
            log.info("Detected an existing Bloop installation")
          case "\n" => None
          case other =>
            log.note(msg"${'['}bsp${']'} ${other}")
        }
      }

      val launcher: LauncherMain = new LauncherMain(serverIn, serverOut, logging,
          StandardCharsets.UTF_8, bloop.bloopgun.core.Shell.default, None, None, promise)
      
      val thread = Threads.launcher.newThread { () =>
        launcher.runLauncher(
          bloopVersionToInstall = bloopVersion,
          skipBspConnection = false,
          serverJvmOptions = Nil
        )
      }

      thread.start()
      
      val client = new FuryBuildClient(multiplexer, compilation, targetId, layout)
        
      val bspServer = new Launcher.Builder[FuryBspServer]()
        //.traceMessages(log)
        .setRemoteInterface(classOf[FuryBspServer])
        .setExecutorService(Threads.bsp)
        .setInput(clientIn)
        .setOutput(clientOut)
        .setLocalService(client)
        .create()
      
      bspServer.startListening()
        
      val proxy = bspServer.getRemoteProxy
        
      val capabilities = new BuildClientCapabilities(List("scala").asJava)
      val initParams = new InitializeBuildParams("fury", FuryVersion.current, "2.0.0-M4", dir.uriString,
          capabilities)

      proxy.buildInitialize(initParams).get
      proxy.onBuildInitialized()
      
      Connection(proxy, client, thread)
    }

  def borrow[T](dir: Path, multiplexer: Multiplexer[ModuleRef, CompileEvent], compilation: Compilation, targetId: TargetId, layout: Layout)(fn: Connection => T)(implicit log: Log): Try[T] = {
    val conn = BloopServer.synchronized {
      connections match {
        case head :: tail =>
          connections = tail
          Some(head)
        case Nil =>
          None
      }
    }.getOrElse(Await.result(connect(dir, multiplexer, compilation, targetId, layout), Duration.Inf))

    try {
      val result = fn(conn)
      BloopServer.synchronized(conn.server.buildShutdown().get())
      //BloopServer.synchronized(connections ::= conn)
      Success(result)
    } catch {
      case e: Exception =>
        //conn.terminate()
        Failure(e)
    }
  }
}

object Compilation {

  private val compilationCache: collection.mutable.Map[Path, Future[Try[Compilation]]] = TrieMap()

  def mkCompilation(schema: Schema,
                    ref: ModuleRef,
                    layout: Layout,
                    https: Boolean,
                    session: Session)(implicit log: Log)
  : Try[Compilation] = for {

    hierarchy   <- schema.hierarchy(layout)
    universe    <- hierarchy.universe
    policy      <- ~Policy.read(log)
    compilation <- fromUniverse(universe, ref, layout, session)
    _           <- policy.checkAll(compilation.requiredPermissions)
    _           <- compilation.generateFiles(layout)
  } yield compilation

  def fromUniverse(universe: Universe, ref: ModuleRef, layout: Layout, session: Session)(implicit log: Log): Try[Compilation] = {
    import universe._

    def directDependencies(target: Target): Set[TargetId] = (target.dependencies ++ target.compiler.map(_.id)).to[Set]

    def graph(target: Target): Try[Target.Graph] = for {
      requiredModules <- dependencies(ref, layout)
      requiredTargets <- requiredModules.traverse(makeTarget(_, layout, session))
    } yield {
      val targetGraph = (requiredTargets + target).map { t => t.id -> directDependencies(t) }
      Target.Graph(targetGraph.toMap, requiredTargets.asMap(_.id, identity))
    }

    def canAffectBuild(target: Target): Boolean = Set[Kind](Compiler, Application, Plugin, Benchmarks).contains(target.module.kind)

    for {
      target              <- makeTarget(ref, layout, session)
      entity              <- findEntity(ref.projectId)
      graph               <- graph(target)

      targetIndex         <- graph.dependencies.keys.traverse { targetId =>
                                 makeTarget(targetId.ref, layout, session).map(t => targetId -> t) 
                             }

      requiredTargets     =  targetIndex.unzip._2
      requiredPermissions =  requiredTargets.flatMap(_.module.policy)
      checkouts           <- graph.dependencies.keys.traverse { targetId => checkout(targetId.ref, layout) }
    } yield {
      val moduleRefToTarget = (requiredTargets ++ target.compiler).map(t => t.ref -> t).toMap
      val intermediateTargets = requiredTargets.filter(canAffectBuild)
      val subgraphs = DirectedGraph(graph.dependencies).subgraph(intermediateTargets.map(_.id).to[Set] +
        TargetId(entity.schema.id, ref, session)).connections
      Compilation(graph, subgraphs, checkouts.foldLeft(Checkouts(Set()))(_ ++ _),
        moduleRefToTarget, targetIndex.toMap, requiredPermissions.toSet, universe, session)
    }
  }

  def asyncCompilation(schema: Schema,
                       ref: ModuleRef,
                       layout: Layout,
                       https: Boolean)(implicit log: Log)
  : Future[Try[Compilation]] = layout.session { session =>

    def fn: Future[Try[Compilation]] = Future(mkCompilation(schema, ref, layout, https, session))

    compilationCache(layout.furyDir) = compilationCache.get(layout.furyDir) match {
      case Some(future) => future.transformWith(fn.waive)
      case None         => fn
    }

    compilationCache(layout.furyDir)
  }

  def syncCompilation(schema: Schema,
                      ref: ModuleRef,
                      layout: Layout,
                      https: Boolean,
                      session: Session)(implicit log: Log): Try[Compilation] = {
    val compilation = mkCompilation(schema, ref, layout, https, session)
    compilationCache(layout.furyDir) = Future.successful(compilation)
    compilation
  }
}

class FuryBuildClient(multiplexer: Multiplexer[ModuleRef, CompileEvent], compilation: Compilation,
    targetId: TargetId, layout: Layout) extends BuildClient {

  override def onBuildShowMessage(params: ShowMessageParams): Unit =
    multiplexer(targetId.ref) = Print(targetId.ref, params.getMessage)

  override def onBuildLogMessage(params: LogMessageParams): Unit =
    multiplexer(targetId.ref) = Print(targetId.ref, params.getMessage)

  override def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit = {
    val targetId: TargetId = getTargetId(params.getBuildTarget.getUri)
    val fileName = new java.net.URI(params.getTextDocument.getUri).getRawPath
    val repos = compilation.checkouts.checkouts.asMap(_.path.value, _.repoId)

    params.getDiagnostics.asScala.foreach { diag =>
      val lineNo  = LineNo(diag.getRange.getStart.getLine + 1)
      val charNum = diag.getRange.getStart.getCharacter
      // FIXME: This reads the same file potentially many times
      val codeLine = scala.io.Source.fromFile(fileName).getLines.toList(lineNo.line - 1)

      def isSymbolic(ch: Char)     = (ch == '_' || !ch.isLetterOrDigit) && ch != ' '
      def isAlphanumeric(ch: Char) = ch == '_' || ch.isLetterOrDigit

      def takeSame(str: String): (String, String) = {
        val ch = str.find(_ != '_').getOrElse('_')
        val matching = if(isSymbolic(ch)) str.takeWhile(isSymbolic) else str.takeWhile(isAlphanumeric)
        val remainder = str.drop(matching.length)
        (matching, remainder)
      }

      val linePrefix = codeLine.take(charNum)
      val (matching, remainder) = takeSame(codeLine.drop(linePrefix.length))
      
      val highlightedLine = UserMsg { theme =>
        msg"$linePrefix${theme.failure(theme.underline(matching))}$remainder".string(theme).dropWhile(_ == ' ')
      }

      val (repo, filePath) = repos.find { case (k, v) => fileName.startsWith(k) }.map {
        case (k, v) => (v, Path(fileName.drop(k.length + 1)))
      }.getOrElse((RepoId("local"), Path(fileName.drop(layout.baseDir.value.length + 1))))

      val severity = UserMsg { theme => diag.getSeverity.toString.toLowerCase match {
        case "error"       => msg"${'['}${theme.failure("E")}${']'}".string(theme)
        case "warning"     => msg"${'['}${theme.ongoing("W")}${']'}".string(theme)
        case "information" => msg"${'['}${theme.info("I")}${']'}".string(theme)
        case _             => msg"${'['}${theme.info("H")}${']'}".string(theme)
      } }

      multiplexer(targetId.ref) = DiagnosticMsg(
        targetId.ref,
        CompilerDiagnostic(
          msg"""$severity ${targetId.ref}${'>'}${repo}${':'}${filePath}${':'}${lineNo}${':'}${(charNum + 1).toString}
${'|'} ${UserMsg(
            theme =>
              diag.getMessage.split("\n").to[List].map(theme.gray(_)).join(msg"""
${'|'} """.string(theme)))}
${'|'} ${highlightedLine}
""",
          repo,
          filePath,
          lineNo,
          charNum
        )
      )
    }
  }

  override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = {}

  private[this] def convertDataTo[A: ClassTag](data: Object): A = {
    val gson = new Gson()
    val json = data.asInstanceOf[JsonElement]
    val report =
      gson.fromJson[A](json, classTag[A].runtimeClass)
    report
  }

  // FIXME: We should implement this using a regular expression
  private[this] def getTargetId(bspUri: String): TargetId = {
    val uriQuery = new java.net.URI(bspUri).getRawQuery.split("&").map(_.split("=", 2)).map { array =>
      array(0) -> array(1)
    }.toMap

    TargetId(uriQuery("id"))
  }

  override def onBuildTaskProgress(params: TaskProgressParams): Unit = {
    val report   = convertDataTo[CompileTask](params.getData)
    val targetId = getTargetId(report.getTarget.getUri)
    multiplexer(targetId.ref) = CompilationProgress(targetId.ref, params.getProgress.toDouble / params.getTotal)
  }

  override def onBuildTaskStart(params: TaskStartParams): Unit = {
    val report   = convertDataTo[CompileTask](params.getData)
    val targetId: TargetId = getTargetId(report.getTarget.getUri)
    multiplexer(targetId.ref) = StartCompile(targetId.ref)
    compilation.deepDependencies(targetId).foreach { dependencyTargetId =>
      multiplexer(dependencyTargetId.ref) = NoCompile(dependencyTargetId.ref)
    }
  }

  override def onBuildTaskFinish(params: TaskFinishParams): Unit = params.getDataKind match {
    case TaskDataKind.COMPILE_REPORT =>
      val report = convertDataTo[CompileReport](params.getData)
      val targetId: TargetId = getTargetId(report.getTarget.getUri)
      val ref = targetId.ref
      multiplexer(ref) = StopCompile(ref, params.getStatus == StatusCode.OK)
      if(!compilation.targets(ref).module.kind.needsExecution) multiplexer(ref) = StopRun(ref)
      else multiplexer(ref) = StartRun(ref)
  }
}

sealed abstract class Dependency(val name: Option[ArtifactId])
case class ModuleDependency(ref: ModuleRef) extends Dependency(None)
case class ArtifactDependency(ref: ArtifactId) extends Dependency(Some(ref))
case class BinaryDependency(ref: Binary) extends Dependency(Some(ref.artifactId))

case class Compilation(graph: Target.Graph,
                       subgraphs: Map[TargetId, Set[TargetId]],
                       checkouts: Checkouts,
                       targets: Map[ModuleRef, Target],
                       targetIndex: Map[TargetId, Target],
                       requiredPermissions: Set[Permission],
                       universe: Universe,
                       session: Session) {

  private[this] val hashes: HashMap[ModuleRef, Digest] = new HashMap()
  
  //lazy val allDependencies: Set[Target] = targets.values.to[Set]

  lazy val deepDependencies: Map[TargetId, Set[TargetId]] = {
    @tailrec
    def flatten[T](aggregated: Set[T], children: T => Set[T], next: Set[T]): Set[T] = {
      if(next.isEmpty) aggregated
      else {
        val node = next.head
        flatten(aggregated + node, children, next - node ++ children(node))
      }
    }
    targetIndex.map { case (targetId, _) =>
      targetId -> flatten[TargetId](Set.empty, graph.dependencies(_).to[Set], Set(targetId))
    }
  }

  def apply(ref: ModuleRef): Try[Target] = targets.get(ref).ascribe(ItemNotFound(ref.moduleId))

  def checkoutAll(layout: Layout, https: Boolean)(implicit log: Log): Try[Unit] =
    checkouts.checkouts.traverse(_.get(layout, https)).map{ _ => ()}

  def generateFiles(layout: Layout)(implicit log: Log): Try[Iterable[Path]] = synchronized {
    Bloop.clean(layout).flatMap(Bloop.generateFiles(this, layout, session).waive)
  }

  def classpath(ref: ModuleRef, layout: Layout): Set[Path] = {
    requiredTargets(ref).flatMap { target =>
      Set(layout.classesDir(target.id), layout.resourcesDir(target.id)) ++ target.binaries
    } ++ targets(ref).binaries
  }

  def dependencyGraph(ref: ModuleRef) = {
    type AMap = Map[Option[ArtifactId], Set[Dependency]]

    def applyFold(base: AMap, inherited: List[AMap]): AMap = {
      val (artifact, _) = base.head
      
      inherited.foldLeft(base) { (aggregation, next) =>
        next.foldLeft(aggregation) {
          case (map, (None, elems)) => map.updated(artifact, elems)
          case (map, (key, elems))  => map.updated(key, map.getOrElse(key, Set()) ++ elems)
        }
      }
    }

    aggregate { t =>
      Map(t.module.artifact -> (Set[Dependency](ModuleDependency(t.ref)) ++ t.compiler.map(_.ref).map(ModuleDependency(_))))
    } (applyFold)(ref)
  }

  def persistentOpts(ref: ModuleRef, layout: Layout)(implicit log: Log): Try[Set[Provenance[Opt]]] = for {
    target      <- apply(ref)

    compileOpts <- ~target.compiler.to[Set].flatMap { c => c.module.optDefs.filter(_.persistent).map(_.opt(c.ref,
                       Origin.Compiler)) }

    refParams   <- ~target.module.opts.filter(_.persistent).map(Provenance(_, target.compiler.map(_.ref).getOrElse(
                       ModuleRef.JavaRef), Origin.Module(target.ref)))

    removals    <- ~refParams.filter(_.value.remove).map(_.value.id)
    inherited   <- target.dependencies.map(_.ref).traverse(persistentOpts(_, layout)).map(_.flatten)

    pOpts       <- ~(if(target.module.kind == Plugin) Set(
                     Provenance(Opt(OptId(str"Xplugin:${layout.classesDir(target.id)}"), persistent = true,
                         remove = false), target.compiler.fold(ModuleRef.JavaRef)(_.ref), Origin.Plugin)
                   ) else Set())

  } yield (pOpts ++ compileOpts ++ inherited ++ refParams.filter(!_.value.remove)).filterNot(removals contains
      _.value.id)

  def aggregatedOpts(ref: ModuleRef, layout: Layout)(implicit log: Log): Try[Set[Provenance[Opt]]] = for {
    target      <- apply(ref)
    optCompiler <- ~target.compiler
    
    tmpParams   <- ~target.module.opts.filter(!_.persistent).map(Provenance(_, optCompiler.map(_.ref).getOrElse(
                       ModuleRef.JavaRef), Origin.Local))

    removals    <- ~tmpParams.filter(_.value.remove).map(_.value.id)
    opts        <- persistentOpts(ref, layout)
  } yield (opts ++ tmpParams.filter(!_.value.remove)).filterNot(removals contains _.value.id)

  def aggregate[T](mkBase: Target => T)(fold: (T, List[T]) => T)(ref: ModuleRef): Try[T] = for {
    target <- apply(ref)
    deps   <- target.dependencies.traverse { target => aggregate(mkBase)(fold)(target.ref) }
  } yield fold(mkBase(target), deps)
   
  def aggregatedOptDefs(ref: ModuleRef): Try[Set[Provenance[OptDef]]] = {
    def mkBase(t: Target) = t.module.optDefs.map(Provenance(_, t.impliedCompiler, Origin.Module(t.ref))) ++
        t.compiler.to[Set].flatMap { c => c.module.optDefs.map(Provenance(_, c.ref, Origin.Compiler)) }

    aggregate(mkBase)(_ ++ _.flatten.to[Set])(ref)
  }
 
  def aggregatedPlugins(ref: ModuleRef): Try[Set[Provenance[Plugin]]] = {
    def mkBase(t: Target) = t.module.plugin.map { m => Provenance(Plugin(m, t.ref, t.module.main.get),
        t.compiler.fold(ModuleRef.JavaRef)(_.ref), Origin.Plugin) }.to[Set]

    aggregate(mkBase)(_ ++ _.flatten.to[Set])(ref)
  }

  def aggregatedResources(ref: ModuleRef): Try[Set[Source]] =
    aggregate(_.module.resources.to[Set])(_ ++ _.flatten.to[Set])(ref)

  def bootClasspath(ref: ModuleRef, layout: Layout): Set[Path] = {
    val requiredPlugins = requiredTargets(ref).filter(_.module.kind == Plugin).flatMap { target =>
      Set(layout.classesDir(target.id), layout.resourcesDir(target.id)) ++ target.binaries
    }

    val compilerClasspath = targets(ref).compiler.to[Set].flatMap { c => classpath(c.ref, layout) }
    compilerClasspath ++ requiredPlugins
  }

  private def requiredTargets(ref: ModuleRef): Set[Target] =
    deepDependencies(targets(ref).id).map(targetIndex.apply)

  def allSources: Set[Path] = targets.values.to[Set].flatMap{x: Target => x.sourcePaths.to[Set]}

  def writePlugin(ref: ModuleRef, layout: Layout): Unit = {
    val target = targets(ref)
    if(target.module.kind == Plugin) {
      val file = layout.classesDir(target.id) / "scalac-plugin.xml"

      target.module.main.foreach { main =>
        file.writeSync(str"""|<plugin>
                             | <name>${target.module.plugin.fold("plugin")(_.key)}</name>
                             | <classname>${main}</classname>
                             |</plugin>""".stripMargin)
      }
    }
  }

  def saveNative(ref: ModuleRef, dest: Path, layout: Layout, main: ClassRef)(implicit log: Log): Try[Unit] =
    for {
      dest <- Try(dest.extant())
      cp   = runtimeClasspath(ref, layout).to[List].map(_.value)
      _    <- Shell(layout.env).native(dest, cp, main.key)
    } yield ()

  def saveAllJars(destination: Path, ref: ModuleRef, classDirs: Map[ModuleRef, Path], layout: Layout, fatJar: Boolean)
                 (implicit log: Log)
                 : Try[Unit] = for {
    artifacts  <- dependencyGraph(ref)
    target     <- apply(ref)
    artifactId <- ~target.module.artifact.getOrElse(ArtifactId(str"${ref.projectId.key}-${ref.moduleId.key}"))
    _          <- ~log.info("Before: "+artifacts.toString)
    artifacts  <- ~artifacts.get(None).fold(artifacts)((artifacts - None).updated(Some(artifactId), _))
    _          <- ~log.info("After: "+artifacts.toString)
    _          <- artifacts.traverse { case (a, v) => saveJars(ref, destination, target.module.main, a.get, v,
                      classDirs, layout, fatJar) }
  } yield ()

  def saveJars(ref: ModuleRef,
               destination: Path,
               main: Option[ClassRef],
               artifactId: ArtifactId,
               refs: Set[Dependency],
               classDirs: Map[ModuleRef, Path],
               layout: Layout,
               fatJar: Boolean)
              (implicit log: Log)
              : Try[Unit] = for {

    targets <- refs.collect { case ModuleDependency(ref) => ref }.traverse(apply(_))
    bins    <- ~targets.flatMap(_.binaries)
    _       <- ~log.info(msg"Outputting binaries: ${bins.toString}")
    ress    <- ~targets.flatMap(_.module.resources)
    manifest = Manifest(bins.map(_.name), main.map(_.key))
    dest     = destination.extant()
    path     = dest / str"${artifactId.key}.jar"
    _       <- ~log.info(msg"Saving JAR file ${path.relativizeTo(layout.baseDir)}")
    classes <- ~(classDirs.collect { case (k, v) if refs.contains(ModuleDependency(k)) => v })
    dir     <- aggregateCompileResults(ref, classes.to[Set], layout)
    _       <- ~log.info(msg"For $ref, using dirs ${classes.toString}")
    _       <- if(fatJar) bins.traverse(Zipper.unpack(_, dir))
               else bins.traverse { bin => bin.copyTo(dest / bin.name) }
    _       <- ~ress.foreach(_.copyTo(checkouts, layout, dir))
    _       <- Shell(layout.env).jar(path, dir.children.filterNot(_.contains("META-INF")).map(dir / _).to[Set], manifest)
  } yield ()

  private[this] def aggregateCompileResults(ref: ModuleRef,
                                            compileResults: Set[Path],
                                            layout: Layout): Try[Path] = {
    val stagingDirectory = layout.workDir(targets(ref).id) / "staging"
    for(_ <- compileResults.filter(_.exists()).traverse(_.copyTo(stagingDirectory))) yield stagingDirectory
  }

  def jmhRuntimeClasspath(ref: ModuleRef, classesDirs: Set[Path], layout: Layout): Set[Path] =
    classesDirs ++ targets(ref).compiler.to[Set].map { compilerTarget =>
      layout.resourcesDir(compilerTarget.id)
    } ++ classpath(ref, layout)

  def runtimeClasspath(ref: ModuleRef, layout: Layout): Set[Path] =
    targets(ref).compiler.to[Set].flatMap { compilerTarget =>
      Set(layout.classesDir(compilerTarget.id), layout.resourcesDir(compilerTarget.id))
    } ++ classpath(ref, layout) + layout.classesDir(targets(ref).id) +
        layout.resourcesDir(targets(ref).id)

  def compileModule(target: Target,
                    layout: Layout,
                    multiplexer: Multiplexer[ModuleRef, CompileEvent],
                    pipelining: Boolean,
                    globalPolicy: Policy,
                    args: List[String])(implicit log: Log)
  : Future[CompileResult] = Future.fromTry {

    val uri: String = str"file://${layout.workDir(target.id).value}?id=${target.id.key}"
    val params = new CompileParams(List(new BuildTargetIdentifier(uri)).asJava)
    if(pipelining) params.setArguments(List("--pipeline").asJava)
    val furyTargetIds = deepDependencies(target.id).toList
    
    val bspTargetIds = furyTargetIds.map { dep =>
      new BuildTargetIdentifier(str"file://${layout.workDir(dep).value}?id=${dep.key}")
    }
    
    val bspToFury = (bspTargetIds zip furyTargetIds).toMap
    val scalacOptionsParams = new ScalacOptionsParams(bspTargetIds.asJava)

    BloopServer.borrow(layout.baseDir, multiplexer, this, target.id, layout) { conn =>
      
      val result: Try[CompileResult] = {
        for {
          res <- wrapServerErrors(conn.server.buildTargetCompile(params))
          opts <- wrapServerErrors(conn.server.buildTargetScalacOptions(scalacOptionsParams))
        } yield CompileResult(res, opts)
      }

      result.get.scalacOptions.getItems.asScala.foreach { case soi =>
        val bti = soi.getTarget
        val classDir = soi.getClassDirectory
        val targetId = bspToFury(bti)
        val permanentClassesDir = layout.classesDir(targetId)
        val temporaryClassesDir = Path(new URI(classDir))
        temporaryClassesDir.copyTo(permanentClassesDir)
        //TODO the method setClassDirectory modifies a mutable structure. Consider refactoring
        soi.setClassDirectory(permanentClassesDir.javaFile.toURI.toString)
      }

      result.get
    }.map {
      case compileResult if compileResult.isSuccessful && target.module.kind.needsExecution =>
        val classDirectories = compileResult.classDirectories
        val runSuccess = run(target, classDirectories.values.to[Set], multiplexer, layout, globalPolicy, args) == 0
        if(runSuccess) compileResult else compileResult.failed
      case otherResult =>
        otherResult
    }
  }

  private[this] def wrapServerErrors[T](f: => CompletableFuture[T]): Try[T] =
    Outcome.rescue[ExecutionException] { e: ExecutionException => BuildServerError(e.getCause) } (f.get)


  def compile(moduleRef: ModuleRef,
              multiplexer: Multiplexer[ModuleRef, CompileEvent],
              futures: Map[TargetId, Future[CompileResult]] = Map(),
              layout: Layout,
              globalPolicy: Policy,
              args: List[String],
              pipelining: Boolean)(implicit log: Log)
  : Map[TargetId, Future[CompileResult]] = {
    val target = targets(moduleRef)

    val newFutures = subgraphs(target.id).foldLeft(futures) { (futures, dependencyTarget) =>
      if(futures.contains(dependencyTarget)) futures
      else compile(dependencyTarget.ref, multiplexer, futures, layout, globalPolicy, args, pipelining)
    }

    val dependencyFutures = Future.sequence(subgraphs(target.id).map(newFutures))

    val future = dependencyFutures.map(CompileResult.merge).flatMap { required =>
      if(!required.isSuccessful) {
        multiplexer(target.ref) = SkipCompile(target.ref)
        multiplexer.close(target.ref)
        Future.successful(required)
      } else {
        val noCompilation = target.sourcePaths.isEmpty

        if(noCompilation) {
          deepDependencies(target.id).foreach { targetId =>
            multiplexer(targetId.ref) = NoCompile(targetId.ref)
          }
          Future.successful(required)
        } else compileModule(target, layout, multiplexer, pipelining, globalPolicy, args)
      }
    }

    newFutures.updated(target.id, future)
  }

  private def run(target: Target, classDirectories: Set[Path], multiplexer: Multiplexer[ModuleRef, CompileEvent],
                  layout: Layout, globalPolicy: Policy, args: List[String]): Int = layout.session { session =>
    if (target.module.kind == Benchmarks) {
      classDirectories.foreach { classDirectory =>
        Jmh.instrument(classDirectory, layout.benchmarksDir(target.id), layout.resourcesDir(target.id))
        val javaSources = layout.benchmarksDir(target.id).findChildren(_.endsWith(".java"))

        Shell(layout.env).javac(
          classpath(target.ref, layout).to[List].map(_.value),
          classDirectory.value,
          javaSources.map(_.value).to[List])
      }
    }
    val exitCode = Shell(layout.env).runJava(
      jmhRuntimeClasspath(target.ref, classDirectories, layout).to[List].map(_.value),
      if (target.module.kind == Benchmarks) "org.openjdk.jmh.Main" else target.module.main.fold("")(_.key),
      securePolicy = target.module.kind == Application,
      env = target.module.environment.asMap(_.key, _.value),
      properties = target.module.properties.asMap(_.key, _.value),
      policy = globalPolicy.forContext(layout, target.ref.projectId),
      layout = layout,
      args
    ) { ln =>
      multiplexer(target.ref) = Print(target.ref, ln)
    }.await()

    deepDependencies(target.id).foreach { targetId =>
      multiplexer(targetId.ref) = NoCompile(targetId.ref)
    }

    multiplexer.close(target.ref)
    multiplexer(target.ref) = StopRun(target.ref)

    exitCode
  }

}
