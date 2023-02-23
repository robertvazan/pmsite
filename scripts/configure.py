# This script generates and updates project configuration files.

# We are assuming that project-config is available in sibling directory.
# Checkout from https://github.com/robertvazan/project-config
import pathlib
project_directory = lambda: pathlib.Path(__file__).parent.parent
config_directory = lambda: project_directory().parent/'project-config'
exec((config_directory()/'src'/'java.py').read_text())

project_script_path = __file__
repository_name = lambda: 'pmsite'
pretty_name = lambda: 'PMSite'
pom_description = lambda: "Simplistic application framework built on top of PushMode HTML streaming library."
inception_year = lambda: 2017
jdk_version = lambda: 17
has_javadoc = lambda: False
stagean_annotations = lambda: True
project_status = lambda: experimental_status()

def dependencies():
    use_hookless()
    use_pushmode()
    # Insist on older SLF4J. Otherwise jetty would pull in 2.0 version.
    use_slf4j()
    use_noexception_slf4j()
    use_streamex()
    use_commons_lang()
    use_commons_math()
    use_guava()
    # Used just to load MIME types from resources.
    use_gson()
    use('org.eclipse.jetty.websocket:websocket-servlet:11.0.7')
    use('net.mikehardy:google-analytics-java:2.0.11')
    use('cz.jiripinkas:jsitemapgenerator:4.5')
    use_junit()
    use_slf4j_test()

javadoc_links = lambda: [
    'https://stagean.machinezoo.com/javadoc/',
    'https://noexception.machinezoo.com/javadoc/',
    'https://hookless.machinezoo.com/javadocs/core/',
    'https://hookless.machinezoo.com/javadocs/servlets/',
    'https://pushmode.machinezoo.com/javadoc/'
    # Jsitemapgenerator not linked, because automatic modules are not supported by javadoc.
]

generate()
