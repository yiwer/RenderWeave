const CONTRACT_ID: &str = "renderweave-renderer-exact-output-capacity-guard/1.0";
const STACK_WATER_FILL_ROUNDS_LIMIT_ID: &str =
    "layoutFontAndRaster.stackWaterFillRoundsPerContainer";
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
            _ => Err(RendererExactOutputCapacityInvariant),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RendererExactOutputCapacityInvariant;

impl RendererExactOutputCapacityInvariant {
    pub const fn limit_id(&self) -> &'static str {
        STACK_WATER_FILL_ROUNDS_LIMIT_ID
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
