use renderweave_renderer_document::validate_render_document;
use renderweave_renderer_layout::{
    DefiniteLayoutUnsupported, LocalLayoutBox, layout_definite_resource_free, preflight_layout,
};
use serde_json::Value;

const FIXTURES: &str = include_str!("../../../definite-layout-fixtures-v1.json");
const VECTORS: &str = include_str!("../../../definite-layout-vectors-v1.json");
const LAYOUT_PREFLIGHT_FIXTURES: &str = include_str!("../../../layout-preflight-fixtures-v1.json");
const ALL_KINDS: &str = include_str!("../../../render-document-all-kinds-v1.json");

#[test]
fn replays_exact_binary64_definite_layout_vectors() {
    let fixtures: Value = serde_json::from_str(FIXTURES).unwrap();
    let vectors: Value = serde_json::from_str(VECTORS).unwrap();
    let layout_preflight_fixtures: Value = serde_json::from_str(LAYOUT_PREFLIGHT_FIXTURES).unwrap();
    assert_eq!(
        vectors["vectorVersion"],
        "renderweave-definite-layout-vectors/56"
    );

    for case in vectors["laidOutCases"].as_array().unwrap() {
        let document = case_document(case, &fixtures, &layout_preflight_fixtures);
        let admitted = validate_render_document(&document).unwrap_or_else(|error| {
            panic!(
                "{} did not cross the T23 admission seam: {error}",
                case["id"]
            )
        });
        preflight_layout(&admitted).unwrap_or_else(|error| {
            panic!(
                "{} did not cross the T25 preflight seam: {error}",
                case["id"]
            )
        });

        let layout = layout_definite_resource_free(&admitted)
            .unwrap_or_else(|error| panic!("{} unexpectedly failed: {error}", case["id"]));
        let expected_entries = case["expected"]["entries"].as_array().unwrap();
        assert_eq!(layout.entries().len(), admitted.occurrence_count());
        assert_eq!(layout.entries().len(), expected_entries.len());

        for (actual, expected) in layout.entries().iter().zip(expected_entries) {
            let case_id = case["id"].as_str().unwrap();
            assert_eq!(
                actual.occurrence_id(),
                expected["occurrenceId"].as_str().unwrap(),
                "{case_id}"
            );
            assert_eq!(
                actual.kind(),
                expected["kind"].as_str().unwrap(),
                "{case_id}"
            );
            assert_box_bits(actual.layout_box(), &expected["layoutBox"], case_id);
            match (actual.content_box(), &expected["contentBox"]) {
                (Some(actual), expected) if expected.is_object() => {
                    assert_box_bits(actual, expected, case_id);
                }
                (None, expected) if expected.is_null() => {}
                _ => panic!("{case_id}: ContentBox presence mismatch"),
            }
        }
    }
}

#[test]
fn returns_the_first_closed_internal_unsupported_boundary() {
    let fixtures: Value = serde_json::from_str(FIXTURES).unwrap();
    let vectors: Value = serde_json::from_str(VECTORS).unwrap();
    let layout_preflight_fixtures: Value = serde_json::from_str(LAYOUT_PREFLIGHT_FIXTURES).unwrap();

    for case in vectors["unsupportedCases"].as_array().unwrap() {
        let document = case_document(case, &fixtures, &layout_preflight_fixtures);
        let admitted = validate_render_document(&document).unwrap_or_else(|error| {
            panic!(
                "{} did not cross the T23 admission seam: {error}",
                case["id"]
            )
        });
        preflight_layout(&admitted).unwrap_or_else(|error| {
            panic!(
                "{} did not cross the T25 preflight seam: {error}",
                case["id"]
            )
        });

        let error = layout_definite_resource_free(&admitted)
            .expect_err("unsupported vector unexpectedly produced a partial layout");
        let expected = &case["expected"];
        assert_eq!(
            error.occurrence_id(),
            expected["occurrenceId"].as_str().unwrap(),
            "{}",
            case["id"]
        );
        assert_eq!(
            error
                .unsupported_feature()
                .unwrap_or_else(|| panic!("{} returned a public preflight problem", case["id"]))
                .as_str(),
            expected["feature"].as_str().unwrap(),
            "{}",
            case["id"]
        );
    }
}

