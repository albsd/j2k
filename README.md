# j2k conversion pipeline

Automated pipeline that runs IntelliJ's J2K converter as a custom plugin, then evaluates output through compilation checks and idiom/structural heuristics. 

---

## Plugin Architecture

The plugin consists of two components registered in `plugin.xml`:

**`J2kService`** (`applicationService`, `preload="true"`)  
`preload="true"` causes the service to initialise before a project is open (at IDE startup). IntelliJ API calls are deferred with `invokeLater`. I do this in order to counter the plugin running before the IDE is in the `COMPONENTS_LOADED` state. It reads the `j2k.projectDir` system property and calls `ProjectManagerEx.openProject()` to load the target Java project into the IDE.

**`J2kActivity`** (`postStartupActivity`)  
Runs after the project is open and dumb mode has exited. It walks the source directory, converts each `.java` file to Kotlin using `OldJ2kConverterExtension` (resolved via `J2kConverterExtension.EP_NAME.extensionList.first()`), and writes the resulting `.kt` files to the configured output directory.

The converter call followed by a reformat pass:
```kotlin
val kotlinSource = converter.elementsToKotlin(listOf(psiFile)).results.firstOrNull()?.text
// then reformatted with CodeStyleManager before writing to disk
val formattedText = WriteCommandAction.runWriteCommandAction<String>(project) {
    val ktFile = fileFactory.createFileFromText("temp.kt", KotlinLanguage.INSTANCE, kotlinSource) as KtFile
    CodeStyleManager.getInstance(project).reformat(ktFile)
    ktFile.text
}
```

System properties passed at runtime (via `runIde` Gradle task):
- `j2k.projectDir` — path to the Java project root
- `j2k.sourceDir` — directory of `.java` files to convert
- `j2k.outputDir` — where converted `.kt` files are written

---

## Local Reproduction

**Prerequisites**
- JDK 17
- IntelliJ IDEA or the Gradle wrapper 
- Submodules initialised: `git submodule update --init --recursive`

**Run the full pipeline**
```bash
chmod +x run-pipeline.sh
./run-pipeline.sh
```

This script kind of mirrors the CI pipeline. We run conversion on petclinic, realworld, and microbenchmark, then evaluate each.

**Run a single conversion**
```bash
./gradlew runIde \
  -PsourceDir="$PWD/test-projects/petclinic/java/spring-petclinic/src" \
  -PoutputDir="$PWD/converted-kotlin" \
  -PprojectDir="$PWD/test-projects/petclinic/java/spring-petclinic" \
  --no-daemon --stacktrace
```

**Run the evaluation**
```bash
./gradlew :evaluator:run \
  --args="$PWD/converted-kotlin/spring-petclinic" \
  --no-daemon
```

---

## CI Pipeline

Defined in `.github/workflows/test-plugin-on-projects.yml`. Stages:

> **Note:**  On CI a virtual display is required. If I did not do this, the setup would break and the workflow would hang.
0. Initialize virtual display
1. Convert each project with `runIde`
2. Validate that `.kt` files were produced and none are empty
3. Evaluate each output with the `evaluator` module
4. Write a Markdown summary to `$GITHUB_STEP_SUMMARY`
5. Upload logs, converted sources, and evaluation JSON reports as artifacts

---

