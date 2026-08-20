use renderweave_renderer_document::validate_render_document;
use renderweave_renderer_layout::preflight_layout;
use serde_json::Value;
use std::collections::BTreeMap;

const FIXTURES: &str = include_str!("../../../layout-preflight-fixtures-v1.json");
const VECTORS: &str = include_str!("../../../layout-preflight-vectors-v1.json");
const ALL_KINDS: &str = include_str!("../../../render-document-all-kinds-v1.json");

#[test]
fn replays_shared_layout_preflight_vectors_after_document_admission() {
    let fixtures: Value = serde_json::from_str(FIXTURES).unwrap();
    let vectors: Value = serde_json::from_str(VECTORS).unwrap();

    for case in vectors["positiveCases"].as_array().unwrap() {
        let document = case_document(case, &fixtures, &vectors);
        let admitted = validate_render_document(&document).unwrap();
        let summary = preflight_layout(&admitted).unwrap();
        let expected = &case["expected"];
        let id = case["id"].as_str().unwrap();
        assert_eq!(
            summary.occurrence_count(),
            usize_value(expected, "occurrenceCount"),
            "{id}"
        );
        assert_eq!(
            summary.tree_edge_count(),
            usize_value(expected, "treeEdgeCount"),
            "{id}"
        );
        assert_eq!(
            summary.max_depth(),
            usize_value(expected, "maxDepth"),
            "{id}"
        );
        assert_eq!(
            summary.grid_count(),
            usize_value(expected, "gridCount"),
            "{id}"
        );
        assert_eq!(
            summary.grid_track_count(),
            usize_value(expected, "gridTrackCount"),
            "{id}"
        );
        assert_eq!(
            summary.grid_cell_count(),
            usize_value(expected, "gridCellCount"),
            "{id}"
        );
    }

    for case in vectors["negativeCases"].as_array().unwrap() {
        let document = case_document(case, &fixtures, &vectors);
        let admitted = validate_render_document(&document).unwrap_or_else(|error| {
            panic!(
                "{} did not cross the T23 admission seam: {error}",
                case["id"]
            )
        });
        let problem = preflight_layout(&admitted)
            .expect_err("negative vector unexpectedly passed layout preflight");
        let expected = &case["expected"];
        assert_eq!(
            problem.code().as_str(),
            expected["code"].as_str().unwrap(),
            "{}",
            case["id"]
        );
        assert_eq!(
            problem.occurrence_id(),
            expected["occurrenceId"].as_str().unwrap(),
            "{}",
            case["id"]
        );
        assert_eq!(
            problem.property(),
            expected["property"].as_str().unwrap(),
            "{}",
            case["id"]
        );
        let expected_parameters = expected["parameters"]
            .as_object()
            .unwrap()
            .iter()
            .map(|(key, value)| (key.clone(), value.as_str().unwrap().to_owned()))
            .collect::<BTreeMap<_, _>>();
        assert_eq!(problem.parameters(), &expected_parameters, "{}", case["id"]);
    }
}

fn usize_value(value: &Value, member: &str) -> usize {
    value[member].as_u64().unwrap() as usize
}

fn case_document(case: &Value, fixtures: &Value, vectors: &Value) -> String {
    let base = case["baseCase"].as_str().unwrap();
    let mut document = if base == "allKinds" {
        serde_json::from_str(ALL_KINDS).unwrap()
    } else {
        fixtures["documents"][base].clone()
    };
    if let Some(preset) = case.get("preset").and_then(Value::as_str) {
        apply_mutations(
            &mut document,
            vectors["presets"][preset].as_array().unwrap(),
        );
    }
    if let Some(mutations) = case.get("mutations").and_then(Value::as_array) {
        apply_mutations(&mut document, mutations);
    }
    serde_json::to_string(&document).unwrap()
}

fn apply_mutations(document: &mut Value, mutations: &[Value]) {
    for mutation in mutations {
        let pointer = mutation["pointer"].as_str().unwrap();
        match mutation["operation"].as_str().unwrap() {
            "repeat" => {
                let count = mutation["count"].as_u64().unwrap() as usize;
                *document.pointer_mut(pointer).unwrap() =
                    Value::Array(vec![mutation["value"].clone(); count]);
            }
            "append" => document
                .pointer_mut(pointer)
                .unwrap()
                .as_array_mut()
                .unwrap()
                .push(mutation["value"].clone()),
            operation => {
                let (parent_pointer, token) = pointer.rsplit_once('/').unwrap();
                let token = token.replace("~1", "/").replace("~0", "~");
                let object = document
                    .pointer_mut(parent_pointer)
                    .unwrap()
                    .as_object_mut()
                    .unwrap();
                match operation {
                    "remove" => {
                        object.remove(&token).unwrap();
                    }
                    "add" | "replace" => {
                        object.insert(token, mutation["value"].clone());
                    }
                    _ => panic!("unknown mutation operation"),
                }
            }
        }
    }
}
