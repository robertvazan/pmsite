# This script generates and updates project configuration files.

# Run this script with rvscaffold in PYTHONPATH
import rvscaffold as scaffold

class Project(scaffold.Java):
    def script_path_text(self): return __file__
    def repository_name(self): return 'pmsite'
    def pretty_name(self): return 'PMSite'
    def pom_description(self): return "Simplistic application framework built on top of PushMode HTML streaming library."
    def inception_year(self): return 2017
    def jdk_version(self): return 17
    def has_javadoc(self): return False
    def stagean_annotations(self): return True
    
    def dependencies(self):
        yield from super().dependencies()
        yield self.use_hookless()
        yield self.use_pushmode()
        # Insist on older SLF4J. Otherwise jetty would pull in 2.0 version.
        yield self.use_slf4j()
        # Force latest version that uses closeablescope
        yield self.use_noexception()
        yield self.use_noexception_slf4j()
        yield self.use_streamex()
        yield self.use_commons_lang()
        yield self.use_commons_math()
        yield self.use_guava()
        # Used just to load MIME types from resources.
        yield self.use_gson()
        yield self.use('org.eclipse.jetty.websocket:websocket-servlet:11.0.7')
        yield self.use('net.mikehardy:google-analytics-java:2.0.11')
        yield self.use('cz.jiripinkas:jsitemapgenerator:4.5')
        yield self.use_junit()
        yield self.use_slf4j_test()
    
    def javadoc_links(self):
        yield 'https://stagean.machinezoo.com/javadoc/'
        yield 'https://noexception.machinezoo.com/javadoc/'
        yield 'https://hookless.machinezoo.com/javadocs/core/'
        yield 'https://hookless.machinezoo.com/javadocs/servlets/'
        yield 'https://pushmode.machinezoo.com/javadoc/'
        # Jsitemapgenerator not linked, because automatic modules are not supported by javadoc.

Project().generate()
