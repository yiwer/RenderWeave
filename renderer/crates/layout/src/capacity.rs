const CONTRACT_ID: &str = "renderweave-renderer-exact-output-capacity-guard/1.0";
const STACK_WATER_FILL_ROUNDS_LIMIT_ID: &str =
    "layoutFontAndRaster.stackWaterFillRoundsPerContainer";
const GRID_SPAN_PASSES_PER_CONSTRAINT_LIMIT_ID: &str =
    "layoutFontAndRaster.gridSpanPassesPerConstraint";
const LAYOUT_PROFILE_STAGE: &str = "LAYOUT_PROFILE";
const ENGINE_STAGE: &str = "ENGINE";
const ALGORITHM_INVARIANT_ZERO_BOUNDARY: &str = "ALGORITHM_INVARIANT";

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct RendererExactOutputCapacityGuard;

impl RendererExactOutputCapacityGuard {
    pub const fn new() -> Self {
        Self
    }

    pub const fn contract_id(&self) -> &'static str {
        CONTRACT_ID
    }

    pub fn admit_stack_water_fill_rounds(
        &self,
        fill_child_count: usize,
        observed_rounds: usize,
    ) -> Result<(), RendererExactOutputCapacityInvariant> {
        match fill_child_count.checked_add(1) {
            Some(maximum_rounds) if observed_rounds <= maximum_rounds => Ok(()),
            _ => Err(RendererExactOutputCapacityInvariant::new(
                RendererExactOutputCapacityLimit::StackWaterFillRoundsPerContainer,
            )),
        }
    }

    pub fn admit_grid_span_passes_per_constraint(
        &self,
        observed_passes: usize,
    ) -> Result<(), RendererExactOutputCapacityInvariant> {
        match observed_passes {
            1 => Ok(()),
            _ => Err(RendererExactOutputCapacityInvariant::new(
                RendererExactOutputCapacityLimit::GridSpanPassesPerConstraint,
            )),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum RendererExactOutputCapacityLimit {
    StackWaterFillRoundsPerContainer,
    GridSpanPassesPerConstraint,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RendererExactOutputCapacityInvariant {
    limit: RendererExactOutputCapacityLimit,
}

impl RendererExactOutputCapacityInvariant {
    const fn new(limit: RendererExactOutputCapacityLimit) -> Self {
        Self { limit }
    }

    pub const fn limit_id(&self) -> &'static str {
        match self.limit {
            RendererExactOutputCapacityLimit::StackWaterFillRoundsPerContainer => {
                STACK_WATER_FILL_ROUNDS_LIMIT_ID
            }
            RendererExactOutputCapacityLimit::GridSpanPassesPerConstraint => {
                GRID_SPAN_PASSES_PER_CONSTRAINT_LIMIT_ID
            }
        }
    }

    pub const fn contract_stage(&self) -> &'static str {
        LAYOUT_PROFILE_STAGE
    }

    pub const fn public_render_stage(&self) -> &'static str {
        ENGINE_STAGE
    }

    pub const fn problem_code(&self) -> Option<&'static str> {
        None
    }

    pub const fn zero_boundary(&self) -> &'static str {
        ALGORITHM_INVARIANT_ZERO_BOUNDARY
    }
}