You can find the results of the evaluation in the summary of the run. For example, see [this run](https://github.com/albsd/j2k/actions/runs/24350328481)
## How the Evaluator Works

The evaluator has two parts: compilation checks and structural heuristics.

Compilation is my primary quality oracle. It does not capture style or idiomatic Kotlin particularly well, but it at least tells you whether the conversion produces something usable. A file that does not compile is essentially a failed conversion. To do this, I inject the Kotlin JVM Gradle plugin and the stdlib dependency into each converted project's `build.gradle`, then run `compileKotlin`. I also handle compatibility issues on the fly. If the project's Gradle wrapper is too old for Kotlin 2.1.20 (requires >= 7.6.3), I bump it automatically.

If the full project compilation crashes the compiler (as happens with the microbenchmark due to the `???` placeholder triggering an internal FIR exception), I fall back to compiling each `.kt` file individually in an isolated temporary Gradle project. This lets me extract per-file error counts and identify which specific files are responsible for failures, rather than getting a single crash with no useful output.

Structural heuristics run separately and scan the converted `.kt` files for things like `val` vs `var` ratio, forced non-null assertions (`!!`), leftover semicolons, remaining Java-style getter/setter calls, null-check `if` blocks, string concatenation with `+`, and collection operation usage. These give a rough picture of how idiomatic the output is, independent of whether it compiles.

Results are written to `evaluations/evaluation-report-<project>.json`.

---

## Real-World Evaluation Results

I evaluate the pipeline using spring-petclinic, spring-boot-realworld-example-app, and a small microbenchmark suite for edge cases. I include the realworld example because it is similar in scope to Petclinic (though slightly larger), but uses a more modern and library-heavy Spring stack, including Lombok. This makes it useful for comparing how the converter behaves on a clean baseline versus a more dependency-heavy codebase.

The val ratio measures the proportion of `val` declarations relative to `var` (higher generally indicates more idiomatic Kotlin output), and collection ops counts functional collection transformations such as `map`, `forEach`, and `filter` used in place of manual loops. The idea is that we would ideally see more collection ops than java-like `for-loops`. 

I also track forced `!!` occurrences as a proxy for unsafe null-handling introduced by the conversion process. A high number indicates that J2K could not infer nullability correctly and fell back to non-idiomatic Kotlin that may compile but is fragile at runtime due to potential NullPointerExceptions.

| Project | Files | Compilation | Errors | Warnings | val ratio | Forced !! | Semicolons | For loops | Collection ops |
|---|---|---|---|---|---|---|---|---|---|
| spring-petclinic | 47 | FAILED | 106 | 0 | 82.1% | 302 | 46 | 11 | 9 |
| spring-boot-realworld-example-app | 116 | FAILED | 1141 | 0 | 93.2% | 719 | 0 | 8 | 44 |
| microbenchmark | 7 | CRASH + FAILED | 78 | 0 | 92.0% | 26 | 1 | 3 | 13 |

**spring-petclinic** — 106 errors across 47 files. The dominant failure categories are unresolved Java getter/setter references (`getName`, `getId`, `getBirthDate`, etc.) that J2K did not convert to Kotlin property access, `isNew` being emitted as a Boolean property but then called as a function, annotation vararg mismatches (`CascadeType` vs `Array<CascadeType>`), and Spring Data generic bounds (`must be subtype of 'Any'`). The 302 forced `!!` and 46 remaining semicolons indicate the converter fell back to safe but non-idiomatic output for a large portion of the codebase.

**spring-boot-realworld-example-app** — heavy use of Lombok (`@Data`, `@Builder`). J2K cannot see Lombok-generated methods (they exist only after annotation processing), producing 719 forced `!!` assertions and widespread unresolved-reference errors. 

**microbenchmark** — the FIR compiler crashes internally on `WildcardGenerics.kt` (`kotlin.UninitializedPropertyAccessException: lateinit property firType has not been initialized`). The evaluator falls back to per-file compilation; all 7 files fail. See edge-case breakdown below.

---

## Edge-Case Dataset (Microbenchmarks)

Each file exercises one J2K translation pattern:

| File | Result | Errors | Reason |
|---|---|---|---|
| `WildcardGenerics.kt` | **CRASH** | 0 | FIR compiler throws `UninitializedPropertyAccessException` on `lateinit property firType` — triggered by J2K emitting `???` for nested wildcard types (e.g. `List<? extends List<?>>`). Crashes before producing any error messages. |
| `JavaStreams.kt` | **FAIL** | 31 | `Stream` API calls converted to Kotlin collection ops but inferred types don't align; many unresolved references |
| `NestedAnonymousClass.kt` | **FAIL** | 15 | Anonymous class bodies with captured fields produce invalid Kotlin; type constructor and member hiding errors |
| `FunctionalInterfaceSam.kt` | **FAIL** | 14 | SAM conversion not fully translated; unresolved references and return type mismatches |
| `StaticInitializer.kt` | **FAIL** | 6 | Static initialiser block translation produces syntax errors |
| `InnerClassThis.kt` | **FAIL** | 2 | Qualified `this` reference translation produces member hiding conflicts |
| `MultiCatch.kt` | **FAIL** | 10 | Multi-catch translation produces type mismatch and syntax errors |

---

## Proposed fixes - pipeline

Obviously, the results do not look so good. This is a combination of both the existing J2K plugin(s) within IntelliJ and my approach. I have had several issues in implementing this, with not enough time to fix all of them. For one, I mentioned at the beginning of the README that we try to avoid running the IDE before the components are loaded. This still happens in the CI and I do not fully know why. I have also tried to use other versions of the converter such as the NewJ2kConverter or manipulate the post processor directly or use nj2k. I did not manage to get nj2k running as I had issues using the 2026.1 IntelliJ Community plugin. I had issues as well for the NewJ2kConverterExtension. This is the reason why the OldJ2kConverterExtension is used here. 

There are big issues with Spring projects. From testing before implementing, I already knew that I needed to also have the spring kotlin plugin for the extension to work but I have not managed to do that in my solution. 

Additionally, many of the issues with multi-file projects seem to revolve around dependencies. I tried to do everything in one batch and have the context of the project for each file, with gradle syncs, but that lead me to issues where I would have two IDEs open and they would stall. 

The pipeline itself is also flaky at times. I assume this is because of the caches, as if I run the pipeline multiple times, without deleting the caches, there's a risk of it getting blocked / staling, with lots of EDT errors. Towards that purpose, I made a workflow file to remove the caches. 

I know that my solution is not necessarily ideal but I tried to fix it and look into as much as possible with the given time. I believe that if I had a few extra days I could have had a much more consistent conversion. 

## Proposed fixes - J2kConverter

Honestly, the biggest suggestion I can bring is a much easier to follow API. I understand that it is technically an internal plugin for the IDE but I believe that test tasks such as this show that maybe, for some reason, you would actually like to use it! In that case, the structure is very hard to navigate, with j2k, n2jk, shared, and all sorts of other directories. It is also difficult to see what API is available depending on the intellij plugin version so what I would suggest is to provide external APIs for the conversion and document this much better (part of debugging, especially at the beginning, consisted of looking through forums or consulting, to no avail, LLMs)




## Also, a proposed fix for a specific issue:

When J2K encounters a nested wildcard type like `List<? extends List<?>>` it cannot represent in Kotlin's type system, it emits the placeholder token `???`. This causes the FIR compiler to crash with `kotlin.UninitializedPropertyAccessException: lateinit property firType has not been initialized` — an internal compiler bug triggered by the invalid token, producing no actionable error messages.

**Proposed fix (in J2K's type converter):**

```kotlin
// Before — emits uncompilable placeholder
is PsiWildcardType -> KtPsiFactory(project).createType("???")

// After — replace with star projection
is PsiWildcardType -> {
    val bound = type.bound
    if (bound == null || isNestedWildcard(bound)) {
        KtPsiFactory(project).createType("List<*>")  // or the appropriate erased type
    } else {
        // existing bound handling
        convertBoundedWildcard(type, bound)
    }
}
```

The fix replaces unrepresentable nested wildcards with star projections (`*`) rather than invalid placeholder tokens. This trades a compiler crash (unrecoverable) for a compile warning about unchecked casts (recoverable and accurate). The resulting code compiles, and the lost type information is documented by the star projection rather than hidden.


> [NOTE] : Refer to [this fork of petclinic](https://github.com/albsd/spring-petclinic-kotlin) for commit history on plugin and workflow file. I initially tried to integrate J2K in a petclinic fork and then extend it to other benchmarks too, leading to this repo.