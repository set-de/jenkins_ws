
/**
 * CheckCommit-Pipeline.
 */

import java.text.SimpleDateFormat

/**
 * Testrunner-User.
 */
String testrunnerUser = 'H1'

/**
 * Liste der ermittelten SVN-Properties.
 */
svn_properties = [:]

/**
 * Laden der Parameter (und gleichzeitig Uebersicht ueber die moeglichen Parameter).
 */
Params params = Params.load(
    steps,
    FurtherPipelineParams.toString(),
    new ParamDef('help', Boolean.class, Boolean.FALSE, 'gibt die moeglichen Parameter aus und beendet danach den Build'),
    new ParamDef('resetCheckstyleLimits', Boolean.class, Boolean.FALSE, 'deaktiviert den Vergleich der Anzahl der Checkstyle-Verstoesse und setzt den Vergleichswert zurueck'),
    new ParamDef('resetFindBugsLimits', Boolean.class, Boolean.FALSE, 'deaktiviert den Vergleich der Anzahl der FindBugs-Verstoesse und setzt den Vergleichswert zurueck')
)

/**
 * Die eigentliche Pipeline.
 *
 * Zur besseren Benennung in der "Pipeline-Steps"-Ansicht werden auch einfache Tasks in ein <code>parallel</code>
 * eingebettet. Dies kann später durch Label-Blöcke ersetzt werden, sobald das Feature verfügbar ist.
 *
 * @param NodeZuordnung entsprechend des Build-Parameters.
 */
