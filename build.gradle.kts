import java.util.concurrent.TimeUnit

plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "4.0.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.aandiclub"
version = "0.0.1-SNAPSHOT"
description = "Demo project for Spring Boot"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation(platform("software.amazon.awssdk:bom:2.25.20"))
	implementation("software.amazon.awssdk:sqs")
	implementation("software.amazon.awssdk:sns")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
	implementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:3.0.1")
	implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j")
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("ca.pjer:logback-awslogs-appender:1.6.0")
	implementation("com.amazonaws:aws-java-sdk-logs:1.12.+")
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
	testImplementation("org.springframework.boot:spring-boot-starter-data-redis-reactive-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-mongodb-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
	testImplementation("io.mockk:mockk:1.13.13")
	testImplementation("com.ninja-squad:springmockk:4.0.2")
	testImplementation("org.testcontainers:junit-jupiter:1.21.3")
	testImplementation("org.testcontainers:mongodb:1.21.3")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()

	// Docker socket existence alone is not enough; the daemon must be reachable.
	val isDockerAvailable = runCatching {
		val process: Process = ProcessBuilder("docker", "info")
			.redirectErrorStream(true)
			.start()
		if (!process.waitFor(5, TimeUnit.SECONDS)) {
			process.destroyForcibly()
			error("docker info timed out")
		}
		check(process.exitValue() == 0)
		true
	}.getOrDefault(false)

	// Docker가 없거나 환경 변수로 명시적으로 스킵하면 E2E 테스트 제외
	if (!isDockerAvailable || System.getenv("SKIP_E2E_TESTS") == "true") {
		println("⚠️  Docker not available - skipping E2E tests")
		exclude("**/*E2ETest*")
	} else {
		println("✓ Docker available - running all tests including E2E")
	}
}
