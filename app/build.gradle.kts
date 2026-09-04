plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.zhizhi.app"
    compileSdk {
        version = release(36)
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.zhizhi.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = false
        checkReleaseBuilds = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.register("testClasses") {
    group = "verification"
    description = "Compatibility alias for Android unit test task."
    dependsOn("testDebugUnitTest")
}

tasks.withType<Test>().configureEach {
    notCompatibleWithConfigurationCache("Custom ASCII classpath rewrite mutates test classpath at execution time")

    // 修复 Windows 中文工程路径下 UnitTest 偶发 ClassNotFound：
    // 在执行前把测试类与主类 JAR 复制到 ASCII 路径，并以该路径重建类路径。
    val variant = name
        .removePrefix("test")
        .removeSuffix("UnitTest")
        .replaceFirstChar { it.lowercase() } + "UnitTest"
    val mainVariant = variant.removeSuffix("UnitTest")
    val mainVariantCap = mainVariant.replaceFirstChar { it.uppercase() }
    val kotlinTestClasses = layout.buildDirectory.dir("tmp/kotlin-classes/$variant")
    val mainClassesJar = layout.buildDirectory.file(
        "intermediates/runtime_app_classes_jar/$mainVariant/bundle${mainVariantCap}ClassesToRuntimeJar/classes.jar"
    )

    dependsOn("compile${mainVariantCap}UnitTestKotlin")
    dependsOn("bundle${mainVariantCap}ClassesToRuntimeJar")

    doFirst {
        val asciiRoot = file("C:/GradleAsciiTestClasspath/${project.name}/$variant")
        val asciiTestDir = file("${asciiRoot.absolutePath}/tests")
        val asciiMainJar = file("${asciiRoot.absolutePath}/main/classes.jar")
        // [Fix: 幽灵测试] 复制前清空目标目录。旧实现只增不删:
        // 已从仓库删除的测试源码, 其陈旧 .class 会永久残留在 ASCII 目录里,
        // 被测试任务当作仍然存在的测试反复执行(仓库无源码, 报告却有失败)。
        // 清空后每次运行只包含当前源码编译出的测试类, 新克隆也不受影响。
        delete(asciiRoot)
        asciiTestDir.mkdirs()
        asciiMainJar.parentFile?.mkdirs()

        copy {
            from(kotlinTestClasses.get().asFile)
            into(asciiTestDir)
        }
        copy {
            from(mainClassesJar.get().asFile)
            into(asciiMainJar.parentFile)
            rename { "classes.jar" }
        }

        val cachedDeps = classpath.filter {
            val p = it.absolutePath
            p.contains("\\caches\\") || p.contains("/caches/")
        }
        testClassesDirs = files(asciiTestDir)
        classpath = files(asciiTestDir, asciiMainJar).plus(cachedDeps)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.gridlayout:gridlayout:1.0.0")
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    // v3.1 C5: 复习回顾选词填空分页 (ViewPager2)
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    // AGP 8.13 unit test 中 org.json.JSONObject 是 stub, optJSONArray 返回 null.
    // 测试需要真实 JSON 解析, 仅 testImplementation, 不影响 production.
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}