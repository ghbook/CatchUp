/*
 * Copyright (c) 2018 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
  id("com.android.library")
  kotlin("android")
  kotlin("kapt")
  id(deps.anvil.pluginId)
  id(deps.ksp.pluginId)
}

//kapt {
//  arguments {
//    //arg("moshi.generated", "javax.annotation.processing.Generated")
//  }
//}

dependencies {
  ksp(deps.moshi.moshix.ksp)

  implementation(project(":libraries:util"))
  implementation(deps.misc.okio)
  implementation(deps.moshi.core)
  implementation(deps.retrofit.core)
  implementation(deps.retrofit.moshi)
  implementation(deps.retrofit.rxJava3)
  implementation(deps.okhttp.core)
  implementation(deps.misc.moshiLazyAdapters)
  implementation(deps.kotlin.datetime)

  // Inspector
  compileOnly(deps.inspector.factoryCompiler.compileOnly.annotations)
  compileOnly(deps.inspector.apt.extensions.android)
  compileOnly(deps.inspector.apt.extensions.nullability)
  kapt(deps.inspector.apt.compiler)
  kapt(deps.inspector.factoryCompiler.apt)
  implementation(deps.inspector.core) {
    exclude(group = "com.android.support")
  }

  api(project(":service-api"))
  api(deps.android.androidx.annotations)
  api(deps.dagger.runtime)
  api(deps.rx.java)
}