#[test]
fn unsupported_feature_names_are_closed_and_stable() {
    let names = [
        DefiniteLayoutUnsupported::HugContent.as_str(),
        DefiniteLayoutUnsupported::Group.as_str(),
        DefiniteLayoutUnsupported::StackMainFill.as_str(),
        DefiniteLayoutUnsupported::GridAutoTrack.as_str(),
        DefiniteLayoutUnsupported::GridFractionTrack.as_str(),
        DefiniteLayoutUnsupported::ChildRotation.as_str(),
        DefiniteLayoutUnsupported::CompositionViewport.as_str(),
        DefiniteLayoutUnsupported::ResourceDependentKind.as_str(),
        DefiniteLayoutUnsupported::NonAbsolutePlacement.as_str(),
    ];
    assert_eq!(
        names,
        [
            "HUG_CONTENT",
            "GROUP",
            "STACK_MAIN_FILL",
            "GRID_AUTO_TRACK",
            "GRID_FRACTION_TRACK",
            "CHILD_ROTATION",
            "COMPOSITION_VIEWPORT",
            "RESOURCE_DEPENDENT_KIND",
            "NON_ABSOLUTE_PLACEMENT",
        ]
    );
}

fn assert_box_bits(actual: &LocalLayoutBox, expected: &Value, case_id: &str) {
    for (member, value) in [
        ("xBits", actual.x()),
        ("yBits", actual.y()),
        ("widthBits", actual.width()),
        ("heightBits", actual.height()),
    ] {
        assert_eq!(
            format!("{:016x}", value.to_bits()),
            expected[member].as_str().unwrap(),
            "{case_id}: {member}"
        );
    }
}

fn case_document(case: &Value, fixtures: &Value, layout_preflight_fixtures: &Value) -> String {
    let base_case = case["baseCase"].as_str();
    let mut document = match case["baseSource"].as_str().unwrap() {
        "fixtures" => fixtures["documents"][base_case.unwrap()].clone(),
        "layoutPreflight" => layout_preflight_fixtures["documents"][base_case.unwrap()].clone(),
        "allKinds" => serde_json::from_str(ALL_KINDS).unwrap(),
        source => panic!("unknown base source {source}"),
    };

    if let Some(indices) = case.get("retainCanvasChildren").and_then(Value::as_array) {
        let children = document["canvas"]["children"].as_array().unwrap().clone();
        document["canvas"]["children"] = Value::Array(
            indices
                .iter()
                .map(|index| children[index.as_u64().unwrap() as usize].clone())
                .collect(),
        );
    }
    if let Some(indices) = case.get("retainResources").and_then(Value::as_array) {
        let resources = document["resources"].as_array().unwrap().clone();
        document["resources"] = Value::Array(
            indices
                .iter()
                .map(|index| resources[index.as_u64().unwrap() as usize].clone())
                .collect(),
        );
    }
    if case
        .get("renumberOccurrences")
        .and_then(Value::as_bool)
        .unwrap_or(false)
    {
        let mut next = 0_u64;
        renumber_node(document["canvas"].as_object_mut().unwrap(), &mut next);
    }
    if let Some(mutations) = case.get("mutations").and_then(Value::as_array) {
        apply_mutations(&mut document, mutations);
    }
    serde_json::to_string(&document).unwrap()
}

fn renumber_node(node: &mut serde_json::Map<String, Value>, next: &mut u64) {
    node.insert(
        "occurrenceId".to_owned(),
        Value::String(format!("rwocc_{:016x}", *next)),
    );
    *next += 1;
    if node.get("kind").and_then(Value::as_str) == Some("compositionViewport") {
        let source = node["sourceCanvas"].as_object_mut().unwrap();
        source.insert(
            "occurrenceId".to_owned(),
            Value::String(format!("rwocc_{:016x}", *next)),
        );
        *next += 1;
        for child in source["children"].as_array_mut().unwrap() {
            renumber_node(child.as_object_mut().unwrap(), next);
        }
    } else if let Some(children) = node.get_mut("children").and_then(Value::as_array_mut) {
        for child in children {
            renumber_node(child.as_object_mut().unwrap(), next);
        }
    }
}

fn apply_mutations(document: &mut Value, mutations: &[Value]) {
    for mutation in mutations {
        let pointer = mutation["pointer"].as_str().unwrap();
        let (parent_pointer, token) = pointer.rsplit_once('/').unwrap();
        let token = token.replace("~1", "/").replace("~0", "~");
        let parent = document.pointer_mut(parent_pointer).unwrap();
        match (parent, mutation["operation"].as_str().unwrap()) {
            (Value::Object(object), "remove") => {
                object.remove(&token).unwrap();
            }
            (Value::Object(object), "add" | "replace") => {
                object.insert(token, mutation["value"].clone());
            }
            (Value::Array(array), "remove") => {
                array.remove(token.parse().unwrap());
            }
            (Value::Array(array), "add") => {
                array.insert(token.parse().unwrap(), mutation["value"].clone());
            }
            (Value::Array(array), "replace") => {
                array[token.parse::<usize>().unwrap()] = mutation["value"].clone();
            }
            (_, operation) => panic!("unknown mutation operation {operation}"),
        }
    }
}
