plugins {
    id("takenaka.base-conventions")
    id("takenaka.publish-conventions")
}

apply(plugin = "org.jetbrains.kotlin.jvm")

dependencies {
    api(project(":generator-common"))
    api(libs.kotlinx.html.jvm)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.slf4j.simple)
}

license {
    exclude("me/kcra/takenaka/generator/web/SignatureFormatter.kt") // third party license
}

tasks.withType<Test> {
    maxHeapSize = "2048m"
}
