package com.typesafe.sbtidea

import sbt._

object SbtIdeaModuleMapping {
  type SourcesClassifier = String
  type JavadocClassifier = String

  def toIdeaLib(instance: ScalaInstance) = {
    IdeaLibrary("scala-" + instance.version, List(instance.libraryJar, instance.compilerJar),
      instance.extraJars.filter(_.getAbsolutePath.endsWith("docs.jar")),
      instance.extraJars.filter(_.getAbsolutePath.endsWith("-sources.jar")))
  }

  /**
   * Extracts IDEA libraries from the keys:
   *
   *   * `externalDependencyClasspath`
   *   * `update`
   *   * `updateClassifiers`
   *   * `updateSbtClassifiers`
   *   * `unmanagedClasspath`
   */
  final class LibrariesExtractor(buildStruct: Load.BuildStructure, state: State, projectRef: ProjectRef,
                                 scalaInstance: ScalaInstance, withClassifiers: Option[(Seq[SourcesClassifier], Seq[JavadocClassifier])]) {

    def allLibraries: Seq[IdeaModuleLibRef] = managedLibraries ++ unmanagedLibraries

    /**
     * Creates an IDEA library entry for each entry in `externalDependencyClasspath` in `Test` and `Compile.
     *
     * The result of `update`, `updateClassifiers`, and is used to find the location of the library,
     * by default in $HOME/.ivy2/cache
     */
    def managedLibraries: Seq[IdeaModuleLibRef] = {
      val deps = evaluateTask(Keys.externalDependencyClasspath in Configurations.Test) match {
        case Some(Value(deps)) => deps
        case _ => state.log.error("Failed to obtain dependency classpath"); throw new IllegalArgumentException()
      }
      val libraries: Seq[(IdeaModuleLibRef, ModuleID)] = evaluateTask(Keys.update) match {

        case Some(Value(report)) =>
          val libraries: Seq[(IdeaModuleLibRef, ModuleID)] = convertDeps(report, deps, scalaInstance.version)

          withClassifiers.map { classifiers =>
            evaluateTask(Keys.updateClassifiers) match {
              case Some(Value(report)) => addClassifiers(libraries, report, classifiers)
              case _ => libraries
            }
          }.getOrElse(libraries)

        case _ => Seq.empty
      }

      libraries.map(_._1)
    }

    /**
     * Creates an IDEA library entry for each entry in `unmanagedClasspath` in `Test` and `Compile.
     *
     * If the entry is both in the compile and test scopes, it is only added to the compile scope.
     *
     * source and javadoc JARs are detected according to the Maven naming convention. They are *not*
     * added to the classpath, but rather associated with the corresponding binary JAR.
     **/
    def unmanagedLibraries: Seq[IdeaModuleLibRef] = {
      def unmanagedLibrariesFor(config: Configuration): Seq[IdeaModuleLibRef] = {
        evaluateTask(Keys.unmanagedClasspath in config) match {
          case Some(Value(unmanagedClassPathSeq)) =>

            /**Uses naming convention to look for an artifact with `classifier` in the same directory as `orig`. */
            def classifier(orig: File, classifier: String): Option[File] = file(orig.getAbsolutePath.replace(".jar", "-%s.jar".format(classifier))) match {
              case x if x.exists => Some(x)
              case _ => None
            }
            for {
              attributedFile <- unmanagedClassPathSeq
              f = attributedFile.data
              if Seq("sources", "javadoc").forall(classifier => !f.name.endsWith("-%s.jar".format(classifier)))
              scope = toScope(config.name)
              sources = classifier(f, "sources").toSeq
              javadocs = classifier(f, "javadoc").toSeq
              ideaLib = IdeaLibrary(f.getName, classes = Seq(f), sources = sources, javaDocs = javadocs)
            } yield IdeaModuleLibRef(scope, ideaLib)
          case _ => Seq()
        }
      }

      val compileUnmanagedLibraries = unmanagedLibrariesFor(Configurations.Compile)
      val testUnmanagedLibraries = unmanagedLibrariesFor(Configurations.Test).filterNot(libRef => compileUnmanagedLibraries.exists(_.library == libRef.library))
      compileUnmanagedLibraries ++ testUnmanagedLibraries
    }

    private def evaluateTask[T](taskKey: sbt.Project.ScopedKey[sbt.Task[T]]) =
      EvaluateTask(buildStruct, taskKey, state, projectRef).map(_._2)
  }

  private def equivModule(m1: ModuleID, m2: ModuleID, scalaVersion: String) = {
    val crossName: ModuleID => ModuleID = CrossVersion(scalaVersion, CrossVersion.binaryScalaVersion(scalaVersion))

    m1.organization == m2.organization && crossName(m1).name == crossName(m2).name
  }

