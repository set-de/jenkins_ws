/**
 * ThePipeline for POSY.
 *
 * Folgende Voraussetzungen müssen erfüllt sein, damit die Pipeline auf einem Jenkins ausgeführt werden kann:
 *
 * <b>Benötigte Plugins</b>
 *
 * <li>lockable-resources</li>
 * <li>subversion:SET-Version</li>
 *
 * <li>testrunner:SET-Version</li>
 * <li>junit</li>
 * <li>jacoco:SET-Version</li>
 *
 * <li>warnings</li>
 * <li>tasks</li>
 * <li>findbugs</li>
 * <li>checkstyle</li>
 * <li>violations:0.8</li>
 *
 * <li>email-ext</li>
 * <li>hipchat</li>
 *
 * <b>Benötigte Ressourcen</b>
 * <li><JOBNAME>_CheckCommit-Label</li>
 * <li><JOBNAME>_Systemtests-Local-Lock</li>
 * <li><JOBNAME>_Systemtests-Remote-Lock</li>
 * <li><JOBNAME>_Deploy-Lock</li>
 *
 */

import java.text.SimpleDateFormat

/**
 * Laden der Parameter (und gleichzeitig Uebersicht ueber die moeglichen Parameter).
 */
params = Params.load(
        steps,
        FurtherPipelineParams.toString(),
        new ParamDef('help', Boolean.class, Boolean.FALSE, 'gibt die moeglichen Parameter aus und beendet danach den Build'),
        new ParamDef('withoutSvnCheckout', Boolean.class, Boolean.FALSE, 'Aktualisieren des Workspace deaktivieren'),
        new ParamDef('withoutClean', Boolean.class, Boolean.FALSE, 'Gradle clean deaktivieren'),
        new ParamDef('withoutBuild', Boolean.class, Boolean.FALSE, 'Bauen der Komponenten und statische Analysen deaktivieren'),
        new ParamDef('withoutStaticAnalysis', Boolean.class, Boolean.FALSE, 'Ausgabe der statischen Analyseergebnisse deaktivieren'),
        new ParamDef('withoutCompileManual', Boolean.class, Boolean.FALSE, 'Erstellung des Handbuchs deaktivieren'),
        new ParamDef('withoutGwtCompile', Boolean.class, Boolean.FALSE, 'GWT-Erstellung deaktivieren'),
        new ParamDef('withoutUnitTestsLinux', Boolean.class, Boolean.FALSE, 'Lokale Unit-Tests (auf Linux) deaktivieren'),
        new ParamDef('withoutUnitTestsAix', Boolean.class, Boolean.FALSE, 'Remote-Unit-Tests auf AIX deaktivieren'),
        new ParamDef('withoutUnitTestsSolaris', Boolean.class, Boolean.FALSE, 'Remote-Unit-Tests auf Solaris deaktivieren'),
        new ParamDef('withoutUnitTestsZos', Boolean.class, Boolean.FALSE, 'Remote-Unit-Tests auf z/OS deaktivieren'),
        new ParamDef('withoutSystemTestsLinux', Boolean.class, Boolean.FALSE, 'Lokale Systemtests (auf Linux) deaktivieren'),
        new ParamDef('withoutSystemTestsAix', Boolean.class, Boolean.FALSE, 'Remote-Systemtests auf AIX deaktivieren'),
        new ParamDef('withoutSystemTestsSolaris', Boolean.class, Boolean.FALSE, 'Remote-Systemtests auf Solaris deaktivieren'),
        new ParamDef('withoutSystemTestsZos', Boolean.class, Boolean.FALSE, 'Remote-Systemtests auf z/OS deaktivieren'),
        new ParamDef('withoutPluginTestsLinux', Boolean.class, Boolean.FALSE, 'Lokale Plugin-Systemtests (auf Linux) deaktivieren'),
        new ParamDef('withoutReplicationTestsLinux', Boolean.class, Boolean.FALSE, 'Lokale Replikations-Systemtests (auf Linux) deaktivieren'),
        new ParamDef('withoutGuiTestsLinux', Boolean.class, Boolean.FALSE, 'Lokale GUI-Tests (auf Linux) deaktivieren'),
        new ParamDef('resetCheckstyleLimits', Boolean.class, Boolean.FALSE, 'deaktiviert den Vergleich der Anzahl der Checkstyle-Verstoesse und setzt den Vergleichswert zurueck'),
        new ParamDef('resetFindBugsLimits', Boolean.class, Boolean.FALSE, 'deaktiviert den Vergleich der Anzahl der FindBugs-Verstoesse und setzt den Vergleichswert zurueck'),
        new ParamDef('withoutEcj', Boolean.class, Boolean.FALSE, 'deaktiviert das Kompilieren mit dem Eclipse Compiler'),
        new ParamDef('withoutCoverage', Boolean.class, Boolean.FALSE, 'Deaktiviert das Ermitteln der Testabdeckung')

)

