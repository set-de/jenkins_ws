
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
 * Die eigentliche Pipeline.
 *
 * Zur besseren Benennung in der "Pipeline-Steps"-Ansicht werden auch einfache Tasks in ein <code>parallel</code>
 * eingebettet. Dies kann später durch Label-Blöcke ersetzt werden, sobald das Feature verfügbar ist.
 *
 * @param NodeZuordnung entsprechend des Build-Parameters.
 */
node(NodeZuordnung) {

    timestamps {

        nodeSetUp(testrunnerUser)

        stage('Cleanup (optional)') {
    
            parallel(
                    'SVN-Cleanup': {
                        if (SVNCleanup.toBoolean()) {
                            run 'svn cleanup Workspace'
                            run 'svn cleanup program'
                        }
                    }
            )
        }
        
        stage('Checkout') {

            parallel(
                    'SVN-Checkout': {
                        if (Checkout.toBoolean()) {
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
                        }
                    }
            )
        }
        
        stage('SVN-Infos') {
    
            parallel(
                    'Get SVN Infos': {
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
                            callGradle(0,'clean')
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
                            tasks.add('-x')
                            tasks.add('compileGwt')
                        }
    
                        callGradle(0, tasks.join(' '))
    
                    }
            )
        }

        stage ('Get Results of Static Analysis') {
            
            parallel(

                    'Get Unit Test Results': {
                        if (WithStaticAnalysis.toBoolean()) {
                            getUnit(StaticAnalysisType.JUNIT)
                        }
                    },
                    'Get CodeNarc Results': {
                        if (WithStaticAnalysis.toBoolean()) {
                            getAsArtefact(StaticAnalysisType.CODENARC)
                        }
                    },
                    'Get Findbugs Results': {
                        if (WithStaticAnalysis.toBoolean()) {
                            getFindbugs(StaticAnalysisType.FINDBUGS)
                        }
                    },
                    'Get Checkstyle Results': {
                        if (WithStaticAnalysis.toBoolean()) {
                            getCheckstyle(StaticAnalysisType.CHECKSTYLE)
                        }
                    },
                    'Get Classycle Results': {
                        if (WithStaticAnalysis.toBoolean()) {
                            getAsArtefact(StaticAnalysisType.CLASSYCLE)
                        }
                    }
            )
        }
        
        stage ('Generate Manual') {

            parallel(
                    'generate manual': {
                        //callGradle(0, 'generateManual')
                    }
            )
        }

        
        stage('Systemtests Local') {
            milestone()
            parallel(
                    'Systemtests Local': {
                        if (SystemtestLocal.toBoolean()) {
                            step([$class               : 'TestrunnerBuilder',
                                  applicationProperties: "${TESTRUNNER_APPLICATION}",
                                  assertionsEnabled    : true,
                                  clusters             : "${TESTRUNNER_CLUSTER}",
                                  instrumented         : true,
                                  javaCommand          : "${env.JAVA_BINARY}",
                                  reportPath           : "${env.WORKSPACE}/Workspace/report/",
                                  statusServerPorts    : '1234',
                                  testSuitePath        : "${env.WORKSPACE}/Workspace/Systemtestfaelle-Plugins",
                                  testrunnerJar        : 'Testtools.jar',
                                  testrunnerMainClass  : 'de.setsoftware.posy.testrunner.TestrunnerMain',
                                  testrunnerPath       : "${env.WORKSPACE}/Workspace/Buildresults/POSY-Testtools",
                                  testrunnerUsers      : "${env.TESTRUNNER_USER}"
                            ])
                        } else {
                            echo 'Skipping SystemtestLocal'
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
        
    }

}

/**
 * Direkt als Erstes innerhalb einer Node-Closure aufrufen. Es werden diverse Environment-Variablen gesetzt und,
 * falls noch nicht geschehen, das Pipeline-Globale Build-Datum ermittelt:
 * <ul>
 *     <li><code>BUILD_DATE</code></li>
 *     <li><code>GRADLE_OPTS</code></li>
 *     <li><code>JAVA_HOME</code></li>
 *     <li><code>JAVA_OPTS</code></li>
 *     <li><code>TESTRUNNER_USER</code></li>
 *     <li><code>WORKSPACE</code></li>
 * </ul>
 * Zuätzlich wird der Pfad knotenspezifisch erweitert, damit folgende Programme gefunden werden:
 * <ul>
 *     <li>Gradle</li>
 *     <li>Subversion</li>
 * </ul>
 * Unter Linux wird auch der LD_LIBRARY_PATH erweitert.
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

    // Testrunner-User für den Build in Environment speichern
    env.TESTRUNNER_USER = testRunnerUser

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
    env.GRADLE_OPTS = '-Xmx2048m'
    env.JAVA_OPTS = '-Xmx8196m'

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
        def jvm_args = '-server -Xmx4G -Xms1G -XX:ReservedCodeCacheSize=1G -XX:+DisableExplicitGC -XX:MaxPermSize=1G -XX:PermSize=256m -XX:+UseConcMarkSweepGC -XX:+CMSClassUnloadingEnabled -XX:+CMSPermGenSweepingEnabled'

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

def getCheckstyle(StaticAnalysisType type) {
    println "Collecting " + type.name + "..."
    step([$class: 'CheckStylePublisher', defaultEncoding: '', healthy: '', unHealthy: '',
                 pattern: type.pattern,
                 unstableNewAll: '0', unstableNewHigh: '0', unstableNewLow: '0', unstableNewNormal: '0',
                 unstableTotalAll: '0', unstableTotalHigh: '0', unstableTotalLow: '0', unstableTotalNormal: '0',
                 usePreviousBuildAsReference: true, useStableBuildAsReference: true])
    archiveArtifacts allowEmptyArchive: true, artifacts: type.pattern, defaultExcludes: false
    
}

def getFindbugs(StaticAnalysisType type) {
    println "Collecting " + type.name + "..."
    step([$class: 'FindBugsPublisher', defaultEncoding: '', excludePattern: '', healthy: '', includePattern: '', unHealthy: '',
                 pattern: type.pattern,
                 unstableNewAll: '0', unstableNewHigh: '0', unstableNewLow: '0', unstableNewNormal: '0',
                 unstableTotalAll: '0', unstableTotalHigh: '0', unstableTotalLow: '0', unstableTotalNormal: '0',
                 usePreviousBuildAsReference: true, useStableBuildAsReference: true])
    archiveArtifacts allowEmptyArchive: true, artifacts: type.pattern, defaultExcludes: false
}

enum StaticAnalysisType {

    FINDBUGS('Findbugs', 'Workspace/**/build/reports/findbugs/*.xml'),
    CHECKSTYLE('Checkstyle', 'Workspace/**/reports/checkstyle*.xml,Workspace/**/reports/xsCheckstyle*.xml,Workspace/**/reports/xsCheckstyle/*.xml'),

    JUNIT('Unit-Test', 'Workspace/**/build/test-results/**/*.xml'),

    CLASSYCLE('Classycle', 'Workspace/**/build/reports/classycle/**'),
    CODENARC('CodeNarc', 'Workspace/**/build/reports/codeNarc/*.xml')

    String name
    String pattern

    public StaticAnalysisType(String name, String pattern) {
        this.name = name
        this.pattern = pattern
    }
    
}

return this;