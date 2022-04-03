plugins {
	id("java")
	id("java-library")
	id("application")
	id("com.github.johnrengelman.shadow") version "6.1.0"
	// TODO: Wait for shadow to support JDK 15 so we can minimize the built jar
	id("com.diffplug.spotless") version "5.8.2"
}

group "net.fabricmc"
version "1.0-SNAPSHOT"

repositories {
	mavenCentral()
}

allprojects {
	apply plugin: "java"
	apply plugin: "java-library"
	apply plugin: "com.diffplug.spotless"

	repositories {
		mavenCentral()

		maven {
			name = "Sonatype Snapshots"
			url = "https://oss.sonatype.org/content/repositories/snapshots"
		}

		maven {
			name = "Fabric"
			url = "https://maven.fabricmc.net"
		}
	}

	// Common dependencies
	dependencies {
		compileOnly("org.jetbrains:annotations:20.1.0")
		implementation("org.javacord:javacord:$javacordVersion")

		// tag parser artifact does not need a full db
		if (project != rootProject.project(":tag-parser")) {
			// Database
			implementation("com.zaxxer:HikariCP:3.4.5")
			implementation("org.xerial:sqlite-jdbc:3.36.0.3")
		}

		// Logging
		implementation("org.apache.logging.log4j:log4j-core:2.17.1")
		implementation("org.apache.logging.log4j:log4j-slf4j-impl:2.17.1")

		// Json Serialization
		implementation("com.google.code.gson:gson:2.8.6")

		implementation("it.unimi.dsi:fastutil-core:8.5.4");

		// TODO: Do we want this so we can :irritater: instead of raw id for custom emotes?
		//  https://github.com/vdurmont/emoji-java
	}

	spotless {
		java {
			ratchetFrom "origin/master"
			licenseHeaderFile(rootProject.file("HEADER")).yearSeparator(", ")
		}

		// Spotless tries to be smart by ignoring package-info files, however license headers are allowed there
		// TODO: Replicate on module-info if needed?
		//  FIXME: Currently broken
//        format("package-info.java") {
//            target "**/package-info.java"
//
//            // Only update license headers when changes have occurred
//            ratchetFrom("origin/master")
//            // Regex is `/**` or `package`
//            licenseHeaderFile(rootProject.file("HEADER"), "/\\*\\*|package").yearSeparator(", ")
//        }
	}

	java {
		modularity.inferModulePath = true

		toolchain {
			languageVersion = JavaLanguageVersion.of(16)
		}
	}

	tasks.withType(JavaCompile).configureEach {
//		options.compilerArgs += ["--enable-preview"]
		options.release = 16
	}

//	tasks.withType(Test).configureEach {
//		jvmArgs += "--enable-preview"
//	}
}

// Main bot module
application {
	mainClass = "net.fabricmc.discord.bot.Main"
//	applicationDefaultJvmArgs = ["--enable-preview"]
	executableDir = "run"
}

// Setup dependencies for big boi jar
dependencies {
	afterEvaluate {
		subprojects.each {
			implementation(it)
		}
	}
}

// Propagate core dependency to subprojects
subprojects {
	if (project != project(":core")) {
		dependencies {
			implementation(rootProject.project(":core"))
		}
	}
}

shadowJar {
	mainClassName = "net.fabricmc.discord.bot.Main"
}

java {
	modularity.inferModulePath = true

	toolchain {
		languageVersion = JavaLanguageVersion.of(16)
	}
}
