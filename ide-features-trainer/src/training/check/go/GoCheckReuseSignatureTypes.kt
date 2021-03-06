/*
 * Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */
package training.check.go

class GoCheckReuseSignatureTypes : GoCheck() {

  override fun check(): Boolean = (goFile
          ?.findFunction("greet")
          ?.signature
          ?.parameters
          ?.parameterDeclarationList
          ?.size ?: -1) == 1

}