if (params == null) {
    echo 'The help-Parameter was used. Build will be UNSTABLE.'
    currentBuild.result = "UNSTABLE"
    return this
}

globalSetUp()

/**
 * Die eigentliche Pipeline.
 *
 * Zur besseren Benennung in der "Pipeline-Steps"-Ansicht werden auch einfache Tasks in ein <code>parallel</code>
 * eingebettet. Dies kann später durch Label-Blöcke ersetzt werden, sobald das Feature verfügbar ist.
 *
 */

/**
 * Informationen in Beschreibung ausgeben.
 */ 
currentBuild.description = ''
  
timestamps {

    withEnv(createEnvironment()) {

        try {

            try {

                //hier wird mit Label gearbeitet, damit mehrere parallele Laeufe moeglich sind
                milestone label: 'Beginn'

                lock(inversePrecedence: true, quantity: 1, label: env.JOB_BASE_NAME + '_CheckCommit-Label') {

                    milestone label: 'CheckCommit-Lock erhalten'

                    extendedStage('Checkout and Read SVN-Infos') {
                        stage_checkout()
                    }

                    extendedStage('Build, Static Analysis, Unit-Tests') {
                        stage_build()
                    }

                    extendedStage('Collect Static Analysis and Test Results') {
                        stage_get_static_analysis_results()
                    }

                }

            } finally {
                def res = effResult()
                currentBuild.description += "CheckCommit Phase: ${res}<br/>"
                notifications('CheckCommit Phase')
            }

            lock(inversePrecedence: true, quantity: 1, resource: env.JOB_BASE_NAME + '_Systemtests-Local-Lock') {

                milestone label: 'Systemtests-Local-Lock erhalten'

                extendedStage('Systemtests Local, Compile Manual, Unit-Tests Remote') {
                    stage_system_tests_manual_unit_remote()
                }

            }

            lock(inversePrecedence: true, quantity: 1, resource: env.JOB_BASE_NAME + '_Systemtests-Remote-Lock') {

                milestone label: 'Systemtests-Remote-Lock erhalten'

                extendedStage('Systemtests Remote') {
                    stage_system_tests_remote()
                }

            }

            extendedStage('Collect Coverage') {
                stage_getTestCoverage()
            }

            lock(inversePrecedence: true, quantity: 1, resource: env.JOB_BASE_NAME + '_Deploy-Lock') {

                milestone label: 'Deploy-Lock erhalten'

                extendedStage('Deployment') {
                    stage_deploy()
                }

            }
            
            finished = true

        } catch (StopBuildException e) {
            // swallow Exception here
        } finally {
            results.add(['Global', effResult()])
            if (!finished) {
                currentBuild.description += "Pipeline-Ende nicht erreicht.<br/>"
                results.add(['Pipeline', 'NOT_FINISHED'])
            }    
            notifications('Full Pipeline')
            currentBuild.description += "Pipeline beendet.<br/>"
        }
    }
}

/**
 * SVN-Checkout und SVN-Infos ermitteln.
 */
def stage_checkout() {
    parallel(
            'SVN-Checkout': {
                // Aus dem SVN Workspace und program auschecken
                if (!params.isSet('withoutSvnCheckout')) {
                    checkout poll: true, scm: [
                            $class          : 'SubversionSCM',
                            locations       : [
                                    [credentialsId: 'jenkins', depthOption: 'infinity', ignoreExternalsOption: true,
                                     local        : 'Workspace',
                                     remote       : "https://svn.intranet.set.de/svn/POSY-Redesign/${Branch}/Workspace@${Revision}"],
                                    [credentialsId: 'jenkins', depthOption: 'infinity', ignoreExternalsOption: true,
                                     local        : 'program',
                                     remote       : "https://svn.intranet.set.de/svn/POSY-Redesign/${Branch}/program@${Revision}"]],
                            workspaceUpdater: [$class: 'UpdateWithCleanUpdater']
                    ]
                } else {
                    echo "Skipping SVN-Checkout"
                }
            }
    )

    parallel(
            'SVN-Infos': {
                // Nun die SVN-Informationen besorgen und als Umgebungsvariablen setzen
                def infos = svninfo 'Workspace'
                echo "Retrieved svn info: $infos"
                env.SVN_REVISION = infos['REVISION']
                echo "SVN_REVISION: ${env.SVN_REVISION}"
                env.SVN_URL = infos['URL']
                echo "SVN_URL: ${env.SVN_URL}"
            }
    )
}

/**
 * Bauen und statische Analyse ausführen.
 */
