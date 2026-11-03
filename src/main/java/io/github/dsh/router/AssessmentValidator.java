package io.github.dsh.router;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dsh.core.Model.*;
import java.util.*;

/** 研判不是任意布尔 accept：逐条规则证据、实施意图和目标已确定均由宿主校验形状。 */
public final class AssessmentValidator {
  private AssessmentValidator() {}

  public static Outcome validate(JsonNode assessment, Set<String> enabled) {
    fields(
        assessment,
        Set.of(
            "decision",
            "rule_assessments",
            "direction",
            "title",
            "worktree_name",
            "scope",
            "intent_evidence",
            "acknowledgement",
            "reason"));
    if (assessment.hasNonNull("worktree_name")) worktree(required(assessment, "worktree_name", 32));
    if (assessment.hasNonNull("intent_evidence")) required(assessment, "intent_evidence", 700);
    if (assessment.hasNonNull("acknowledgement"))
      words(required(assessment, "acknowledgement", 16000), 100);
    required(assessment, "title", 160);
    if (!assessment.path("scope").isTextual() || assessment.path("scope").asText().length() > 1500)
      throw new IllegalArgumentException("Invalid scope");
    JsonNode judgments = assessment.path("rule_assessments");
    if (!judgments.isArray()) throw new IllegalArgumentException("rule_assessments required");
    Set<String> seen = new HashSet<>(), matched = new LinkedHashSet<>();
    for (JsonNode rule : judgments) {
      fields(rule, Set.of("rule_id", "matched", "reason"));
      String id = required(rule, "rule_id", 100);
      if (!seen.add(id) || !enabled.contains(id))
        throw new IllegalArgumentException("Duplicate or unknown rule");
      required(rule, "reason", 500);
      if (!rule.path("matched").isBoolean())
        throw new IllegalArgumentException("matched must be boolean");
      if (rule.path("matched").asBoolean()) matched.add(id);
    }
    if (!seen.equals(enabled))
      throw new IllegalArgumentException("Exactly one assessment per enabled rule required");
    String kind = required(assessment, "decision", 20),
        reason = required(assessment, "reason", 500);
    if (!Set.of("accept", "ignore", "unavailable").contains(kind))
      throw new IllegalArgumentException("Invalid assessment decision");
    JsonNode direction = assessment.path("direction");
    fields(
        direction,
        Set.of("implementation_requested", "requires_design_decision", "target_basis", "evidence"));
    required(direction, "evidence", 700);
    if (!direction.path("implementation_requested").isBoolean()
        || !direction.path("requires_design_decision").isBoolean()
        || !Set.of("specified", "existing-standard", "undetermined")
            .contains(direction.path("target_basis").asText()))
      throw new IllegalArgumentException("Invalid direction evidence");
    if (!kind.equals("accept"))
      return new Outcome(
          DecisionKind.valueOf(kind.toUpperCase()), null, null, matched, reason, null);
    if (!direction.path("implementation_requested").asBoolean()
        || direction.path("target_basis").asText().equals("undetermined")
        || direction.path("requires_design_decision").asBoolean())
      return Outcome.ignore("directionUnsettled");
    if (matched.isEmpty()) return Outcome.ignore("noRuleMatched");
    required(assessment, "intent_evidence", 700);
    worktree(required(assessment, "worktree_name", 32));
    return new Outcome(
        DecisionKind.ACCEPT,
        required(assessment, "title", 160),
        assessment.path("scope").asText(),
        matched,
        reason,
        null);
  }

  public static void fields(JsonNode object, Set<String> allowed) {
    if (!object.isObject()) throw new IllegalArgumentException("Expected object");
    object
        .fieldNames()
        .forEachRemaining(
            name -> {
              if (!allowed.contains(name))
                throw new IllegalArgumentException("Unknown field: " + name);
            });
  }

  public static String required(JsonNode value, String field, int max) {
    JsonNode node = value.path(field);
    if (!node.isTextual() || node.asText().isBlank() || node.asText().length() > max)
      throw new IllegalArgumentException("Invalid " + field);
    return node.asText();
  }

  public static String worktree(String name) {
    if (!name.matches("[a-z]+(?:-[a-z0-9]+){0,3}") || name.length() > 32)
      throw new IllegalArgumentException("worktree_name requires 1-4 lowercase English words");
    return name;
  }

  public static void words(String text, int maximum) {
    if (io.github.dsh.sources.SourceText.wordCount(text) > maximum)
      throw new IllegalArgumentException("Text exceeds " + maximum + " words");
  }
}