node(NodeZuordnung) {

    timestamps {

		try {
			nodeSetUp(testrunnerUser)

			stage('Checkout') {

				parallel(
					'SVN-Checkout': {
						// Wenn angefordert erstmal ein cleanup auf dem SVN machen
						if (WithSvnCleanup.toBoolean()) {
							run 'svn cleanup Workspace'
							run 'svn cleanup program'
						} else {
							println "Skipping SVN-Cleanup"
						}

						// Dann aus dem SVN Workspace und program auschecken
						if (WithCheckout.toBoolean()) {
							checkout poll: true, scm: [
								$class          : 'SubversionSCM',
								locations       : [
									[credentialsId: 'jenkins', depthOption: 'infinity', ignoreExternalsOption: true,
									 local        : 'Workspace',
									 remote       : "https://svn.intranet.set.de/svn/POSY-Redesign/${Branch}/Workspace@${Revision}"],
									[credentialsId: 'jenkins', depthOption: 'infinity', ignoreExternalsOption: true,
									 local        : 'program',
									 remote       : "https://svn.intranet.set.de/svn/POSY-Redesign/${Branch}/program@${Revision}"]],
									// workspaceUpdater: [$class: 'UpdateWithCleanUpdater']
							]
						} else {
							println "Skipping SVN-Checkout"
						}

						// Nun die SVN-Informationen besorgen und als Umgebungsvariablen setzen
						env.SVN_REVISION = getSvnRev('Workspace')
						println "SVN_REVISION: ${env.SVN_REVISION}"
						env.SVN_URL = getSvnUrl('Workspace')
						println "SVN_URL: ${env.SVN_URL}"
					}
				)
			}

			stage ('Build Components and Static Analysis') {
		
				parallel(
					'clean': {
						if (WithClean.toBoolean())
							callGradle(0, 'clean')
					}
				)

				parallel(
					'build': {

						def tasks = []

						if (WithCheckBuildscripts.toBoolean()) {
							tasks.add('checkBuildscripts')
						}

						if (WithBuild.toBoolean()) {
							tasks.add('buildAllComponents')
							if (!WithGwtCompile.toBoolean()) {
								tasks.add('-x compileGwt')
							}
						}

						if (!tasks.isEmpty()) {
							callGradle(0, tasks.join(' '))
						} else {
							println "No grade tasks to execute"
						}
					}
				)
			}

			stage ('Get Results of Static Analysis') {
				
				try {
					parallel(

						'Get Unit Test Results': {
							if (WithStaticAnalysis.toBoolean()) {
								getUnit(StaticAnalysisType.JUNIT)
							}
						},
						'Get CodeNarc Results': {
							if (WithCheckBuildscripts.toBoolean()) {
								getAsArtefact(StaticAnalysisType.CODENARC)
							}
						},
						'Get Findbugs Results': {
							if (WithStaticAnalysis.toBoolean()) {
								getFindbugs(StaticAnalysisType.FINDBUGS, ResetFindbugsLimits.toBoolean())
							}
						},
						'Get Checkstyle Results': {
							if (WithStaticAnalysis.toBoolean()) {
								getCheckstyle(StaticAnalysisType.CHECKSTYLE, ResetCheckstyleLimits.toBoolean())
							}
						},
						'Get Classycle Results': {
							if (WithStaticAnalysis.toBoolean()) {
								getAsArtefact(StaticAnalysisType.CLASSYCLE)
							}
						},
						'Get Task Scanner Results': {
							if (WithStaticAnalysis.toBoolean()) {
								getTodos(StaticAnalysisType.TODOS)
							}
						},
						'Get Compiler Warnings': {
							if (WithStaticAnalysis.toBoolean()) {
								getCompilerWarnings(StaticAnalysisType.WARNINGS)
							}
						}
					)
				} catch (Exception e) {
					currentBuild.result = 'FAILED'
					throw e
				} finally {
					parallel (
						'Send Mail': {
							mailToCommitters()
						},
						'HipChat': {
							jabber('CheckCommit Phases')
						}
					)
				}
			}
			
			stage ('Systemtests local and compile Manual') {

				parallel(
					'Compile Manual': {
						if (WithCompileManual.toBoolean()) {
							println "Stashing flare-input"
							stash includes: 'Workspace/POSY-Online-Hilfe/', name: 'flare-input'

							node('windows && flare') {
								deleteDir()
								unstash 'flare-input'
								dir('Workspace/POSY-Online-Hilfe') {
									bat 'buildFlare.cmd'
								}
								println "Stashing flare-output"
								stash includes: 'Workspace/POSY-Online-Hilfe/POSY-MailManagement/Output/jenkins/', excludes: '**/Temporary/**', name: 'flare-output'
								println "Archiving Flare logs"
								archiveArtifacts 'Workspace/POSY-Online-Hilfe/POSY-MailManagement/Output/**/*.mclog'
							}
							unstash 'flare-output'
							sh "mkdir -p '$env.WORKSPACE/Workspace/Buildresults'"
							sh "rm -rf '$env.WORKSPACE/Workspace/Buildresults/Handbuch'"
							sh "cp -R '$env.WORKSPACE/Workspace/POSY-Online-Hilfe/POSY-MailManagement/Output/jenkins' '$env.WORKSPACE/Workspace/Buildresults/Handbuch'"

							dir('Workspace/rootProject') {
								callGradle(0, "injectManual")
							}
						} else {
							println "Skipping compile manual"
						}
					},
					'Systemtests local' : {
						if (WithSystemtestLocal.toBoolean()) {
							step([$class               : 'TestrunnerBuilder',
								  applicationProperties: "${TESTRUNNER_APPLICATION}",
								  assertionsEnabled    : true,
								  clusters             : "${TESTRUNNER_CLUSTER}",
								  instrumented         : true,
								  javaCommand          : "${env.JAVA_BINARY}",
								  reportPath           : "${env.WORKSPACE}/Workspace/report/",
								  statusServerPorts    : '1234',
								  testSuitePath        : "${env.WORKSPACE}/Workspace/Systemtestfaelle",
								  testrunnerJar        : 'Testtools.jar',
								  testrunnerMainClass  : 'de.setsoftware.posy.testrunner.TestrunnerMain',
								  testrunnerPath       : "${env.WORKSPACE}/Workspace/Buildresults/POSY-Testtools",
								  testrunnerUsers      : "H0"
							])
						} else {
							echo 'Skipping SystemtestLocal'
						}
					},
					'Systemtests Plugins' : {
						if (WithSystemtestPlugins.toBoolean()) {
							step([$class               : 'TestrunnerBuilder',
								  applicationProperties: "${TESTRUNNER_APPLICATION}",
								  assertionsEnabled    : true,
								  clusters             : "${TESTRUNNER_CLUSTER}",
								  instrumented         : true,
								  javaCommand          : "${env.JAVA_BINARY}",
								  reportPath           : "${env.WORKSPACE}/Workspace/report/",
								  statusServerPorts    : '1235',
								  testSuitePath        : "${env.WORKSPACE}/Workspace/Systemtestfaelle-Plugins",
								  testrunnerJar        : 'Testtools.jar',
								  testrunnerMainClass  : 'de.setsoftware.posy.testrunner.TestrunnerMain',
								  testrunnerPath       : "${env.WORKSPACE}/Workspace/Buildresults/POSY-Testtools",
								  testrunnerUsers      : "H1"
							])
						} else {
							echo 'Skipping SystemtestPlugins'
						}
					},
					'Systemtests Replikation' : {
						if (WithSystemtestReplikation.toBoolean()) {
							step([$class               : 'TestrunnerBuilder',
								  applicationProperties: "${TESTRUNNER_APPLICATION}",
								  assertionsEnabled    : true,
								  clusters             : "${TESTRUNNER_CLUSTER}",
								  instrumented         : true,
								  javaCommand          : "${env.JAVA_BINARY}",
								  reportPath           : "${env.WORKSPACE}/Workspace/report/",
								  statusServerPorts    : '1236',
								  testSuitePath        : "${env.WORKSPACE}/Workspace/Systemtestfaelle-Replikation",
								  testrunnerJar        : 'Testtools.jar',
								  testrunnerMainClass  : 'de.setsoftware.posy.testrunner.TestrunnerMain',
								  testrunnerPath       : "${env.WORKSPACE}/Workspace/Buildresults/POSY-Testtools",
								  testrunnerUsers      : "H2"
							])
						} else {
							echo 'Skipping SystemtestReplikation'
						}
					}
				)
			}

			stage('Systemtests Remote') {
				milestone()
				parallel(
					'Systemtests Remote': {
						if (!"${SystemtestTestsysteme}".isEmpty()) {
							echo 'SystemTests Testsysteme DUMMY...'
							step([$class: 'JUnitResultArchiver', keepLongStdio: true, testResults: "Workspace/**/build/test-results/*.xml,Workspace/reports/junit/*_system_result.xml"])
						} else {
							echo 'Skipping Systemtests'
						}
					}
				)
			}
			
			stage('UnitTests Remote') {
				milestone()
				parallel(
					'Unit-Tests Remote': {
						if (!"${UnitTestTestsysteme}".isEmpty()) {
							echo 'UnitTests Testsysteme DUMMY...'
						} else {
							echo 'Skipping Unit-Tests'
						}
					}
				)
			}
			
			stage('Auslieferung vorbereiten') {
				milestone()
				parallel(
					'Deployment (optional)': {
						if (currentBuild.description != null && currentBuild.description.contains("Auslieferbereit")) {
							build job: 'Auslieferbereit',
									parameters: [
											[$class: 'StringParameterValue', name: 'SVN_REVISION', value: "${env.SVN_REVISION}"],
											[$class: 'StringParameterValue', name: 'BUILD_NUMBER', value: "${env.BUILD_ID}"]],
									propagate: false, wait: false
						}
					}
				)
			}
			
		} catch (Exception e) {
			currentBuild.result = 'FAILED'
			throw e
		} finally {
			parallel (
				'Send Mail': {
					mailToCommitters()
				},
				'HipChat': {
					jabber('Full Pipeline')
				}
			)
        }
        
    }

}

