plugins {
    `java-library`
}

applyPlatformAndCoreConfiguration()

dependencies {
    api(project(":nuvotifier-api"))
    compileOnly("com.google.code.gson:gson:${Versions.GSON}")
    implementation("io.netty:netty-handler:${Versions.NETTYIO}")
    implementation("io.netty:netty-transport-native-epoll:${Versions.NETTYIO}:linux-x86_64")
    implementation("redis.clients:jedis:${Versions.JEDIS}")
    testImplementation("org.json:json:20180130") // retain this for testing reasons
    testImplementation("com.google.guava:guava:28.1-jre")
}