import { createHash } from "node:crypto";
import { existsSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { basename, join, resolve } from "node:path";

type JsonObject = Record<string, any>;
type Decimal = { unscaled: bigint; scale: number };

const EXECUTION_CLASS = "EXEC::DESIGN_INPUT_EXPRESSION::1.0";
const TARGET_VERSION = "renderweave-design-input-expression-capacity-component-target/1.0";
const TARGET_ID = "DESIGN_INPUT_EXPRESSION_TARGET::CAPACITY_AUTHORITY_PRODUCT_WIRING_COMPLETE::17.0";
const IMPLEMENTATION_REVISION = "12a3f7e69b9a814358133c8d84ddc2b53da84789";
const REPORT_VERSION = "renderweave-design-input-expression-capacity-independent/1";
const INTEGER = /^-?(?:0|[1-9][0-9]*)$/;
const DECIMAL = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$/;
const FORBIDDEN_FIXTURE_KEYS = new Set([
  "expectedTerminal",
  "expectedAssertions",
  "plannedAssertions",
  "plannedOracleId",
  "requirementIds",
  "resolvedCode",
  "resolvedKind",
  "latest",
  "default",
  "script",
]);
const ROTATION_MAXIMUM_WIRED_IDS = [
  "geometry.rotationDegreesMax",
];
const RETIRED_RENDERING_OWNED_EXPRESSION_ARTIFACTS = [
  "renderweave-rendering/src/main/java/cn/hbads/renderweave/rendering/internal/ExpressionAst.java",
  "renderweave-rendering/src/main/java/cn/hbads/renderweave/rendering/internal/ExpressionParser.java",
];

function requiredOption(name: string): string {
  const index = process.argv.indexOf(name);
  if (index < 0 || index + 1 >= process.argv.length) {
    throw new Error(`Missing required option ${name}`);
  }
  return process.argv[index + 1];
}

function readJson(path: string): JsonObject {
  return JSON.parse(readFileSync(path, "utf8"));
}

function sha256(bytes: Buffer): string {
  return `sha256:${createHash("sha256").update(bytes).digest("hex")}`;
}

function requireCondition(condition: unknown, message: string): asserts condition {
  if (!condition) {
    throw new Error(message);
  }
}

function requireEqual(actual: unknown, expected: unknown, message: string): void {
  requireCondition(
    JSON.stringify(actual) === JSON.stringify(expected),
    `${message}: expected ${JSON.stringify(expected)}, observed ${JSON.stringify(actual)}`,
  );
}

function assertClosedFixture(value: unknown, location = "fixture"): void {
  if (Array.isArray(value)) {
    value.forEach((item, index) => assertClosedFixture(item, `${location}[${index}]`));
    return;
  }
  if (value === null || typeof value !== "object") {
    return;
  }
  for (const [key, nested] of Object.entries(value as JsonObject)) {
    requireCondition(!FORBIDDEN_FIXTURE_KEYS.has(key), `${location} contains forbidden key ${key}`);
    assertClosedFixture(nested, `${location}.${key}`);
  }
}

function parseDecimal(text: string): Decimal {
  requireCondition(DECIMAL.test(text), `invalid canonical decimal ${text}`);
  const negative = text.startsWith("-");
  const unsigned = negative ? text.slice(1) : text;
  const [whole, fraction = ""] = unsigned.split(".");
  const magnitude = BigInt(whole + fraction);
  return {
    unscaled: negative ? -magnitude : magnitude,
    scale: fraction.length,
  };
}

function compareDecimal(leftText: string, rightText: string): number {
  const left = parseDecimal(leftText);
  const right = parseDecimal(rightText);
  const scale = Math.max(left.scale, right.scale);
  const leftAligned = left.unscaled * 10n ** BigInt(scale - left.scale);
  const rightAligned = right.unscaled * 10n ** BigInt(scale - right.scale);
  return leftAligned < rightAligned ? -1 : leftAligned > rightAligned ? 1 : 0;
}

function compareInteger(left: string, right: string): number {
  requireCondition(INTEGER.test(left), `invalid canonical integer ${left}`);
  requireCondition(INTEGER.test(right), `invalid canonical integer limit ${right}`);
  const leftValue = BigInt(left);
  const rightValue = BigInt(right);
  return leftValue < rightValue ? -1 : leftValue > rightValue ? 1 : 0;
}

function acceptedBy(axis: JsonObject, observedValue: string): boolean {
  if (axis.valueEncoding === "ENUM_TOKEN") {
    requireCondition(axis.comparator === "ENUM_EXACT", `invalid enum comparator ${axis.limitId}`);
    requireCondition(observedValue.length > 0, `empty enum observation ${axis.limitId}`);
    return observedValue === axis.limitValue;
  }
  const ordering = axis.valueEncoding === "CANONICAL_INTEGER"
    ? compareInteger(observedValue, axis.limitValue)
    : axis.valueEncoding === "CANONICAL_DECIMAL"
      ? compareDecimal(observedValue, axis.limitValue)
      : (() => { throw new Error(`unsupported value encoding ${axis.valueEncoding}`); })();
  switch (axis.comparator) {
    case "MAX_INCLUSIVE": return ordering <= 0;
    case "MIN_INCLUSIVE": return ordering >= 0;
    case "MIN_EXCLUSIVE": return ordering > 0;
    case "EXACT": return ordering === 0;
    default: throw new Error(`unsupported comparator ${axis.comparator}`);
  }
}

function rejectedEffects(limitId: string): string[] {
  if (limitId.startsWith("renderInput.")) {
    return [
      "capabilityStates=0",
      "evaluations=0",
      "renderDocuments=0",
      "engineCommands=0",
      "renderOutputs=0",
    ];
  }
  if (limitId.startsWith("problems.")) {
    return ["boundedProblemPrefix=1", "problemLimitMarkers=1"];
  }
  return [
    "templateWrites=0",
    "assetWrites=0",
    "evaluationStarts=0",
    "renderDocuments=0",
    "renderOutputs=0",
  ];
}

function expectedObservation(axis: JsonObject, scenario: JsonObject): JsonObject {
  const accepted = acceptedBy(axis, scenario.observedValue);
  return {
    accepted,
    terminalCode: accepted ? null : axis.resolvedOracle.code,
    terminalStage: accepted ? null : axis.resolvedOracle.contractStage,
    publicRenderStage: accepted ? null : axis.resolvedOracle.publicRenderStage,
    zeroBoundary: accepted ? null : axis.resolvedOracle.zeroBoundary,
    downstreamEffects: accepted ? ["targetAxisAccepted=1"] : rejectedEffects(axis.limitId),
    limitId: scenario.limitId,
    observedValue: scenario.observedValue,
    reservationReached: true,
  };
}

function main(): void {
  const repo = resolve(requiredOption("--repo"));
  const targetPath = resolve(requiredOption("--target"));
  const primaryPath = resolve(requiredOption("--primary-report"));
  const reportPath = resolve(requiredOption("--report"));
  requireCondition(!existsSync(reportPath), `independent report already exists: ${reportPath}`);

  const targetBytes = readFileSync(targetPath);
  const target = JSON.parse(targetBytes.toString("utf8"));
  requireEqual(target.artifactVersion, TARGET_VERSION, "target version");
  requireEqual(target.targetId, TARGET_ID, "target id");
  requireEqual(target.implementationRevision, IMPLEMENTATION_REVISION, "implementation revision");
  requireEqual(target.executionClass, EXECUTION_CLASS, "target execution class");
  requireEqual(target.guardContractId,
    "renderweave-design-input-expression-capacity-guard/1.0", "guard contract");
  requireEqual(target.scalarReplay.axisCount, 65, "target scalar axis count");
  requireEqual(target.scalarReplay.caseCount, 195, "target scalar case count");
  requireEqual(target.productWiring.wiredAxisCount, 65, "wired product axes");
  requireEqual(target.productWiring.remainingAxisCount, 0, "remaining product axes");
  requireEqual(target.productWiring.productReservationProofComplete, true,
    "product reservation proof completeness");
  requireEqual(target.boundary.preissuanceReady, false, "target preissuance boundary");
  requireEqual(target.boundary.recordIssuanceAllowed, false, "target issuance boundary");
  requireEqual(target.boundary.executionClassExecutable, false, "target executable boundary");

  const predecessorPath = resolve(repo, target.predecessorTarget.path);
  const predecessorBytes = readFileSync(predecessorPath);
  const predecessor = JSON.parse(predecessorBytes.toString("utf8"));
  requireEqual(sha256(predecessorBytes), target.predecessorTarget.sha256,
    "predecessor target hash");
  requireEqual(predecessorBytes.length, target.predecessorTarget.byteLength,
    "predecessor target length");
  requireEqual(target.predecessorTarget.mutationAllowed, false,
    "predecessor target mutation boundary");
  const predecessorWired = predecessor.productWiring.wiredLimitIds as string[];
  requireEqual(predecessor.productWiring.expressionDefinitionReservationProof,
    "cn.hbads.renderweave.template.internal.ExpressionDefinitionCapacityReservationTest",
    "predecessor Expression Definition reservation proof");
  requireEqual(predecessor.productWiring.expressionDefinitionReservationProofAxisCount, 10,
    "predecessor Expression Definition reservation proof axis count");
  requireEqual(predecessor.productWiring.expressionDecimalReservationProof, [
    "cn.hbads.renderweave.template.internal.ExpressionDecimalCapacityReservationTest",
    "cn.hbads.renderweave.rendering.internal.ExpressionDecimalCapacityEvaluationTest",
    "cn.hbads.renderweave.rendering.internal.MaterializerTest#injectedCapacityAuthorityReachesExpressionEvaluation",
  ], "predecessor Expression decimal reservation proof");
  requireEqual(predecessor.productWiring.expressionDecimalReservationProofAxisCount, 7,
    "predecessor Expression decimal reservation proof axis count");
  requireEqual(predecessor.productWiring.geometryReservationProofAxisCount, 9,
    "predecessor Geometry reservation proof axis count");
  const expectedWired = [...predecessorWired, ...ROTATION_MAXIMUM_WIRED_IDS].sort();
  const targetWired = target.productWiring.wiredLimitIds as string[];
  requireEqual(new Set(targetWired).size, 65, "unique wired product axes");
  requireEqual([...targetWired].sort(), expectedWired, "monotonic wired product axes");
  requireEqual(target.productWiring.remainingGroups, [], "remaining product groups");
  requireEqual(target.productWiring.expressionDefinitionReservationProof,
    "cn.hbads.renderweave.template.internal.ExpressionDefinitionCapacityReservationTest",
    "Expression Definition reservation proof");
  requireEqual(target.productWiring.expressionDefinitionReservationProofAxisCount, 10,
    "Expression Definition reservation proof axis count");
  requireEqual(target.productWiring.expressionAstConsumptionProof,
    "cn.hbads.renderweave.rendering.internal.ExpressionEngineTest",
    "Expression AST Rendering consumption proof");
  requireEqual(target.productWiring.expressionDecimalReservationProof, [
    "cn.hbads.renderweave.template.internal.ExpressionDecimalCapacityReservationTest",
    "cn.hbads.renderweave.rendering.internal.ExpressionDecimalCapacityEvaluationTest",
    "cn.hbads.renderweave.rendering.internal.MaterializerTest#injectedCapacityAuthorityReachesExpressionEvaluation",
  ], "Expression decimal reservation proof");
  requireEqual(target.productWiring.expressionDecimalReservationProofAxisCount, 7,
    "Expression decimal reservation proof axis count");
  requireEqual(target.productWiring.geometryReservationProof,
    "cn.hbads.renderweave.template.internal.GeometryCapacityReservationTest",
    "Geometry reservation proof");
  requireEqual(target.productWiring.geometryReservationProofAxisCount, 10,
    "Geometry reservation proof axis count");
  requireEqual(target.productWiring.retiredRenderingOwnedExpressionArtifacts,
    RETIRED_RENDERING_OWNED_EXPRESSION_ARTIFACTS,
    "retired Rendering-owned Expression artifacts");

  let checkCount = 20;
  for (const artifact of target.artifacts as JsonObject[]) {
    const artifactPath = resolve(repo, artifact.path);
    const bytes = readFileSync(artifactPath);
    requireEqual(sha256(bytes), artifact.sha256, `artifact hash ${artifact.path}`);
    requireEqual(bytes.length, artifact.byteLength, `artifact length ${artifact.path}`);
    checkCount += 2;
  }
  for (const retiredPath of target.productWiring
    .retiredRenderingOwnedExpressionArtifacts as string[]) {
    requireCondition(!existsSync(resolve(repo, retiredPath)),
      `retired Rendering-owned Expression artifact still exists: ${retiredPath}`);
    checkCount += 1;
  }

  const primaryBytes = readFileSync(primaryPath);
  const primary = JSON.parse(primaryBytes.toString("utf8"));
  requireEqual(primary.reportVersion,
    "renderweave-design-input-expression-capacity-primary/1", "primary version");
  requireEqual(primary.engine, "java-semantic-authority", "primary engine");
  requireEqual(primary.executionClass, EXECUTION_CLASS, "primary execution class");
  requireEqual(primary.targetManifest.sha256, sha256(targetBytes), "primary target hash");
  requireEqual(primary.targetManifest.byteLength, targetBytes.length, "primary target length");
  requireEqual(primary.axisCount, 65, "primary axis count");
  requireEqual(primary.caseCount, 195, "primary case count");
  requireEqual(primary.passed, 195, "primary passed count");
  requireEqual(primary.failed, 0, "primary failure count");
  requireEqual(primary.boundary.wiredProductAxisCount, 65, "primary wired axes");
  requireEqual(primary.boundary.remainingProductAxisCount, 0, "primary remaining axes");
  requireEqual(primary.boundary.productReservationProofComplete, true,
    "primary product reservation proof completeness");
  requireEqual(primary.boundary.preissuanceReady, false, "primary preissuance boundary");
  requireEqual(primary.boundary.recordIssuanceAllowed, false, "primary issuance boundary");
  requireEqual(primary.boundary.executionClassExecutable, false, "primary executable boundary");
  checkCount += 15;

  const coveragePath = resolve(repo,
    ".scratch/renderweave-template-v1/conformance-capacity-coverage-v1.json");
  const coverage = readJson(coveragePath);
  const axes = (coverage.axes as JsonObject[])
    .filter((axis) => axis.executionClass === EXECUTION_CLASS);
  requireEqual(axes.length, 65, "coverage assigned axes");
  const axisById = new Map(axes.map((axis) => [axis.limitId, axis]));
  const fixtureRoot = resolve(repo,
    ".scratch/renderweave-template-v1/design-input-expression/fixtures");
  const fixtures = readdirSync(fixtureRoot)
    .filter((name) => name.startsWith("cap-") && name.endsWith(".json"))
    .sort();
  requireEqual(fixtures.length, 195, "fixture count");
  requireEqual(primary.observations.length, 195, "primary observation count");
  const primaryByCase = new Map(
    (primary.observations as JsonObject[]).map((entry) => [entry.caseId, entry]),
  );

  const observedAxes = new Set<string>();
  const observedCases = new Set<string>();
  const normalizedObservations: JsonObject[] = [];
  let acceptedCount = 0;
  let rejectedCount = 0;
  for (const fixtureName of fixtures) {
    const fixturePath = join(fixtureRoot, fixtureName);
    const fixtureBytes = readFileSync(fixturePath);
    const fixture = JSON.parse(fixtureBytes.toString("utf8"));
    assertClosedFixture(fixture);
    const scenario = fixture.scenario as JsonObject;
    const axis = axisById.get(scenario.limitId);
    requireCondition(axis, `unknown fixture limit ${scenario.limitId}`);
    requireEqual(scenario.scenarioId,
      `CAP::${scenario.limitId}::${scenario.variant}`, `scenario identity ${fixtureName}`);
    requireEqual(scenario.valueEncoding, axis.valueEncoding, `encoding ${scenario.scenarioId}`);
    requireEqual(scenario.comparator, axis.comparator, `comparator ${scenario.scenarioId}`);
    requireEqual(scenario.contractStage,
      axis.resolvedOracle.contractStage, `contract stage ${scenario.scenarioId}`);
    requireEqual(scenario.publicRenderStage,
      axis.resolvedOracle.publicRenderStage, `public stage ${scenario.scenarioId}`);
    requireEqual(scenario.zeroBoundary,
      axis.resolvedOracle.zeroBoundary, `zero boundary ${scenario.scenarioId}`);
    const coverageVariant = (axis.variants as JsonObject[])
      .find((variant) => variant.variant === scenario.variant);
    requireCondition(coverageVariant, `missing coverage variant ${scenario.scenarioId}`);
    requireEqual(scenario.observedValue,
      coverageVariant.stimulusValue, `stimulus ${scenario.scenarioId}`);

    const expected = expectedObservation(axis, scenario);
    const primaryEntry = primaryByCase.get(scenario.scenarioId);
    requireCondition(primaryEntry, `missing primary observation ${scenario.scenarioId}`);
    requireEqual(primaryEntry.fixturePath,
      `design-input-expression/fixtures/${fixtureName}`, `fixture path ${scenario.scenarioId}`);
    requireEqual(primaryEntry.fixtureSha256,
      sha256(fixtureBytes), `fixture hash ${scenario.scenarioId}`);
    requireEqual(primaryEntry.observation, expected, `product decision ${scenario.scenarioId}`);
    requireCondition(!observedCases.has(scenario.scenarioId), `duplicate case ${scenario.scenarioId}`);
    observedCases.add(scenario.scenarioId);
    observedAxes.add(scenario.limitId);
    if (expected.accepted) acceptedCount += 1;
    else rejectedCount += 1;
    normalizedObservations.push({ caseId: scenario.scenarioId, observation: expected });
    checkCount += 13;
  }

  requireEqual(observedAxes.size, 65, "observed axis count");
  requireEqual(observedCases.size, 195, "observed case count");
  requireEqual(acceptedCount, 125, "accepted count");
  requireEqual(rejectedCount, 70, "rejected count");
  requireEqual(primary.acceptedCount, acceptedCount, "primary accepted count");
  requireEqual(primary.rejectedCount, rejectedCount, "primary rejected count");
  checkCount += 6;

  const report = {
    reportVersion: REPORT_VERSION,
    engine: "typescript-independent-authoring-replayer",
    role: "independent-capacity-profile-replayer",
    assurance: "A2_COMPONENT_SCALAR_REPLAY_COMPLETE_PRODUCT_WIRING",
    executionClass: EXECUTION_CLASS,
    targetManifest: {
      path: ".scratch/renderweave-template-v1/design-input-expression/capacity-component-target-v17.json",
      sha256: sha256(targetBytes),
      byteLength: targetBytes.length,
    },
    primaryReportSha256: sha256(primaryBytes),
    axisCount: observedAxes.size,
    caseCount: observedCases.size,
    acceptedCount,
    rejectedCount,
    passed: observedCases.size,
    failed: 0,
    checkCount,
    observationDigest: sha256(Buffer.from(JSON.stringify(normalizedObservations), "utf8")),
    boundary: {
      scalarGuardOnly: true,
      wiredProductAxisCount: 65,
      remainingProductAxisCount: 0,
      productReservationProofComplete: true,
      preissuanceReady: false,
      formalRecordsIssued: 0,
      recordIssuanceAllowed: false,
      executionClassExecutable: false,
    },
  };
  writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`, { flag: "wx" });
  process.stdout.write(
    `DESIGN_INPUT_EXPRESSION independent replay: ${observedCases.size}/195 PASS, 65/65 wired\n`,
  );
}

main();