/**
 * Direkt als Erstes innerhalb einer Node-Closure aufrufen. Es werden diverse Environment-Variablen gesetzt und,
 * falls noch nicht geschehen, das Pipeline-Globale Build-Datum ermittelt:
 * <ul>
 *     <li><code>BUILD_DATE</code></li>
 *     <li><code>GRADLE_OPTS</code></li>
 *     <li><code>JAVA_HOME</code></li>
 *     <li><code>JAVA_BINARY</code></li>
 *     <li><code>JAVA_OPTS</code></li>
 *     <li><code>WORKSPACE</code></li>
 * </ul>
 * Zuätzlich wird der Pfad knotenspezifisch erweitert, damit folgende Programme gefunden werden:
 * <ul>
 *     <li>Gradle</li>
 *     <li>Subversion</li>
 * </ul>
 * Unter Linux wird auch der LD_LIBRARY_PATH erweitert.
 *
 * Für die Ausführung der Systemtestfälle werden die folgenden Variablen gesetzt
 * <ul>
 *     <li><code>TESTRUNNER_APPLICATION</code></li>
 *     <li><code>TESTRUNNER_CLUSTER</code></li>
 *     <li><code>TESTRUNNER_USER</code></li>
 * </ul>
 *
 * @param testRunnerUser Testrunner-User für den Jenkins-Lauf.
 */