def stage_build() {
    parallel(
            'clean': {
                if (!params.isSet('withoutClean')) {
                    callGradle(0, 'clean')
                }
            }
    )

    parallel(
            'build': {

                def tasks = []
                def excludes = []

                if (params.isSet('withoutStaticAnalysis')) {
                    excludes += 'check'
                }

                if (!params.isSet('withoutBuild')) {
                    tasks.add('buildAllComponents')
                    if (params.isSet('withoutGwtCompile')) {
                        excludes += 'compileGwt'
                    }

                    if (!tasks.isEmpty()) {
                        gradle tasks: tasks, excludes: excludes
                    } else {
                        echo "No gradle tasks to execute"
                    }
                }
            }
    )
}

/**
 * Ergebnisse der statischen Analyse ermitteln.
 */
def stage_get_static_analysis_results() {
    if (!params.isSet('withoutStaticAnalysis')) {
        parallel(
                'Get Unit Test Results': {
                    getUnit(StaticAnalysisType.JUNIT)
                },
                'Get CodeNarc Results': {
                    getAsArtefact(StaticAnalysisType.CODENARC)
                },
                'Get Findbugs Results': {
                    getFindbugs(StaticAnalysisType.FINDBUGS, params.isSet('resetFindBugsLimits'))
                },
                'Get Checkstyle Results': {
                    getCheckstyle(StaticAnalysisType.CHECKSTYLE, params.isSet('resetCheckstyleLimits'))
                },
                'Get Classycle Results': {
                    getAsArtefact(StaticAnalysisType.CLASSYCLE)
                },
                'Get Task Scanner Results': {
                    getTodos(StaticAnalysisType.TODOS)
                },
                'Get Compiler Warnings': {
                    getCompilerWarnings(StaticAnalysisType.WARNINGS)
                }
        )
    }
}

/**
 * Lokale Systemtests ausführen, Handbuch erstellen und Remote-Unit-Tests durchführen.
 */
