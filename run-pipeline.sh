#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="$ROOT/converted-kotlin"
LOGS_DIR="$ROOT/logs"

step() { echo; echo "==> $*"; echo; }
fail() { echo "ERROR: $*" >&2; exit 1; }

run_conversion() {
  local name="$1" source_dir="$2" project_dir="$3"
  step "J2K conversion — $name"
  mkdir -p "$LOGS_DIR"
  ./gradlew runIde \
    -PsourceDir="$source_dir" \
    -PoutputDir="$OUTPUT_DIR" \
    -PprojectDir="$project_dir" \
    --no-daemon --stacktrace \
    2>&1 | tee "$LOGS_DIR/j2k-$name.log"
}

run_evaluate() {
  local name="$1" project_dir="$2"
  step "Evaluate — $name"
  ./gradlew :evaluator:run \
    --args="$project_dir" \
    --no-daemon
}


cd "$ROOT"
chmod +x gradlew

[[ -d test-projects/petclinic/java/spring-petclinic/.git ]] || \
  fail "Submodules not initialised — run: git submodule update --init --recursive"


run_conversion "petclinic" \
  "$ROOT/test-projects/petclinic/java/spring-petclinic/src" \
  "$ROOT/test-projects/petclinic/java/spring-petclinic"

run_conversion "realworld" \
  "$ROOT/test-projects/realworld/java/spring-boot-realworld-example-app/src" \
  "$ROOT/test-projects/realworld/java/spring-boot-realworld-example-app"

run_conversion "microbenchmark" \
  "$ROOT/test-projects/microbenchmark/src" \
  "$ROOT/test-projects/microbenchmark"


step "Converted Kotlin files"
for project in spring-petclinic spring-boot-realworld-example-app microbenchmark; do
  dir="$OUTPUT_DIR/$project"
  count=$(find "$dir" -name "*.kt" 2>/dev/null | wc -l)
  echo "  $project: $count file(s)"
done


step "Validating output"
total=$(find "$OUTPUT_DIR" -name "*.kt" 2>/dev/null | wc -l)
[[ "$total" -gt 0 ]] || fail "No .kt files found in $OUTPUT_DIR"

empty=$(find "$OUTPUT_DIR" -name "*.kt" -empty 2>/dev/null)
if [[ -n "$empty" ]]; then
  echo "Empty files found:"
  echo "$empty"
  fail "Empty .kt files detected"
fi
echo "  $total file(s) converted, none empty"


run_evaluate "petclinic"        "$OUTPUT_DIR/spring-petclinic"
run_evaluate "realworld"        "$OUTPUT_DIR/spring-boot-realworld-example-app"
run_evaluate "microbenchmark"   "$OUTPUT_DIR/microbenchmark"


step "Evaluation Summary"
for report in evaluations/evaluation-report-*.json; do
  [[ -f "$report" ]] || continue
  project=$(jq -r '.project' "$report")
  success=$(jq -r '.compilation.success' "$report")
  errors=$(jq -r '.compilation.errorCount' "$report")
  warnings=$(jq -r '.compilation.warningCount' "$report")
  files=$(jq -r '.heuristics.filesScanned' "$report")
  forced=$(jq -r '.heuristics.forcedNonNull' "$report")
  val_ratio=$(jq -r '.heuristics.valRatioPct' "$report")
  semicolons=$(jq -r '.heuristics.semicolons' "$report")
  col_ops=$(jq -r '.heuristics.collectionOps' "$report")
  crash=$(jq -r '.compilation.compilerCrash // empty' "$report")
  unique_count=$(jq '.compilation.uniqueErrors | length' "$report")

  [[ "$success" == "true" ]] && status="SUCCESS" || status="FAILED"

  echo "--- $project ---"
  printf "  %-30s %s\n" "Compilation:"        "$status"
  printf "  %-30s %s / %s\n" "Errors / Warnings:"  "$errors" "$warnings"
  printf "  %-30s %s\n" "Files scanned:"      "$files"
  printf "  %-30s %s%%\n" "val ratio:"         "$val_ratio"
  printf "  %-30s %s\n" "Forced non-null !!:" "$forced"
  printf "  %-30s %s\n" "Semicolons left:"    "$semicolons"
  printf "  %-30s %s\n" "Collection ops:"     "$col_ops"

  if [[ -n "$crash" ]]; then
    echo "  COMPILER CRASH: $crash"
  fi

  if [[ "$unique_count" -gt 0 ]]; then
    echo "  Unique error types ($unique_count):"
    jq -r '.compilation.uniqueErrors[] | "    x\(.count)  \(.message)"' "$report"
  fi
  echo
done

step "Done — reports in evaluations/"