void nodeSetUp(String testRunnerUser) {

    println 'starting node setup'

    // Environment-Variable für den Jenkins-Workspace
    env.WORKSPACE = pwd()
    println "WORKSPACE: ${env.WORKSPACE}"

    // Build-Date für gesamte Pipeline festlegen
    if (env.BUILD_DATE == null) {
        env.BUILD_DATE = new SimpleDateFormat('yyyy-MM-dd_HH-mm-ss', Locale.GERMAN).format(new Date())
        println "BUILD_DATE: ${env.BUILD_DATE}"
    }

    // JAVA zum Bauen und Testen
    if (isUnix()) {
        env.JAVA_HOME = "${env.WORKSPACE}/program/java7"
        env.JAVA_BINARY = "${JAVA_HOME}/bin/java"
    } else {
        if (env.JAVA_HOME == null) {
            env.JAVA_HOME = 'C:\\Program Files\\Java\\jdk1.7.0_79'
        }
        env.JAVA_BINARY = "${JAVA_HOME}/bin/java.exe"
    }

    // Speicherverbrauch etwas minimieren im Gegensatz zu lokalen Builds
    env.GRADLE_OPTS = '-Xmx4G'
    env.JAVA_OPTS = '-Xmx4G'

    // Pfade relativ zum Workspace unter Linux
    String[] pathsLinux = [
            'program/svn',
            'Workspace/extern/development/gradle/bin'
    ]

    // Pfade relativ zum Workspace unter Windows
    String[] pathsWindows = [
            'Workspace\\extern\\development\\subversion-1.8',
            'Workspace\\extern\\development\\gradle\\bin'
    ]

    // Pfade relativ zum Workspace unter Linux
    String[] libPathsLinux = [
            'program/svn'
    ]

    // relative Pfade ergänzen
    String[] relevantPaths = isUnix() ? pathsLinux : pathsWindows
    for (int i = 0; i < relevantPaths.length; i++) {
        String p = relevantPaths[i]
        println "adding path: " + p
        env.PATH = env.WORKSPACE + fileSep() + p + pathSep() + env.PATH
    }

    // JAVA-JDK ergänzen
    env.PATH = env.JAVA_HOME + fileSep() + 'bin' + pathSep() + env.PATH

    println "PATH: ${env.PATH}"

    // Lib-Path unter Linux ergänzen
    if (isUnix()) {
        for (int i = 0; i < libPathsLinux.length; i++) {
            String p = libPathsLinux[i]
            println "adding lib path: $p"
            env.LD_LIBRARY_PATH = env.WORKSPACE + fileSep() + p +
                    (env.LD_LIBARY_PATH ? (pathSep() + env.LD_LIBRARY_PATH) : '')
        }
        println "LD_LIBRARY_PATH: ${env.LD_LIBRARY_PATH}"
    }

    // Einstellungen für den Testrunner machen
    if (isUnix()) {
        TESTRUNNER_APPLICATION="application_jenkins.xml"
        TESTRUNNER_CLUSTER="local_linux"
    } else {
        TESTRUNNER_APPLICATION="application_jenkins_windows.xml"
        TESTRUNNER_CLUSTER="local_components"
    }

    // Testrunner-User für den Build in Environment speichern
    //env.TESTRUNNER_USER = testRunnerUser

    println 'done node setup'
}

/**
 * Befehl mit Argumenten entsprechend des Knotens unterschiedlich ausführen.
 *
 * @param linux der Befehl für Linux und, falls nur dieser angegeben, auch für Windows.
 * @param windows optional der alternative Befehl für Windows
 */
void run(String linux, String windows = null) {
    if (isUnix()) {
        sh linux
    } else {
        bat windows != null ? windows : linux
    }
}

/**
 * SVN-Informatationen auslesen und bereitstellen. Wird intern benutzt.
 */
