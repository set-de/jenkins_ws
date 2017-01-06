FROM jenkins:2.32.1

ARG workspace
USER root

#ADD $workspace /tmp/ws
# COPY src/jobs/
COPY plugins /usr/share/jenkins/ref/plugins
COPY src/main/jobs/Jenkinsfile.groovy /tmp/ws/
RUN chown -R jenkins:jenkins /tmp/ws /usr/share/jenkins/ref/plugins

USER jenkins

# TODO without this JENKINS-24752 workaround, it takes too long to provision.
# (Do not add hudson.model.LoadStatistics.decay=0.1; in that case we overprovision slaves which never get used, and OnceRetentionStrategy.check disconnects them after an idle timeout.)
ENV JAVA_OPTS -Dhudson.model.LoadStatistics.clock=1000 -Djenkins.install.state=INITIAL_SECURITY_SETUP

ADD JENKINS_HOME /usr/share/jenkins/ref