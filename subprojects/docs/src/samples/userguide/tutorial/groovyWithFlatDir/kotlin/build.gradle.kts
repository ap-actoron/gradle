plugins {
    groovy
}

repositories {
    mavenCentral()
}

// tag::groovy-dependency[]
repositories {
    flatDir { dirs("lib") }
}

dependencies {
    implementation(module("org.codehaus.groovy:groovy:2.4.15") {
        dependency("org.ow2.asm:asm-all:5.0.3")
        dependency("antlr:antlr:2.7.7")
        dependency("commons-cli:commons-cli:1.2")
        module("org.apache.ant:ant:1.9.4") {
            dependencies("org.apache.ant:ant-junit:1.9.4@jar",
                         "org.apache.ant:ant-launcher:1.9.4")
        }
    })
}
// end::groovy-dependency[]