void initSvnInfo(String path) {
    if (!svn_properties.isEmpty()) return;
    def tmpFileName = pwd(tmp:true) + '/SVNINFO'
    File tmpFile = new File(tmpFileName)
    try {
        run "svn info ${path} > ${tmpFileName}"
        def info = readFile(tmpFileName)
        String[] parts = info.split('\n')
        for (int i = 0; i < parts.length; i++) {
            String[] line = parts[i].split(':')
            if (line.size() >= 2) {
                def key = line[0].trim().toUpperCase()
                def value = Arrays.copyOfRange(line as String[], 1, line.size()).join(':').trim()
                println "SVN-Property: ${key} = ${value}"
                svn_properties[key] = value
            }
        }
    } finally {
        tmpFile.delete()
    }
}

/**
 * Liefert die SVN-Revision.
 */
String getSvnRev(String path) {
    initSvnInfo(path)
    return svn_properties['REVISION']
}

/**
 * Liefert die SVN-URL.
 */
String getSvnUrl(String path) {
    initSvnInfo(path)
    return svn_properties['URL']
}

/**
 * Allgemeiner Gradle-Aufruf nicht parallelisiert.
 *
 * @param workers Maximale Anzahl paralleler Tasks.
 * @param args Die zu bauenden Tasks. Anhand der Globalen Variablen <code>tasks</code> werden alle nicht angegebenen
 *          Tasks ausgeschaltet.
 */
void callGradle(int workers, String tasks) {
    dir('Workspace/rootProject') {
        def max_workers = workers > 0 ? "--parallel --max-workers=${workers}" : ''
        def jvm_args = '-server -Xmx1G -Xms1G -XX:ReservedCodeCacheSize=1G -XX:+DisableExplicitGC -XX:MaxPermSize=1G -XX:PermSize=256m -XX:+UseConcMarkSweepGC -XX:+CMSClassUnloadingEnabled -XX:+CMSPermGenSweepingEnabled'

        run "gradle --no-daemon -s -PsetBuildDate=${env.BUILD_DATE} -Dorg.gradle.jvmargs=\"${jvm_args}\" ${max_workers} $tasks"
    }
}

/**
 * Der Pfad-Separator ist vom Knoten abhängig. Files.pathSep bezieht sich auf den Master.
 */
String pathSep() {
    isUnix() ? ':' : ';'
}

/**
 * Der File-Separator ist vom Knoten abhängig. Files.fileSep bezieht sich auf den Master.
 */
String fileSep() {
    isUnix() ? '/' : '\\'
}


void makeBuildUnstable(String reason) {
    echo "Making build UNSTABLE: " + reason
    currentBuild.result = "UNSTABLE"
    try {
        error "mark step as unstable/failed but do not stop the build (${reason}) -> ${currentBuild.result}"
    } catch (err) {}
}

void makeBuildFailed(String reason) {
    echo "Making build FAILED: " + reason
    currentBuild.result = "FAILED"
    try {
        error "mark step as unstable/failed but do not stop the build (${reason}) -> ${currentBuild.result}"
    } catch (err) {}
}

def getUnit(StaticAnalysisType type) {
    println "Collecting " + type.name + "..."
    step([$class: 'JUnitResultArchiver', keepLongStdio: true, testResults: type.pattern])
    archiveArtifacts allowEmptyArchive: true, artifacts: type.pattern, defaultExcludes: false
}

def getAsArtefact(StaticAnalysisType type) {
    println "Collecting " + type.name + "..."
    archiveArtifacts allowEmptyArchive: true, artifacts: type.pattern, defaultExcludes: false
}

def getCheckstyle(StaticAnalysisType type, boolean resetLimits) {
    println "Collecting " + type.name + "..."
    //hier sind aktuell nur Grenzwerte fuer neue Warnungen aktiviert (also sowas aehnliches wie Ratcheting)
    step([$class: 'CheckStylePublisher', defaultEncoding: 'UTF-8', healthy: '', unHealthy: '',
                 pattern: type.pattern,
                 unstableNewAll: '0', unstableNewHigh: '0', unstableNewLow: '0', unstableNewNormal: '0',
                 useDeltaValues: true, canComputeNew: !resetLimits,
                 usePreviousBuildAsReference: false, useStableBuildAsReference: false])
    archiveArtifacts allowEmptyArchive: true, artifacts: type.pattern, defaultExcludes: false
    
}