def stage_system_tests_manual_unit_remote() {
    parallel(
            'Compile Manual': {
                if (!params.isSet('withoutCompileManual')) {
                    echo "Stashing flare-input"
                    stash includes: 'Workspace/POSY-Online-Hilfe/', name: 'flare-input'

                    node('windows && flare') {
                        deleteDir()
                        unstash 'flare-input'
                        dir('Workspace/POSY-Online-Hilfe') {
                            bat 'buildFlare.cmd'
                        }
                        echo "Stashing flare-output"
                        stash includes: 'Workspace/POSY-Online-Hilfe/POSY-MailManagement/Output/jenkins/', excludes: '**/Temporary/**', name: 'flare-output'
                        echo "Archiving Flare logs"
                        archiveArtifacts 'Workspace/POSY-Online-Hilfe/POSY-MailManagement/Output/**/*.mclog'
                    }
                    unstash 'flare-output'
                    sh "mkdir -p '$env.WORKSPACE/Workspace/Buildresults'"
                    sh "rm -rf '$env.WORKSPACE/Workspace/Buildresults/Handbuch'"
                    sh "cp -R '$env.WORKSPACE/Workspace/POSY-Online-Hilfe/POSY-MailManagement/Output/jenkins' '$env.WORKSPACE/Workspace/Buildresults/Handbuch'"

                    callGradle(0, "injectManual")

                } else {
                    echo 'Skipping Compile Manual'
                }
            },
            'Systemtests local': {
                if (!params.isSet('withoutSystemTestsLinux')) {
                    testrunner([
                          applicationProperties: "${TESTRUNNER_APPLICATION}",
                          assertionsEnabled    : true,
                          clusters             : "${TESTRUNNER_CLUSTER}",
                          instrumented         : true,
                          javaCommand          : "${JAVA_BINARY}",
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
            'Systemtests Plugins': {
                if (!params.isSet('withoutPluginTestsLinux')) {
                    testrunner([
                          applicationProperties: "${TESTRUNNER_APPLICATION}",
                          assertionsEnabled    : true,
                          clusters             : "${TESTRUNNER_CLUSTER}",
                          instrumented         : true,
                          javaCommand          : "${JAVA_BINARY}",
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
            'Systemtests Replikation': {
                if (!params.isSet('withoutReplicationTestsLinux')) {
                    testrunner([
                          applicationProperties: "${TESTRUNNER_APPLICATION}",
                          assertionsEnabled    : true,
                          clusters             : "${TESTRUNNER_CLUSTER}",
                          instrumented         : true,
                          javaCommand          : "${JAVA_BINARY}",
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
            },
            'Unit-Tests Remote': {
                def TESTRUNNER_JAR = "${env.WORKSPACE}/Workspace/Buildresults/POSY-Testtools/Testtools.jar"
                def MAIN_CLASS = 'de.setsoftware.posy.testrunner.UnitMain'
                def APPLICATION_NAME = 'application_localconsole.xml'
                def REPORT_PATH = "${env.WORKSPACE}/Workspace/report/"
                def INSTRUMENTED = 'true'
                def ASSERTIONS = 'true'
                def INSTALL_POSY = 'true'

                echo 'Preparing for execution of remote Unit-Tests...'
                echo "Path to Testrunner Jar: ${TESTRUNNER_JAR}"
                echo "Path to Reports: ${REPORT_PATH}"

                // Die gwt_servlet.jar ans Ende packen, damit unsere eigene Implementierung in den Tests verwendet wird.
                dir("${env.WORKSPACE}/Workspace/Buildresults/POSY-UnitTestRunner") {
                    echo 'Move gwt-servlet.jar to end of classpath...'
                    run('mv jars/gwt-servlet.jar jars/z_gwt-servlet.jar', 'move /Y jars\\gwt-servlet.jar jars\\z_gwt-servlet.jar')
                }

                echo 'Executing remote Unit-Tests...'
                parallel(
                        'UnitTests ZOS': {
                            if (!params.isSet('withoutUnitTestsZos')) {
                                withEnv(['TESTRUNNER_USER=HB']) {
                                    dir("${env.WORKSPACE}/Workspace/Buildresults/POSY-Testtools") {
                                        echo 'Executing remote Unit-Tests on z/OS...'
                                        run("\"${JAVA_BINARY}\" -cp ${TESTRUNNER_JAR} ${MAIN_CLASS} zos ${APPLICATION_NAME} ${REPORT_PATH} ${INSTRUMENTED} ${ASSERTIONS} ${INSTALL_POSY}")
                                    }
                                    junit keepLongStdio: true, testResults: "Workspace/report/jUnit_zos_result.xml"
                                }
                            } else {
                                echo 'Skipping Unittests on z/OS...'
                            }
                        },
                        'UnitTests Solaris': {
                            if (!params.isSet('withoutUnitTestsSolaris')) {
                                withEnv(['TESTRUNNER_USER=HA']) {
                                    dir("${env.WORKSPACE}/Workspace/Buildresults/POSY-Testtools") {
                                        echo 'Executing remote Unit-Tests on Solaris...'
                                        run("\"${JAVA_BINARY}\" -cp ${TESTRUNNER_JAR} ${MAIN_CLASS} solaris ${APPLICATION_NAME} ${REPORT_PATH} ${INSTRUMENTED} ${ASSERTIONS} ${INSTALL_POSY}")
                                    }
                                    junit keepLongStdio: true, testResults: "Workspace/report/jUnit_solaris_result.xml"
                                }
                            } else {
                                echo 'Skipping Unittests on Solaris...'
                            }
                        },
                        'UnitTests AIX': {
                            if (!params.isSet('withoutUnitTestsAix')) {
                                withEnv(['TESTRUNNER_USER=H8']) {
                                    dir("${env.WORKSPACE}/Workspace/Buildresults/POSY-Testtools") {
                                        echo 'Executing remote Unit-Tests on AIX...'
                                        run("\"${JAVA_BINARY}\" -cp ${TESTRUNNER_JAR} ${MAIN_CLASS} aix ${APPLICATION_NAME} ${REPORT_PATH} ${INSTRUMENTED} ${ASSERTIONS} ${INSTALL_POSY}")
                                    }
                                    junit keepLongStdio: true, testResults: "Workspace/report/jUnit_aix_result.xml"
                                }
                            } else {
                                echo 'Skipping Unittests on AIX...'
                            }
                        }
                )
            }
    )
}

/**
 * Remote-Systemtests durchführen.
 */
def stage_system_tests_remote() {
    parallel(
        'Systemtests AIX': {
            if (!params.isSet('withoutSystemTestsAix')) {
                testrunner([
                        applicationProperties: "${TESTRUNNER_APPLICATION}",
                        assertionsEnabled    : true,
                        clusters             : 'aix',
                        instrumented         : true,
                        javaCommand          : "${JAVA_BINARY}",
                        reportPath           : "${env.WORKSPACE}/Workspace/report/",
                        statusServerPorts    : '1236',
                        testSuitePath        : "${env.WORKSPACE}/Workspace/Systemtestfaelle-Replikation",
                        testrunnerJar        : 'Testtools.jar',
                        testrunnerMainClass  : 'de.setsoftware.posy.testrunner.TestrunnerMain',
                        testrunnerPath       : "${env.WORKSPACE}/Workspace/Buildresults/POSY-Testtools",
                        testrunnerUsers      : "H3"
                ])
            } else {
                echo 'Skipping Systemtests AIX'
            }
        },
        'Systemtests Solaris': {
            if (!params.isSet('withoutSystemTestsSolaris')) {
                testrunner([
                        applicationProperties: "${TESTRUNNER_APPLICATION}",
                        assertionsEnabled    : true,
                        clusters             : 'solaris',
                        instrumented         : true,
                        javaCommand          : "${JAVA_BINARY}",
                        reportPath           : "${env.WORKSPACE}/Workspace/report/",
                        statusServerPorts    : '1236',
                        testSuitePath        : "${env.WORKSPACE}/Workspace/Systemtestfaelle-Replikation",
                        testrunnerJar        : 'Testtools.jar',
                        testrunnerMainClass  : 'de.setsoftware.posy.testrunner.TestrunnerMain',
                        testrunnerPath       : "${env.WORKSPACE}/Workspace/Buildresults/POSY-Testtools",
                        testrunnerUsers      : "H4"
                ])
            } else {
                echo 'Skipping Systemtests Solaris'
            }
        },
        'Systemtests ZOS': {
            if (!params.isSet('withoutSystemTestsZos')) {
                testrunner([
                        applicationProperties: "${TESTRUNNER_APPLICATION}",
                        assertionsEnabled    : true,
                        clusters             : 'zos',
                        instrumented         : true,
                        javaCommand          : "${JAVA_BINARY}",
                        reportPath           : "${env.WORKSPACE}/Workspace/report/",
                        statusServerPorts    : '1236',
                        testSuitePath        : "${env.WORKSPACE}/Workspace/Systemtestfaelle-Replikation",
                        testrunnerJar        : 'Testtools.jar',
                        testrunnerMainClass  : 'de.setsoftware.posy.testrunner.TestrunnerMain',
                        testrunnerPath       : "${env.WORKSPACE}/Workspace/Buildresults/POSY-Testtools",
                        testrunnerUsers      : "H5"
                ])
            } else {
                echo 'Skipping Systemtests ZOS'
            }
        }
    )
}

/**
 * Stage zum Aufsammeln der JaCoCo Testabedeckung aus den Testfällen.
 */
def stage_getTestCoverage() {
    parallel(
        'Coverage' : {
            if (!params.isSet('withoutCoverage')) {
                step([$class                    : 'JacocoPublisher',
                      changeBuildStatus         : false,
                      exceptionFilters          : 'de/setsoftware/stdlib/common/ShouldNotHappenError',
                      exclusionPattern          : '.*/wsdl/.*,.*/bixjab/.*,.*/xml/.*,.*/gen/.*,de/setsoftware/posy/plugins/customerPlugins/VsTestPlugin.*,.*Messages,.*Messages_DE,.*Messages_EN',
                      execPath                  : 'Workspace/report/coverage/',
                      inclusionPattern          : 'de/set/.*,de/setsoftware/.*',
                      javaCommand               : "${JAVA_BINARY}",
                      projectExcludes           : 'GuiTestrunner,Testrunner,UnitTestRunner,POSY-Config-GUI,POSY-Dialog-Basisdaten,POSY-DispatchManagement-GUI,POSY-GUI-Lib,POSY-Viewer-GUI,POSY-PM-StatusView,POSY-PM-Workplace-Plugins,POSY-Workflow-GUI,POSY-Workplace-GUI,POSY-Workplace-Plugins,SET-GUI-Lib,SET-Web-GUI-Lib,POSY-Workstation,SET-CodeAnalysis,POSY-StorageTest,Infrastructure,SET-Tools,POSY-Tools,POSY-Plugins-Gen,POSY-StorageInterface-Gen,POSY-LongTermStorage-Interface-Gen,Gui-Performance-Tests,POSY-GUI-Test-Lib,SET-AspectJ',
                      reportGeneratorPath       : 'program/jacocoReportGenerator/JacocoReportGenerator.jar',
                      reportTargetPath          : 'Workspace/report/coverageReport/',
                      sourceFileEncoding        : 'UTF-8',
                      workspaceDir              : 'Workspace/',
                      maximumBranchCoverage     : '0',
                      maximumClassCoverage      : '0',
                      maximumComplexityCoverage : '0',
                      maximumInstructionCoverage: '0',
                      maximumLineCoverage       : '0',
                      maximumMethodCoverage     : '0',
                      minimumBranchCoverage     : '0',
                      minimumClassCoverage      : '0',
                      minimumComplexityCoverage : '0',
                      minimumInstructionCoverage: '0',
                      minimumLineCoverage       : '0',
                      minimumMethodCoverage     : '0'
                ])
            } else {
                echo 'Skipping Test Coverage'
            }
        }
    )
}

/**
 * Version bereitstellen.
 */
def stage_deploy() {
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

/**
 * Informationen per Mail und HipChat versenden.
 */
def notifications(String info) {
    def res = joinResults();
    parallel(
            'Send Mail': {
                mailToComitters(info, res)
            },
            'HipChat': {
                chat(info, res)
            }
    )
}

/**
 * Direkt als Erstes innerhalb einer Node-Closure aufrufen. Es wird das Pipeline-Globale Build-Datum ermittelt:
 * <ul>
 *     <li><code>BUILD_DATE</code></li>
 * </ul>
 *
 * Für die Ausführung der Systemtestfälle werden die folgenden Variablen gesetzt
 * <ul>
 *     <li><code>TESTRUNNER_APPLICATION</code></li>
 *     <li><code>TESTRUNNER_CLUSTER</code></li>
 * </ul>
 */
void globalSetUp() {

    // Ergebnisse der einzelnen Stages.
    results = []

    // Pipeline ordentlich beendet?
    finished = false

    // Build-Date für gesamte Pipeline festlegen
    if (env.BUILD_DATE == null) {
        env.BUILD_DATE = new SimpleDateFormat('yyyy-MM-dd_HH-mm-ss', Locale.GERMAN).format(new Date())
        echo "BUILD_DATE: ${env.BUILD_DATE}"
    }

    // Einstellungen für den Testrunner machen
    if (isUnix()) {
        JAVA_HOME="${env.WORKSPACE}/program/java7"
        JAVA_BINARY="${JAVA_HOME}/bin/java"
        GRADLE_BINARY = "${env.WORKSPACE}/Workspace/extern/development/gradle/bin/gradle"
        TESTRUNNER_APPLICATION = "application_jenkins.xml"
        TESTRUNNER_CLUSTER = "local_linux"
    } else {
        JAVA_HOME='C:\\Program Files\\Java\\jdk1.7.0_79'
        JAVA_BINARY="${JAVA_HOME}\\bin\\java.exe"
        GRADLE_BINARY = "${env.WORKSPACE}\\Workspace\\extern\\development\\gradle\\bin\\gradle.bat"
        TESTRUNNER_APPLICATION = "application_jenkins_windows.xml"
        TESTRUNNER_CLUSTER = "local_components"
    }

}

String createEnvironment() {
    if (isUnix()) {
        return ["JAVA_HOME=${env.WORKSPACE}/program/java7"]
    } else {
        return []
    }
}

/**
 * Befehl mit Argumenten entsprechend des Knotens unterschiedlich ausführen.
 *
 * @param linux der Befehl für Linux und, falls nur dieser angegeben, auch für Windows.
 * @param windows optional der alternative Befehl für Windows
 */
def run(String linux, String windows = null) {
    if (isUnix()) {
        sh linux
    } else {
        bat windows != null ? windows : linux
    }
}

/**
 * Allgemeiner interner Gradle-Aufruf.
 *
 * @param workers Maximale Anzahl paralleler Tasks.
 * @param args Die zu bauenden Tasks. Anhand der Globalen Variablen <code>tasks</code> werden alle nicht angegebenen
 *          Tasks ausgeschaltet.
 */
def callGradle(int workers, String tasks) {
    dir('Workspace/rootProject') {
        def args = []
        args += '--no-daemon'
        args += '-s'
        args += "-PsetBuildDate=${env.BUILD_DATE}"
        args += '--profile'

        if (workers > 0) {
            args += '--parallel'
            args += '-max-workers=${workers}'
        }

        if (!params.get('withoutEcj')) {
            args += '-Pjava.compile.ecj.enabled=true'
        }

        def jvm_args = '-server -Xmx1G -Xms1G -XX:ReservedCodeCacheSize=1G -XX:+DisableExplicitGC -XX:MaxPermSize=1G -XX:PermSize=256m -XX:+UseConcMarkSweepGC -XX:+CMSClassUnloadingEnabled -XX:+CMSPermGenSweepingEnabled'
        args += "-Dorg.gradle.jvmargs=\"${jvm_args}\""
        args += tasks

        def command = args.join(' ')

        run "${GRADLE_BINARY} ${command}"
    }
}

/**
 * Gradle aufrufen.
 */
def gradle(Map<String, Object> configuration) {
    def tasks = configuration['tasks']
    def excludes = configuration['excludes'] ? configuration['excludes'] : []
    def workers = configuration['workers'] ? configuration['workers'] : 0
    def args = configuration['args'] ? configuration['args'] : []

    def call = []
    call.addAll(args)
    call.addAll(tasks)
    call.addAll(excludes.collect({
        "-x $it"
    }))
    callGradle(workers, call.join(' '))
}

/**
 * Der Pfad-Separator ist vom Knoten abhängig. Files.pathSep bezieht sich auf den Master.
 */
def pathSep() {
    isUnix() ? ':' : ';'
}

/**
 * Der File-Separator ist vom Knoten abhängig. Files.fileSep bezieht sich auf den Master.
 */
def fileSep() {
    isUnix() ? '/' : '\\'
}

/**
 * Unit-Tests einsammeln.
 */
def getUnit(StaticAnalysisType type) {
    echo "Collecting " + type.name + "..."
    step([$class: 'JUnitResultArchiver', keepLongStdio: true, testResults: type.pattern])
    archiveArtifacts allowEmptyArchive: true, artifacts: type.pattern, defaultExcludes: false
}

/**
 * Artefakte einsammeln.
 */
def getAsArtefact(StaticAnalysisType type) {
    echo "Collecting " + type.name + "..."
    archiveArtifacts allowEmptyArchive: true, artifacts: type.pattern, defaultExcludes: false
}

/**
 * Ergebnisse von Checkstyle einsammeln.
 */
def getCheckstyle(StaticAnalysisType type, boolean resetLimits) {
    echo "Collecting " + type.name + "..."
    //hier sind aktuell nur Grenzwerte fuer neue Warnungen aktiviert (also sowas aehnliches wie Ratcheting)
    step([$class                     : 'CheckStylePublisher', defaultEncoding: 'UTF-8', healthy: '', unHealthy: '',
          pattern                    : type.pattern,
          unstableNewAll             : '0', unstableNewHigh: '0', unstableNewLow: '0', unstableNewNormal: '0',
          useDeltaValues             : true, canComputeNew: !resetLimits,
          usePreviousBuildAsReference: false, useStableBuildAsReference: false])
    archiveArtifacts allowEmptyArchive: true, artifacts: type.pattern, defaultExcludes: false

}

/**
 * Ergebnisse von Findbugs einsammeln.
 */
def getFindbugs(StaticAnalysisType type, boolean resetLimits) {
    echo "Collecting " + type.name + "..."
    //hier sind aktuell nur Grenzwerte fuer neue Warnungen aktiviert (also sowas aehnliches wie Ratcheting)
    step([$class                     : 'FindBugsPublisher', defaultEncoding: 'UTF-8', excludePattern: '', healthy: '', includePattern: '', unHealthy: '',
          pattern                    : type.pattern,
          unstableNewAll             : '0', unstableNewHigh: '0', unstableNewLow: '0', unstableNewNormal: '0',
          useDeltaValues             : true, canComputeNew: !resetLimits,
          usePreviousBuildAsReference: false, useStableBuildAsReference: false])
    archiveArtifacts allowEmptyArchive: true, artifacts: type.pattern, defaultExcludes: false
}

/**
 * TODO-Suchergebnisse einsammeln.
 */
def getTodos(StaticAnalysisType type) {
    echo "Collecting " + type.name + "..."
    step([$class: 'TasksPublisher', high: '^.*(FIXME)(.*)$',
          normal: '^.*(TODO (?:PSY|Auto-generated|implement|8.2|10.6|[0-9]{1,2}|1[0-3][0-9]|140|141|142))(.*)$',
          low   : '^.*(@deprecated)(.*)$', ignoreCase: true, asRegexp: true, excludePattern: '', pattern: type.pattern])
    // Kein Archivieren der Dateien, die das Pattern matchen, da es sich hier um Sourcedateien handelt.
}

/**
 * Compiler-Warnungen einsammeln.
 */
def getCompilerWarnings(StaticAnalysisType type) {
    echo "Collecting " + type.name + "..."
    step([$class: 'WarningsPublisher', consoleParsers: [
        [parserName: 'Java Compiler (javac)'],
        [parserName: 'Java Compiler (Eclipse)']
    ]])
    // Kein Archivieren, da die Konsole geparst wird.
}

/**
 * Ergebnisse von Codenarc auswerten.
 */
def getCodenarc(StaticAnalysisType type) {
    echo "Collecting " + type.name + "..."
    step([$class          : 'WarningsPublisher', parserConfigurations: [[parserName: 'Codenarc', pattern: type.pattern]],
          unstableTotalAll: '0', canComputeNew: false, canResolveRelativePaths: false])
    archiveArtifacts allowEmptyArchive: true, artifacts: type.pattern, defaultExcludes: false
}

/**
 * Arten der Statischen Analyse.
 */
enum StaticAnalysisType {

    //TODO das Scannen des kompletten Workspaces mit ** ist inperformant. Wenn wir die statischen Analyse
    //  wieder auf globale Analyse umgestellt haben dran denken das auszubauen
    FINDBUGS('Findbugs', 'Workspace/**/build/reports/findbugs/*.xml'),
    CHECKSTYLE('Checkstyle', 'Workspace/**/reports/checkstyle*.xml,Workspace/**/reports/xsCheckstyle*.xml,Workspace/**/reports/xsCheckstyle/*.xml'),

    JUNIT('Unit-Test', 'Workspace/**/build/test-results/**/*.xml'),

    CLASSYCLE('Classycle', 'Workspace/**/build/reports/classycle/**'),
    CODENARC('CodeNarc', 'Workspace/**/build/reports/codeNarc/*.xml'),

    TODOS('TODOS', 'Workspace/**/src/**/*.java'),
    WARNINGS('Warnings', '')

    String name
    String pattern

    StaticAnalysisType(String name, String pattern) {
        this.name = name
        this.pattern = pattern
    }

}

/**
 * E-Mail an Comitter versenden.
 */
def mailToComitters(String info, String res) {
    emailext to: 'as@set.de', // to darf nicht leer oder null sein
            recipientProviders: [[$class: 'CulpritsRecipientProvider'], [$class: 'DevelopersRecipientProvider']],
            subject: "'${env.JOB_NAME} [${env.BUILD_NUMBER}]' - ${info} ${currentBuild.result == null ? 'SUCCESS' : currentBuild.result}",
            body: "Rev ${env.SVN_REVISION} - Siehe ${env.BUILD_URL}<br/>${res}"
}

/**
 * Ergebnisse der Steps zur Ausgabe zusammenfassen
 */
def joinResults() {
    def res = ''
    for (int i = 0; i < results.size(); i++) {
        def entry = results.get(i);
        res += 'Stage \'' + entry[0] + '\' : ' + entry[1] + '<br/>'
    }
    return res
}

/**
 * Effektiven Result-String ermitteln. <code>null</code> steht für <code>SUCCESS</code>.
 */
def effResult() {
    return currentBuild.result == null ? 'SUCCESS' : currentBuild.result
}

/**
 * Erweiterter Stage mit Exception- und Ergebnisauswertung. Zusätzlich wird geprüft, ob die Pipeline
 * weiterlaufen soll.
 */
def extendedStage(String name, Closure closure) {
    try {
        stage(name) {
            closure()
        }
    } catch (StopBuildException e) {
        throw e
    } catch (Exception e) {
        if (currentBuild.result != 'ABORTED')
            currentBuild.result = 'FAILURE'
        throw e
    } finally {
        results.add([name, effResult()])
    }
    continueCheck(name)
}

/**
 * Information zum Build in HipChat posten.
 */
def chat(String info, String res) {
    def col = 'WHITE'
    def state = effResult()
    switch (currentBuild.result) {
        case 'UNSTABLE':
            col = 'YELLOW'
            break
        case null:
        // fallthru
        case 'SUCCESS':
            col = 'GREEN'
            break
        case 'FAILURE':
            col = 'RED'
            break
        case 'NOT_BUILT':
            col = 'WHITE'
            break
        case 'ABORTED':
            col = 'GRAY'
            break
    }
    def dynamicRecipients = emailextrecipients([[$class: 'CulpritsRecipientProvider'], [$class: 'DevelopersRecipientProvider']])
    def msg = "'${env.JOB_NAME} [${env.BUILD_NUMBER}]' " +
            "- ${info} ${state} " +
            "(<a href=\"${env.BUILD_URL}\">View in Jenkins</a>)<br/>" +
            "Revision ${env.SVN_REVISION}<br/>" +
            "${res}" +
            "Mail erhalten: ${dynamicRecipients}"
    hipchatSend(color: col, notify: true, message: msg)
}

/**
 * Parameterdefinition.
 */
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

/**
 * Job-Parameter auswerten.
 */
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
            return null
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
                    messages.add('Duplicate value for the parameter ' + cur.name)
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
        String msg = 'Possible Build Parameters:\n'
        for (int i = 0; i < possibleParams.length; i++) {
            ParamDef pd = possibleParams[i]
            msg += 'Name: ' + pd.name + ', Type: ' + pd.type + ', Default-Value: ' + pd.defaultValue + ', Description: ' + pd.description + '\n'
        }
        myEcho(steps, msg)
    }

    private static void myEcho(def steps, String message) {
        //direkter echo-Aufruf nicht moeglich, weil eigene Klasse.
        //  Und println geht auch nicht, vermutlich weil ausserhalb eines node
        steps.invokeMethod('echo', message)
    }

    public boolean isSet(String flagName) {
        return get(flagName)
    }

    public Object get(String paramName) {
        Object v = this.params.get(paramName)
        if (v == null) {
            throw new RuntimeException('error in pipeline script. invalid parameter name ' + paramName)
        }
        return v
    }
}

/**
 * Exception mit Sonderbehandlung, die benutzt wird, um die Pipeline bei UNSTABLE-Results zu beenden.
 */
class StopBuildException extends Exception implements Serializable {

    public StopBuildException(String s) {
        super(s)
    }

}

/**
 * Prüfung, ob die Pipeline weiterlaufen soll.
 */
def continueCheck(String lastStage) {
    echo "Checking Build-Result after Stage '${lastStage}': " + currentBuild.result
    if (currentBuild.result != null && currentBuild.result != 'SUCCESS') {
        echo "Stopping Pipeline..."
        currentBuild.description += "Pipeline gestoppt nach '${lastStage}'<br/>"
        throw new StopBuildException('Test')
    }
}

// Wird benötigt, damit das Load (aus dem Jenkins Job, das diese Pipeline läd) nicht hängen bleibt.
return this