  private def ideaLibFromModule(moduleReport: ModuleReport, classifiers: Option[(Seq[SourcesClassifier], Seq[JavadocClassifier])] = None): IdeaLibrary = {
    val module = moduleReport.module
    def findByClassifier(classifier: Option[String]) = moduleReport.artifacts.collect {
      case (artifact, file) if (artifact.classifier == classifier) => file
    }
    def findByClassifiers(classifiers: Option[Seq[String]]): Seq[File] = classifiers match {
      case Some(classifiers) => classifiers.foldLeft(Seq[File]()) { (acc, classifier) => acc ++ findByClassifier(Some(classifier)) }
      case None => Seq[File]()
    }
    IdeaLibrary(module.organization + "_" + module.name + "_" + module.revision,
      classes = findByClassifier(None),
      sources = findByClassifiers(classifiers.map(_._1)),
      javaDocs = findByClassifiers(classifiers.map(_._2)))
  }

  private def toScope(conf: String) = {
    import com.typesafe.sbtidea.IdeaLibrary._
    conf match {
      case "compile" => CompileScope
      case "runtime" => RuntimeScope
      case "test" => TestScope
      case "provided" => ProvidedScope
      case _ => CompileScope
    }
  }

  private def mapToIdeaModuleLibs(configuration: String, modules: Seq[ModuleReport], deps: Keys.Classpath,
                                  scalaVersion: String) = {

    val scope = toScope(configuration)
    val depFilter = libDepFilter(deps.flatMap(_.get(Keys.moduleID.key)), scalaVersion) _

    modules.filter(modReport => depFilter(modReport.module)).map(moduleReport => {
      (IdeaModuleLibRef(scope, ideaLibFromModule(moduleReport)), moduleReport.module)
    })
  }

  private def libDepFilter(deps: Seq[ModuleID], scalaVersion: String)(module: ModuleID): Boolean = {
    deps.exists(equivModule(_, module, scalaVersion))
  }

  private def convertDeps(report: UpdateReport, deps: Keys.Classpath, scalaVersion: String): Seq[(IdeaModuleLibRef, ModuleID)] = {

    //TODO If we could retrieve the correct configurations for the ModuleID, we could filter by configuration in
    //mapToIdeaModuleLibs and remove the hardcoded configurations. Something like the following would be enough:
    //report.configurations.flatMap(configReport => mapToIdeaModuleLibs(configReport.configuration, configReport.modules, deps))

    Seq("compile", "runtime", "test", "provided").flatMap(report.configuration).foldLeft(Seq[(IdeaModuleLibRef, ModuleID)]()) {
      (acc, configReport) =>
        val filteredModules = configReport.modules.filterNot(m1 =>
          acc.exists {
            case (_, m2) => equivModule(m1.module, m2, scalaVersion)
          })
        acc ++ mapToIdeaModuleLibs(configReport.configuration, filteredModules, deps, scalaVersion)
    }
  }

  private def addClassifiers(ideaModuleLibRefs: Seq[(IdeaModuleLibRef, ModuleID)],
                             report: UpdateReport, classifiers: (Seq[SourcesClassifier], Seq[JavadocClassifier])): Seq[(IdeaModuleLibRef, ModuleID)] = {

    /* Both retrieved from UpdateTask, so we don't need to deal with crossVersion here */
    def equivModule(m1: ModuleID, m2: ModuleID): Boolean =
      m1.name == m2.name && m1.organization == m2.organization && m1.revision == m2.revision

    ideaModuleLibRefs.map { case (moduleLibRef, moduleId) =>
      val configsAndModules = report.configurations.flatMap(configReport => configReport.modules.map(configReport.configuration -> _))
      configsAndModules.find { case (configuration, moduleReport) =>
        moduleLibRef.config == toScope(configuration) && equivModule(moduleReport.module, moduleId)
      } map { case (_, moduleReport) =>
        val ideaLibrary = {
          val il = ideaLibFromModule(moduleReport, Some(classifiers))
          il.copy(classes = il.classes ++ moduleLibRef.library.classes,
            javaDocs = il.javaDocs ++ moduleLibRef.library.javaDocs,
            sources = il.sources ++ moduleLibRef.library.sources)
        }

        moduleLibRef.copy(library = ideaLibrary) -> moduleId
      } getOrElse (moduleLibRef -> moduleId)
    }
  }

  def extractLibraries(report: UpdateReport): Seq[IdeaLibrary] = {
    report.configurations.flatMap {
      configReport =>
        configReport.modules.map {
          moduleReport =>
            ideaLibFromModule(moduleReport, Some(Seq("sources"), Seq("javadoc")))
        }
    }
  }
}