def getFindbugs(StaticAnalysisType type, boolean resetLimits) {
    println "Collecting " + type.name + "..."
    //hier sind aktuell nur Grenzwerte fuer neue Warnungen aktiviert (also sowas aehnliches wie Ratcheting)
    step([$class: 'FindBugsPublisher', defaultEncoding: 'UTF-8', excludePattern: '', healthy: '', includePattern: '', unHealthy: '',
                 pattern: type.pattern,
                 unstableNewAll: '0', unstableNewHigh: '0', unstableNewLow: '0', unstableNewNormal: '0',
                 useDeltaValues: true, canComputeNew: !resetLimits,
                 usePreviousBuildAsReference: false, useStableBuildAsReference: false])
    archiveArtifacts allowEmptyArchive: true, artifacts: type.pattern, defaultExcludes: false
}

def getTodos(StaticAnalysisType type) {
    println "Collecting " + type.name + "..."
    step([$class: 'TasksPublisher', high: '^.*(FIXME)(.*)$',
          normal: '^.*(TODO (?:PSY|Auto-generated|implement|8.2|10.6|[0-9]{1,2}|1[0-3][0-9]|140|141|142))(.*)$',
          low   : '^.*(@deprecated)(.*)$', ignoreCase: true, asRegexp: true, excludePattern: '', pattern: type.pattern])
    // Kein Archivieren der Dateien, die das Pattern matchen, da es sich hier um Sourcedateien handelt.
}

def getCompilerWarnings(StaticAnalysisType type) {
    println "Collecting " + type.name + "..."
    step([$class: 'WarningsPublisher', consoleParsers: [[parserName: 'Java Compiler (javac)']]])
    // Kein Archivieren, da die Konsole geparst wird.
}

def getCodenarc(StaticAnalysisType type) {
    println "Collecting " + type.name + "..."
    step([$class: 'WarningsPublisher', parserConfigurations: [[parserName: 'Codenarc', pattern: type.pattern]],
                     unstableTotalAll: '0', canComputeNew: false, canResolveRelativePaths: false])
    archiveArtifacts allowEmptyArchive: true, artifacts: type.pattern, defaultExcludes: false
}

enum StaticAnalysisType {

    //TODO das Scannen des kompletten Workspaces mit ** ist inperformant. Wenn wir die statischen Analyse
    //  wieder auf globale Analyse umgestellt haben dran denken das auszubauen
    FINDBUGS('Findbugs', 'Workspace/**/build/reports/findbugs/*.xml'),
    CHECKSTYLE('Checkstyle', 'Workspace/**/reports/checkstyle*.xml,Workspace/**/reports/xsCheckstyle*.xml,Workspace/**/reports/xsCheckstyle/*.xml'),

    JUNIT('Unit-Test', 'Workspace/**/build/test-results/**/*.xml'),

    CLASSYCLE('Classycle', 'Workspace/**/build/reports/classycle/**'),
    CODENARC('CodeNarc', 'Workspace/**/build/reports/codeNarc/*.xml'),

    TODOS('TODOS', 'Workspace/**/src/**/*.java'),
    WARNINGS('Warnings','')

    String name
    String pattern

    public StaticAnalysisType(String name, String pattern) {
        this.name = name
        this.pattern = pattern
    }
    
}

def mailToCommitters() {
	emailext to: 'as@set.de', // to darf nicht leer oder null sein
			// recipientProviders: [[$class: 'CulpritsRecipientProvider'], [$class: 'DevelopersRecipientProvider']],
			subject: "Pipeline CheckCommit-Anteil ${currentBuild.result == null ? 'SUCCESSFUL' : currentBuild.result}",
			body: "Siehe ${env.BUILD_URL}"
}

def jabber(String info) {
	def col = 'RED'
	def state = 'UNDEFINED'
	switch (currentBuild.result) {
		case 'UNSTABLE':
			col = 'YELLOW'
			state = 'UNSTABLE'
			break;
		case null:
		case 'SUCCESS':
			col = 'GREEN'
			state = 'SUCCESSFUL'
			break;
		case 'FAILURE':
			col = 'RED'
			state = 'FAILURE'
			break;
		case 'ABORTED':
			col = 'RED'
			state = 'ABORTED'
			break;
	}
	echo "RESULT: ${currentBuild.result}"
	hipchatSend (color: col, notify: true,
		message: "${info} '${env.JOB_NAME} [${env.BUILD_NUMBER}]' ${state} (<a href=\"${env.BUILD_URL}\">View in Jenkins</a>)"
	)
}

