use renderweave_renderer_layout::RendererExactOutputCapacityGuard;

#[test]
fn stack_water_fill_round_formula_exposes_frozen_code_less_invariant() {
    let guard = RendererExactOutputCapacityGuard::new();

    assert_eq!(
        guard.contract_id(),
        "renderweave-renderer-exact-output-capacity-guard/1.0"
    );
    assert!(guard.admit_stack_water_fill_rounds(4, 4).is_ok());
    assert!(guard.admit_stack_water_fill_rounds(4, 5).is_ok());

    let invariant = guard
        .admit_stack_water_fill_rounds(4, 6)
        .expect_err("fillChildCount+2 must violate the frozen formula");
    assert_eq!(
        invariant.limit_id(),
        "layoutFontAndRaster.stackWaterFillRoundsPerContainer"
    );
    assert_eq!(invariant.contract_stage(), "LAYOUT_PROFILE");
    assert_eq!(invariant.public_render_stage(), "ENGINE");
    assert_eq!(invariant.problem_code(), None);
    assert_eq!(invariant.zero_boundary(), "ALGORITHM_INVARIANT");

    assert!(guard.admit_stack_water_fill_rounds(0, 0).is_ok());
    assert!(guard.admit_stack_water_fill_rounds(0, 1).is_ok());
    assert!(guard.admit_stack_water_fill_rounds(0, 2).is_err());
    assert!(
        guard.admit_stack_water_fill_rounds(usize::MAX, 0).is_err(),
        "formula overflow must fail closed"
    );
}

#[test]
fn grid_span_constraint_exact_pass_exposes_frozen_code_less_invariant() {
    let guard = RendererExactOutputCapacityGuard::new();

    assert!(guard.admit_grid_span_passes_per_constraint(1).is_ok());

    for observed_passes in [0, 2] {
        let invariant = guard
            .admit_grid_span_passes_per_constraint(observed_passes)
            .expect_err("every Grid span constraint must receive exactly one pass");
        assert_eq!(
            invariant.limit_id(),
            "layoutFontAndRaster.gridSpanPassesPerConstraint"
        );
        assert_eq!(invariant.contract_stage(), "LAYOUT_PROFILE");
        assert_eq!(invariant.public_render_stage(), "ENGINE");
        assert_eq!(invariant.problem_code(), None);
        assert_eq!(invariant.zero_boundary(), "ALGORITHM_INVARIANT");
    }
}