class ParamDef implements Serializable {
    private String name;
    private Class<?> type;
    private Object defaultValue;
    private String description;
    
    public ParamDef(String name, Class<?> type, Object defaultValue, String description) {
        this.name = name
        this.type = type
        this.defaultValue = defaultValue
        this.description = description
    }
    
    public boolean matches(String param) {
        return param.equals(this.name) || param.startsWith(this.name + '=')
    }
    
    public Object parse(String param, Set<String> messages) {
        if (param.equals(this.name)) {
            if (this.type.equals(Boolean.class)) {
                return Boolean.TRUE
            } else {
                messages.add('Fuer Nicht-Boolean-Parameter muss ein Wert angegeben werden ' + param)
                return Boolean.FALSE
            }
        } else {
            String value = param.substring(this.name.length() + 1)
            if (this.type.equals(Boolean.class)) {
                if (!value.matches('true|false')) {
                    messages.add('Ungueltiger Wert fuer Boolean-Parameter: ' + param)
                }
                return Boolean.parseBoolean(value)
            } else {
                return value
            }
        }
        return null
    }
}

class Params implements Serializable {
    private Map<String, Object> params = new TreeMap<>()

    public static Params load(def steps, String unparsed, ParamDef... possibleParams) {
        String[] parts = unparsed.split(' ')
        Params ret = new Params()
        Set<ParamDef> found = new HashSet<>()
        Set<String> messages = new LinkedHashSet<>()
        for (int i = 0; i < parts.length; i++) {
            String pt = parts[i].trim()
            if (pt.isEmpty()) {
                continue;
            }
            handleParam(pt, possibleParams, ret, found, messages)
        }
        for (int i = 0; i < possibleParams.length; i++) {
            ParamDef pd = possibleParams[i]
            if (!found.contains(pd)) {
                ret.params.put(pd.name, pd.defaultValue)
            }
        }
        if (!messages.isEmpty()) {
            myEcho(steps, 'Ungueltige Parameter!\n' + messages)
            printHelp(possibleParams, steps)
            throw new RuntimeException('Ungueltige Parameter')
        }
        if (ret.isSet('help')) {
            printHelp(possibleParams, steps)
            throw new RuntimeException('Hilfe ausgeloest')
        }
        myEcho(steps, 'Loaded parameters: ' + ret.params)
        return ret
    }
    
    private static void handleParam(
        String param, ParamDef[] possibleParams, Params ret, Set<ParamDef> found, Set<String> messages) {
        
        boolean valid = false
        for (int i = 0; i < possibleParams.length; i++) {
            ParamDef cur = possibleParams[i]
            if (cur.matches(param)) {
                valid = true
                if (found.contains(cur)) {
                    messages.add('Doppelter Wert fuer den Parameter ' + cur.name)
                }
                found.add(cur)
                ret.params.put(cur.name, cur.parse(param, messages))
            }
        }
        if (!valid) {
            messages.add('Parametername ungueltig: ' + param)
        }
    }
    
    private static void printHelp(ParamDef[] possibleParams, def steps) {
        String msg = 'Moegliche Build-Parameter:\n'
        for (int i = 0; i < possibleParams.length; i++) {
            ParamDef pd = possibleParams[i]
            msg += 'Name: ' + pd.name + ', Typ: ' + pd.type + ', Standardwert: ' +  pd.defaultValue + ', Beschreibung: ' + pd.description + '\n'
        }
        myEcho(steps, msg)
    }
    
    private static void myEcho(def steps, String message) {
        //direkter echo-Aufruf nicht moeglich, weil eigene Klasse.
        //  Und println geht auch nicht, vermutlich weil ausserhalb eines node
        steps.invokeMethod('echo', message)
    }
    
    public boolean isSet(String flagName) {
        return this.params.get(flagName)
    }
}

// Wird benötigt, damit das Load (aus dem Jenkins Job, das diese Pipeline läd) nicht hängen bleibt.
return this;
